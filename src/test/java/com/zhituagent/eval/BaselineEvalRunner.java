package com.zhituagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BaselineEvalRunner {

    private static final String FIXTURE_PATH = "eval/baseline-chat-cases.jsonl";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final Path reportPath;

    BaselineEvalRunner(MockMvc mockMvc, ObjectMapper objectMapper, Path reportPath) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.reportPath = reportPath;
    }

    BaselineEvalResult runBaselineFixture() throws Exception {
        List<BaselineEvalCase> cases = loadCases(FIXTURE_PATH);
        List<BaselineEvalResult.CaseResult> results = new ArrayList<>();

        for (BaselineEvalCase evalCase : cases) {
            results.add(runCase(evalCase));
        }

        BaselineEvalResult report = new BaselineEvalResult(
                FIXTURE_PATH,
                OffsetDateTime.now().toString(),
                results.size(),
                (int) results.stream().filter(this::passed).count(),
                ratio(results.stream().filter(BaselineEvalResult.CaseResult::routeMatched).count(), results.size()),
                ratio(results.stream().filter(BaselineEvalResult.CaseResult::actualRetrievalHit).count(), results.size()),
                ratio(results.stream().filter(BaselineEvalResult.CaseResult::actualToolUsed).count(), results.size()),
                ratio(results.stream().filter(BaselineEvalResult.CaseResult::summaryMatched).count(), results.size()),
                average(results.stream().map(BaselineEvalResult.CaseResult::latencyMs).toList()),
                percentile(results.stream().map(BaselineEvalResult.CaseResult::latencyMs).toList(), 0.50),
                percentile(results.stream().map(BaselineEvalResult.CaseResult::latencyMs).toList(), 0.90),
                average(results.stream().map(BaselineEvalResult.CaseResult::inputTokenEstimate).toList()),
                average(results.stream().map(BaselineEvalResult.CaseResult::outputTokenEstimate).toList()),
                List.copyOf(results)
        );

        writeReport(report);
        return report;
    }

    private BaselineEvalResult.CaseResult runCase(BaselineEvalCase evalCase) throws Exception {
        String sessionId = "eval_" + evalCase.caseId() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String userId = "eval_user";

        for (BaselineEvalCase.KnowledgeSeed knowledgeEntry : evalCase.knowledgeEntries()) {
            writeKnowledge(knowledgeEntry);
        }

        for (BaselineEvalCase.HistoryTurn historyTurn : evalCase.historyTurns()) {
            sendChat(sessionId, userId, historyTurn.user());
        }

        boolean summaryPresentBeforeRun = evalCase.historyTurns().isEmpty()
                ? false
                : sessionSummaryPresent(sessionId);
        JsonNode chatJson = sendChat(sessionId, userId, evalCase.message());
        JsonNode trace = chatJson.path("trace");

        String actualPath = trace.path("path").asText();
        boolean actualRetrievalHit = trace.path("retrievalHit").asBoolean();
        boolean actualToolUsed = trace.path("toolUsed").asBoolean();

        return new BaselineEvalResult.CaseResult(
                evalCase.caseId(),
                evalCase.type(),
                sessionId,
                evalCase.expectedPath(),
                actualPath,
                evalCase.expectedPath().equals(actualPath),
                evalCase.expectedRetrievalHit(),
                actualRetrievalHit,
                evalCase.expectedRetrievalHit() == actualRetrievalHit,
                evalCase.expectedToolUsed(),
                actualToolUsed,
                evalCase.expectedToolUsed() == actualToolUsed,
                evalCase.expectedSummaryPresentBeforeRun(),
                summaryPresentBeforeRun,
                evalCase.expectedSummaryPresentBeforeRun() == summaryPresentBeforeRun,
                trace.path("retrievalMode").asText(),
                trace.path("contextStrategy").asText(),
                trace.path("snippetCount").asInt(),
                trace.path("retrievalCandidateCount").asInt(),
                trace.path("topSource").asText(),
                trace.path("topScore").asDouble(),
                trace.path("rerankModel").asText(),
                trace.path("rerankTopScore").asDouble(),
                trace.path("latencyMs").asLong(),
                trace.path("inputTokenEstimate").asLong(),
                trace.path("outputTokenEstimate").asLong(),
                preview(chatJson.path("answer").asText())
        );
    }

    private List<BaselineEvalCase> loadCases(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Fixture not found: " + resourcePath);
            }

            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return content.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .map(this::parseCase)
                    .toList();
        }
    }

    private BaselineEvalCase parseCase(String line) {
        try {
            return objectMapper.readValue(line, BaselineEvalCase.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid eval case: " + line, exception);
        }
    }

    private void writeKnowledge(BaselineEvalCase.KnowledgeSeed knowledgeEntry) throws Exception {
        String payload = objectMapper.writeValueAsString(knowledgeEntry);
        mockMvc.perform(post("/api/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
    }

    private JsonNode sendChat(String sessionId, String userId, String message) throws Exception {
        String payload = objectMapper.writeValueAsString(new ChatPayload(sessionId, userId, message));
        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private boolean sessionSummaryPresent(String sessionId) throws Exception {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        MvcResult result = mockMvc.perform(get("/api/sessions/{sessionId}", sessionId))
                .andReturn();
        if (result.getResponse().getStatus() >= 400) {
            return false;
        }
        JsonNode sessionJson = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return !sessionJson.path("summary").asText("").isBlank();
    }

    private void writeReport(BaselineEvalResult report) throws IOException {
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    }

    private boolean passed(BaselineEvalResult.CaseResult result) {
        return result.routeMatched()
                && result.retrievalMatched()
                && result.toolMatched()
                && result.summaryMatched();
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return numerator / (double) denominator;
    }

    private double average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private double percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        int safeIndex = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(safeIndex);
    }

    private String preview(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        return answer.length() <= 96 ? answer : answer.substring(0, 96) + "...";
    }

    private record ChatPayload(
            String sessionId,
            String userId,
            String message
    ) {
    }
}
