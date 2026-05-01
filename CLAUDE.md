# Claude Code working notes

精炼到"新 session Read 一次能 30 秒进入工作状态"。深度细节去看 `optimize-progress.md` 与 user-level memory。

## TL;DR

zhitu-agent-java = 用户 Agent 实习的核心作品集项目。**阶段 2 全部 ✅ 完成**(19 commit + A-5/A-6/A-7 三幕剧闭环)。当前可讲故事:**评测体系拍出 fusion silent bug → 一行修复 → 重跑 v2 p90 latency -25%**。

栈:Java 21 + Spring Boot 3.5 + LangChain4j 1.1 + pgvector + Redis + React/TS。

## 协作模式(重要)

**I drive, user watches and learns** — 我主导写代码 + 讲设计决策,用户旁观学习。详见 user memory `feedback_collab_mode.md`。要点:

- 每个改造前先 1-2 段说清"改什么 / 为什么这样选 / 对标谁",再动手
- 关键决策点(指标定义、协议选择、错误恢复策略)显式 trade-off
- 解释 < 200 字,代码后给一句"简历卖点是 X"
- 关键里程碑(评测扩充完、function calling 跑通、v1/v2 baseline 跑完)主动停下让用户确认
- 用户改主意要他亲自写或讨论时,以最新指示为准

## 关键文档(按重要度)

1. **`optimize-progress.md`** — 项目主进度,完整 A-1..A-7 + 简历叙事框架最终版
2. **user memory** `~/.claude/projects/D--dev-my-proj-java-zhitu-agent-java/memory/`
   - `MEMORY.md` 索引
   - `project_upgrade_roadmap.md` 19 commit 表 + 三幕剧 + 阶段 3 候选
   - `project_zhitu_state.md` v1 升级前基线快照(对比锚点,不更新)
   - `user_intern.md` 用户求职背景
   - `feedback_collab_mode.md` 协作模式细则
3. **`AGENTS.md`** — 长期协作 / 密钥 / 基础设施约束(不变项)
4. **`docs/*.md`** — 4 份 active 设计(api / design / observability / report-template)
5. **`docs/archive/`** — 4 份已实施的 plan,只读参考

## 常用命令

```bash
# 后端测试 — 阶段 2 完成时基准 122/122,任何改动后必须保持全绿
mvn -o test

# 前端 tsc — 阶段 2 完成时干净,无错误
cd frontend && npm run build

# 跑真 LLM baseline(需 .env + zhitu_agent_eval 隔离表预先建好)
ZHITU_PGVECTOR_TABLE=zhitu_agent_eval ZHITU_LLM_MOCK_MODE=false \
  mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="\
    --zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true \
    --zhitu.eval.label=vN --zhitu.eval.modes=hybrid-rerank \
    --zhitu.llm.rate-limit.enabled=true \
    --zhitu.app.react-enabled={true|false} \
    --zhitu.rag.contextual-enabled={true|false} \
    --zhitu.rag.fusion-strategy={rrf|linear} \
    --zhitu.rag.self-rag-enabled={true|false}"

# v1↔v2 对比报告(零 LLM 调用,扫 reportDir 找 latest baseline-{label}-*.json)
mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="\
    --zhitu.eval.enabled=true --zhitu.eval.exit-after-run=true \
    --zhitu.eval.compare-labels=v1,v2"
```

跑出来产物在 `target/eval-reports/`:`baseline-{label}-{ts}.json` 单跑,`baseline-compare-{labels}-{ts}.{json,md}` 对比报告。

## 关键约束(避免常见踩坑)

- **不要 commit**:`archive/sessions/`、`docs/resume/`、`.env`、`*.log`、`target/`(都已 gitignore)
- **真 LLM eval 必须切隔离表**:`ZHITU_PGVECTOR_TABLE=zhitu_agent_eval`,别污染 prod KB(`zhitu_agent_knowledge`)
- **`BaselineEvalRunner` 单 case 必须容错**:`catch RuntimeException` 转 errored case,否则一个 timeout 让整 run 崩
- **`RrfFusionMerger` 输出 score 量级 0.01~0.06**:不要在 retriever 套 cosine 阈值(A-6 fix `RagRetriever.shouldRejectLowConfidence` 已 mode-aware)
- **GLM-5.1 限速 48 calls/min**:跑 baseline 时打开 `--zhitu.llm.rate-limit.enabled=true`(默认 limitForPeriod=48 / refresh=60s / timeout=120s)
- **v1 / v2 用同一份 fixture 对比**:label 区分输出文件名,fixture 不动避免污染对比口径

## 当前状态速查(2026-05-01)

- ✅ 19 commit + 122/122 单测 + 16/16 真 LLM baseline 双 split 满分
- ✅ v2 p90 latency 比 v1 低 25%(self-rag 早停 + ReAct 路由+生成合并)
- ⚠️ fixture 已到 ceiling(score 1.000 上限),提升空间转向其他维度

**阶段 3 候选**(开放式,不必都做):

- **fixture 升级**:graded relevance(LLM-as-judge 0-3 分)/ adversarial cases(否定句、多步推理)/ 大规模(>50 case)
- **MemGPT / Mem0 风格 memory**:LLM 抽取 + add/update/merge + reflection 循环
- **faithfulness eval**:Ragas 风格 LLM-as-judge 验证答案是否真实基于 evidence
- **CR-2 真 BM25**(PostgreSQL tsvector + jieba GIN):优先级降低,因为 A-7 已发现 score 不是当前瓶颈

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
