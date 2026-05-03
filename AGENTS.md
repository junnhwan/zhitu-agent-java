# 协作约束

这个文件用于记录当前仓库的开发约束、协作规则和已经确认的边界。**当前进度状态见 `optimize-progress.md`,session 入口见 `CLAUDE.md`,本文件只放长期不变的约束**。

## 项目方向

- 构建 `ZhituAgent` 的 Java 版本
- 项目形态采用单模块 Spring Boot
- 使用 LangChain4j 作为大模型接入层
- 主链路编排尽量自己掌控,不把核心 Agent 逻辑完全托管给高层框架

## 架构约束

- 单模块,不提前拆多模块
- 会话记忆:Redis
- RAG:Elasticsearch 8.10 + IK 中文分词器(M1 已退役 pgvector)— dense+sparse 都进 ES,hybrid 单次 KNN+match+rescore
- 文件入库:MinIO + Tika + HanLP(M2 同步)→ Kafka KRaft 异步 pipeline(M3 完成,producer 事务 + consumer at-least-once + DLT)
- 阶段 2 已完成(混合检索、可观测性、评估体系、ReAct、Contextual Retrieval、Self-RAG、HITL、MCP);阶段 3 v3 ES 栈现代化 M1+M2+M3 已完成,M4(测试治理 + 文档刷新)进行中

## 交付约束

- 优先采用"少量阶段、少量 commit"的推进方式,不要拆成过多碎小提交
- 后续阶段统一使用 `Task 1` / `Task 2` / `Task 3` / `Task 4` 描述阶段
- 规划、设计、计划文档统一放在 `docs/`,已实施的 plan 沉到 `docs/archive/`
- 必须维护 `optimize-progress.md`,作为当前开发进度的实时记录(原 `progress.md` 已删除,被这个文件替代)

## 基础设施约束

- 当前阶段不要把 Docker 作为日常验证前提,除非用户明确要求
- 用户后面会自行测试基础设施
- 当前代码设计要支持先用本地兜底实现,后续再平滑替换到 Redis / pgvector

## 密钥与配置约束

- 绝对不要把 provider 密钥硬编码进会提交的源码或配置
- 本地敏感信息只放 `.env`
- `.env` 必须保持在 `.gitignore` 中
- 应用配置应通过环境变量读取地址、模型名和 API key

## 沟通约束

- 必须清楚区分:已完成、部分完成、未完成
- 如果测试失败是因为后续阶段的失败测试先写了,要明确说明原因
- 汇报进度时尽量绑定到具体文件和具体行为,而不是只说"差不多完成了"

## 当前本地模型入口

- 对话模型走 OpenAI 兼容接口:`https://wzw.pp.ua/v1`
- Embedding 接口:`https://router.tumuer.me/v1/embeddings`
- Rerank 接口:`https://router.tumuer.me/v1/rerank`
- 实际 key 只保存在 `.env`

## 如果后续还有别的 Agent 接手

- **必读入口**:
  - `CLAUDE.md` — session 速查卡
  - `optimize-progress.md` — 完整工程史(C-1..C-3 / A-1..A-7 / SG / CR-1 / SR / T1+T2 / HL.a+HL.b / MCP / UI)
- **设计文档**(`docs/`):
  - `2026-04-27-zhitu-agent-java-design.md`
  - `2026-04-27-zhitu-agent-java-api.md`
  - `2026-04-28-zhitu-agent-java-observability.md`
  - `2026-04-28-zhitu-agent-java-report-template.md`
- **历史 plan**(`docs/archive/`,只读参考):
  - `2026-04-27-zhitu-agent-java-plan.md`
  - `2026-04-27-zhitu-agent-java-implementation-plan.md`
  - `2026-04-28-zhitu-agent-java-phase-two-plan.md`
  - `2026-04-28-zhitu-agent-java-optimization-plan.md`
- **当前状态**(详见 `optimize-progress.md`):
  - 阶段 1 / 阶段 2 全部完成,真实 LLM 链路 + Redis + ES 全部联调通过
  - 阶段 3 v3 ES 栈现代化 M1+M2+M3 完成:pgvector→ES+IK、MinIO+Tika 同步入库、Kafka KRaft 异步 pipeline
  - 单元测试 217/217 全绿(`mvn test`),IT 通过 `mvn verify` + Docker 跑(目前本地无 docker 自动跳过 4 个 Kafka IT)
  - 真 LLM v1/v2 baseline 双 split 满分;v3 baseline 在云端 ES 上跑通 smoke
  - 详细进度看 user-level memory `project_v3_es_stack.md`
