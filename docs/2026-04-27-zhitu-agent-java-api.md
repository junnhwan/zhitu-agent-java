# Zhitu Agent Java API

> 日期：2026-04-27
> 范围：第一版接口约定

## 1. 基本约定

### 1.1 Base URL

本地开发建议：

```text
http://localhost:8080/api
```

### 1.2 Content Type

- 普通接口：`application/json`
- SSE 接口：`text/event-stream`

### 1.3 响应风格

第一版建议遵循：

- 成功的聊天接口返回业务数据
- 参数错误和系统错误返回统一错误结构

统一错误结构：

```json
{
  "code": "INVALID_ARGUMENT",
  "message": "sessionId is required",
  "requestId": "req_20260427_xxx",
  "category": "validation"
}
```

## 2. 数据模型

### 2.1 ChatRequest

```json
{
  "sessionId": "sess_10001",
  "userId": "user_20001",
  "message": "帮我总结这段对话的重点",
  "metadata": {
    "client": "web"
  }
}
```

字段说明：

- `sessionId`
  必填。会话标识。
- `userId`
  必填。用户或客户端标识。
- `message`
  必填。用户当前输入。
- `metadata`
  选填。扩展字段，第一版只透传不参与主逻辑。

### 2.2 SessionCreateRequest

```json
{
  "userId": "user_20001",
  "title": "Java Agent 调试"
}
```

### 2.3 SessionResponse

```json
{
  "sessionId": "sess_10001",
  "userId": "user_20001",
  "title": "Java Agent 调试",
  "createdAt": "2026-04-27T16:20:00+08:00",
  "updatedAt": "2026-04-27T16:28:00+08:00"
}
```

### 2.4 KnowledgeWriteRequest

```json
{
  "question": "这个项目第一版做什么？",
  "answer": "第一版先做 Context、记忆、RAG、会话、SSE 和 ToolUse。",
  "sourceName": "project-notes.md"
}
```

### 2.5 SessionDetailResponse

```json
{
  "session": {
    "sessionId": "sess_10001",
    "userId": "user_20001",
    "title": "Java Agent 调试",
    "createdAt": "2026-04-27T16:20:00+08:00",
    "updatedAt": "2026-04-27T16:28:00+08:00"
  },
  "summary": "本会话主要讨论 Java Agent 第一版设计。",
  "recentMessages": [
    {
      "role": "user",
      "content": "第一版先做哪些能力？",
      "timestamp": "2026-04-27T16:21:00+08:00"
    },
    {
      "role": "assistant",
      "content": "先做 Context、记忆、RAG、会话、SSE 和 ToolUse。",
      "timestamp": "2026-04-27T16:21:03+08:00"
    }
  ],
  "facts": [
    "我叫小智",
    "我在杭州做 Java Agent 后端开发"
  ]
}
```

## 3. 接口清单

### 3.1 `POST /api/chat`

用途：

- 非流式对话
- 便于联调、回归测试和脚本调用

请求体：`ChatRequest`

成功响应示例：

```json
{
  "sessionId": "sess_10001",
  "answer": "第一版建议先把会话、记忆、RAG 和 ToolUse 主链跑通。",
  "trace": {
    "path": "retrieve-then-answer",
    "retrievalHit": true,
    "toolUsed": false,
    "retrievalMode": "hybrid-rerank",
    "contextStrategy": "recent-summary",
    "requestId": "req_xxx",
    "latencyMs": 128,
    "snippetCount": 2,
    "topSource": "project-notes.md",
    "topScore": 0.83,
    "retrievalCandidateCount": 6,
    "rerankModel": "Qwen/Qwen3-Reranker-8B",
    "rerankTopScore": 0.97,
    "factCount": 2,
    "inputTokenEstimate": 96,
    "outputTokenEstimate": 34
  }
}
```

说明：

- `trace.path` 第一版建议返回，便于前端演示和后续评估。
- 当前第一段增强后，`trace` 已额外返回：
  - `retrievalMode`
  - `contextStrategy`
  - `requestId`
  - `latencyMs`
  - `snippetCount`
  - `topSource`
  - `topScore`
  - `retrievalCandidateCount`
  - `rerankModel`
  - `rerankTopScore`
  - `factCount`
  - `inputTokenEstimate`
  - `outputTokenEstimate`
- 当前这些字段主要用于联调、观测和后续评估，不代表已经实现完整 tracing 平台。
- `contextStrategy` 当前可见值示例：
  - `recent-summary`
    表示使用了基础 summary + recent messages 策略
  - `recent-summary-facts`
    表示额外注入了轻量 facts 层
  - `recent-summary-facts-budgeted`
    表示注入 facts 的同时，发生了 budget-aware 上下文裁剪

curl 示例：

```bash
curl -X POST "http://localhost:8080/api/chat" ^
  -H "Content-Type: application/json" ^
  -d "{\"sessionId\":\"sess_10001\",\"userId\":\"user_20001\",\"message\":\"介绍一下第一版目标\"}"
```

### 3.2 `POST /api/streamChat`

用途：

- SSE 流式对话
- 第一版主演示入口

请求体：`ChatRequest`

事件格式：

#### `start`

```text
event: start
data: {"sessionId":"sess_10001","requestId":"req_xxx"}
```

#### `token`

```text
event: token
data: {"content":"第一版建议先把"}
```

#### `complete`

```text
event: complete
data: {"path":"tool-then-answer","retrievalHit":false,"toolUsed":true,"retrievalMode":"none","contextStrategy":"recent-summary","requestId":"req_xxx","latencyMs":66,"snippetCount":0,"topSource":"","topScore":0.0,"retrievalCandidateCount":0,"rerankModel":"","rerankTopScore":0.0,"factCount":1,"inputTokenEstimate":72,"outputTokenEstimate":18}
```

#### `error`

```text
event: error
data: {"code":"LLM_CALL_FAILED","message":"model timeout","category":"unexpected"}
```

前端处理建议：

- `start` 初始化状态
- `token` 累加内容
- `complete` 更新路径和命中状态
- `error` 终止流并提示失败原因

curl 示例：

```bash
curl -N -X POST "http://localhost:8080/api/streamChat" ^
  -H "Content-Type: application/json" ^
  -d "{\"sessionId\":\"sess_10001\",\"userId\":\"user_20001\",\"message\":\"流式介绍一下当前方案\"}"
```

### 3.3 `POST /api/sessions`

用途：

- 创建会话

请求体：`SessionCreateRequest`

成功响应：`SessionResponse`

成功响应示例：

```json
{
  "sessionId": "sess_10001",
  "userId": "user_20001",
  "title": "Java Agent 调试",
  "createdAt": "2026-04-27T16:20:00+08:00",
  "updatedAt": "2026-04-27T16:20:00+08:00"
}
```

### 3.4 `GET /api/sessions/{sessionId}`

用途：

- 查询会话详情
- 恢复前端会话状态

成功响应：`SessionDetailResponse`

说明：

- 当前返回摘要、最近消息和一层轻量 `facts`，不返回全量历史。
- 更早消息通过 summary 表达。
- `facts` 当前用于表达较稳定的用户背景信息，例如姓名、地点、职责、目标等。
- 当会话输入过长时，后端会优先裁掉更旧的 `recentMessages`，必要时再缩短其他上下文块。

### 3.5 `POST /api/knowledge`

用途：

- 外部写入知识
- 打通 ToolUse 和 RAG

请求体：`KnowledgeWriteRequest`

成功响应示例：

```json
{
  "success": true,
  "sourceName": "project-notes.md",
  "message": "knowledge written and indexed"
}
```

失败但部分成功响应示例：

```json
{
  "success": false,
  "sourceName": "project-notes.md",
  "message": "file written but vector indexing failed"
}
```

### 3.6 `GET /api/healthz`

用途：

- 健康检查

成功响应示例：

```json
{
  "status": "UP"
}
```

## 4. 工具与 RAG 约定

### 4.1 ToolUse 返回结构

第一版建议工具层内部统一返回：

```json
{
  "toolName": "knowledge-write",
  "success": true,
  "summary": "knowledge inserted",
  "payload": {
    "sourceName": "project-notes.md"
  }
}
```

### 4.2 RAG 证据结构

第一版建议响应或内部 trace 至少保留：

```json
{
  "source": "project-notes.md",
  "chunkId": "chunk-12",
  "score": 0.83,
  "content": "第一版先做 Context、记忆、RAG、会话、SSE 和 ToolUse。"
}
```

## 5. 错误码建议

### 5.0 错误响应补充字段

当前统一错误结构 `ApiErrorResponse` 额外返回：

- `category`
  - `business`
    业务异常，例如会话不存在、消息为空、工具执行失败等
  - `validation`
    参数校验异常，例如缺字段、字段格式非法
  - `unexpected`
    未预期系统异常，例如运行时错误、未捕获异常

这一字段同时会进入 Prometheus 指标 `zhitu_api_errors_total`，便于后续按类别做错误归因和面板统计。

### 5.1 参数与资源错误

- `INVALID_ARGUMENT`
- `SESSION_NOT_FOUND`
- `EMPTY_MESSAGE`

### 5.2 主链路错误

- `LLM_CALL_FAILED`
- `STREAM_FAILED`
- `MEMORY_READ_FAILED`
- `MEMORY_WRITE_FAILED`
- `CONTEXT_BUILD_FAILED`

### 5.3 RAG 与工具错误

- `RAG_INGEST_FAILED`
- `RAG_RETRIEVE_FAILED`
- `TOOL_EXECUTION_FAILED`
- `TOOL_TIMEOUT`

## 6. 第一版前端对接要求

前端演示页至少依赖以下接口：

- `POST /api/sessions`
- `GET /api/sessions/{sessionId}`
- `POST /api/streamChat`

可选接入：

- `POST /api/knowledge`

前端需要显示：

- 当前 `sessionId`
- 当前流式回答
- 当前路径：`direct-answer` / `retrieve-then-answer` / `tool-then-answer`
- 是否命中 RAG
- 是否调用工具

## 7. 第二版兼容预留

第一版建议在响应或内部 trace 中预留：

- `retrievalMode`
  当前默认 `dense`
- `contextStrategy`
  当前基础值为 `recent-summary`，有 facts 或 budget 裁剪时会扩展成更具体的策略名
- `factCount`
- `latencyMs`
- `inputTokenEstimate`
- `outputTokenEstimate`

这些字段先不必全部对外暴露，但接口和内部模型层最好预留位置，方便第二版做量化对比。
