# Zhitu Agent Java 优化报告模板

日期：2026-04-28

这份模板用于沉淀每一轮优化结果，方便：

- 项目复盘
- 简历提炼
- 面试讲解
- 后续 A/B 对比

建议每完成一轮较大的检索、记忆、上下文或可观测性优化，就补一份报告。

## 1. 本轮背景

- 优化日期：
- 优化阶段：
- 负责人：
- 目标问题：
- 关联代码 / 提交：

## 2. 实验环境

- 对话模型：
- Embedding 模型：
- Rerank 模型：
- 检索模式：
- Redis / pgvector 环境：
- Eval fixture 版本：
- 关键配置：

## 3. 变更摘要

- 这轮主要改了什么：
- 为什么改：
- 预期收益是什么：
- 明确不在这轮解决的问题：

## 4. 评估口径

建议至少固定以下口径：

- `passedCases`
- `routeAccuracy`
- `retrievalHitRate`
- `toolHitRate`
- `topSourceExpectationHitRate`
- `contextStrategyExpectationHitRate`
- `factExpectationHitRate`
- `averageLatencyMs`
- `p50LatencyMs`
- `p90LatencyMs`
- `averageInputTokenEstimate`
- `averageOutputTokenEstimate`

## 5. 模式对比表

| mode | passedCases | routeAccuracy | retrievalHitRate | topSourceExpectationHitRate | contextStrategyExpectationHitRate | factExpectationHitRate | avgLatencyMs |
| --- | --- | --- | --- | --- | --- | --- | --- |
| dense |  |  |  |  |  |  |  |
| dense-rerank |  |  |  |  |  |  |  |
| hybrid-rerank |  |  |  |  |  |  |  |

## 6. 关键 case 解读

建议至少挑 2 到 4 个最能说明问题的 case：

- `rag-rerank-001`
  - 旧结果：
  - 新结果：
  - 结论：
- `rag-hybrid-001`
  - 旧结果：
  - 新结果：
  - 结论：
- `context-budget-001`
  - `contextStrategy`：
  - `actualFactCount`：
  - 结论：

## 7. 指标与日志观察

- Prometheus 指标有没有变化：
- `zhitu_rag_retrieval_total` 是否更稳定：
- `zhitu_api_errors_total` 是否出现新增异常类别：
- `zhitu_memory_compression_total` 的 `compressed / lock_miss / not_needed` 分布：
- 控制台中文日志是否提供了足够的排障信息：

## 8. 收益总结

建议分三类写：

### 8.1 质量收益

- 例如：
  - `dense-rerank` 的 `passedCases` 从 `4/6` 提升到 `6/6`
  - `hybrid-rerank` 的 `topSourceExpectationHitRate` 从 `0.5` 提升到 `1.0`

### 8.2 性能代价

- 例如：
  - 平均耗时增加多少
  - 输入 token 是否下降或上升

### 8.3 工程收益

- 例如：
  - 新增可观测性指标
  - 能更稳定复现实验
  - 能解释失败原因

## 9. 风险与副作用

- 是否出现新误召回：
- 是否出现延迟明显上升：
- 是否对长会话造成副作用：
- 是否存在只在少量 case 上成立的问题：

## 10. 下一步动作

- 下一轮最优先解决什么：
- 准备怎么验证：
- 哪些项先不做：

## 11. 简历表达草稿

这里建议沉淀成 1 到 3 句可直接改写进简历的话术：

- 例句 1：
  基于 LangChain4j、Redis、pgvector 实现单体 Java Agent 后端，打通会话记忆、RAG、SSE、ToolUse 主链，并构建可复用评估运行器与 Prometheus 可观测性体系。
- 例句 2：
  通过 `dense-rerank`、`hybrid-rerank`、中文切分与轻量重排校准，将关键评测样例的检索命中与证据排序稳定提升到可量化对比水平。
- 例句 3：
  设计 `summary + facts + recent messages` 的分层记忆与 budget-aware 上下文策略，支持长会话裁剪观测与效果评估。
