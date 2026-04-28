package com.zhituagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.llm.LlmRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {ZhituAgentApplication.class, BaselineEvalRunnerTest.StubConfig.class})
@AutoConfigureMockMvc
class BaselineEvalRunnerTest {

    @Autowired
    private MockMvc mockMvc;

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
    }

    @Test
    void shouldRunBaselineFixtureAndWriteEvalReport() throws Exception {
        Path reportPath = Path.of("target", "eval-reports", "baseline-" + UUID.randomUUID() + ".json");

        Object runner = instantiateRunner(mockMvc, objectMapper, reportPath);
        Object report = invokeRun(runner);
        JsonNode reportJson = objectMapper.valueToTree(report);

        assertThat(reportJson.path("totalCases").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(reportJson.path("passedCases").asInt()).isEqualTo(reportJson.path("totalCases").asInt());
        assertThat(reportJson.path("routeAccuracy").asDouble()).isEqualTo(1.0);
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

        assertThat(reportPath).exists();
        JsonNode writtenReport = objectMapper.readTree(Files.readString(reportPath));
        assertThat(writtenReport.path("totalCases").asInt()).isEqualTo(reportJson.path("totalCases").asInt());
        assertThat(writtenReport.path("results")).hasSize(reportJson.path("results").size());
    }

    private Object instantiateRunner(MockMvc mockMvc, ObjectMapper objectMapper, Path reportPath) throws Exception {
        Class<?> runnerClass = Class.forName("com.zhituagent.eval.BaselineEvalRunner");
        Constructor<?> constructor = runnerClass.getDeclaredConstructor(MockMvc.class, ObjectMapper.class, Path.class);
        return constructor.newInstance(mockMvc, objectMapper, reportPath);
    }

    private Object invokeRun(Object runner) throws Exception {
        Method method = runner.getClass().getDeclaredMethod("runBaselineFixture");
        return method.invoke(runner);
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
}
