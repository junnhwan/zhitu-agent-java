# Zhitu Agent Java MVP Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a backend-first single-module Spring Boot Java Agent MVP that delivers session management, Redis-backed memory, pgvector RAG, SSE chat, and LangChain4j-based ToolUse.

**Architecture:** Keep one explicit request chain: API -> session/memory -> context -> orchestrator -> RAG/tool/direct answer -> LLM runtime -> memory writeback. Use LangChain4j only for model, embedding, and vector integration; keep routing, context assembly, and tool orchestration in project code. Ship in four Task-sized slices so each slice is independently runnable and easy to explain in a project retrospective.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring MVC, LangChain4j 1.1.x, Redis, PostgreSQL + pgvector, Jackson, JUnit 5, Testcontainers

---

## 0. Prerequisites

- [ ] Confirm JDK 21 is installed and available on `PATH`
- [ ] Prepare model API keys and local `.env` or `application-local.yml`
- [ ] Prepare Redis and PostgreSQL + pgvector access, either cloud-hosted or local
- [ ] If Task commits matter, initialize git before implementation starts

Recommended runtime services:

- Redis on `localhost:6379`
- PostgreSQL + pgvector on `localhost:5432`

Recommended local commands:

```powershell
.\mvnw.cmd test
```

Expected:

- Maven test suite can run from project root

## 1. Planned File Layout

### 1.1 Root and build files

- Create: `pom.xml`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `.gitignore`
- Create: `docker-compose.yml`
- Create: `README.md`

### 1.2 App entry and configuration

- Create: `src/main/java/com/zhituagent/ZhituAgentApplication.java`
- Create: `src/main/java/com/zhituagent/config/AppProperties.java`
- Create: `src/main/java/com/zhituagent/config/LlmProperties.java`
- Create: `src/main/java/com/zhituagent/config/RedisConfig.java`
- Create: `src/main/java/com/zhituagent/config/PgVectorConfig.java`
- Create: `src/main/java/com/zhituagent/config/WebConfig.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/application-local.yml`
- Create: `src/main/resources/system-prompt/chat-agent.txt`

### 1.3 API and DTO layer

- Create: `src/main/java/com/zhituagent/api/ChatController.java`
- Create: `src/main/java/com/zhituagent/api/SessionController.java`
- Create: `src/main/java/com/zhituagent/api/KnowledgeController.java`
- Create: `src/main/java/com/zhituagent/api/HealthController.java`
- Create: `src/main/java/com/zhituagent/api/dto/ChatRequest.java`
- Create: `src/main/java/com/zhituagent/api/dto/ChatResponse.java`
- Create: `src/main/java/com/zhituagent/api/dto/SessionCreateRequest.java`
- Create: `src/main/java/com/zhituagent/api/dto/SessionResponse.java`
- Create: `src/main/java/com/zhituagent/api/dto/SessionDetailResponse.java`
- Create: `src/main/java/com/zhituagent/api/dto/KnowledgeWriteRequest.java`
- Create: `src/main/java/com/zhituagent/api/dto/ApiErrorResponse.java`

### 1.4 Common error and request context

- Create: `src/main/java/com/zhituagent/common/error/ErrorCode.java`
- Create: `src/main/java/com/zhituagent/common/error/ApiException.java`
- Create: `src/main/java/com/zhituagent/common/error/GlobalExceptionHandler.java`
- Create: `src/main/java/com/zhituagent/common/web/RequestIdFilter.java`

### 1.5 Session and memory

- Create: `src/main/java/com/zhituagent/session/SessionMetadata.java`
- Create: `src/main/java/com/zhituagent/session/SessionService.java`
- Create: `src/main/java/com/zhituagent/session/RedisSessionRepository.java`
- Create: `src/main/java/com/zhituagent/memory/ChatMessageRecord.java`
- Create: `src/main/java/com/zhituagent/memory/MemoryService.java`
- Create: `src/main/java/com/zhituagent/memory/RedisMemoryStore.java`
- Create: `src/main/java/com/zhituagent/memory/MessageSummaryCompressor.java`

### 1.6 Context and orchestration

- Create: `src/main/java/com/zhituagent/context/ContextBundle.java`
- Create: `src/main/java/com/zhituagent/context/ContextManager.java`
- Create: `src/main/java/com/zhituagent/orchestrator/RouteDecision.java`
- Create: `src/main/java/com/zhituagent/orchestrator/AgentOrchestrator.java`

### 1.7 LLM, tools, and RAG

- Create: `src/main/java/com/zhituagent/llm/LlmRuntime.java`
- Create: `src/main/java/com/zhituagent/llm/LangChain4jLlmRuntime.java`
- Create: `src/main/java/com/zhituagent/tool/ToolDefinition.java`
- Create: `src/main/java/com/zhituagent/tool/ToolRegistry.java`
- Create: `src/main/java/com/zhituagent/tool/ToolExecutor.java`
- Create: `src/main/java/com/zhituagent/tool/ToolResult.java`
- Create: `src/main/java/com/zhituagent/tool/builtin/TimeTool.java`
- Create: `src/main/java/com/zhituagent/tool/builtin/KnowledgeWriteTool.java`
- Create: `src/main/java/com/zhituagent/tool/builtin/SessionInspectTool.java`
- Create: `src/main/java/com/zhituagent/rag/KnowledgeDocument.java`
- Create: `src/main/java/com/zhituagent/rag/KnowledgeIngestService.java`
- Create: `src/main/java/com/zhituagent/rag/RagRetriever.java`
- Create: `src/main/java/com/zhituagent/rag/DocumentSplitter.java`
- Create: `src/main/java/com/zhituagent/rag/EvidenceBlockFormatter.java`

### 1.8 Frontend boundary note

- No frontend implementation is owned by this backend execution plan
- If another collaborator maintains a frontend, it should consume the documented backend APIs independently

### 1.9 Tests and fixtures

- Create: `src/test/java/com/zhituagent/api/HealthControllerTest.java`
- Create: `src/test/java/com/zhituagent/api/SessionControllerTest.java`
- Create: `src/test/java/com/zhituagent/api/ChatControllerTest.java`
- Create: `src/test/java/com/zhituagent/context/ContextManagerTest.java`
- Create: `src/test/java/com/zhituagent/orchestrator/AgentOrchestratorTest.java`
- Create: `src/test/java/com/zhituagent/tool/ToolRegistryTest.java`
- Create: `src/test/java/com/zhituagent/memory/MemoryServiceTest.java`
- Create: `src/test/java/com/zhituagent/rag/RagRetrieverIntegrationTest.java`
- Create: `src/test/java/com/zhituagent/rag/KnowledgeIngestServiceIntegrationTest.java`
- Create: `src/test/resources/eval/baseline-chat-cases.jsonl`

## 2. Task Commit Policy

The user explicitly wants fewer commits. Follow this boundary:

- Task 1 -> one main commit
- Task 2 -> one main commit
- Task 3 -> one main commit
- Task 4 -> one main commit

Inside each Task, still work test-first where practical, but do not split into many tiny commits unless a change becomes risky.

## 3. Task 1: Bootstrap the app, API shell, and SSE flow

**Files:**

- Create: `pom.xml`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `.gitignore`
- Create: `docker-compose.yml`
- Create: `src/main/java/com/zhituagent/ZhituAgentApplication.java`
- Create: `src/main/java/com/zhituagent/config/AppProperties.java`
- Create: `src/main/java/com/zhituagent/config/LlmProperties.java`
- Create: `src/main/java/com/zhituagent/config/WebConfig.java`
- Create: `src/main/java/com/zhituagent/api/HealthController.java`
- Create: `src/main/java/com/zhituagent/api/SessionController.java`
- Create: `src/main/java/com/zhituagent/api/ChatController.java`
- Create: `src/main/java/com/zhituagent/api/dto/*.java`
- Create: `src/main/java/com/zhituagent/common/error/*.java`
- Create: `src/main/java/com/zhituagent/common/web/RequestIdFilter.java`
- Create: `src/main/java/com/zhituagent/llm/LlmRuntime.java`
- Create: `src/main/java/com/zhituagent/llm/LangChain4jLlmRuntime.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/application-local.yml`
- Create: `src/main/resources/system-prompt/chat-agent.txt`
- Test: `src/test/java/com/zhituagent/api/HealthControllerTest.java`
- Test: `src/test/java/com/zhituagent/api/SessionControllerTest.java`
- Test: `src/test/java/com/zhituagent/api/ChatControllerTest.java`

- [ ] **Step 1: Generate the Maven Spring Boot skeleton and dependency graph**

Add only the dependencies needed for Task 1:

- Spring Boot Web
- Validation
- Spring Data Redis
- LangChain4j core + model integration
- Jackson
- Spring Boot Test
- Testcontainers BOM placeholder

Also add Maven Surefire and Wrapper so the project is runnable on Windows with `.\mvnw.cmd`.

- [ ] **Step 2: Write failing API smoke tests before controllers**

Cover:

- `GET /api/healthz` returns `200`
- `POST /api/sessions` creates a session shell
- `POST /api/chat` returns a mock non-stream response
- `POST /api/streamChat` emits `start`, at least one `token`, and `complete`

Use a mocked `LlmRuntime` and a lightweight in-memory `SessionService` double so Task 1 does not depend on Redis yet.

Run:

```powershell
.\mvnw.cmd -Dtest=HealthControllerTest,SessionControllerTest,ChatControllerTest test
```

Expected:

- tests fail because controllers and beans do not exist yet

- [ ] **Step 3: Implement minimal runtime, controllers, and request/error contracts**

Implement:

- app entry point
- request ID filter
- global exception handler
- session create/get shell
- mockable chat controller
- `SseEmitter`-based streaming endpoint

Keep the first version of `LangChain4jLlmRuntime` narrow:

- `String generate(...)`
- `void stream(..., Consumer<String> onToken, Runnable onComplete)`

- [ ] **Step 4: Verify the backend API shell manually**

Manual check requirements:

- `GET /api/healthz` returns `200`
- `POST /api/sessions` creates a session
- `POST /api/chat` returns a non-stream response
- `POST /api/streamChat` emits incremental events

Do not make a frontend a prerequisite for this Task.

- [ ] **Step 5: Verify the Task end-to-end**

Run:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Manual check:

- call `GET /api/healthz`
- call `POST /api/sessions`
- call `POST /api/chat`
- call `POST /api/streamChat`

Expected:

- backend starts
- chat and stream endpoints behave according to the API doc

- [ ] **Step 6: Commit Task checkpoint**

If git is initialized:

```powershell
git add .
git commit -m "feat: bootstrap java agent api and sse flow"
```

## 4. Task 2: Add Redis session state, memory persistence, and base context compression

**Files:**

- Create: `src/main/java/com/zhituagent/session/SessionMetadata.java`
- Create: `src/main/java/com/zhituagent/session/SessionService.java`
- Create: `src/main/java/com/zhituagent/session/RedisSessionRepository.java`
- Create: `src/main/java/com/zhituagent/memory/ChatMessageRecord.java`
- Create: `src/main/java/com/zhituagent/memory/MemoryService.java`
- Create: `src/main/java/com/zhituagent/memory/RedisMemoryStore.java`
- Create: `src/main/java/com/zhituagent/memory/MessageSummaryCompressor.java`
- Create: `src/main/java/com/zhituagent/context/ContextBundle.java`
- Create: `src/main/java/com/zhituagent/context/ContextManager.java`
- Modify: `src/main/java/com/zhituagent/api/SessionController.java`
- Modify: `src/main/java/com/zhituagent/api/ChatController.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/zhituagent/memory/MemoryServiceTest.java`
- Test: `src/test/java/com/zhituagent/context/ContextManagerTest.java`
- Test: `src/test/java/com/zhituagent/api/SessionControllerTest.java`

- [ ] **Step 1: Write failing tests for session recovery and context assembly**

Cover:

- session metadata persists across requests
- chat history is restored by `sessionId`
- context bundle contains summary + recent messages + current message
- compression keeps recent turns and collapses older turns when thresholds are exceeded

Run:

```powershell
.\mvnw.cmd -Dtest=MemoryServiceTest,ContextManagerTest,SessionControllerTest test
```

Expected:

- tests fail because Redis-backed session and memory implementations do not exist yet

- [ ] **Step 2: Implement Redis-backed session metadata and message storage**

Use Redis for:

- session metadata hash or JSON payload
- message list per `sessionId`
- optional TTL for inactive sessions

Do not overbuild locking in this Task. Keep writes single-request safe first; only add minimal guards if tests expose a problem.

- [ ] **Step 3: Implement first-pass context management**

Rules:

- system prompt
- optional session summary
- recent 6 to 8 turns
- current user message

Compression rules:

- trigger by message-count threshold
- optional token estimate threshold
- summary generation can start as deterministic text folding, not LLM summary

The output must stay easy to inspect and debug.

- [ ] **Step 4: Wire Redis memory into chat flow**

Update chat flow so each request:

- reads session metadata
- reads memory history
- builds context
- writes user and assistant messages back
- updates session `updatedAt`

- [ ] **Step 5: Verify the Task**

Run:

```powershell
.\mvnw.cmd test
```

Manual check:

- create a session
- send multiple turns
- refresh the page
- reload session detail
- confirm recent context is preserved and summary starts appearing after threshold

Expected:

- multi-turn continuity works
- no controller contract regresses from Task 1

- [ ] **Step 6: Commit Task checkpoint**

If git is initialized:

```powershell
git add .
git commit -m "feat: add redis session memory and base context compression"
```

## 5. Task 3: Add pgvector RAG, knowledge ingest, and built-in tools

**Files:**

- Create: `src/main/java/com/zhituagent/orchestrator/RouteDecision.java`
- Create: `src/main/java/com/zhituagent/orchestrator/AgentOrchestrator.java`
- Create: `src/main/java/com/zhituagent/rag/KnowledgeDocument.java`
- Create: `src/main/java/com/zhituagent/rag/KnowledgeIngestService.java`
- Create: `src/main/java/com/zhituagent/rag/RagRetriever.java`
- Create: `src/main/java/com/zhituagent/rag/DocumentSplitter.java`
- Create: `src/main/java/com/zhituagent/rag/EvidenceBlockFormatter.java`
- Create: `src/main/java/com/zhituagent/tool/ToolDefinition.java`
- Create: `src/main/java/com/zhituagent/tool/ToolRegistry.java`
- Create: `src/main/java/com/zhituagent/tool/ToolExecutor.java`
- Create: `src/main/java/com/zhituagent/tool/ToolResult.java`
- Create: `src/main/java/com/zhituagent/tool/builtin/TimeTool.java`
- Create: `src/main/java/com/zhituagent/tool/builtin/KnowledgeWriteTool.java`
- Create: `src/main/java/com/zhituagent/tool/builtin/SessionInspectTool.java`
- Create: `src/main/java/com/zhituagent/config/PgVectorConfig.java`
- Create: `src/main/java/com/zhituagent/api/KnowledgeController.java`
- Modify: `src/main/java/com/zhituagent/api/ChatController.java`
- Modify: `src/main/java/com/zhituagent/llm/LangChain4jLlmRuntime.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/zhituagent/orchestrator/AgentOrchestratorTest.java`
- Test: `src/test/java/com/zhituagent/tool/ToolRegistryTest.java`
- Test: `src/test/java/com/zhituagent/rag/RagRetrieverIntegrationTest.java`
- Test: `src/test/java/com/zhituagent/rag/KnowledgeIngestServiceIntegrationTest.java`

- [ ] **Step 1: Write failing tests for routing, retrieval, and tool registration**

Cover:

- direct-answer path for plain chat
- retrieve-then-answer path when a knowledge query is detected
- tool-then-answer path when tool intent is detected
- `knowledge-write` persists and becomes retrievable
- `time` and `session-inspect` are discoverable from the registry

Run:

```powershell
.\mvnw.cmd -Dtest=AgentOrchestratorTest,ToolRegistryTest,RagRetrieverIntegrationTest,KnowledgeIngestServiceIntegrationTest test
```

Expected:

- tests fail because RAG, orchestrator, and tool implementations are missing

- [ ] **Step 2: Implement dense retrieval only**

Implement:

- document splitter
- ingest service
- pgvector-backed retrieval
- evidence formatter with `source`, `chunkId`, `score`, `content`

Do not add rerank or hybrid retrieval in this Task.

- [ ] **Step 3: Implement the explicit orchestrator**

Add one project-owned router that chooses between:

- direct answer
- retrieve then answer
- tool then answer

Keep the decision policy simple and explainable. A lightweight classifier or rule-based heuristic is enough for Task 3.

- [ ] **Step 4: Implement built-in tools and wire ToolUse into the runtime**

Required tools:

- `time`
- `knowledge-write`
- `session-inspect`

The `knowledge-write` tool must call the same ingest path as `POST /api/knowledge`, so the behavior stays consistent across API and tool execution.

- [ ] **Step 5: Verify the Task end-to-end**

Run:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Manual check:

- write knowledge via API
- ask a question that should hit RAG
- trigger a tool path
- confirm stream and non-stream endpoints still work

Expected:

- RAG evidence affects answers
- tools execute and return structured results
- session continuity still works

- [ ] **Step 6: Commit Task checkpoint**

If git is initialized:

```powershell
git add .
git commit -m "feat: add pgvector rag orchestrator and built-in tools"
```

## 6. Task 4: Align docs, add trace placeholders, and seed second-phase evaluation

**Files:**

- Modify: `docs/2026-04-27-zhitu-agent-java-design.md`
- Modify: `docs/2026-04-27-zhitu-agent-java-api.md`
- Modify: `README.md`
- Create: `src/test/resources/eval/baseline-chat-cases.jsonl`
- Create: `src/main/java/com/zhituagent/api/dto/TraceInfo.java`
- Modify: `src/main/java/com/zhituagent/api/dto/ChatResponse.java`
- Modify: `src/main/java/com/zhituagent/orchestrator/RouteDecision.java`
- Modify: `src/main/java/com/zhituagent/api/ChatController.java`
- Test: `src/test/java/com/zhituagent/api/ChatControllerTest.java`

- [ ] **Step 1: Write failing contract tests for trace fields**

Cover:

- `path` is returned in chat responses
- SSE `complete` includes `path`, `retrievalHit`, and `toolUsed`
- baseline fixture file can be loaded by tests without format errors

Run:

```powershell
.\mvnw.cmd -Dtest=ChatControllerTest test
```

Expected:

- tests fail because trace placeholders are not fully exposed yet

- [ ] **Step 2: Add first-phase trace metadata**

Expose or internally preserve:

- `path`
- `retrievalHit`
- `toolUsed`
- optional placeholders for `retrievalMode`, `contextStrategy`, and token estimates

Do not build full metrics collection in this Task. Only add stable response and internal fields that the second phase can reuse.

- [ ] **Step 3: Create a baseline evaluation fixture**

Add a small JSONL file with representative cases:

- direct answer
- RAG answer
- tool answer
- long-context continuation

The file should be human-readable and easy to extend later.

- [ ] **Step 4: Reconcile docs with actual implementation**

Update:

- `README.md`
- design doc if implementation decisions drifted
- API doc if request/response fields changed

The docs at the end of Task 4 must match the running code, not the earlier design assumptions.

- [ ] **Step 5: Verify the Task**

Run:

```powershell
.\mvnw.cmd test
```

Manual check:

- chat responses and trace fields show route and flags
- markdown docs reflect actual endpoint names and payloads

Expected:

- project is runnable
- docs match code
- baseline fixture exists for second-phase optimization work

- [ ] **Step 6: Commit Task checkpoint**

If git is initialized:

```powershell
git add .
git commit -m "docs: align contracts and seed evaluation baseline"
```

## 7. Final Verification Checklist

- [ ] `.\mvnw.cmd test` passes
- [ ] `.\mvnw.cmd spring-boot:run` starts the app cleanly
- [ ] `GET /api/healthz` returns `200`
- [ ] `POST /api/sessions` creates a session
- [ ] `POST /api/chat` works for a direct-answer path
- [ ] `POST /api/streamChat` streams incremental output
- [ ] multi-turn session memory survives refresh/reload
- [ ] `POST /api/knowledge` writes retrievable knowledge
- [ ] at least one tool path executes successfully
- [ ] if a separate frontend exists, it can consume the backend contracts without backend-side assumptions

## 8. Out of Scope for This Plan

Do not add these during MVP execution unless the user explicitly expands scope:

- hybrid retrieval
- BM25
- sparse + dense fusion
- advanced rerank pipeline
- MCP server/client integration
- full observability dashboard
- complete offline eval runner
- multi-agent swarm

## 9. Handoff Notes

- The current workspace was not a git repository during planning, so commit steps are conditional.
- Keep Task scope disciplined. The value of this project is a clean, explainable Agent backbone, not breadth.
- If implementation pressure rises, cut optional polish before cutting the six core capabilities.
