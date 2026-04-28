# 协作约束

这个文件用于记录当前仓库的开发约束、协作规则和已经确认的边界。

## 项目方向

- 构建 `ZhituAgent` 的 Java 版本
- 项目形态采用单模块 Spring Boot
- 使用 LangChain4j 作为大模型接入层
- 主链路编排尽量自己掌控，不把核心 Agent 逻辑完全托管给高层框架

## 架构约束

- 第一阶段先保持单模块，不提前拆多模块
- 会话记忆的目标实现是 Redis
- RAG 的目标实现是 PostgreSQL + pgvector
- 第一版重点聚焦：
  - Context 管理
  - Memory
  - RAG
  - 会话管理
  - SSE 对话
  - ToolUse
- 混合检索、可观测性、评估体系已经在第二阶段完成第一版
- 深记忆、更强的上下文策略、进一步的检索增强继续放到后续阶段

## 交付约束

- 优先采用“少量阶段、少量 commit”的推进方式，不要拆成过多碎小提交
- 后续统一使用 `Task 1`、`Task 2`、`Task 3`、`Task 4` 来描述阶段
- 规划、设计、计划文档统一放在 `docs/`，不要放在 `docs/superpowers/`
- 必须维护根目录下的 `progress.md`，作为当前开发进度的实时记录

## 前端约束

- 当前这个 agent 不再负责前端实现与维护
- 前端将由其他 AI 或其他协作者单独处理
- 本仓库当前应以纯后端接口链路为主

## 基础设施约束

- 当前阶段不要把 Docker 作为日常验证前提，除非用户明确要求
- 用户后面会自行测试基础设施
- 当前代码设计要支持先用本地兜底实现，后续再平滑替换到 Redis / pgvector

## 密钥与配置约束

- 绝对不要把 provider 密钥硬编码进会提交的源码或配置
- 本地敏感信息只放 `.env`
- `.env` 必须保持在 `.gitignore` 中
- 应用配置应通过环境变量读取地址、模型名和 API key

## 沟通约束

- 必须清楚区分：已完成、部分完成、未完成
- 如果测试失败是因为后续阶段的失败测试先写了，要明确说明原因
- 汇报进度时尽量绑定到具体文件和具体行为，而不是只说“差不多完成了”

## 当前本地模型入口

- 对话模型走 OpenAI 兼容接口：`https://wzw.pp.ua/v1`
- Embedding 接口：`https://router.tumuer.me/v1/embeddings`
- Rerank 接口：`https://router.tumuer.me/v1/rerank`
- 实际 key 只保存在 `.env`

## 如果后续还有别的 Agent 接手

- 先看 `progress.md`
- 再看以下文档：
  - `docs/2026-04-27-zhitu-agent-java-design.md`
  - `docs/2026-04-27-zhitu-agent-java-api.md`
  - `docs/2026-04-27-zhitu-agent-java-implementation-plan.md`
  - `docs/2026-04-28-zhitu-agent-java-phase-two-plan.md`
  - `docs/2026-04-28-zhitu-agent-java-observability.md`
  - `docs/2026-04-28-zhitu-agent-java-report-template.md`
- 需要默认知道：
  - 第一阶段 `Task 1` 到 `Task 4` 已全部完成
  - 真实 LLM 调用已接入
  - Redis 真实链路已完成首轮联调
  - pgvector + dense retrieval 真实链路已完成首轮联调
  - 第一阶段收口提交为：`f5b66c3 feat: complete phase-one backend baseline and tracing`
  - 第二阶段 `Task 1` 到 `Task 4` 的第一版也已完成：
    - 评估基线运行器
    - dense recall -> rerank
    - hybrid retrieval + 中文优化切分
    - Prometheus 指标 + Redis 记忆并发保护
  - 第二阶段后的深化优化 `Task 1` 到 `Task 4` 第一轮也已完成：
    - 真实评估 runner 与多模式对比
    - rerank 质量校准
    - 轻量 facts 记忆 + budget-aware context
    - Prometheus 指标说明、Grafana 模板、错误分类指标、报告模板
  - 当前下一步重点已转向深化优化之后的持续提升：
    - 用更多真实 LLM 评估结果固化收益与副作用边界
    - 继续增强 hybrid retrieval 与检索质量对比
    - 深化记忆机制与上下文压缩策略
    - 做更完整的指标看板与效果沉淀
