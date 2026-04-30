# Zhitu Agent Java 可观测性说明

日期：2026-04-28

## 1. 目标

这份文档用于说明当前后端已经落地的可观测性能力，重点覆盖：

- 健康检查
- Prometheus 指标
- 错误分类
- 评估报告与指标联动方式

当前范围仍然是“轻量但真实可用”的第一版，不追求完整 APM 平台。

## 2. 当前可用端点

### 2.1 健康检查

- `GET /actuator/health`

用途：

- 快速确认应用是否启动
- 当接入 Redis 时，可观察 Redis 健康状态是否被 Spring Actuator 正常感知

### 2.2 Prometheus 指标

- `GET /actuator/prometheus`

用途：

- 给 Prometheus 拉取指标
- 给 Grafana 面板提供数据源

### 2.3 Trace 文本归档

当前后端还会把关键对话 trace 额外写入本地文本文件。

默认位置：

- `logs/trace/zhitu-agent-trace-YYYY-MM-DD.jsonl`

默认配置：

- `ZHITU_TRACE_ARCHIVE_ENABLED=true`
- `ZHITU_TRACE_ARCHIVE_DIR=logs/trace`
- `ZHITU_TRACE_ARCHIVE_FILE_PREFIX=zhitu-agent-trace`
- `ZHITU_RAG_MIN_ACCEPTED_SCORE=0.15`

当前会归档的事件：

- `chat.completed`
- `chat.failed`
- `chat.stream.completed`
- `chat.stream.failed`

当前每行是一条 JSON，适合：

- 手工排查
- `rg` / `Select-String` 搜索
- 后续转成评估样本或异常分析材料

## 3. 当前指标清单

### 3.1 会话与接口请求

- `zhitu_chat_requests_total`
  - tags:
    - `path`
    - `stream`
    - `success`
- `zhitu_chat_request_duration_seconds`
  - tags:
    - `path`
    - `stream`
    - `success`

含义：

- 统计聊天请求总量
- 统计聊天请求耗时

### 3.2 模型请求

- `zhitu_llm_requests_total`
  - tags:
    - `model`
    - `mode`
    - `success`
- `zhitu_llm_request_duration_seconds`
  - tags:
    - `model`
    - `mode`
    - `success`

含义：

- 统计真实 LLM 调用量
- 统计不同模型、不同模式下的平均耗时

### 3.3 RAG 与 rerank

- `zhitu_rag_retrieval_total`
  - tags:
    - `retrieval_mode`
    - `hit`
- `zhitu_rag_retrieval_duration_seconds`
  - tags:
    - `retrieval_mode`
    - `hit`
- `zhitu_rag_recall_size`
  - tags:
    - `retrieval_mode`
    - `kind`
  - `kind` 当前取值：
    - `candidates`
    - `results`
- `zhitu_rerank_requests_total`
  - tags:
    - `model`
    - `success`

含义：

- 统计不同检索模式的命中情况
- 统计不同检索模式的平均检索耗时
- 统计召回候选数与最终结果数
- 统计 rerank 请求是否成功

### 3.4 ToolUse

- `zhitu_tool_invocations_total`
  - tags:
    - `tool`
    - `success`

含义：

- 统计工具调用频次
- 统计工具调用失败率

### 3.5 记忆压缩

- `zhitu_memory_compression_total`
  - tags:
    - `outcome`
    - `store`

当前 `outcome` 典型值：

- `compressed`
- `not_needed`
- `lock_miss`

含义：

- 观察 summary 压缩是否发生
- 观察 Redis 并发锁未抢到时的退化情况

### 3.6 API 错误分类

- `zhitu_api_errors_total`
  - tags:
    - `category`
    - `code`
    - `status`

当前 `category` 取值：

- `business`
- `validation`
- `unexpected`

含义：

- 按错误类别统计 API 失败
- 给后续错误面板和问题排查提供聚合入口

### 3.7 Trace 文本归档字段

trace 文本归档当前会保留以下高价值字段：

- `timestamp`
- `event`
- `stream`
- `sessionId`
- `userId`
- `requestId`
- `userMessage`
- `answerPreview`
- `errorMessage`
- `path`
- `retrievalHit`
- `toolUsed`
- `toolName`
- `retrievalMode`
- `contextStrategy`
- `snippetCount`
- `retrievalCandidateCount`
- `topSource`
- `topScore`
- `rerankModel`
- `rerankTopScore`
- `factCount`
- `inputTokenEstimate`
- `outputTokenEstimate`
- `latencyMs`
- `snippets`

其中 `snippets` 会额外保留：

- `source`
- `chunkId`
- `score`
- `denseScore`
- `rerankScore`
- `contentPreview`

## 3.8 路由纠偏日志

当前后端对“弱相关但仍然召回到候选”的场景，已经补了一层低分拒绝。

当 top score 低于 `zhitu.rag.min-accepted-score` 时，日志里会额外打印：

- `rag.search.rejected`

当前日志会带：

- `retrievalMode`
- `candidateCount`
- `topSource`
- `topScore`
- `minAcceptedScore`
- `queryPreview`

这类日志特别适合排查：

- 为什么明明召回到了候选，但最终没有走 `retrieve-then-answer`
- 为什么某些短问题不应该再被判成 RAG 命中

## 4. 设计边界

当前指标设计明确保持低基数：

- 不把 `sessionId`
- 不把 `userId`
- 不把 `requestId`

直接作为 Prometheus 标签，避免指标基数爆炸。

这些更高基数的信息主要留给：

- 结构化日志
- 评估报告
- trace 返回字段

## 5. 推荐 Prometheus 查询

### 5.1 聊天请求吞吐

```promql
sum(rate(zhitu_chat_requests_total[5m])) by (path, stream, success)
```

### 5.2 聊天平均耗时（毫秒）

```promql
1000 *
sum(rate(zhitu_chat_request_duration_seconds_sum[5m])) by (path) /
sum(rate(zhitu_chat_request_duration_seconds_count[5m])) by (path)
```

### 5.3 LLM 平均耗时（毫秒）

```promql
1000 *
sum(rate(zhitu_llm_request_duration_seconds_sum[5m])) by (model, mode) /
sum(rate(zhitu_llm_request_duration_seconds_count[5m])) by (model, mode)
```

### 5.4 RAG 命中率

```promql
sum(rate(zhitu_rag_retrieval_total{hit="true"}[5m])) by (retrieval_mode) /
sum(rate(zhitu_rag_retrieval_total[5m])) by (retrieval_mode)
```

### 5.5 API 错误速率

```promql
sum(rate(zhitu_api_errors_total[5m])) by (category, code, status)
```

## 6. Grafana 导入说明

仓库已提供一个最小可用仪表盘模板：

- `docs/grafana/zhitu-agent-dashboard.json`

导入步骤：

1. 在 Grafana 中选择 `Dashboards -> Import`
2. 上传 `docs/grafana/zhitu-agent-dashboard.json`
3. 选择 Prometheus 数据源
4. 根据实际环境调整 title、uid、time range

当前模板重点展示：

- 聊天吞吐
- 聊天平均耗时
- LLM 平均耗时
- RAG 命中率
- Memory 压缩结果
- API 错误分类

## 7. 与评估报告如何联动

当前建议把线上指标和离线/半离线评估报告分开看：

- Prometheus / Grafana 关注：
  - 请求量
  - 平均耗时
  - 错误分类
  - 检索命中趋势
- Trace 文本归档关注：
  - 单次请求为什么走到某条路径
  - 低分误召回时命中了哪些 snippet
  - SSE 失败时当时的 requestId、sessionId、errorMessage 和部分输出
- Eval Report 关注：
  - dense / dense-rerank / hybrid-rerank 对比
  - topSource 是否符合预期
  - contextStrategy 是否符合预期
  - factCount 是否符合预期

两者组合后，可以回答三类核心问题：

- 哪种 retrieval mode 更稳
- 哪类 case 更容易 miss
- 优化是否真的换来了收益，而不是只增加复杂度

## 8. 当前已知不足

当前这一版可观测性仍有几个明显边界：

- 还没有正式的告警规则
- 还没有长期存储和看板截图归档流程
- 还没有把日志、指标、评估报告做统一关联 ID 页面
- Timer 当前更适合看平均耗时，分位数展示还可以继续增强

## 9. 下一步建议

优先顺序建议为：

1. 用真实 LLM 再跑几轮评估报告，固化参数与收益数据
2. 把 Grafana 面板截图和核心指标结论沉淀进项目复盘
3. 继续细化错误分类与失败样例归因
4. 视情况再补更细的 latency bucket 或 tracing 方案
