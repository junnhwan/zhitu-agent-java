# Zhitu Agent Java

后端优先的单模块 Spring Boot Agent 项目，当前聚焦这几条主链能力：

- 会话管理
- 会话记忆
- Context 管理
- RAG 检索
- SSE 流式对话
- ToolUse

当前状态：

- 第一阶段 `Task 1` 到 `Task 4` 已完成
- 已支持真实 OpenAI 兼容对话模型接入
- 已支持可切换的 Redis 会话/记忆存储
- 已支持可切换的 pgvector dense RAG
- 已补第一版 trace 字段与 baseline eval fixture
- 第二阶段计划文档已补入 `docs/2026-04-28-zhitu-agent-java-phase-two-plan.md`

## 运行要求

- Java 21
- Maven Wrapper
- 根目录 `.env`
- Redis 与 PostgreSQL + pgvector 可访问

说明：

- 当前不要把 Docker 作为日常验证前提
- 可以直接连接你自己的云上 Redis / pgvector
- 前端不在当前仓库后端交付范围内，由其他协作者独立维护

## 本地启动

在项目根目录执行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

默认接口：

- `GET /api/healthz`
- `POST /api/sessions`
- `GET /api/sessions/{sessionId}`
- `POST /api/chat`
- `POST /api/streamChat`
- `POST /api/knowledge`

## 配置方式

当前配置通过根目录 `.env` 注入，`application.yml` 已包含：

```yaml
spring:
  config:
    import: optional:file:.env[.properties]
```

重点环境变量：

- `ZHITU_LLM_MOCK_MODE`
- `ZHITU_CHAT_BASE_URL`
- `ZHITU_CHAT_API_KEY`
- `ZHITU_CHAT_MODEL_NAME`
- `ZHITU_REDIS_ENABLED`
- `ZHITU_REDIS_HOST`
- `ZHITU_REDIS_PORT`
- `ZHITU_PGVECTOR_ENABLED`
- `ZHITU_PGVECTOR_HOST`
- `ZHITU_PGVECTOR_PORT`
- `ZHITU_PGVECTOR_DATABASE`
- `ZHITU_PGVECTOR_USERNAME`
- `ZHITU_PGVECTOR_PASSWORD`

## 关键文档

- `AGENTS.md`
- `progress.md`
- `docs/2026-04-27-zhitu-agent-java-design.md`
- `docs/2026-04-27-zhitu-agent-java-api.md`
- `docs/2026-04-27-zhitu-agent-java-implementation-plan.md`
- `docs/2026-04-28-zhitu-agent-java-phase-two-plan.md`

## 当前观测能力

控制台当前已补最小日志：

- 请求完成日志
- 路由决策日志
- RAG 检索日志
- 模型调用日志
- 异常日志

返回给前端或调试方的 `trace` 当前已包含：

- `path`
- `retrievalHit`
- `toolUsed`
- `retrievalMode`
- `contextStrategy`
- `requestId`
- `latencyMs`
- `snippetCount`
- `topSource`
- `topScore`
- `inputTokenEstimate`
- `outputTokenEstimate`
