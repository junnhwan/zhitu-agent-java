package com.zhituagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.rag.RerankClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {ZhituAgentApplication.class, BaselineEvalRunnerTest.StubConfig.class},
        properties = {
                "zhitu.infrastructure.redis-enabled=false",
                "zhitu.rerank.enabled=true",
                "zhitu.rerank.url=https://router.tumuer.me/v1/rerank",
                "zhitu.rerank.api-key=demo-key",
                "zhitu.rerank.model-name=Qwen/Qwen3-Reranker-8B",
                "zhitu.rag.hybrid-enabled=true"
        }
)
@AutoConfigureMockMvc
class BaselineEvalRunnerTest {

    @Autowired
    private BaselineEvalRunner baselineEvalRunner;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        LlmRuntime testLlmRuntime() {
            return new LlmRuntime() {
                @Override
                public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                    String lastUserMessage = messages.stream()
                            .filter(message -> message.startsWith("USER: "))
                            .reduce((first, second) -> second)
                            .orElse("USER: ");
                    boolean hasEvidence = messages.stream().anyMatch(message -> message.startsWith("EVIDENCE: "));
                    boolean hasSummary = messages.stream().anyMatch(message -> message.startsWith("SUMMARY: "));
                    return "Mock answer for " + lastUserMessage.substring("USER: ".length())
                            + " | evidence=" + hasEvidence
                            + " | summary=" + hasSummary;
                }

                @Override
                public void stream(String systemPrompt,
                                   List<String> messages,
                                   Map<String, Object> metadata,
                                   Consumer<String> onToken,
                                   Runnable onComplete) {
                    onToken.accept("Mock stream");
                    onComplete.run();
                }
            };
        }

        @Bean
        @Primary
        RerankClient testRerankClient() {
            return (query, candidates, topN) -> {
                if (candidates.isEmpty()) {
                    return new RerankClient.RerankResponse("Qwen/Qwen3-Reranker-8B", List.of());
                }
                int preferredIndex = IntStream.range(0, candidates.size())
                        .filter(index -> shouldPreferCandidate(query, candidates.get(index).source()))
                        .findFirst()
                        .orElse(0);
                return new RerankClient.RerankResponse(
                        "Qwen/Qwen3-Reranker-8B",
                        List.of(new RerankClient.RerankResult(preferredIndex, 0.97))
                );
            };
        }

        private boolean shouldPreferCandidate(String query, String source) {
            if (query == null || source == null) {
                return false;
            }
            String normalizedQuery = query.toLowerCase();
            if (normalizedQuery.contains("sse") && normalizedQuery.contains("tooluse")) {
                return "phase-one-keyword-target".equals(source);
            }
            if (query.contains("六项能力")) {
                return "phase-one-precise".equals(source);
            }
            return false;
        }
    }

    @Test
    void shouldRunBaselineFixtureAndWriteEvalReport() throws Exception {
        Path reportPath = Path.of("target", "eval-reports", "baseline-" + UUID.randomUUID() + ".json");

        Object report = baselineEvalRunner.runBaselineFixture(reportPath);
        JsonNode reportJson = objectMapper.valueToTree(report);

        assertThat(reportJson.path("totalCases").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(reportJson.path("passedCases").asInt()).isEqualTo(reportJson.path("totalCases").asInt());
        assertThat(reportJson.path("mode").asText()).isEqualTo("hybrid-rerank");
        assertThat(reportJson.path("routeAccuracy").asDouble()).isEqualTo(1.0);
        assertThat(reportJson.path("topSourceExpectationHitRate").asDouble()).isGreaterThan(0.0);
        assertThat(reportJson.path("results").isArray()).isTrue();
        assertThat(reportJson.path("results")).hasSize(reportJson.path("totalCases").asInt());
        assertThat(reportJson.path("averageLatencyMs").asDouble()).isGreaterThanOrEqualTo(0.0);
        assertThat(reportJson.path("p90LatencyMs").asDouble()).isGreaterThanOrEqualTo(reportJson.path("p50LatencyMs").asDouble());
        assertThat(reportJson.path("averageInputTokenEstimate").asDouble()).isGreaterThan(0.0);
        assertThat(reportJson.path("averageOutputTokenEstimate").asDouble()).isGreaterThan(0.0);

        JsonNode ragCase = findCase(reportJson, "rag-001");
        assertThat(ragCase.path("actualPath").asText()).isEqualTo("retrieve-then-answer");
        assertThat(ragCase.path("actualRetrievalHit").asBoolean()).isTrue();
        assertThat(ragCase.path("topSource").asText()).isEqualTo("phase-one-plan");

        JsonNode toolCase = findCase(reportJson, "tool-001");
        assertThat(toolCase.path("actualToolUsed").asBoolean()).isTrue();
        assertThat(toolCase.path("actualPath").asText()).isEqualTo("tool-then-answer");

        JsonNode contextCase = findCase(reportJson, "context-001");
        assertThat(contextCase.path("summaryPresentBeforeRun").asBoolean()).isTrue();
        assertThat(contextCase.path("contextStrategy").asText()).isEqualTo("recent-summary");

        JsonNode budgetedContextCase = findCase(reportJson, "context-budget-001");
        assertThat(budgetedContextCase.path("summaryPresentBeforeRun").asBoolean()).isTrue();
        assertThat(budgetedContextCase.path("contextStrategy").asText()).isEqualTo("recent-summary-facts-budgeted");
        assertThat(budgetedContextCase.path("actualFactCount").asInt()).isGreaterThanOrEqualTo(1);

        assertThat(reportPath).exists();
        JsonNode writtenReport = objectMapper.readTree(Files.readString(reportPath));
        assertThat(writtenReport.path("totalCases").asInt()).isEqualTo(reportJson.path("totalCases").asInt());
        assertThat(writtenReport.path("results")).hasSize(reportJson.path("results").size());
    }

    @Test
    void shouldRunModeComparisonFixtureAndWriteAggregatedReport() throws Exception {
        Path reportPath = Path.of("target", "eval-reports", "comparison-" + UUID.randomUUID() + ".json");

        Object report = baselineEvalRunner.runModeComparisonFixture(
                List.of("dense", "dense-rerank", "hybrid-rerank"),
                reportPath
        );
        JsonNode reportJson = objectMapper.valueToTree(report);

        assertThat(reportJson.path("fixtureName").asText()).isEqualTo("eval/baseline-chat-cases.jsonl");
        assertThat(reportJson.path("modeReports")).hasSize(3);

        JsonNode denseReport = findModeReport(reportJson, "dense");
        assertThat(denseReport.path("totalCases").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(denseReport.path("passedCases").asInt()).isEqualTo(denseReport.path("totalCases").asInt());
        assertThat(findCase(denseReport, "rag-001").path("retrievalMode").asText()).isEqualTo("dense");

        JsonNode denseRerankReport = findModeReport(reportJson, "dense-rerank");
        assertThat(denseRerankReport.path("passedCases").asInt()).isEqualTo(denseRerankReport.path("totalCases").asInt());
        JsonNode denseRerankCase = findCase(denseRerankReport, "rag-001");
        assertThat(denseRerankCase.path("retrievalMode").asText()).isEqualTo("dense-rerank");
        assertThat(denseRerankCase.path("rerankModel").asText()).isEqualTo("Qwen/Qwen3-Reranker-8B");

        JsonNode hybridRerankReport = findModeReport(reportJson, "hybrid-rerank");
        assertThat(hybridRerankReport.path("passedCases").asInt()).isEqualTo(hybridRerankReport.path("totalCases").asInt());
        JsonNode hybridRerankCase = findCase(hybridRerankReport, "rag-001");
        assertThat(hybridRerankCase.path("retrievalMode").asText()).isEqualTo("hybrid-rerank");
        assertThat(hybridRerankCase.path("actualRetrievalHit").asBoolean()).isTrue();

        JsonNode rerankDenseCase = findCase(denseReport, "rag-rerank-001");
        assertThat(rerankDenseCase.path("topSource").asText()).isEqualTo("phase-one-vague");

        JsonNode rerankDenseRerankCase = findCase(denseRerankReport, "rag-rerank-001");
        assertThat(rerankDenseRerankCase.path("topSource").asText()).isEqualTo("phase-one-precise");
        assertThat(rerankDenseRerankCase.path("topSourceMatched").asBoolean()).isTrue();

        JsonNode hybridDenseCase = findCase(denseReport, "rag-hybrid-001");
        assertThat(hybridDenseCase.path("topSource").asText()).isEqualTo("phase-one-vague-a");
        assertThat(hybridDenseCase.path("topSourceMatched").asBoolean()).isTrue();

        JsonNode hybridDenseRerankCase = findCase(denseRerankReport, "rag-hybrid-001");
        assertThat(hybridDenseRerankCase.path("topSource").asText()).isEqualTo("phase-one-keyword-target");
        assertThat(hybridDenseRerankCase.path("topSourceMatched").asBoolean()).isTrue();

        JsonNode hybridCase = findCase(hybridRerankReport, "rag-hybrid-001");
        assertThat(hybridCase.path("topSource").asText()).isEqualTo("phase-one-keyword-target");
        assertThat(hybridCase.path("topSourceMatched").asBoolean()).isTrue();

        assertThat(reportPath).exists();
        JsonNode writtenReport = objectMapper.readTree(Files.readString(reportPath));
        assertThat(writtenReport.path("modeReports")).hasSize(3);
    }

    private JsonNode findCase(JsonNode reportJson, String caseId) {
        return reportJson.path("results").findValuesAsText("caseId").stream()
                .filter(caseId::equals)
                .findFirst()
                .map(ignored -> {
                    for (JsonNode resultNode : reportJson.path("results")) {
                        if (caseId.equals(resultNode.path("caseId").asText())) {
                            return resultNode;
                        }
                    }
                    return objectMapper.createObjectNode();
                })
                .orElseGet(objectMapper::createObjectNode);
    }

    private JsonNode findModeReport(JsonNode reportJson, String mode) {
        for (JsonNode modeReport : reportJson.path("modeReports")) {
            if (mode.equals(modeReport.path("mode").asText())) {
                return modeReport;
            }
        }
        return objectMapper.createObjectNode();
    }
}
