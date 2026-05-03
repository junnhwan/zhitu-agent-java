# Claude Code working notes

精炼到"新 session Read 一次能 30 秒进入工作状态"。深度细节去看 `optimize-progress.md` 与 user-level memory。

## TL;DR

zhitu-agent-java = 用户 Agent 实习的核心作品集项目。**阶段 2 ✅ 完成**(19 commit)+ **阶段 3 v3 ES 栈 M1+M2+M3 ✅ 完成**(6 commit / 217 单测 + 4 Kafka IT / 真实云端 smoke 通过)。

栈:Java 21 + Spring Boot 3.5 + LangChain4j 1.1 + **Elasticsearch 8.10 + IK** + Redis + **MinIO + Tika + HanLP** + **Kafka 3.7 KRaft** + React/TS。云中间件部署在 106.12.190.62 docker(`infra/cloud/`,Kafka 已在跑)。

**当前可讲故事**:
- 阶段 2: 评测体系拍出 fusion silent bug → 一行修复 → 重跑 v2 p90 latency -25%
- 阶段 3 M1+M2: pgvector 退役 → ES native hybrid(KNN+IK BM25+rescore 单次调用)+ MinIO+Tika+Redis bitmap 同步入库管线
- 阶段 3 M3: Kafka KRaft 异步管线 — 上传立即 202,consumer 跑 Tika+embed+ES bulk;producer 事务 + idempotent + DLT 重试;at-least-once + ES `_id=chunkId` = exactly-once-effect
- **🔥 叙事更正**: routeAcc +0.20 提升 **不是 ES 功劳是 multi-agent SRE Phase 1 commit**(4 个 SRE case 全 False→全 True)。ES + Kafka 价值在工程深度(IK/KNN/真 hybrid/事务异步管线),不在数字。

下一步: M4(failsafe IT 拆分 ✅ 完成 / docs 刷新中)→ M5 sync vs async perf 微基准 + resume 叙事段落。

## 协作模式(重要)

**I drive, user watches and learns** — 我主导写代码 + 讲设计决策,用户旁观学习。详见 user memory `feedback_collab_mode.md`。要点:

- 每个改造前先 1-2 段说清"改什么 / 为什么这样选 / 对标谁",再动手
- 关键决策点(指标定义、协议选择、错误恢复策略)显式 trade-off
- 解释 < 200 字,代码后给一句"简历卖点是 X"
- 关键里程碑(评测扩充完、function calling 跑通、v1/v2 baseline 跑完)主动停下让用户确认
- 用户改主意要他亲自写或讨论时,以最新指示为准

## 关键文档(按重要度)

1. **`optimize-progress.md`** — 项目主进度,完整 A-1..A-7 + 简历叙事框架最终版(⚠️ 阶段 3 部分待补,routeAcc 归因需更正)
2. **user memory** `~/.claude/projects/D--dev-my-proj-java-zhitu-agent-java/memory/`
   - `MEMORY.md` 索引
   - `project_v3_es_stack.md` ⭐ **当前阶段 3 进度** + 续接 prompt + 5 commit 表 + routeAcc 真相
   - `project_upgrade_roadmap.md` 阶段 2 完整 19 commit 表
   - `project_zhitu_state.md` v1 升级前基线快照(对比锚点,不更新)
   - `user_intern.md` 用户求职背景
   - `feedback_collab_mode.md` 协作模式细则
   - `feedback_commit_granularity.md` ⭐ **commit 粒度** = milestone 级,sub-task 决策自主
3. **`~/.claude/plans/generic-riding-wind.md`** — v3 完整 plan(M1-M5 11-14 天)
4. **`AGENTS.md`** — 长期协作 / 密钥 / 基础设施约束(不变项)
5. **`docs/*.md`** — active 设计 + `infra/cloud/` 云端中间件 compose

## 常用命令

```bash
# 后端单测 — M3 完成时基准 217/217,IT 走 mvn verify(需要 Docker)
mvn -o test
mvn -o verify          # 跑单测 + IT(@Tag("integration"));无 Docker 时 IT 自动 skip

# 前端 tsc — 阶段 2 完成时干净,无错误
cd frontend && npm run build

# 启动 boot(连云端 ES + MinIO + Redis,.env 已配)
mvn -o spring-boot:run -Dspring-boot.run.profiles=local
# 启动日志会打 "ZhituAgent active stores: KnowledgeStore=ElasticsearchKnowledgeStore (nativeHybrid=true), ..."
# 如果看到 InMemoryKnowledgeStore,说明 .env 没生效或 ES 没开

# 启 Kafka 异步管线(M3,需要 docker compose 起 kafka 或连 cloud)
ZHITU_KAFKA_ENABLED=true ZHITU_KAFKA_BOOTSTRAP_SERVERS=106.12.190.62:9092 \
  mvn -o spring-boot:run -Dspring-boot.run.profiles=local

# 文件上传 smoke (M2 同步)
curl -F file=@docs/m2-smoke-sample.txt http://localhost:8080/api/files/upload
# 验证 ES 收到:
curl "http://106.12.190.62:9200/zhitu_agent_knowledge/_search?q=source:m2-smoke-sample.txt&pretty"

# 文件上传 smoke (M3 异步,期望 HTTP 202 + uploadId)
curl -i -F file=@docs/m2-smoke-sample.txt http://localhost:8080/api/files/upload
# 查询解析状态:
curl "http://localhost:8080/api/files/status/{uploadId}"   # parseStatus: QUEUED→PARSING→INDEXED

# 跑真 LLM baseline(切隔离 index 避免污染 prod)
mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=0 \
    --zhitu.elasticsearch.index-name=zhitu_agent_eval \
    --zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true \
    --zhitu.eval.label=vN --zhitu.eval.modes=hybrid-rerank \
    --zhitu.llm.rate-limit.enabled=true"

# v2↔v3 对比报告(零 LLM 调用,扫 reportDir 找 latest baseline-{label}-*.json)
mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=0 \
    --zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true \
    --zhitu.eval.compare-labels=v2,v3"
```

跑出来产物在 `target/eval-reports/`:`baseline-{label}-{ts}.json` 单跑,`baseline-compare-{labels}-{ts}.{json,md}` 对比报告。

## 关键约束(避免常见踩坑)

- **不要 commit**:`archive/sessions/`、`docs/resume/`、`.env`、`*.log`、`target/`、`20*-*.txt`(都已 gitignore)
- **真 LLM eval 必须切隔离 index**:`--zhitu.elasticsearch.index-name=zhitu_agent_eval`,别污染 prod KB(`zhitu_agent_knowledge`)
- **ES idle 6 分钟后会被 server RST**:`ElasticsearchKnowledgeStore.bulkWithRetry` 已加 1 次 retry on IOException
- **MinIO 默认 off**:本地测试不需要;真要跑 file pipeline 必须 `.env` 设 `ZHITU_MINIO_ENABLED=true`(已配)+ `ZHITU_MINIO_ACCESS_KEY/SECRET_KEY`
- **Kafka 默认 off**:M3 异步管线只在 `ZHITU_KAFKA_ENABLED=true` 时激活;关掉 → controller 走 M2 同步路径(等价 200 + chunkCount)
- **Kafka producer 用事务**:`KafkaTemplate.executeInTransaction` + `acks=all` + `enable.idempotence=true`;`transactional.id` 前缀 Spring 自动追加 epoch,跨进程多实例不冲突
- **Kafka consumer 不开事务**:side-effect 是 ES 写,不是 Kafka 写。靠 `_id=chunkId` 幂等吃掉 at-least-once 重投递,DLT 收 poison pill
- **`@ConditionalOnProperty` 优于 `@ConditionalOnBean`**:链式 conditional(A 依赖 B 也是 conditional)在 WebMvcTest 里时序不稳,统一用 property gate 干净
- **`BaselineEvalRunner` 单 case 必须容错**:`catch RuntimeException` 转 errored case,否则一个 timeout 让整 run 崩
- **`RrfFusionMerger` 输出 score 量级 0.01~0.06**:不要在 retriever 套 cosine 阈值(A-6 fix `RagRetriever.shouldRejectLowConfidence` 已 mode-aware);ES native hybrid 路径 RRF 是 passthrough
- **GLM-5.1 限速 48 calls/min**:跑 baseline 时打开 `--zhitu.llm.rate-limit.enabled=true`(默认 limitForPeriod=48 / refresh=60s / timeout=120s)
- **`exit-after-run=true` 跳过 ApplicationReadyEvent**:active stores 启动日志用 `ApplicationStartedEvent` 才能在 BaselineEvalRunner.System.exit 之前打印
- **失败安全 IT**:Kafka/ES Testcontainers IT 标 `@Tag("integration")` + 文件名 `*IntegrationTest.java`;`mvn test` 排除,`mvn verify` 包含;无 Docker 时 `disabledWithoutDocker=true` 自动跳过

## 当前状态速查(2026-05-03)

- ✅ 阶段 2 完整: 19 commit + 122 单测基线 + v2 p90 -25%
- ✅ v3 M1: ES + IK + KnowledgeStore swap (commit `3df2de7`)
- ✅ v3 M2: 同步上传管线 (commit `0a8ebb1` `c65aaf9` `7940bb6` `3051569`)
- ✅ v3 M3: Kafka KRaft 异步管线 (commit `1b4a36a`) — producer 事务 + DLT + idempotent
- ✅ 217/217 单测全绿(`mvn test`)+ 4 Kafka IT 通过 `mvn verify`(本地无 Docker 自动 skip)
- ✅ 真实云端 smoke 通过(`docs/m2-smoke-sample.txt` → ES 命中)
- ⚠️ **routeAcc +0.20 真功臣是 multi-agent SRE Phase 1 不是 ES** — 见 `project_v3_es_stack.md`
- 🔄 M4: failsafe IT 拆分 ✅ 完成,文档刷新 ✅ 完成
- ⏳ M5: sync vs async perf 微基准 + resume 叙事 (~1d)

**下次会话续接**: 看 `project_v3_es_stack.md` 续接 prompt + git log -10 + plan 文件。

## 简历叙事框架(最终版,不要随便改)

| 层 | v1 现状 | v2 改造 | 业界对标 | 对应 commit |
|---|---|---|---|---|
| 评测 | topSource 字符串相等 | Recall/MRR/nDCG/keyword + train/eval split | BEIR/MTEB/Ragas | C-1 / C-2 |
| Tool | 关键词 if-else | function calling + 并行 + schema 校验 + loop 检测 | OpenAI / Anthropic | A-1..A-4 |
| 编排 | 单趟流水线 | ReAct AgentLoop 4 轮 + 嵌套 span | LangGraph | SG |
| RAG | dense + ILIKE + 手写 calibrator | sha256 chunkId + Anthropic CR + RRF + Self-RAG | Anthropic CR / Self-RAG / CRAG | CR-1 / SR |
| Trace | 扁平 KV | flat-on-wire / tree-in-view 时间轴 | LangSmith / Phoenix | T1 / T2 |
| Tool 治理 | 零治理 | requireApproval + single-use token + 前端 modal + auto resume | Anthropic computer use / LangGraph interrupt | HL.a / HL.b |
| 协议接入 | 只有内置工具 | MCP McpClient interface + adapter pattern + late-binding | MCP | MCP |
| 前端 | 扁平默认 | tokens + hash hue + iMessage 气泡 + 引导卡 + Trace 折叠 | Linear / Stripe / ChatGPT | UI |
| **eval 闭环** | **代码已落地缺数字** | **真 LLM baseline + 拍出 fusion silent bug + 一行修复 + 重跑闭环** | **BEIR holdout / 工程师 root cause debug** | **A-5 / A-6 / A-7** |
