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

### C-2 ⏳ 扩充 baseline cases 到 30-50 条 + train/eval 拆分

**目标**: 用 7 条 fixture 算出来的数字统计意义不足。扩到 30-50 条,8:2 拆 train/eval,eval 集 holdout 不参与调试。

类型分布建议:
- 5 条 `direct-answer`(打招呼、能力提问、观点性问题)
- 3 条 `tool-answer`(time 工具的不同问法)
- 5 条 `long-context`(总结、跨轮引用、预算触发)
- 17 条 `rag-answer`(单 doc / 多 doc / 歧义召回 / 不相关 query / 多跳推理)
- 总计 ~30 条

每条带 `splitMode: "train" | "eval"` 标签。

---

### C-3 ⏳ 跑 v1 基线并归档

**目标**: 用真 LLM + 真 embedding 跑一遍 C-2 fixture,把数字归档到本文档,作为后续每个改造的对比基线。

---

## 阶段 A — Function Calling(待开始)

5 个子任务:
- A-1: `ToolDefinition` 加 `description` + JSON Schema(victools/jsonschema-generator)
- A-2: `ToolRegistry` 生成 `ToolSpecification` 列表
- A-3: 接入真 function calling,删除 `looksLikeTimeQuestion` 关键词路由
- A-4: Tool 调用错误恢复三件套(Retry / schema 校验 / observation 重投)
- A-5: 跑 v2 评测对比 v1

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
