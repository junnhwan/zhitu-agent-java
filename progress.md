# 开发进度

最后更新时间：2026-04-28
当前分支：`main`

## 总体状态

项目已经完成基础骨架，`Task 1` 到 `Task 3` 的 MVP 主链仍然存在，但底层的关键基础设施已经不再只有本地兜底。当前已经完成“真实模型调用第一步 + 可切换基础设施接口层第一步”，并且 Redis 与 pgvector 的真实运行链路都已完成首轮联调验证。

目前状态需要明确区分：

- 已完成：真实对话模型调用的第一步已经接入
- 已完成：默认 in-memory、可选 Redis、可选 pgvector 的接口层骨架已经接好
- 已完成：Redis 真实运行链路已经完成手工联调
- 已完成：pgvector + embedding 的真实 dense retrieval 链路已经完成手工联调
- 已完成：联调所需的最小日志与链路观测已补入
- 部分完成：当前还没有接入 rerank，也没有混合检索
- 未完成：rerank、hybrid retrieval 和第二阶段增强项还没有开始主实现

## 当前进展

### 规划与文档

- 已完成：现有规划评估
- 已完成：设计文档 `docs/2026-04-27-zhitu-agent-java-design.md`
- 已完成：接口文档 `docs/2026-04-27-zhitu-agent-java-api.md`
- 已完成：实现计划 `docs/2026-04-27-zhitu-agent-java-implementation-plan.md`
- 已完成：文档术语已统一切到 `Task 1/2/3/4`，并明确当前仓库以后端链路为主

### Task 1：项目骨架、基础 API、SSE

状态：已完成

已完成内容：

- Spring Boot 单模块项目骨架
- Maven Wrapper 和基础 `pom.xml`
- 基础接口：
  - `GET /api/healthz`
  - `POST /api/sessions`
  - `GET /api/sessions/{sessionId}`
  - `POST /api/chat`
  - `POST /api/streamChat`
- 基于 `SseEmitter` 的流式响应外壳
- Request ID Filter 与全局异常处理

已完成验证：

- Task 1 对应的定向测试已通过

### Task 2：会话记忆与基础 Context 管理

状态：当前阶段已完成基础版

已完成内容：

- 引入 `MemoryService`
- 引入 `MessageSummaryCompressor`
- 引入 `ContextManager`
- 会话详情接口已支持返回：
  - `summary`
  - `recentMessages`
- 对话主链已经从“只传当前输入”升级为：
  - `system`
  - `summary`
  - `recent messages`
  - `current message`

已完成验证：

- Task 2 的行为测试已通过
- 在加入 Task 3 的失败测试之前，全量测试曾为绿色

说明：

- 当前记忆实现仍是“以内存行为为主”的过渡实现
- Redis 持久化版本还需要在后续阶段继续补齐

### Task 3：路由、RAG、ToolUse

状态：当前阶段已完成基础版

已完成内容：

- `ToolRegistry`
- 3 个内置工具的最小实现：
  - `time`
  - `knowledge-write`
  - `session-inspect`
- `AgentOrchestrator`
- 本地兜底 RAG：
  - `KnowledgeIngestService`
  - `DocumentSplitter`
  - `RagRetriever`
- `KnowledgeController`
- `retrieve-then-answer` 与 `tool-then-answer` 的基础主链打通

已完成验证：

- Task 3 对应测试全部通过
- 当前全量测试已经恢复为绿色
- 已手动验证：
  - `POST /api/knowledge` 可成功写入知识
  - `POST /api/chat` 在知识命中时可返回 `retrieve-then-answer`
  - `POST /api/streamChat` 在时间问题上可返回 `tool-then-answer`

说明：

- 当前 RAG 还是本地内存实现，用于先打通行为 contract
- pgvector、真实 embedding、真实 rerank 还没有正式接入主链

### 当前替换阶段：真实模型调用与基础设施接口层

状态：部分完成

已完成内容：

- `LangChain4jLlmRuntime` 已从纯 mock/fallback 改为：
  - 配置齐全时优先走真实 OpenAI 兼容接口
  - 配置缺失或显式 mock 时再走本地兜底
- 已新增 `OpenAiCompatibleBaseUrlNormalizer`
  - 兼容 base URL 直接写成 `/v1`
  - 兼容 base URL 误写成 `/v1/chat/completions`
- 已新增定向测试：
  - `src/test/java/com/zhituagent/llm/LangChain4jLlmRuntimeTest.java`
- 已新增基础设施配置类：
  - `EmbeddingProperties`
  - `PgVectorProperties`
  - `InfrastructureProperties`
- 已新增基础设施 wiring：
  - `InfrastructureConfig`
- 已新增可切换存储接口：
  - `SessionRepository`
  - `MemoryStore`
  - `KnowledgeStore`
- 已新增默认本地实现：
  - `InMemorySessionRepository`
  - `InMemoryMemoryStore`
  - `InMemoryKnowledgeStore`
- 已新增后续真实基础设施适配实现：
  - `RedisSessionRepository`
  - `RedisMemoryStore`
  - `PgVectorKnowledgeStore`
- 已把以下服务改造成依赖接口层而不是直接持有本地状态：
  - `SessionService`
  - `MemoryService`
  - `KnowledgeIngestService`
- 已补充 PostgreSQL / pgvector 初始化 SQL：
  - `docs/sql/01-create-zhitu-agent-db.sql`
  - `docs/sql/02-enable-pgvector-extension.sql`
- 已新增测试环境配置：
  - `src/test/resources/application.yml`
  - 作用：避免 Spring 集成测试误读本地 `.env` 后直接访问真实模型
- 已新增基础设施条件 wiring 测试：
  - `InfrastructureWiringTest`
  - `RedisInfrastructureWiringTest`
  - `PgVectorInfrastructureWiringTest`

### 当前联调增强：最小日志与链路观测

状态：已完成第一版

已完成内容：

- 已在 `RequestIdFilter` 中补充请求完成日志：
  - `method`
  - `path`
  - `status`
  - `requestId`
  - `latencyMs`
- 已在 `ChatController` 中补充聊天链路日志：
  - 路由决策日志
  - 普通对话完成日志
  - SSE 完成 / 失败日志
- 已在 `RagRetriever` 中补充检索日志：
  - `resultCount`
  - `topSource`
  - `topScore`
  - `queryPreview`
- 已在 `LangChain4jLlmRuntime` 中补充模型运行日志：
  - `provider`
  - `messageCount`
  - `model`
  - `latencyMs`
- 已在 `GlobalExceptionHandler` 中补充：
  - 业务异常日志
  - 参数校验异常日志
  - 未预期异常日志

已完成验证：

- 已先写失败测试，再补实现：
  - `HealthControllerTest`
  - `ChatControllerTest`
  - `LangChain4jLlmRuntimeTest`
- 上述定向测试已恢复为绿色

已完成验证：

- `.\mvnw.cmd -Dtest=LangChain4jLlmRuntimeTest test` 已通过
- `.\mvnw.cmd -Dtest=InfrastructureWiringTest test` 已通过
- `.\mvnw.cmd -Dtest=InfrastructureWiringTest,RedisInfrastructureWiringTest,PgVectorInfrastructureWiringTest test` 已通过
- `.\mvnw.cmd test` 已通过

Redis 手工联调结果：

- 已用本地启动应用方式验证：
  - `ZHITU_REDIS_ENABLED=true`
  - `ZHITU_LLM_MOCK_MODE=true`
  - `ZHITU_PGVECTOR_ENABLED=false`
- 已验证接口链路：
  - `POST /api/sessions`
  - `POST /api/chat`
  - `GET /api/sessions/{sessionId}`
- 已直接读取云上 Redis，确认存在：
  - `zhitu:session:<sessionId>`
  - `zhitu:memory:<sessionId>`
- 已完成“停应用 -> 重启应用 -> 再读取同一 sessionId”的跨重启验证
- 结论：
  - `RedisSessionRepository` 已真实生效
  - `RedisMemoryStore` 已真实生效
  - 当前 Redis 会话与短期记忆不再只依赖 JVM 内存

pgvector 手工联调结果：

- 已补齐默认配置映射：
  - `application.yml` 现已覆盖
    - `zhitu.embedding.*`
    - `zhitu.rerank.*`
    - `zhitu.pgvector.*`
- 已修复 pgvector 写入 ID 问题：
  - 新增 `KnowledgeStoreIds`
  - 将业务 `chunkId` 稳定映射为合法 UUID
- 已新增回归测试：
  - `KnowledgeStoreIdsTest`
- 已用本地启动应用方式验证：
  - `ZHITU_REDIS_ENABLED=true`
  - `ZHITU_PGVECTOR_ENABLED=true`
  - `ZHITU_LLM_MOCK_MODE=true`
- 已验证接口链路：
  - `POST /api/knowledge`
  - `POST /api/chat`
- 已验证返回结果：
  - `POST /api/knowledge` 返回成功
  - `POST /api/chat` 返回
    - `trace.path = retrieve-then-answer`
    - `trace.retrievalHit = true`
- 已直接查询 PostgreSQL：
  - `public.zhitu_agent_knowledge` 表存在且已有数据
  - 当前手工验证时查到 `count(*) = 1`
- 结论：
  - `PgVectorKnowledgeStore` 已真实生效
  - `KnowledgeIngestService -> embedding -> pgvector -> RagRetriever` 的 dense RAG 主链已打通

部分完成内容：

- `PgVectorKnowledgeStore` 目前只接了 dense embedding + vector search
- rerank、hybrid retrieval、稀疏/稠密增强仍未进入主链

## 配置与密钥

已完成：

- 本地 `.env` 已创建，并已被 git 忽略
- 本地配置已支持从环境变量读取：
  - 对话模型地址与 key
  - embedding 地址与 key
  - rerank 地址与 key
- 已新增 PostgreSQL / pgvector 初始化脚本：
  - `docs/sql/01-create-zhitu-agent-db.sql`
  - `docs/sql/02-enable-pgvector-extension.sql`

待补充：

- 第二阶段需要补：
  - rerank 主链接入
  - hybrid retrieval
  - 更细粒度 trace 字段增强
  - baseline eval 运行与评估机制

## Task 4：trace 与评估基线

状态：已完成

已完成内容：

- 已新增 `ChatTraceFactory`
  - 将对外 trace 组装从 `ChatController` 中抽离
- 已扩展 `TraceInfo` 返回字段：
  - `retrievalMode`
  - `contextStrategy`
  - `requestId`
  - `latencyMs`
  - `snippetCount`
  - `topSource`
  - `topScore`
  - `inputTokenEstimate`
  - `outputTokenEstimate`
- 已补充 baseline eval 文件：
  - `src/test/resources/eval/baseline-chat-cases.jsonl`
- 已创建根目录 `README.md`
- 已将 API / 设计 / 实现计划文档同步到当前实现
- 已新增定向测试：
  - `ChatControllerTest`
  - `BaselineEvalFixtureTest`

已完成验证：

- `.\mvnw.cmd -Dtest=ChatControllerTest,BaselineEvalFixtureTest test` 已通过
- `.\mvnw.cmd test` 已通过

## 运行状态

- 当前未确认应用正处于持续运行状态
- 按用户要求，暂时不使用 Docker 做验证
- 当前仓库不再维护前端静态页面，后续前端由其他 AI 或其他协作者负责

## 当前约束

- 现在不要依赖 Docker，除非用户再次明确要求
- 文档统一放在 `docs/`
- 优先保持阶段性推进，不要拆成太多零碎 commit
- 后续统一使用 `Task 1/2/3/4` 的说法，不再使用 `M1/M2/M3/M4`
- 密钥只放在 `.env`，不要写进会提交的配置文件

## 下一步

1. 补真实 provider 接入，把当前 mock / fallback 模式逐步换成可调用模型
2. 在当前真实基础设施链路上补 trace 字段和更稳定的调试信息
3. 开始补 `Task 4` 里提到的评估基线文件
4. 逐步接入 rerank
5. 为后续 hybrid retrieval 和可量化优化做准备
