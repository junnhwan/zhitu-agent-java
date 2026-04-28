# Zhitu Agent Java 第二阶段优化计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变“单模块 Spring Boot + 项目自控编排”前提下，把第一阶段已经打通的 Java Agent 主链升级成一个可量化优化、可做简历数据沉淀的后端 Agent 系统。

**Architecture:** 第二阶段不推翻现有主链，也不把核心流程迁移成 LangChain4j `AiServices` 黑盒。继续保留 `API -> session/memory -> context -> orchestrator -> rag/tool/direct-answer -> llm -> writeback` 这条显式链路，围绕现有 `RagRetriever`、`PgVectorKnowledgeStore`、`MemoryService`、`ChatController`、`TraceInfo` 做增量增强。

**Tech Stack:** Java 21、Spring Boot 3.5.9、Spring MVC、LangChain4j 1.1.0、Redis、PostgreSQL + pgvector、Micrometer、Prometheus、JUnit 5

---

## 1. 背景与阶段结论

第一阶段基线已经完成，并已在以下提交落盘：

- `f5b66c3 feat: complete phase-one backend baseline and tracing`

当前项目已经具备：

- 单模块 Spring Boot 后端骨架
- 会话管理与 SSE 对话
- 基础 Context 管理与 summary 压缩
- Redis 会话 / 记忆持久化第一版
- pgvector dense retrieval 第一版
- 3 个内置工具的 ToolUse 第一版
- 真实 OpenAI 兼容模型接入
- 最小 trace 与 baseline eval fixture

对比 `ByteCoach` 后，当前最值得优先补齐的差距不是“功能数量”，而是下面三类能力：

- RAG 质量：当前只有单阶段 dense topK，没有 rerank、没有 hybrid retrieval、没有中文友好的 query / chunk 策略
- 可量化评估：当前只有 baseline fixture，没有可运行的评估器，优化前后无法稳定出数据
- 可观测性：当前主要靠日志，没有 Prometheus 指标，也没有面向优化的数据面板

第二阶段的核心结论是：

1. 先把“评估器”补出来，否则后面的优化没有对照基线
2. 再优先补 `dense recall -> rerank`，这是当前最短路径、最容易显著提升 RAG 质量的一步
3. 再补 hybrid retrieval 和中文切分，继续提升召回与排序质量
4. 同步补 Prometheus 指标与更细粒度 trace，让优化结果能被量化展示
5. Redis 并发保护、MCP、多 Agent、自动热加载等能力保留到第二阶段后半段或后续阶段

## 2. 第二阶段目标与非目标

### 2.1 第二阶段目标

- 提供可运行的离线评估入口，能输出优化前后的对比结果
- 在现有 dense retrieval 主链上接入 rerank 与查询预处理
- 在不新增 Elasticsearch 等中间件的前提下，补齐轻量 hybrid retrieval
- 暴露 Prometheus 指标，形成日志 + trace + metrics 的三层观测
- 补齐记忆压缩 / 写回路径的最小并发保护
- 产出能直接写进简历的量化指标口径

### 2.2 第二阶段明确不做

- 不拆多模块
- 不引入新的重型检索基础设施
- 不把主链改成 LangChain4j `AiServices` 驱动
- 不优先做 MCP、多 Agent、邮件工具、知识库自动热加载
- 不把前端联调作为第二阶段后端优化的前置条件

## 3. 第二阶段优先级

### 3.1 必做项

- `Task 1`：评估基线运行器 + trace 扩展
- `Task 2`：查询预处理 + dense recall -> rerank
- `Task 3`：hybrid retrieval + 中文优化切分
- `Task 4`：Prometheus 指标 + 记忆并发保护

### 3.2 延后项

- MCP 工具协议集成
- 多 Agent 抽象
- 知识库自动热加载
- 邮件 / 通知类工具
- 深层长期记忆与用户画像

## 4. 第二阶段文件落点

以下是第二阶段大概率要新增或修改的代码位置，后续实现应尽量沿用现有包结构：

### 4.1 评估与 trace

- 新增：`src/test/java/com/zhituagent/eval/BaselineEvalRunner.java`
- 新增：`src/test/java/com/zhituagent/eval/BaselineEvalCase.java`
- 新增：`src/test/java/com/zhituagent/eval/BaselineEvalResult.java`
- 新增：`src/test/java/com/zhituagent/eval/BaselineEvalRunnerTest.java`
- 修改：`src/test/resources/eval/baseline-chat-cases.jsonl`
- 修改：`src/main/java/com/zhituagent/api/ChatTraceFactory.java`
- 修改：`src/main/java/com/zhituagent/api/dto/TraceInfo.java`
- 修改：`src/main/java/com/zhituagent/orchestrator/RouteDecision.java`

### 4.2 检索与 rerank

- 新增：`src/main/java/com/zhituagent/config/RerankProperties.java`
- 新增：`src/main/java/com/zhituagent/rag/QueryPreprocessor.java`
- 新增：`src/main/java/com/zhituagent/rag/RerankClient.java`
- 新增：`src/main/java/com/zhituagent/rag/OpenAiCompatibleRerankClient.java`
- 新增：`src/main/java/com/zhituagent/rag/RetrievalCandidate.java`
- 修改：`src/main/java/com/zhituagent/config/InfrastructureConfig.java`
- 修改：`src/main/java/com/zhituagent/rag/RagRetriever.java`
- 修改：`src/main/java/com/zhituagent/rag/KnowledgeSnippet.java`
- 修改：`src/main/resources/application.yml`

### 4.3 Hybrid retrieval 与中文切分

- 修改：`src/main/java/com/zhituagent/rag/DocumentSplitter.java`
- 新增：`src/main/java/com/zhituagent/rag/LexicalRetriever.java`
- 新增：`src/main/java/com/zhituagent/rag/HybridRetrievalMerger.java`
- 修改：`src/main/java/com/zhituagent/rag/PgVectorKnowledgeStore.java`
- 修改：`src/main/java/com/zhituagent/rag/KnowledgeIngestService.java`
- 新增：`docs/sql/03-add-hybrid-retrieval-support.sql`

### 4.4 观测与并发保护

- 修改：`pom.xml`
- 修改：`src/main/resources/application.yml`
- 新增：`src/main/java/com/zhituagent/metrics/AiMetricsRecorder.java`
- 新增：`src/main/java/com/zhituagent/metrics/RagMetricsRecorder.java`
- 新增：`src/main/java/com/zhituagent/metrics/ToolMetricsRecorder.java`
- 修改：`src/main/java/com/zhituagent/api/ChatController.java`
- 修改：`src/main/java/com/zhituagent/llm/LangChain4jLlmRuntime.java`
- 修改：`src/main/java/com/zhituagent/rag/RagRetriever.java`
- 修改：`src/main/java/com/zhituagent/memory/MemoryService.java`
- 新增：`src/main/java/com/zhituagent/memory/RedisMemoryLock.java`

## 5. Task 1：评估基线运行器 + trace 扩展

**目标：** 把当前的 `baseline-chat-cases.jsonl` 从“静态样例文件”升级为“可运行、可对比、可保存结果”的评估入口。

**为什么先做：**

- 没有评估器，就没有优化前后的量化对比
- rerank、hybrid retrieval、上下文优化都需要统一评测口径
- 这是后续写简历数据和项目复盘的基础

**交付内容：**

- 支持读取 `src/test/resources/eval/baseline-chat-cases.jsonl`
- 支持执行 direct / rag / tool / long-context 四类样例
- 输出以下结果：
  - 路由命中率
  - RAG 命中率
  - Tool 命中率
  - 评估样例平均耗时、P50、P90
  - 平均 `inputTokenEstimate`、`outputTokenEstimate`
- 生成本地报告文件，建议落到：
  - `target/eval-reports/baseline-latest.json`
- 为后续 A/B 对比预留“baseline vs current”结果结构

**实现要点：**

- 评估入口优先放在 `src/test/java`，避免把第二阶段实验工具过早塞进生产主链
- `TraceInfo` 需要补足后续评估要读取的字段，例如：
  - `retrievalMode`
  - `contextStrategy`
  - `snippetCount`
  - `topSource`
  - `topScore`
  - 可选 `rerankModel`
  - 可选 `rerankTopScore`
- `RouteDecision` 需要保留更多中间态数据，避免评估时只能看最终布尔值

**验收标准：**

- 能通过一条命令跑评估
- 能稳定输出 JSON 结果
- 后续 rerank / hybrid 改造前后，能直接复用同一套样例做对比

**建议命令：**

```powershell
.\mvnw.cmd -Dtest=BaselineEvalFixtureTest,BaselineEvalRunnerTest test
```

## 6. Task 2：查询预处理 + dense recall -> rerank

**目标：** 在不改变现有 pgvector 主链的前提下，先把单阶段 dense retrieval 升级成“两阶段检索”的最小可用版本。

**当前问题：**

- 只有 dense topK，误召回和排序误差都会直接带到答案里
- 中文口语问题、带标点的问题、带冗余修饰的问题，召回稳定性不够
- 现在的 `retrievalHit=true` 只能说明“向量搜到了东西”，不能说明“搜到的是最相关的东西”

**交付内容：**

- 新增中文友好的 `QueryPreprocessor`
  - 标点清洗
  - 多空格归一化
  - 常见无意义问句尾词处理
- `RagRetriever` 改成：
  - dense recall `topK=20~30`
  - rerank 精排 `topN=5`
  - 最终注入上下文 `topM=3~5`
- rerank 走用户现有 OpenAI 兼容中转接口，不改动密钥管理方式
- 在配置中支持开关：
  - 启用 / 禁用 rerank
  - recall topK
  - rerank topN
  - 最终注入数量

**实现要点：**

- 新增 `RerankClient` 抽象，避免把 HTTP 细节直接塞进 `RagRetriever`
- 继续保持“配置齐全时走真实 rerank，配置缺失时优雅降级”的模式
- `KnowledgeSnippet` 或中间结构里要保留：
  - dense score
  - rerank score
  - source
  - chunkId
- `TraceInfo.retrievalMode` 建议开始区分：
  - `dense`
  - `dense-rerank`

**验收标准：**

- 有至少一组中文查询用例在 rerank 开启后结果优于 dense-only
- `trace` 和日志里能看出 recall 数量、rerank 数量、最终使用的证据数量
- rerank 服务异常时，系统能自动降级回 dense-only，而不是整条链失败

**建议命令：**

```powershell
.\mvnw.cmd -Dtest=KnowledgeIngestServiceIntegrationTest,AgentOrchestratorTest,BaselineEvalRunnerTest test
```

## 7. Task 3：hybrid retrieval + 中文优化切分

**目标：** 在继续只依赖 PostgreSQL + pgvector 的前提下，补齐轻量 sparse/dense 混合检索，并让中文文档切分更贴近语义边界。

**为什么排在 rerank 后面：**

- rerank 是当前收益最高、改动面更可控的一步
- hybrid retrieval 要牵涉召回合并、去重、SQL 支持和切分策略调整，复杂度更高

**交付内容：**

- `DocumentSplitter` 从固定 240 字符切块升级为中文友好的切分策略
  - 优先按 `。！？；` 等标点切段
  - 建议 chunk 大小 `600~800` 字
  - 建议 overlap `100~200` 字
- 在 PostgreSQL 中增加轻量 lexical retrieval 支持
  - 优先考虑 `tsvector + ts_rank`
  - 不引入 Elasticsearch
- `RagRetriever` 升级成：
  - dense recall
  - lexical recall
  - merge / dedupe
  - rerank final list
- 新增 SQL 脚本，保证用户能自己在云上数据库执行

**实现要点：**

- 新增 `docs/sql/03-add-hybrid-retrieval-support.sql`
- 不建议把 lexical retrieval 写成纯 Java 遍历，避免知识规模扩大后退化太快
- 合并策略建议优先基于 `chunkId` 去重，再按 rerank 排序
- `TraceInfo.retrievalMode` 建议扩展到：
  - `hybrid-rerank`

**验收标准：**

- 新增至少一组“关键词明确但语义表达不稳定”的样例，验证 hybrid 比 dense-only 更稳
- 中文长文档切分后，chunk 可读性明显优于固定 240 字符切块
- SQL 脚本可独立执行，不依赖 Docker

**建议命令：**

```powershell
.\mvnw.cmd -Dtest=KnowledgeIngestServiceIntegrationTest,BaselineEvalRunnerTest test
```

## 8. Task 4：Prometheus 指标 + 记忆并发保护

**目标：** 让第二阶段优化不只“能跑”，还“能被持续观测”，同时补掉记忆压缩路径的并发隐患。

**交付内容：**

- 引入：
  - `spring-boot-starter-actuator`
  - `micrometer-registry-prometheus`
- 暴露：
  - `/actuator/health`
  - `/actuator/prometheus`
- 至少补齐以下指标：
  - `zhitu_chat_requests_total`
  - `zhitu_chat_request_duration_seconds`
  - `zhitu_llm_requests_total`
  - `zhitu_llm_request_duration_seconds`
  - `zhitu_rag_retrieval_total`
  - `zhitu_rag_retrieval_duration_seconds`
  - `zhitu_rag_recall_size`
  - `zhitu_rerank_requests_total`
  - `zhitu_tool_invocations_total`
  - `zhitu_memory_compression_total`
- 对 Redis 记忆压缩 / 写回路径增加最小并发保护
  - 基于 `SETNX + EXPIRE`
  - 安全释放建议走 Lua 或 value compare

**关键约束：**

- `sessionId`、`userId`、`requestId` 不直接作为 Prometheus label，避免高基数
- 这些维度继续放在日志和 trace 里
- Prometheus 指标重点看：
  - model
  - route path
  - retrieval mode
  - tool name
  - success / failure

**实现要点：**

- `ChatController`、`LangChain4jLlmRuntime`、`RagRetriever` 都要埋点
- 中文日志继续保留，同时统一补结构化 key/value，兼顾人读和机器检索
- `MemoryService` 在抢不到锁时应优雅降级：
  - 跳过本轮压缩
  - 继续保留 recent messages
  - 记录一次 `lock-miss` 指标或日志

**验收标准：**

- 本地启动后访问 `/actuator/prometheus` 能看到核心指标
- 跑一轮 chat / rag / tool 请求后，指标值会变化
- 并发写同一 `sessionId` 时不会出现 summary 被覆盖或消息顺序明显错乱

**建议命令：**

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

## 9. 第二阶段执行顺序与提交策略

为继续符合“少量阶段、少量 commit”的偏好，第二阶段建议仍然控制为 4 个主任务、4 次主提交：

1. `Task 1`：评估基线运行器 + trace 扩展
2. `Task 2`：查询预处理 + dense recall -> rerank
3. `Task 3`：hybrid retrieval + 中文优化切分
4. `Task 4`：Prometheus 指标 + 记忆并发保护

推荐提交信息方向：

- `feat: add baseline eval runner and richer trace fields`
- `feat: add rerank pipeline for dense retrieval`
- `feat: add hybrid retrieval and chinese-aware chunking`
- `feat: add prometheus metrics and redis memory locking`

## 10. 第二阶段量化指标口径

为了后面能形成“优化前后对比数据”，第二阶段建议固定以下口径：

- 检索命中率：`retrievalHit=true` 的样例占比
- 路由命中率：`expectedPath == actualPath` 的样例占比
- Tool 命中率：工具类问题中 `toolUsed=true` 的样例占比
- 平均输入 token 估算
- 平均输出 token 估算
- 平均耗时、P50、P90
- dense-only 与 dense-rerank / hybrid-rerank 的评估对比

如果第二阶段执行顺利，简历上可以沉淀的表述会更像：

- “将单阶段 dense RAG 升级为 `dense recall + rerank + hybrid retrieval`，检索命中率提升 xx%”
- “搭建离线评估与 Prometheus 指标体系，使路由命中率、RAG 命中率、延迟指标可量化对比”
- “在 Redis 记忆链路引入并发保护，避免多请求同时压缩导致的 summary 覆盖问题”

## 11. ByteCoach 能借鉴但不应直接照搬的点

第二阶段会借鉴 `ByteCoach`，但不会机械复制：

- 借鉴两阶段检索与中文优化
- 借鉴 Micrometer + Prometheus 观测思路
- 借鉴 Redis 并发保护思路

同时保留当前项目自己的边界：

- 继续保持单模块
- 继续保持项目自控的 `AgentOrchestrator`
- 继续避免把核心编排完全托管给 `AiServices`
- 继续保持 `.env` 管理敏感配置，不把 key 写死进仓库

## 12. 计划结论

第二阶段最重要的不是“继续堆功能”，而是把第一阶段已经跑通的主链升级成一个：

- 可评估
- 可观测
- 可比较
- 可持续优化

的后端 Agent 系统。

如果只选最值得先做的三件事，顺序就是：

1. 评估器
2. rerank
3. Prometheus + hybrid retrieval
