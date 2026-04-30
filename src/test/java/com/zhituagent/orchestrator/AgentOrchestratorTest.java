package com.zhituagent.orchestrator;

import com.zhituagent.rag.DocumentSplitter;
import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.builtin.TimeTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorTest {

    @Test
    void shouldChooseDirectRetrieveAndToolRoutes() {
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter());
        ingestService.ingest("第一版先做什么？", "第一版先做会话、记忆、RAG、SSE 和 ToolUse。", "project-notes.md");

        RagRetriever ragRetriever = new RagRetriever(ingestService);
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new TimeTool(Clock.fixed(Instant.parse("2026-04-27T09:00:00Z"), ZoneId.of("Asia/Shanghai")))
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(ragRetriever, toolRegistry);

        RouteDecision directDecision = orchestrator.decide("你好");
        RouteDecision retrieveDecision = orchestrator.decide("第一版先做什么？");
        RouteDecision toolDecision = orchestrator.decide("现在几点了？");
        RouteDecision weekdayDecision = orchestrator.decide("今天星期几？");

        assertThat(directDecision.path()).isEqualTo("direct-answer");
        assertThat(directDecision.toolUsed()).isFalse();
        assertThat(directDecision.retrievalMode()).isEqualTo("none");

        assertThat(retrieveDecision.path()).isEqualTo("retrieve-then-answer");
        assertThat(retrieveDecision.retrievalHit()).isTrue();
        assertThat(retrieveDecision.snippets()).isNotEmpty();
        assertThat(retrieveDecision.retrievalMode()).isEqualTo("dense");
        assertThat(retrieveDecision.retrievalCandidateCount()).isEqualTo(1);

        assertThat(toolDecision.path()).isEqualTo("tool-then-answer");
        assertThat(toolDecision.toolUsed()).isTrue();
        assertThat(toolDecision.toolName()).isEqualTo("time");
        assertThat(toolDecision.retrievalMode()).isEqualTo("none");

        assertThat(weekdayDecision.path()).isEqualTo("tool-then-answer");
        assertThat(weekdayDecision.toolUsed()).isTrue();
        assertThat(weekdayDecision.toolName()).isEqualTo("time");
        assertThat(weekdayDecision.retrievalMode()).isEqualTo("none");
    }
}
