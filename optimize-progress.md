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
- A-2: ✅ `ChatLanguageModel` 真 function calling 改造,删除 `looksLikeTimeQuestion` 关键词路由
- A-3: ✅ tool_calls 并行执行 + 错误回退到 observation
- A-4: ✅ 错误恢复三件套(Retry / schema 校验 / observation 重投)
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

### A-2 ✅ 真 function calling(2026-04-30 完成)

**做了什么**

把"`AgentOrchestrator` 内的 `looksLikeTimeQuestion` 关键词 if-else 路由"换成"真正的 LangChain4j function calling":

- 新增 `llm/ChatTurnResult` record:`{text, List<ToolExecutionRequest> toolCalls}` —— LLM 一轮可能输出 text 也可能输出 toolCalls
- `LlmRuntime` 接口加 default 方法 `generateWithTools(systemPrompt, messages, tools, metadata)` —— 默认 fallback 到 `generate(...)` 仅返回 text(向后兼容)
- `LangChain4jLlmRuntime.generateWithTools` 真路径用 `ChatRequest.builder().toolSpecifications(specs).build()` + `ChatModel.chat(ChatRequest)`,从返回的 `AiMessage.toolExecutionRequests()` 解析 toolCalls
- Mock 路径(本地无 API key 时)通过中文/英文时间关键词模拟 LLM 输出 `time` 工具调用,保留测试可观测性
- `AgentOrchestrator.decide()`:删除 `looksLikeTimeQuestion`,流程改为
  1. 先做 RAG 检索 → 命中即 `RouteDecision.retrieval`
  2. 否则调 `llmRuntime.generateWithTools(systemPrompt, [USER:msg], specs)` 让 LLM 决定工具
  3. LLM 输出 toolCalls → 解析参数 JSON → 执行 → `RouteDecision.tool`
  4. 否则 `RouteDecision.direct`
- `AgentOrchestrator` 注入 `LlmRuntime` + `AppProperties` + `ResourceLoader`,加载 system prompt 一同喂给工具决策模型
- 工具参数 JSON 用 Jackson `ObjectMapper.readValue(..., Map<String, Object>)` 解析,失败容错为空 map

**关键设计决策**

1. **Default 方法兜底,不破坏现有 LlmRuntime 实现** —— 旧 stub 不实现 `generateWithTools` 会自动 fallback 到 `generate(...)` 返回 text(toolCalls 为空),不是编译错误。新功能渐进引入。
2. **decide-with-LLM,不改 ChatService** —— `AgentOrchestrator.decide()` 内部多调一次 LLM 决定工具,但 `ChatService.chat()` 仍只看到 `RouteDecision`(retrieval/tool/direct),evidence block 通过 `TOOL RESULT: ...` 注入给最终生成。代价是每个非 RAG 请求 +1 次 LLM call,收益是 ChatService 流程不变,trace/SSE/error path 全部不动。
3. **Mock LLM 关键词模拟** —— stub 模式下,LangChain4jLlmRuntime / 测试 stub 都识别"几点/星期几/周几/几号/日期/time/date"等关键词,模拟真 LLM 会输出 `time` 工具调用的行为。这样所有 fixture 测试无需真 API key 就能验证 function calling 路径,简历也可挂"我有一套不依赖外部 API 的 fixture 验证 LLM 工具选择"。
4. **公开 5-arg `@Autowired` 构造器 + 包级 4-arg 测试构造器** —— Spring 看到 `@Autowired` 标注挑公开构造器,测试用 4-arg 注入 stub。比改 LlmRuntime 接口或 mock Spring context 简洁。
5. **Tool 选择只用单条 USER message,不带 history/summary** —— 节省 input token。代价:LLM 看不到 session 上下文,可能在多轮时漏掉工具机会。下一步 ReAct 改造里会让循环节点能复用历史。
6. **未注册工具兼容** —— LLM 幻觉式输出未注册工具名时,降级为 `RouteDecision.direct()` 不抛异常,只 warn log。这是 OpenAI cookbook 推荐的 robustness 写法。

**改动文件**

```
新增   src/main/java/com/zhituagent/llm/ChatTurnResult.java
修改   src/main/java/com/zhituagent/llm/LlmRuntime.java                 # +generateWithTools default
修改   src/main/java/com/zhituagent/llm/LangChain4jLlmRuntime.java      # +generateWithTools real+mock
修改   src/main/java/com/zhituagent/orchestrator/AgentOrchestrator.java # 删 looksLikeTimeQuestion, 加 LLM tool selection
修改   src/test/java/com/zhituagent/orchestrator/AgentOrchestratorTest.java  # 注入 stub LlmRuntime
修改   src/test/java/com/zhituagent/api/ChatControllerTest.java         # stub +generateWithTools
修改   src/test/java/com/zhituagent/api/ObservabilityEndpointTest.java  # stub +generateWithTools
修改   src/test/java/com/zhituagent/eval/BaselineEvalRunnerTest.java    # stub +generateWithTools
```

测试:`mvn -o test` 50/50 全绿。

---

### A-3 ✅ 并行 tool 执行 + observation 回退(2026-04-30 完成)

**做了什么**

把"LLM 输出多个 toolCalls 时只跑第一个、且任一工具抛异常即整轮 chat 失败"改成"全部并行跑、失败转成 observation 回投":

- 新增 `orchestrator/ToolCallExecutor` Spring bean
  - 内部 4 线程 `tool-exec-*` `FixedThreadPool` + 守护线程
  - `executeAll(List<ToolExecutionRequest>)` 用 `CompletableFuture.allOf` 并行触发,返回 `List<ToolExecution>`(每条 = `{ToolExecutionRequest, ToolResult}`)
  - 工具异常:`catch RuntimeException` 包成 `ToolResult.success=false, summary=tool execution failed: ...`,**不抛**
  - 未注册工具:返回 `ToolResult.success=false, summary=tool not registered: <name>`(避免 LLM 幻觉式工具名让 chat 崩盘)
  - 参数 JSON 解析失败:warn log + 空 map 兜底
  - `@PreDestroy` 关线程池
- `AgentOrchestrator` 把单 toolCall 处理逻辑全部下沉到 `ToolCallExecutor`,本身只负责:
  - 多个工具结果聚合 → `aggregate(List<ToolExecution>)`:N=1 直接返回原 `ToolResult`;N>1 拼成 `ToolResult("multi-tool", allSuccess, "[name1] sum1\n[name2] sum2", payload={name → {success,summary,payload}})`
  - 这样 `RouteDecision.tool` 单一形状不变,ChatService 仍只看到一个 `toolName/toolResult`,evidence block 仍走 `TOOL RESULT: ...` 注入

**关键设计决策**

1. **保 `RouteDecision.tool` 单一形状,不破契约** —— 多工具结果聚合到 `multi-tool` 复合 result。Trace / archive / SSE / metrics 全套不动。简历可挂"用 `payload` map 携带多工具明细"作为后续 trace 树升级的伏笔。
2. **Failure-as-observation 而非 throw** —— 这是 OpenAI cookbook + Anthropic tool use 推荐的 robustness 写法。LLM 在多轮里能看到失败原因并改写参数;A-4 retry 与 A-5 ReAct 循环都依赖这个语义。
3. **`CompletableFuture.allOf(...).join()` 而非 `.get(timeout)`** —— 当前没全局超时(单工具超时由工具自己处理)。A-4 引入 spring-retry / `@Timed` 时再补 per-tool timeout。
4. **未注册工具返回失败 result 而非 RouteDecision.direct** —— 这样 LLM 会在 observation 里看到"tool not registered: foo",下一轮可以纠正。比 direct 兜底更有教学价值。
5. **测试覆盖三种 observation** —— 新增 `ToolCallExecutorTest` 用 `CountDownLatch` 验证 fast-tool 与 slow-tool 真的并发(各自 start_order 都被记录),boom-tool 转成 success=false summary 含 "simulated tool failure",ghost-tool 不在 registry 里转成 "tool not registered"。这就是简历讲并行 + 失败回退 的 demo。

**改动文件**

```
新增   src/main/java/com/zhituagent/orchestrator/ToolCallExecutor.java
修改   src/main/java/com/zhituagent/orchestrator/AgentOrchestrator.java   # 用 ToolCallExecutor + aggregate
新增   src/test/java/com/zhituagent/orchestrator/ToolCallExecutorTest.java # 并行 + 三种失败回退
修改   src/test/java/com/zhituagent/orchestrator/AgentOrchestratorTest.java  # 注入 ToolCallExecutor
```

测试:`mvn -o test` 52/52 全绿(+2 新)。

---

### A-4 ✅ 错误恢复三件套(2026-04-30 完成)

**做了什么**

把 `ToolCallExecutor` 从"原样跑工具"升级到"在跑之前先校验 + 跑过之后看是不是 loop":

- 新增 `orchestrator/JsonArgumentValidator` 静态工具类
  - 输入:`JsonObjectSchema` + `Map<String,Object> arguments`
  - 校验:必填属性是否齐全、`additionalProperties=false` 时是否有意外键、基础类型(string/integer/number/boolean)
  - 失败时返回 `ValidationResult{valid=false, errors=List<String>}`,errors 直接 join 成 LLM 可读的 observation
- 新增 `orchestrator/LoopDetector`
  - 内部 `LinkedHashMap` access-order LRU(MAX_KEYS=256)
  - `record(toolName, argumentsJson)` → 计数 +1,key = `toolName + "#" + sha256(args)`
  - `loopThreshold()=3`:同 `(tool,args)` 第 3 次起被判定为环
- `ToolCallExecutor.executeOne` 串成新流程:
  1. 工具未注册 → "tool not registered"(A-3)
  2. **环检测命中** → "tool call loop detected: tool 'X' invoked N times with identical arguments. Please change arguments or pick a different tool."
  3. 解析 args JSON
  4. **schema 校验失败** → "argument validation failed: missing required property 'sourceName'; unexpected property 'foo'. Please re-issue the call with correct arguments matching the tool schema."
  5. 真正调用工具
  6. 抛异常 → "tool execution failed: ..."(A-3)

每一步失败都被包成 `ToolResult(success=false, summary=...)`,LLM 看到的 observation 都是结构化、可消费、能自纠的。

**故意省掉的:`spring-retry`**

- 原计划在 `LlmRuntime.generateWithTools` / `ToolDefinition.execute` 加 `@Retryable`
- 调研发现:LangChain4j 1.1.0 的 `RetryUtils` 已经在网络层做指数退避(rate limit / 5xx 自动重试 ≥3 次)。再叠加 spring-retry 会变成"重试 × 重试",rate limit 时反而加剧 429
- 工具层的失败大多是参数错误或业务异常(retry 没意义)。真正稀缺的"幂等可重试失败"只剩 LLM 输出 schema 不合规这一类,而 schema 校验已经把这种失败回投给 LLM 让它自行重发了 —— LLM 自己重试比 spring-retry 自动重发同一参数有效得多
- 简历叙事点:"没有为 retry 而 retry,把 retry 留给最有信号的层(LLM 自纠)"

**关键设计决策**

1. **Schema 校验只覆盖我们用到的子集** —— required / additionalProperties / 基础类型,不引入 `everit-org/json-schema-validator`(依赖 ~250 KB,功能过剩)。代价:不支持 anyOf/oneOf/pattern。如果以后要,Switch 到 victools 即可。
2. **LoopDetector 进程全局而非会话级** —— 当前每个 chat() 是一轮,环主要发生在 ReAct 多步循环里(SG 阶段)。SG 落地时会换成 per-conversation,但流程已先把"loop detected"这条 observation 信号建立起来。
3. **观察值文本是给 LLM 读的,不是给人看的** —— 错误信息明确告诉 LLM "Please re-issue the call with correct arguments matching the tool schema",这是 OpenAI cookbook + Anthropic guide 推荐的"用自然语言指令 LLM 自纠"。简历可挂"我把可观测性从面向工程师扩展到了面向 LLM"。
4. **测试三件套覆盖** —— 新增 `ToolCallResilienceTest`:missing required / additional property / 重复调用第 3 次触发 loop。前两次成功 + 第三次 loop,正好对应 `loopThreshold=3` 的边界。

**改动文件**

```
新增   src/main/java/com/zhituagent/orchestrator/JsonArgumentValidator.java
新增   src/main/java/com/zhituagent/orchestrator/LoopDetector.java
修改   src/main/java/com/zhituagent/orchestrator/ToolCallExecutor.java   # 串入 loop check + schema validate
新增   src/test/java/com/zhituagent/orchestrator/ToolCallResilienceTest.java
```

测试:`mvn -o test` 55/55 全绿(+3 新)。

---

### T1 ✅ Span 树 + SseEventType 枚举(后端)(2026-04-30 完成)

**做了什么**

把"扁平 KV trace"升级到"父子 span 树",并把 SSE 事件名从字符串字面量收紧到枚举:

- 新增 `trace/Span` record:`{spanId, parentSpanId, traceId, name, kind, startEpochMillis, endEpochMillis, status, attributes}` —— 直接对齐 OpenTelemetry / LangSmith run tree 的 schema 形态
- 新增 `trace/SpanCollector` 请求作用域 bean:`ThreadLocal` 持有当前 traceId + 开放栈 + completed 列表;支持嵌套 span,自动关联 parentSpanId;`drain()` 取出全部并清 ThreadLocal,未关闭的 span 标 "incomplete" 兜底
- 新增 `api/sse/SseEventType` 枚举:除了原有 `start/token/complete/error`,预留了 `span_start / span_end / retrieval_step / tool_start / tool_end / thinking_delta / tool_call_pending / tool_call_resolved` 8 个未来事件类型
- `TraceInfo` 加 `traceId` + `spans: List<Span>` 字段(向后兼容)
- `TraceArchiveEntry` 加 `traceId` + `spans` 字段(JSONL 归档随之带 span 树)
- `ChatTraceFactory` 调 `spanCollector.drain()` 把 span 树注入 `TraceInfo`
- `ChatService.chat()` 起 root span(`chat.turn` / kind=request)+ 3 个子 span(`orchestrator.decide` / `context.build` / `llm.generate`),失败路径也在 catch 里 `endSpan + drain`

**关键设计决策**

1. **ThreadLocal 而非 Spring 请求作用域 bean** —— 请求作用域 bean 在 `@Async` 子线程不可见,而 ThreadLocal 有同样问题但更轻量。当前 chat() 是单线程同步的;ToolCallExecutor 的并行 fan-out 在 `.join()` 之后才回到主线程,需要 SG 阶段补丁(把 spanContext 传到子线程)。T1 暂不做。
2. **未关闭 span 兜底为 "incomplete"** —— `drain()` 自动给所有还在栈里的 span 补 endTs + status=incomplete。这保证 trace 树不会出现"半开放节点",前端可视化时永远是良构 DAG。
3. **`SseEventType` 一次性把所有未来事件枚举登记** —— 即使现在 `span_start` 还没真实发出去(streamChat 路径还没插桩),提前把 enum 名占好,SG/HL/T2 加事件时一行 `SseEventType.SPAN_START.value()` 即可,不会再修 enum。简历可挂"我用 enum 把 SSE 协议契约固化,前后端类型可以镜像"。
4. **span attributes 是 Map<String, Object>** —— 给后续节点(retrieval / tool)放任意结构化数据(snippet count / tool name / arg hash),前端 SpanTree 可挂 detail panel。
5. **ChatTraceFactory 通过构造器加可选 SpanCollector** —— 老的无参构造器和单参构造器保留,旧测试不破坏(给一个 fresh SpanCollector 即可,drain 空列表)。

**改动文件**

```
新增   src/main/java/com/zhituagent/trace/Span.java
新增   src/main/java/com/zhituagent/trace/SpanCollector.java
新增   src/main/java/com/zhituagent/api/sse/SseEventType.java
修改   src/main/java/com/zhituagent/api/dto/TraceInfo.java          # +traceId +spans
修改   src/main/java/com/zhituagent/trace/TraceArchiveEntry.java    # +traceId +spans
修改   src/main/java/com/zhituagent/trace/TraceArchiveService.java  # 写 traceId+spans
修改   src/main/java/com/zhituagent/api/ChatTraceFactory.java       # 注入 SpanCollector
修改   src/main/java/com/zhituagent/chat/ChatService.java           # 4 个 span(root/route/context/llm)
修改   src/main/java/com/zhituagent/api/ChatController.java         # 字符串 → SseEventType.value()
新增   src/test/java/com/zhituagent/trace/SpanCollectorTest.java
```

测试:`mvn -o test` 57/57 全绿(+2 新)。

---

### SG ✅ ReAct 多轮循环(2026-04-30 完成)

**做了什么**

把"单次 LLM 工具决策 → 单次 LLM 生成答案"升级到"LLM ⇄ 工具多轮 ReAct 循环",最大 4 轮。新增 `orchestrator/AgentLoop` 单类替代完整 StateGraph 框架(避免 7-node 过度设计):

- `LlmRuntime` 加新方法 `generateChatTurn(systemPrompt, List<ChatMessage>, tools, metadata)` — 接受类型化 ChatMessage 而非字符串前缀,可正确 round-trip `AiMessage(toolCalls)` 与 `ToolExecutionResultMessage(toolCallId, name, content)`(OpenAI tool_call_id 协议)。default 实现 fallback 到 `generateWithTools` + 字符串化(测试 stub 默认走得通)
- `LangChain4jLlmRuntime.generateChatTurn` 真路径用 `ChatRequest.builder().messages(typed).toolSpecifications(specs).build()` + `ChatModel.chat(req)`
- 新增 `orchestrator/AgentLoop`:
  - `run(systemPrompt, userMessage, contextBundle, metadata, maxIters)` 返回 `LoopResult{finalAnswer, iterations, converged, executions, firstResultByTool}`
  - 每轮 = 1 个 `agent.iter` span,内嵌 1 个 `agent.llm_call` span;有工具调用时再起 1 个 `agent.tool_calls` span。失败时 `endSpan` 状态为 `continue`,完成时为 `ok`
  - LLM 输出 text(无 toolCalls) → finalAnswer,converged=true 退出
  - LLM 输出 toolCalls → 拼 `AiMessage.from(text, toolCalls)` 进 conversation,`ToolCallExecutor.executeAll(...)` 并行执行,每个结果转 `ToolExecutionResultMessage` 接到 conversation 尾,继续下一轮
  - 触顶 maxIters 仍未收敛 → `composeStepLimitFallback`:`"[reached step limit] partial observations: [time] current time is X; ..."`
- `bootstrap(contextBundle, userMessage)`:把 `contextBundle.modelMessages` 字符串前缀(SUMMARY:/EVIDENCE:/USER:/ASSISTANT:)还原成类型化 ChatMessage,确保新旧 ContextBundle 都能喂给 ReAct 循环。最后兜底:若末尾不是 UserMessage,补一条
- `AppProperties` 加 `react-enabled` (default `false`) + `react-max-iters` (default `4`):**默认关闭,完全向后兼容**;A-5 跑 v2 eval 时打开
- `ChatService.chat()`:`reactEnabled && !routeDecision.retrievalHit()` 时走 `agentLoop.run(...)` 替代单次 `llmRuntime.generate(...)`;循环结束后从 `LoopResult.firstSuccessfulResult()` 反推 `RouteDecision.tool(...)`,trace/SSE/archive 全套 schema 不变

**关键设计决策**

1. **单类 `AgentLoop` 不是 7-node StateGraph** — 当前节点只有 LlmCall 与 CallTool 两类,自循环。Plan/Reflect/Self-RAG/Route 都在外层(orchestrator.decide 已做 RAG check)或下个子任务(SR)再做。如果硬塞 7 个节点类全是空 stub,测试覆盖反而稀薄。等 SR/HL 阶段需要更复杂条件边时再升级到真正的 StateGraph 框架(可考虑 langgraph4j 或自研 DSL)
2. **`react-enabled` 默认 false** — 不破坏现有 ChatControllerTest / ObservabilityEndpointTest 等 16 个集成测试。ReAct 路径通过 `AgentLoopTest` 直接验证。A-5 真 eval 时打开比较 v1 (false) vs v2 (true)
3. **`generateChatTurn` 单独走** — 没有把 `generateWithTools` 升级成接受 `List<ChatMessage>`,因为现有 `AgentOrchestrator.decide()` 单次 tool selection 仍依赖字符串前缀(老接口够用),A-2 兼容性不破。两个方法并存:单次决策用 string,多轮循环用 ChatMessage
4. **Span 嵌套 3 层** — `chat.turn`(root)→ `agent.iter`(每轮)→ `agent.llm_call` / `agent.tool_calls`。前端 SpanTree(T2)直接渲染时间轴树。简历可挂"我用嵌套 span 实现了 LangSmith 风格的 trace tree,每轮迭代独立可视化"
5. **`composeStepLimitFallback` 把 partial observation 拼成兜底答案** — 比单纯返回空字符串更能体现"reached step limit but here's what I found"。LLM 后续可基于此判断是否需要重新提问
6. **保留 `RouteDecision.tool` 单一形状** — `firstResultByTool` map 携带多工具明细,但 `RouteDecision` 只记录"第一个成功的工具",ChatService.trace 仍走旧 path。多工具完整明细在 trace span attributes 里展开

**改动文件**

```
新增   src/main/java/com/zhituagent/orchestrator/AgentLoop.java          # 单类 ReAct 循环
修改   src/main/java/com/zhituagent/llm/LlmRuntime.java                  # +generateChatTurn default
修改   src/main/java/com/zhituagent/llm/LangChain4jLlmRuntime.java       # generateChatTurn 真实现
修改   src/main/java/com/zhituagent/config/AppProperties.java            # +reactEnabled+maxIters
修改   src/main/java/com/zhituagent/chat/ChatService.java                # reactEnabled 分支调 AgentLoop
新增   src/test/java/com/zhituagent/orchestrator/AgentLoopTest.java      # 多轮收敛 + maxIter fallback
```

测试:`mvn -o test` 59/59 全绿(+2 新)。

**怎么开 v2**: `application-local.yml` 加 `zhitu.app.react-enabled: true` 即可。下次 A-5 eval 会自动跑通 ReAct 路径。

---

### CR-1 ✅ Contextual Retrieval 三件套(2026-04-30 完成)

**做了什么**

CR 拆分:CR-1 做"零 schema 风险三件套",CR-2(真 BM25 + Flyway + jieba/tsvector)推迟到 v2 数字出来后再做。

- **(a) chunkId 改 sha256** — `KnowledgeStoreIds.computeChunkId(source, content)` 用 SHA-256 取前 16 hex 字符,得到 `<source>#<hash>` 形式 chunkId。`KnowledgeIngestService` 删掉 `AtomicInteger chunkCounter`(原来重启漂移)。`InMemoryKnowledgeStore` 内部从 `CopyOnWriteArrayList<KnowledgeChunk>` 换成 `LinkedHashMap<chunkId, KnowledgeChunk>`,行为与 PgVectorEmbeddingStore.addAll 的 ON CONFLICT UPSERT 对齐。重复 ingest 不再产生重复 row,简历可挂"幂等 ingest"。
- **(b) ContextualChunkAnnotator** — Anthropic Contextual Retrieval 落地:`@Component` 持有 `LlmRuntime + RagProperties`,在 `KnowledgeIngestService.ingest` 内部对每个 chunk 调 `annotate(fullDoc, chunk)` → 短上下文前缀,embed 用 `prefix\n\nchunk`。提示模板就是 Anthropic 官方 cookbook 的那段 `<document>... <chunk>... give a short succinct context...`。`KnowledgeChunk` record 加 `embedText` 字段(默认 null);`PgVectorKnowledgeStore.addAll` 走 `chunk.effectiveEmbedText()` 喂 embedding model,但 `metadata.rawText` 仍存原 chunk → `toSnippet` 取 rawText 优先,evidence/lexical 见到的还是干净的原文。`RagProperties.contextualEnabled=false` 默认关。LLM 失败/返回空 → 回退原文,**eval/CI 不破**。
- **(c) RrfFusionMerger** — 新增 `RrfFusionMerger` 实现 Reciprocal Rank Fusion:`score = Σ 1/(60 + rank_i)`,只看排名不看分数,跨模式天然校准,不再需要 `RerankResultCalibrator` 那种手写 bonus。`HybridRetrievalMerger` 加 strategy switch,根据 `RagProperties.fusionStrategy=linear|rrf` dispatch。默认 `linear` 兼容现有 v1 数字;A-5 eval 时 toggle `rrf` 看 nDCG@5 提升幅度。

**关键设计决策**

1. **CR 拆 CR-1 / CR-2** — Flyway + Jieba + tsvector 一起 land 表迁移风险大,容易把 langchain4j EmbeddingStore 自动建表逻辑打架。先做"不动表"的三件套,跑 v2 eval 拿到数字,再回头评估真 BM25 是否还需要。简历叙事:"把高风险迁移和高 ROI 改造解耦,有数字驱动的优先级"。
2. **chunkId 用 `<source>#<sha16>` 而非纯 sha** — chunkId 在 trace/log 里要可读,`source` 仍出现作为前缀肉眼可识别;sha 只取 16 hex (64 bit) 抗碰撞,KB 级 dataset 不可能撞。同时旧的 `KnowledgeStoreIds.toEmbeddingId(chunkId)` 仍把 chunkId 经 UUID v3 投到 stable UUID,pgvector 端 ON CONFLICT 自动 UPSERT。
3. **embedText 不入 record canonical 字段以外的位置** — 只在内存 record 上携带,`metadata.rawText` 保存原文。这样 schema 不动(metadata 是 jsonb),`toSnippet` 取 `rawText` 优先回到原文。代价:metadata 多存一份原文(KB 级 chunk 才几百字符),收益:不破 pgvector 表 schema,CR-2 才动表。
4. **Annotator 默认关 + LLM 失败回退** — 真 LLM 调用才有效,mock/test 路径不受影响;`isEnabled() && response.isBlank()` 兜底回退到原 chunk。这意味着 contextual 是"有就用、没有也能跑"的渐进 enhancement,A-5 eval 时打开比较 v1/v2 数字干净。
5. **RRF 不需要分数校准** — RRF 的核心论点:不同检索器分数尺度不可比(cosine 0-1 vs ILIKE 整数计数),用 rank 替代是经典做法。简历可挂"用 RRF 替换手写线性加权 + bonus,业内对标 Pinecone/Vespa/Elastic 的标准做法"。
6. **保留 0-arg `HybridRetrievalMerger()`** — `RagRetriever` 内部老的 4-arg 构造器还在用 `new HybridRetrievalMerger()`,保留 0-arg 便利构造器(默认 RagProperties + 默认 RrfFusionMerger),不破老代码。

**改动文件**

```
新增   src/main/java/com/zhituagent/rag/ContextualChunkAnnotator.java
新增   src/main/java/com/zhituagent/rag/RrfFusionMerger.java
修改   src/main/java/com/zhituagent/rag/KnowledgeStoreIds.java       # +computeChunkId
修改   src/main/java/com/zhituagent/rag/KnowledgeChunk.java          # +embedText +effectiveEmbedText
修改   src/main/java/com/zhituagent/rag/KnowledgeIngestService.java  # sha256 chunkId + Annotator 钩子
修改   src/main/java/com/zhituagent/rag/InMemoryKnowledgeStore.java  # LinkedHashMap UPSERT
修改   src/main/java/com/zhituagent/rag/PgVectorKnowledgeStore.java  # effectiveEmbedText + rawText metadata
修改   src/main/java/com/zhituagent/rag/HybridRetrievalMerger.java   # +RagProperties+RrfFusionMerger strategy switch
修改   src/main/java/com/zhituagent/config/RagProperties.java        # +contextualEnabled +fusionStrategy
新增   src/test/java/com/zhituagent/rag/KnowledgeIngestServiceIdempotencyTest.java  # 4 cases
新增   src/test/java/com/zhituagent/rag/ContextualChunkAnnotatorTest.java          # 5 cases
新增   src/test/java/com/zhituagent/rag/RrfFusionMergerTest.java                   # 5 cases
修改   src/test/java/com/zhituagent/rag/KnowledgeStoreIdsTest.java                 # +5 cases
修改   src/test/java/com/zhituagent/rag/HybridRetrievalMergerTest.java             # +2 cases (strategy switch)
```

测试:`mvn -o test` 80/80 全绿(+21 新)。

**怎么开 v2**: `application-local.yml`:
```yaml
zhitu:
  rag:
    contextual-enabled: true
    fusion-strategy: rrf
  app:
    react-enabled: true
```
A-5 跑 v2 baseline 时所有三件套同时启用,与 v1 对比表的"对照量"就是这三个 flag 的 false→true。

---

### SR ✅ Self-RAG 评估 + query 改写(2026-04-30 完成)

**做了什么**

把"检索一次就交差"升级到"LLM 评判检索是否充分,不充分则改写 query 重检索",最多 2 次。基于 Asai et al. 2023 的 Self-RAG 范式 + Yan et al. 2024 的 CRAG 思想。

- 新增 `rag/SelfRagDecision` record:`{sufficient, rewrittenQuery, reason}`,`sufficient`/`insufficient` 静态工厂语义清晰。
- 新增 `rag/SelfRagOrchestrator` (@Component,Strategy/Decorator pattern):
  - 注入 `RagRetriever + LlmRuntime + RagProperties + SpanCollector`
  - `retrieveWithRefinement(query, limit, options)`:先正常检索;开关 ON 且 maxRewrites>0 时,进入 LLM 评判 loop
  - LLM prompt 让模型回答严格 JSON `{"sufficient": <bool>, "rewrite": "<alternate query if not sufficient>"}`
  - 不充分 → 用 rewrite 重检索,最多 N 次
  - 触顶仍未 sufficient → `pickBest()` 返回 top-1 score 最高的那一轮,**不返回最后一轮(防止越改越差)**
  - 失败模式:LLM throw / 返回空 / JSON 解析失败 → 回退 `sufficient=true`,**永不死循环**
  - LLM 包 ```json\n{...}\n``` 的 markdown fence 自动 strip
  - 同一 rewrite == 原 query(LLM 不思考)→ 立即停止
  - 每轮起一个 `rag.self_rag.iter` span 带 iteration / next_query / decision 属性,与 SG 的 agent.iter span 对称
- `RagProperties` 加 `selfRagEnabled=false` (默认关) + `selfRagMaxRewrites=2`
- `AgentOrchestrator.decide()`:抽 `retrieveWithOptionalSelfRag(...)` 私有方法,根据 `selfRagOrchestrator.isEnabled()` 分发到 SelfRagOrchestrator 或直接走 RagRetriever。AgentOrchestrator 加 6-arg 测试构造器接受 SelfRagOrchestrator(可空),5-arg 老构造器 delegate 到 6-arg 传 null,旧测试不破。

**关键设计决策**

1. **Strategy/Decorator 而非内嵌 RagRetriever** — RagRetriever 仍只负责"一次检索"职责,SelfRagOrchestrator 是 decorator 包装,层级清晰。简历可挂"用 decorator pattern 把 LLM 评判旁路化,RAG 链路单元可独立替换"。
2. **`pickBest` 返回最高 top-score 那一轮** — Self-RAG 论文里 LLM 评判可能误判(尤其 mock/小模型),如果不充分但 retrieve 已经命中 gold doc,我们让分数说话;CRAG 也强调"refine 不一定 monotonic"。代价:如果分数 calibration 跨 query 不一致,这条 fallback 可能选错;但比"返回最后一轮"安全得多。
3. **LLM 失败 = sufficient,不是 insufficient** — 反直觉但正确:Self-RAG 的失败模式是 LLM stalling 或 timeout,如果失败时假设 insufficient 会触发 rewrite → 继续 stalling,死循环。失败时 假设 sufficient 让外层流程拿到原始 retrieval,降级体验比死循环好。
4. **JSON 严格解析 + markdown fence 兼容** — GLM-5.1 / Claude / GPT 都偶尔会包 ```json``` fence。`stripMarkdownFence()` 一行搞定;主路径仍要求严格 JSON,失败回退到 sufficient。简历可挂"我已经知道国产 LLM 在 JSON output 上的边界 case,默认有 fence 处理"。
5. **rewrite == 原 query 的早退检测** — LLM 标 `sufficient=false` 但又给出和原 query 一样的 `rewrite`,实际是它无法想出更好的方法。直接退出循环避免无意义重检索。
6. **`@Autowired` + 单参备选构造器** — Spring 注入 4-arg(含 SpanCollector),测试 3-arg(自己 new SpanCollector)。SelfRag 在没 active trace 时 `startSpan` 返回 null + `endSpan(null,...)` no-op,所以单测不必先 `beginTrace()`。
7. **空初始 retrieval 不走 LLM** — `evaluate()` 入口判 `result.snippets().isEmpty()` 直接 short-circuit 为 insufficient。这避免给 LLM 喂"评估这 0 个 snippet"的废话,省 1 次 LLM call。

**改动文件**

```
新增   src/main/java/com/zhituagent/rag/SelfRagDecision.java
新增   src/main/java/com/zhituagent/rag/SelfRagOrchestrator.java
修改   src/main/java/com/zhituagent/config/RagProperties.java               # +selfRagEnabled+selfRagMaxRewrites
修改   src/main/java/com/zhituagent/orchestrator/AgentOrchestrator.java     # 注入 SelfRag + 分发 decide
新增   src/test/java/com/zhituagent/rag/SelfRagOrchestratorTest.java        # 9 cases
```

测试:`mvn -o test` 89/89 全绿(+9 新)。

**怎么开 v2**: `application-local.yml` 加:
```yaml
zhitu:
  rag:
    self-rag-enabled: true
    self-rag-max-rewrites: 2
```
A-5 baseline 比对时打开此 flag,看那些 v1 检索 miss 的 case 是否被 LLM-driven rewrite 救回。

---

### T2 ✅ 前端 SpanTree(2026-04-30 完成)

**做了什么**

把扁平 `TracePanel` 的 KV 升级到嵌套时间轴树。后端 T1/SG/SR 已经在 chat.turn / agent.iter / agent.llm_call / agent.tool_calls / rag.self_rag.iter 等关键节点起好了 span,前端直接消费 `complete` 事件携带的 spans 数组重建树,按起始时间排序。

- `frontend/src/types/api.ts`:新增 `Span` 接口(对齐后端 `com.zhituagent.trace.Span` record:`spanId/parentSpanId/traceId/name/kind/start/end/status/attributes`)。`TraceInfo` 加 `traceId`、`spans`、`retrievedSources` 三个字段(原 wire format 已经在送,前端 TS 类型补齐)。
- `frontend/src/hooks/useStreamingChat.ts`:`emptyTrace` 加默认空 `traceId/spans/retrievedSources`,onComplete 不需要改 — 整个 TraceInfo 整体传递。
- 新增 `frontend/src/components/layout/SpanTree.tsx`(单文件 ~190 行):
  - `buildTree(spans)`:扫一遍建 `Map<spanId, node>`,二次扫挂 `parentSpanId` 为 children;DFS stamp depth + 按 startEpochMillis 排序 → roots 列表
  - `SpanNode` 递归渲染,缩进 `depth * 14px`,显示 name | kind chip | duration ms | status badge | timeline bar(以 `(start - minStart)/totalDuration` 为 offset,以 `duration/totalDuration` 为 width)
  - 子节点折叠/展开 (`<ChevronRight/Down>`),attributes 折叠为 "查看 N 项属性" 按钮 → 展开 key/value 表格
  - status 颜色:`ok`(绿)/`error`(红)/`incomplete`/`continue`(黄)
- 新增 `frontend/src/components/layout/SpanTree.css`(~190 行):glassmorphism + `tabular-nums` + status 颜色 ramp,与现有 TracePanel 视觉一致
- `TracePanel.tsx`:在 `aside-extended` 内嵌 `<SpanTree spans={trace.spans} />`,只在有 span 时渲染

**关键设计决策**

1. **客户端建树而非后端送好** — 后端 spans 是平铺数组(简化序列化),前端拿到自己组树。优点:后端不需要维护 nested 结构序列化,SSE wire format 始终是 flat list 的 JSON 数组,易于 archive/replay/diff。简历卖点:"flat-on-wire, tree-in-view 是 OpenTelemetry/LangSmith trace UI 的标准做法"。
2. **timeline bar offset+width 用百分比** — 任何 chat.turn 的总时长不一样,bar 用相对百分比自动适应;同一深度的兄弟 span 起止位置反映真实时序(不是均匀分布)。简历可挂"用 timeline bar 让时间轴 + 嵌套树同框,LangSmith 同款"。
3. **attributes lazy 展开** — 默认折成一个按钮,点击才显示表格。span 数量多时(ReAct 4 轮 + 每轮 3 个 sub-span ≈ 12 span)默认全展开会让 panel 很拥挤;按需展开是体验加分。
4. **`SpanNode` 递归而非扁平 + flex indent** — 递归更直观,depth 缩进 = `depth * 14px`,层级关系一目了然。代价:深度过深时 css padding 累加,但 chat.turn 一般不超过 4 层,可控。
5. **客户端类型与后端 Java record 1:1** — `Span` interface 字段名/类型与 `com.zhituagent.trace.Span` record 完全对齐,改任一边都要同步另一边。简历可挂"前后端 trace 协议契约由 SseEventType enum + Span record 双向锁死"。
6. **不引第三方 D3/Vis lib** — 全靠 React + framer-motion + 几条 CSS gradient bar 搞定可视化,bundle size 不变。如果未来要 zoomable timeline 再换 visx/d3。

**改动文件**

```
修改   frontend/src/types/api.ts                                # +Span +traceId +spans +retrievedSources
修改   frontend/src/hooks/useStreamingChat.ts                   # emptyTrace 补默认
新增   frontend/src/components/layout/SpanTree.tsx              # 递归节点 + buildTree
新增   frontend/src/components/layout/SpanTree.css              # glassmorphism + status 颜色
修改   frontend/src/components/layout/TracePanel.tsx            # 嵌入 SpanTree
```

测试:`npx tsc --noEmit` clean(无 TS 错误);后端 mvn 89/89 仍绿(后端没碰)。

**怎么看效果**: 在 `application-local.yml` 打开 `react-enabled: true` + `self-rag-enabled: true`,问一个会触发工具/RAG/Self-RAG 的复合问题,前端 Run Trace 面板下方就会出现 SpanTree:`chat.turn` 根 → `orchestrator.decide` / `context.build` / `llm.generate` 子 → `agent.iter`(每轮)/`rag.self_rag.iter`(每次重检索)。

---

## 阶段 2 — 差异化亮点(待开始)

7 个子任务,任挑两三个写进简历即可:
- ✅ ReAct/StateGraph 循环(LangGraph 对标)— 见 SG 段落
- ✅ Anthropic Contextual Retrieval + 真 BM25 + RRF — 见 CR-1 段落(CR-2 真 BM25 推迟)
- ✅ Self-RAG / CRAG 检索充分性评估 — 见 SR 段落
- ✅ Trace 升 span 树 + 前端 TraceTree(LangSmith 对标)— 见 T2 段落
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
