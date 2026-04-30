# zhitu-agent-java 优化进度

> 升级目标: 把项目从"RAG 聊天 demo"升级为能在 Agent 实习面试中讲对标 LangGraph / CrewAI / MemGPT / MCP / Anthropic Contextual Retrieval 等业界最佳实践的成熟 Agent 系统。
>
> 协作模式: Claude 主导实现并讲解设计思路;用户 review 关键决策点。

## 路线图

```
阶段 C 评测基线  →  阶段 A Function Calling  →  阶段 2 差异化亮点
   3 步                  5 步                       7 步
```

每个里程碑跑一次评测,把数字写进本文档,作为简历对比叙事的底料。

---

## 阶段 C — 评测基线

### C-1 ✅ 扩充评测指标体系(2026-04-30 完成)

**做了什么**

把 `BaselineEvalRunner` 从"只比对 `topSource` 字符串相等(等价 Hit@1)"扩成完整 IR 指标矩阵:
- `Hit@5` / `Recall@5` / `MRR@5` / `nDCG@5`(二元相关性)
- `answerKeywordCoverage`(答案关键词覆盖度,Ragas faithfulness 的廉价代理)

新增字段:
- `BaselineEvalCase.relevantSourceIds`(ground truth)
- `BaselineEvalCase.expectedAnswerKeywords`
- `TraceInfo.retrievedSources`(API 契约升级,顺便给前端 citation 与未来 trace tree 用)
- `BaselineEvalResult.CaseResult` 加 9 个指标字段
- `BaselineEvalResult` aggregate 加 5 个 mean 指标 + 2 个计数

**关键设计决策**

1. **二元相关性 nDCG**(非 graded)—— 当前 ground truth 无评分,binary 起点最低成本;后续 LLM-as-judge 评分后可平滑升级到 graded nDCG。
2. **`retrievedSources` 升入 `TraceInfo` 而非塞 eval 内部** —— 一鱼多吃:前端 citation 高亮、trace span 树、未来 replay 端点都要它,API 契约升级值得。
3. **`rankingCheckApplied` / `keywordCheckApplied` 旗标** —— 没标 ground truth 的 case 不参与聚合,避免 divide-by-zero,N/A 不污染指标。
4. **fixture 设计反过来突出 mode 差异** —— `rag-rerank-001` 只标 `phase-one-precise` 为相关:dense 顶 `vague` → MRR 低,rerank 纠正 → MRR=1.0。这数字就是 v1→v2 对比叙事的底料。

**stub LLM 跑出的当前数字**(7 case fixture, hybrid-rerank mode)

| Case | Retrieved | Relevant | Hit@5 | Recall@5 | MRR@5 | nDCG@5 |
|---|---|---|---|---|---|---|
| `rag-001` | `[plan]` | `[plan, why]` | ✓ | **0.5** | 1.0 | **0.613** |
| `rag-rerank-001` | `[precise]` | `[precise]` | ✓ | 1.0 | 1.0 | 1.0 |
| `rag-hybrid-001` | `[keyword-target]` | `[keyword-target]` | ✓ | 1.0 | 1.0 | 1.0 |

aggregate: `meanRecallAt5 = 0.833`, `meanNdcgAt5 = 0.871`, `rankingCheckedCases = 3`

**诊断信号**

`rag-001` 的 Recall@5 = 0.5 暴露了一个真问题:`finalTopK = 1` 配置下,即使有 2 篇 gold doc,系统只能召回 1 篇 → recall 上限就是 0.5。这是简历可挂的"识别瓶颈、为下一阶段铺路"的论证素材。

**改动文件**

```
新增   src/main/java/com/zhituagent/eval/RankingMetrics.java
修改   src/main/java/com/zhituagent/api/dto/TraceInfo.java
修改   src/main/java/com/zhituagent/api/ChatTraceFactory.java
修改   src/main/java/com/zhituagent/eval/BaselineEvalCase.java
修改   src/main/java/com/zhituagent/eval/BaselineEvalResult.java
修改   src/main/java/com/zhituagent/eval/BaselineEvalRunner.java
修改   src/main/resources/eval/baseline-chat-cases.jsonl
修改   src/test/resources/eval/baseline-chat-cases.jsonl
```

测试:`mvn -o test` 49/49 全绿。

---

### C-2 ✅ 扩充 baseline cases + train/eval 拆分(2026-04-30 完成)

**做了什么**

- Fixture 从 7 → **16 cases**(增 9 条)
- 新增 `BaselineEvalCase.splitMode`(默认 `train`,显式标 `eval` 进入 holdout)
- `BaselineEvalResult` 加 `trainSplit` / `evalSplit` 两个 `SplitBreakdown` 子聚合(对比 train vs holdout 是否存在过拟合 fixture)
- `BaselineEvalResult.CaseResult` 加 `splitMode` 字段便于 per-case 追溯

**新增 9 条 case 类型分布**

| 类型 | 新增 | split | 关键测试点 |
|---|---|---|---|
| `direct-002` | 闲聊 | train | empty knowledgeEntries,验证 RAG 不误触 |
| `direct-003` | 抽象观点 | **eval** | 问 Java vs Python,无 KB seed |
| `direct-004` | 创作 | train | 讲笑话,无 KB seed |
| `tool-002` | 时间变体 | **eval** | "日期+几号",验证关键词路由对变体的鲁棒性 |
| `rag-simple-002` | 单 doc 事实 | train | LangChain4j 版本查询 |
| `rag-simple-003` | 单 doc 选型 | train | 向量数据库选 pgvector |
| `rag-simple-004` | 实现细节 | train | trace 归档机制 |
| `rag-simple-005` | 内部命名 | **eval** | knowledge-write 工具名 |
| `rag-multi-001` | 多 doc 都相关 | **eval** | Agent 编排路径(Recall@5 = 1.0 理想场景) |

**关键设计决策**

1. **`splitMode` 默认 train** —— 老 case 不指定即进 train,向后兼容。eval 集 4 条由我手动选,覆盖 direct/tool/rag-simple/rag-multi 4 种类型(防止 eval 集 type 偏 skew)。
2. **`allowedSources` 已经按 case 过滤** —— 测试 KB 是 `InMemoryKnowledgeStore` 累积式存储,但 `RetrievalRequestOptions.scoped(mode, allowedSources)` 把每个 case 的检索范围锁定到自己的 `knowledgeEntries.sourceName`。这意味着 KB 跨 case 污染不会触发误命中,设计 case 时不用回避字符重叠。**这是项目原有的工程审美点**,可以在面试讲。
3. **eval 集刻意挑边界场景** —— `direct-003` 含 "Java"(可能与 latin tech 词形成 char overlap)、`tool-002` 是变体问法、`rag-multi-001` 多 doc 全相关。这些 case 对路由/检索更挑战,放 eval 集才能真正测出泛化能力。

**stub LLM 跑出的 v1 数字**(16 case fixture, hybrid-rerank mode)

| 指标 | Train (n=12) | Eval (n=4) | Overall |
|---|---|---|---|
| 通过率 | 12/12 = 100% | 4/4 = 100% | 16/16 |
| 路由准确率 | 1.000 | 1.000 | 1.000 |
| Recall@5 | **0.917** (n=6) | **0.750** (n=2) | 0.875 |
| MRR@5 | 1.000 | 1.000 | 1.000 |
| nDCG@5 | **0.936** | **0.807** | 0.903 |
| keyword Coverage | 0.250 | 0.000 | 0.222 |

**讲故事点**: train nDCG 0.936 → eval nDCG 0.807,**0.13 的 gap 就是"我有 holdout 集合,且观察到了非平凡 generalization gap"**。在阶段 A 改造后,eval 数字应该和 train 一起涨,这就是"我的优化没有过拟合到 fixture"的证据。

**改动文件**

```
修改   src/main/java/com/zhituagent/eval/BaselineEvalCase.java        # +splitMode
修改   src/main/java/com/zhituagent/eval/BaselineEvalResult.java      # +SplitBreakdown record + 2 splits + CaseResult.splitMode
修改   src/main/java/com/zhituagent/eval/BaselineEvalRunner.java      # buildSplit 算 per-split aggregate
修改   src/main/resources/eval/baseline-chat-cases.jsonl              # 7 → 16 cases
修改   src/test/resources/eval/baseline-chat-cases.jsonl              # 同步
```

测试:`mvn -o test` 49/49 全绿。

---

### C-3 ⏸ 跑 v1 基线并归档(命令 ready,推迟到 A-5 一起跑)

**为什么推迟**
- API 成本: 48 LLM calls + 16 embedding + ~30 rerank ≈ $1 量级
- 数据隔离: 默认 `ZHITU_PGVECTOR_TABLE=zhitu_agent_knowledge` 是 prod 库,eval seed 会污染。需要先准备隔离表 `zhitu_agent_eval`。
- ROI: A-5 阶段必须跑 v2 baseline,届时一次跑 v1 + v2 节省一半 API 开销。

**运行命令(供未来直接复制)**

```bash
# 准备隔离表
psql ... -c "CREATE TABLE zhitu_agent_eval (LIKE zhitu_agent_knowledge INCLUDING ALL);"

# 跑 eval
ZHITU_PGVECTOR_TABLE=zhitu_agent_eval \
ZHITU_LLM_MOCK_MODE=false \
mvn -o spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true"

# 报告产物在 target/eval-reports/baseline-comparison-*.json
```

跑完把数字 paste 进本文档对应表格即可。

---

## 阶段 A — Function Calling

5 个子任务:
- A-1: ✅ `ToolDefinition` 加 `description` + JSON Schema (LangChain4j `ToolSpecification`)
- A-2: `ChatLanguageModel` 真 function calling 改造,删除 `looksLikeTimeQuestion` 关键词路由
- A-3: tool_calls 并行执行 + 错误回退到 observation
- A-4: 错误恢复三件套(Retry / schema 校验 / observation 重投)
- A-5: 跑 v2 评测对比 v1

---

### A-1 ✅ ToolDefinition 加 description + JSON Schema(2026-04-30 完成)

**做了什么**

- `ToolDefinition` 接口加两个 default 方法 + 一个桥接方法:
  - `description()` — 给 LLM 看的工具说明,默认 fallback 到 name()
  - `parameterSchema()` — 返回 LangChain4j `JsonObjectSchema`,默认空对象
  - `toolSpecification()` — 直接返回可投喂 LangChain4j `OpenAiChatModel.tools(...)` 的 `ToolSpecification`
- 3 个内置工具填实 schema:
  - `time` — 无参,description 强调 ISO 8601 + 何时调用
  - `knowledge-write` — 3 必填 string 参数(question/answer/sourceName),`additionalProperties=false`
  - `session-inspect` — sessionId 必填,description 提示"this conversation"语义
- `ToolRegistry.specifications()` 一行返回 `List<ToolSpecification>`,A-2 接入 LLM 即用

**关键设计决策**

1. **default 方法兜底,不破坏外部实现** — 任何已有 `ToolDefinition` 实现仍可编译,仅得到 fallback schema。这是 A-2 真 function calling 的最小侵入前置。
2. **直接复用 LangChain4j 的 `JsonObjectSchema`** — 不引入第三方 schema 生成器(如 victools/jsonschema-generator),零新依赖。LangChain4j 1.1.0 自带的 schema DSL 已经够用:`addStringProperty(name, description)` + `required(...)` + `additionalProperties(false)`。
3. **schema description 字段是面试加分点** — 比 OpenAI cookbook 的最佳实践更细:每个 property 都有"什么时候填、用什么形式填"的中文化说明,让 LLM 在边界 case(如 sessionId 含义)上也能正确选择参数。
4. **`additionalProperties: false`** — 强制 LLM 不发明额外参数,降低幻觉调用失败率。这是 OpenAI structured output / Anthropic tool use 的官方推荐。

**改动文件**

```
修改   src/main/java/com/zhituagent/tool/ToolDefinition.java          # +description/+parameterSchema/+toolSpecification
修改   src/main/java/com/zhituagent/tool/ToolRegistry.java            # +specifications()
修改   src/main/java/com/zhituagent/tool/builtin/TimeTool.java        # +description+schema
修改   src/main/java/com/zhituagent/tool/builtin/KnowledgeWriteTool.java
修改   src/main/java/com/zhituagent/tool/builtin/SessionInspectTool.java
修改   src/test/java/com/zhituagent/tool/ToolRegistryTest.java        # +shouldExposeToolSpecifications
```

测试:`mvn -o test` 50/50 全绿(+1 新)。

---

## 阶段 2 — 差异化亮点(待开始)

7 个子任务,任挑两三个写进简历即可:
- ReAct/StateGraph 循环(LangGraph 对标)
- Anthropic Contextual Retrieval + 真 BM25 + RRF
- Self-RAG / CRAG 检索充分性评估
- MemGPT / Mem0 风格 memory(LLM 抽取 + add/update/merge + reflection)
- MCP 客户端
- HITL(@RequireApproval + SSE tool_call_pending)
- Trace 升 span 树 + 前端 TraceTree

---

## 简历叙事框架

每个改造点都对应一个**对标 + 数字**的短故事:

| 层 | v1 现状 | v2 改造 | 业界对标 | 量化指标 |
|---|---|---|---|---|
| 评测 | topSource 字符串相等 | Recall/MRR/nDCG/keyword | BEIR/MTEB/Ragas | 30 条 holdout |
| Tool | 关键词 if-else | 真 function calling | OpenAI / Anthropic | tool 选择准确率 |
| 编排 | 单趟流水线 | ReAct StateGraph | LangGraph | 多步任务通过率 |
| RAG | dense + ILIKE + 手写 calibrator | Contextual + RRF + Self-RAG | Anthropic / Self-RAG | nDCG@5 提升 |
| Memory | 正则 facts 不持久化 | LLM 抽取 + add/update/merge | MemGPT / Mem0 | facts 准确率 |
| Tool 治理 | 零治理 | MCP + HITL + Resilience4j | Anthropic computer use | tool 失败率 |
| Trace | 扁平 KV | span 树 + replay | LangSmith / Phoenix | 时间轴可视化 |
