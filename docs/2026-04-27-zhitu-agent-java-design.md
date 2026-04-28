# Zhitu Agent Java Design

> 日期：2026-04-27
> 范围：第一版设计定稿
> 参考项目：`D:\dev\my_proj\go\ZhituAgent`、`D:\dev\learn_proj\hualvqing\ByteCoach`、`D:\dev\learn_proj\hualvqing\QianyanAgent`

## 1. 项目定位

`zhitu-agent-java` 的第一版是一个单体单模块的 Spring Boot Agent 服务，目标不是全量复刻 Go 版 `ZhituAgent`，而是围绕以下 6 个核心能力做一个边界清晰、可真实落地、后续可量化优化的 Java 版本：

- 基础 Context 管理策略
- 会话级记忆机制
- RAG 检索增强
- 会话管理
- SSE 流式问答
- ToolUse

项目主链路由我们自己组织，框架只承担底层能力接入。LangChain4j 用于模型调用、Embedding、向量存储适配和工具接入，但不接管核心编排逻辑。

## 2. 第一版目标与非目标

### 2.1 第一版目标

- 提供普通对话和 SSE 流式对话接口
- 提供显式会话管理能力，而不是每轮裸聊
- 使用 Redis 持久化会话消息和基础会话元数据
- 使用 PostgreSQL + pgvector 承载向量检索
- 提供一套简单但真实有效的 Context 管理策略
- 提供 2 到 3 个内置工具和简单注册扩展层
- 提供知识写入接口，打通 ToolUse 和 RAG
- 从第一版开始预留第二版量化优化所需的日志字段和数据结构

### 2.2 第一版明确不做

- 不做多模块拆分
- 不做复杂的多 Agent swarm
- 不做 MCP Server
- 不做 hybrid retrieval、BM25、稀疏稠密融合
- 不做复杂 rerank pipeline
- 不做跨会话人格记忆、长期画像记忆
- 不做完整的 observability dashboard 和评估中心
- 不把核心主链托管给高层 Agent workflow 框架

## 3. 技术选型

### 3.1 后端

- Java 21
- Spring Boot 3.5.x
- Spring MVC
- `SseEmitter` 作为第一版流式输出方案
- LangChain4j 1.1.x
- Redis 作为会话记忆和会话状态存储
- PostgreSQL 15 + pgvector 作为向量检索存储
- Jackson
- JUnit 5
- Testcontainers

说明：

- 第一版不采用 WebFlux，避免把复杂度放在响应式编排上。
- 当前阶段前端不在本 agent 的交付范围内，项目以纯后端接口主链为主。

## 4. 总体架构

### 4.1 主链路

```text
HTTP/SSE API
  -> SessionService
  -> MemoryService
  -> ContextManager
  -> AgentOrchestrator
  -> (Direct Answer | RAG | ToolUse)
  -> LlmRuntime
  -> MemoryWriter
  -> Session metadata update
```

### 4.2 核心原则

- 单主链路优先，先把能力跑通
- 组件职责单一，避免“一个类做完所有事情”
- LangChain4j 只做能力接入，不做黑盒编排
- Context、Memory、RAG、ToolUse 要有清晰边界
- 第一版所有设计都要能为第二版量化优化提供基线

## 5. 模块设计

建议目录结构：

```text
src/main/java/com/zhituagent/
  api/
  session/
  memory/
  context/
  orchestrator/
  rag/
  tool/
  llm/
  common/
  config/
src/main/resources/
  static/
  system-prompt/
  application.yml
docs/
```

### 5.1 `api`

职责：

- 暴露 REST 和 SSE 接口
- 参数校验
- 响应格式和异常映射
- 不参与业务路径决策

### 5.2 `session`

职责：

- 创建和查询会话
- 管理 `sessionId`
- 维护会话元数据，如创建时间、最近活跃时间、标题摘要

### 5.3 `memory`

职责：

- 读写 Redis 会话消息
- 提供最近 N 轮消息读取能力
- 提供历史摘要写回能力
- 压缩失败时进行降级处理

第一版记忆边界：

- 只做会话内持续性
- 不做跨会话用户画像
- 不做长期反思式记忆

### 5.4 `context`

职责：

- 统一组装模型输入上下文
- 控制上下文预算
- 处理历史摘要、最近消息、RAG 证据和工具结果摘要

### 5.5 `orchestrator`

职责：

- 决定本轮请求走哪条路径
- 输出统一的执行决策

第一版固定 3 条路径：

- `direct-answer`
- `retrieve-then-answer`
- `tool-then-answer`

### 5.6 `rag`

职责：

- 文档切分
- Embedding
- pgvector 入库
- Top-K 检索
- 证据格式化

第一版只保留一条稳定链：

`query normalize -> embedding -> dense retrieval -> evidence block -> answer context`

### 5.7 `tool`

职责：

- 提供内置工具
- 提供简单注册表
- 规范工具执行返回结构

第一版建议内置工具：

- `time`
- `knowledge-write`
- `echo-debug` 或 `session-inspect`

其中 `knowledge-write` 是第一版重点工具，因为它能直接打通 ToolUse 和 RAG。

### 5.8 `llm`

职责：

- 封装 LangChain4j ChatModel、StreamingChatModel、EmbeddingModel
- 统一处理模型调用参数
- 统一非流式和流式输出

### 5.9 `common`

职责：

- DTO
- 错误码
- 统一异常
- 通用响应封装

### 5.10 `config`

职责：

- 模型配置
- Redis 配置
- pgvector 配置
- RAG 参数
- Context 参数
- Tool 参数

## 6. 会话与记忆设计

### 6.1 会话模型

第一版建议拆分两类数据：

- `session metadata`
  - `sessionId`
  - `userId` 或 `clientId`
  - `title`
  - `createdAt`
  - `updatedAt`
- `session messages`
  - `role`
  - `content`
  - `timestamp`
  - `messageType`
  - 可选 `toolName`

### 6.2 第一版 Context 管理策略

固定上下文结构：

`system prompt + session summary + recent 6~8 turns + rag evidence block + tool result summary + current user message`

触发压缩条件：

- 消息数超过阈值
- 估算 token 超过阈值

第一版压缩策略：

- 保留最近 N 轮完整消息
- 将更早的消息压缩成一段 `session summary`
- 工具长输出做 `micro-compact`

降级策略：

- 摘要失败时只保留最近 N 轮
- 工具结果过长时截断并标记已压缩

这一版不追求“智能上下文引擎”，只追求可解释、可实现、可量化。

## 7. RAG 设计

### 7.1 数据流

```text
knowledge ingest
  -> document split
  -> embedding
  -> pgvector write

chat request
  -> query normalize
  -> embedding
  -> top-k vector search
  -> evidence format
  -> inject into prompt
```

### 7.2 第一版约束

- 只做稠密检索
- 不做 hybrid retrieval
- `topK` 先控制在 `3~5`
- 检索结果作为一次性证据注入本轮上下文，不写回长期记忆

### 7.3 证据块格式

每条证据保留：

- `source`
- `chunkId`
- `score`
- `content`

这样第二版切到混合检索时，结果对比和质量分析会更方便。

## 8. ToolUse 设计

### 8.1 第一版目标

- 证明系统具备基础行动能力
- 避免一开始把工具层做成复杂平台

### 8.2 工具结构

- `ToolDefinition`
- `ToolRegistry`
- `ToolExecutor`
- `ToolResult`

### 8.3 第一版建议工具

- `time`
  返回当前时间
- `knowledge-write`
  接收结构化输入，写入知识源并触发向量化
- `session-inspect`
  返回当前会话基础信息或最近摘要，用于调试和演示

### 8.4 失败处理

- 工具执行异常时返回结构化错误结果
- Orchestrator 可以选择：
  - 终止并向用户说明
  - 忽略工具结果并降级回答

## 9. SSE 设计

第一版流式方案使用 `SseEmitter`。

原因：

- 主链路清晰
- 与 Spring MVC 结合简单
- 便于把注意力集中在 Agent 核心能力，而不是响应式复杂度

SSE 事件建议统一为：

- `start`
- `token`
- `complete`
- `error`

前端按事件类型更新消息区域和状态栏。

## 10. 前端协作边界

当前阶段前端由其他 AI 或其他协作者独立处理，这份设计文档只约束后端接口、会话、Context、RAG 和 ToolUse 主链。

## 11. 接口与文档交付

第一版至少交付两份文档：

- 设计文档：本文
- 接口文档：`docs/2026-04-27-zhitu-agent-java-api.md`

接口文档需覆盖：

- 请求/响应结构
- SSE 事件结构
- 错误码
- curl 示例

## 12. 测试策略

### 12.1 单元测试

- `ContextManager`
- `AgentOrchestrator`
- `ToolRegistry`
- `RagEvidenceFormatter`

### 12.2 集成测试

- Redis 会话读写
- pgvector 检索
- `knowledge-write` 后可检索

### 12.3 接口测试

- `/api/chat`
- `/api/streamChat`
- 会话续聊

### 12.4 容器测试

通过 Testcontainers 启动：

- Redis
- PostgreSQL + pgvector

## 13. Task 与提交节奏

为避免提交过碎，第一版控制为 4 个 Task：

### Task 1 项目骨架 + 对话主链 + SSE

产出：

- Spring Boot 骨架
- LangChain4j 模型接入
- `/api/chat`
- `/api/streamChat`

### Task 2 会话管理 + Redis 记忆 + 基础 Context 压缩

产出：

- 会话创建和查询
- Redis 消息存储
- 最近消息 + 历史摘要压缩策略

### Task 3 pgvector RAG + knowledge write + ToolUse

产出：

- 文档入库和向量检索
- 证据注入
- `knowledge-write`
- 2 到 3 个内置工具

### Task 4 文档补全 + 第二版增强预埋点

产出：

- 接口文档
- 设计文档补完
- 为第二版预留 `retrievalMode`、`contextStrategy`、`requestId`、`latencyMs`、`inputTokenEstimate`、`outputTokenEstimate` 等基础 trace 字段

## 14. 第二版增强方向

第二版再补：

- hybrid retrieval
- 稀疏/稠密增强
- 更强记忆机制
- 可观测性
- 离线评估与 A/B 对比

目标是最终能沉淀出可放简历的数据，例如：

- 检索命中率提升
- 回答正确率提升
- 上下文 token 消耗下降
- 首 token 延迟和总延迟变化

## 15. 设计结论

第一版的重点不是功能做得最多，而是把以下能力做成一个可运行、可解释、可演示、可继续优化的 Java Agent 主链：

- 会话
- Context
- 记忆
- RAG
- ToolUse
- SSE 对话

这会为第二版的混合检索、深记忆、评估与可观测性提供非常扎实的基线。
