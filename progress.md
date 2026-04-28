# 开发进度

最后更新时间：2026-04-28
当前分支：`main`

## 总体状态

当前仓库的后端主链已经完成三层交付：

- 第一阶段 `Task 1` 到 `Task 4` 已全部完成
  - 阶段收口提交：`f5b66c3 feat: complete phase-one backend baseline and tracing`
- 第二阶段 `Task 1` 到 `Task 4` 第一版已全部完成
  - 已有提交：
    - `af79d94`
    - `3ccb902`
- 第二阶段后的深化优化 `Task 1` 到 `Task 4` 第一轮也已全部完成
  - 本轮重点收口：
    - 轻量 facts 记忆规则校准
    - budget-aware 上下文评估 case
    - 错误分类指标
    - 可观测性文档、Grafana 模板、报告模板

当前必须明确区分：

- 已完成：真实对话模型调用已接入
- 已完成：真实 embedding / rerank / pgvector 首轮联调已打通
- 已完成：Redis 会话与记忆真实链路首轮联调已打通
- 已完成：dense / dense-rerank / hybrid-rerank 已具备可运行和可评估能力
- 已完成：Prometheus 指标、错误分类、评估报告资产已补齐第一版
- 已完成：当前仓库以后端接口链路为主，不再由本 agent 维护前端

最新全量验证结果：

- `.\mvnw.cmd test`
  - 结果：通过
  - 统计：`45` 个测试全部通过

## 当前已具备能力

### 会话与对话主链

- `POST /api/sessions`
- `GET /api/sessions/{sessionId}`
- `POST /api/chat`
- `POST /api/streamChat`
- `GET /api/healthz`

已具备：

- 非流式对话
- SSE 流式对话
- 会话详情恢复
- 全局异常处理
- Request ID
- 中文日志

### 记忆与上下文

已具备：

- `summary + recentMessages` 基础会话记忆
- 轻量 facts 提取
- `GET /api/sessions/{sessionId}` 返回 `facts`
- budget-aware 上下文组装
- `contextStrategy` 真实透出

当前可见的上下文策略示例：

- `recent-summary`
- `recent-summary-facts`
- `recent-summary-facts-budgeted`

### RAG 与检索增强

已具备：

- 知识写入接口
- 文档切分
- OpenAI 兼容 embedding
- pgvector dense retrieval
- lexical retrieval
- hybrid retrieval
- dense recall -> rerank
- 轻量 rerank 校准

当前支持的检索模式：

- `dense`
- `dense-rerank`
- `hybrid-rerank`

### ToolUse

当前内置工具：

- `time`
- `knowledge-write`
- `session-inspect`

### 评估与报告

已具备：

- 运行时 `BaselineEvalRunner`
- 多模式对比报告
- fixture 预置知识与历史会话
- `topSource` 期望校验
- `contextStrategy` 期望校验
- `factCount` 期望校验

当前评估样例已覆盖：

- `direct-001`
- `tool-001`
- `context-001`
- `context-budget-001`
- `rag-001`
- `rag-rerank-001`
- `rag-hybrid-001`

### 可观测性

已具备：

- `GET /actuator/health`
- `GET /actuator/prometheus`
- 聊天请求指标
- LLM 请求指标
- RAG / rerank 指标
- ToolUse 指标
- 记忆压缩指标
- API 错误分类指标

错误分类当前取值：

- `business`
- `validation`
- `unexpected`

## 阶段完成明细

### 第一阶段 Task 1：项目骨架、基础 API、SSE

状态：已完成

已完成内容：

- Spring Boot 单模块骨架
- LangChain4j 接入
- `/api/chat`
- `/api/streamChat`
- `SseEmitter`
- 基础 DTO、错误码、异常处理

### 第一阶段 Task 2：会话管理、记忆、基础 Context

状态：已完成

已完成内容：

- `SessionService`
- `MemoryService`
- `MessageSummaryCompressor`
- `ContextManager`
- `SessionDetailResponse` 返回 summary 与 recent messages

### 第一阶段 Task 3：RAG、知识写入、ToolUse

状态：已完成

已完成内容：

- `KnowledgeController`
- `KnowledgeIngestService`
- `RagRetriever`
- `ToolRegistry`
- `AgentOrchestrator`

### 第一阶段 Task 4：文档补全与 trace 预埋点

状态：已完成

已完成内容：

- 接口文档
- 设计文档
- 实现计划
- `trace` 基础字段预留

### 第二阶段 Task 1：评估运行器与 trace 扩展

状态：已完成第一版

已完成内容：

- `BaselineEvalRunner`
- `BaselineEvalCase`
- `BaselineEvalResult`
- `BaselineEvalComparisonReport`
- route / rag / tool / latency / token 估算汇总

### 第二阶段 Task 2：query preprocessing 与 dense recall -> rerank

状态：已完成第一版

已完成内容：

- `QueryPreprocessor`
- `OpenAiCompatibleRerankClient`
- `RerankProperties`
- `dense-rerank`

### 第二阶段 Task 3：hybrid retrieval 与中文优化切分

状态：已完成第一版

已完成内容：

- 中文友好切分
- lexical retrieval
- hybrid merge
- pgvector lexical support
- `docs/sql/03-add-hybrid-retrieval-support.sql`

### 第二阶段 Task 4：Prometheus 指标与记忆并发保护

状态：已完成第一版

已完成内容：

- Spring Actuator
- Prometheus registry
- `ChatMetricsRecorder`
- `AiMetricsRecorder`
- `RagMetricsRecorder`
- `ToolMetricsRecorder`
- `MemoryMetricsRecorder`
- `MemoryLock` / `RedisMemoryLock`

### 深化优化阶段 Task 1：真实评估与多模式对比

状态：已完成第一轮

已完成内容：

- 真实检索模式对比
- mode comparison 报告输出
- 检索 source 白名单隔离
- 真实报告已产出到 `target/eval-reports/`

### 深化优化阶段 Task 2：检索质量深化优化

状态：已完成第一轮

已完成内容：

- `RerankResultCalibrator`
- 中文 case 轻量校准
- `rag-rerank-001`
- `rag-hybrid-001`
  两类关键 case 的 topSource 结果稳定化

### 深化优化阶段 Task 3：记忆与上下文策略深化

状态：已完成第一轮

已完成内容：

- `FactExtractor`
- `facts` 注入上下文
- `factCount` 进入 trace
- `context-budget-001`
- `ContextManager` 默认预算收紧到 `640`
- 意图型 facts 误提取抑制

### 深化优化阶段 Task 4：可观测性沉淀与展示资产

状态：已完成第一轮

已完成内容：

- `ErrorMetricsRecorder`
- `ApiErrorResponse.category`
- `GlobalExceptionHandler` 错误分类指标接入
- 可观测性说明文档
- Grafana 导入模板
- 优化报告模板

## 文档与资产

当前需要优先看的文档：

- `docs/2026-04-27-zhitu-agent-java-design.md`
- `docs/2026-04-27-zhitu-agent-java-api.md`
- `docs/2026-04-27-zhitu-agent-java-implementation-plan.md`
- `docs/2026-04-28-zhitu-agent-java-phase-two-plan.md`
- `docs/2026-04-28-zhitu-agent-java-optimization-plan.md`
- `docs/2026-04-28-zhitu-agent-java-observability.md`
- `docs/2026-04-28-zhitu-agent-java-report-template.md`
- `docs/grafana/zhitu-agent-dashboard.json`

## 最新验证记录

### 2026-04-28

- `.\mvnw.cmd test`
  - 结果：通过
  - 统计：`45` 个测试全部通过
- `docs/grafana/zhitu-agent-dashboard.json`
  - 已用 PowerShell `ConvertFrom-Json` 校验 JSON 格式有效

## 当前已知边界

- 前端当前不由本 agent 维护，仓库当前以后端链路为主
- facts 仍然是规则提取，不是独立持久化的长期画像系统
- budget-aware context 仍然基于轻量 token 估算器，不是 provider 真实 tokenizer
- Grafana 模板已经提供，但是否接入真实 Prometheus / Grafana 仍取决于用户环境
- Docker 不是当前日常验证前提

## 下一步重点

当前这轮“剩余 Task 收口”已经完成，后续重点应转向持续优化而不是补基础功能：

1. 用更多真实 LLM 评估结果固化收益区间和副作用边界
2. 继续增强 hybrid retrieval 与检索质量对比
3. 深化记忆机制与上下文压缩策略
4. 把 Grafana 面板截图、评估报告和量化收益沉淀成项目复盘与简历素材
