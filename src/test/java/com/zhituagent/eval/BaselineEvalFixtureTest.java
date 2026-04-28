package com.zhituagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineEvalFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldProvideReadableBaselineEvalCases() throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("eval/baseline-chat-cases.jsonl")) {
            assertThat(inputStream).isNotNull();

            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = content.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();

            assertThat(lines).hasSizeGreaterThanOrEqualTo(7);
            assertThat(lines).allSatisfy(this::assertValidCase);
            assertThat(lines).anySatisfy(line -> assertThat(caseType(line)).isEqualTo("direct-answer"));
            assertThat(lines).anySatisfy(line -> assertThat(caseType(line)).isEqualTo("rag-answer"));
            assertThat(lines).anySatisfy(line -> assertThat(caseType(line)).isEqualTo("tool-answer"));
            assertThat(lines).anySatisfy(line -> assertThat(caseType(line)).isEqualTo("long-context"));
            assertThat(lines).anySatisfy(line -> assertThat(caseId(line)).isEqualTo("rag-rerank-001"));
            assertThat(lines).anySatisfy(line -> assertThat(caseId(line)).isEqualTo("rag-hybrid-001"));
            assertThat(lines).anySatisfy(line -> assertThat(caseId(line)).isEqualTo("context-budget-001"));
        }
    }

    private void assertValidCase(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            assertThat(node.path("caseId").asText()).isNotBlank();
            assertThat(node.path("type").asText()).isNotBlank();
            assertThat(node.path("message").asText()).isNotBlank();
            assertThat(node.path("expectedPath").asText()).isNotBlank();
            assertThat(node.path("expectedRetrievalHit").isBoolean()).isTrue();
            assertThat(node.path("expectedToolUsed").isBoolean()).isTrue();
            assertThat(node.path("expectedSummaryPresentBeforeRun").isBoolean()).isTrue();
            if (node.has("expectedContextStrategy")) {
                assertThat(node.path("expectedContextStrategy").asText()).isNotBlank();
            }
            if (node.has("expectedFactCountAtLeast")) {
                assertThat(node.path("expectedFactCountAtLeast").canConvertToInt()).isTrue();
            }

            if (node.has("knowledgeEntries")) {
                assertThat(node.path("knowledgeEntries").isArray()).isTrue();
                node.path("knowledgeEntries").forEach(knowledgeEntry -> {
                    assertThat(knowledgeEntry.path("question").asText()).isNotBlank();
                    assertThat(knowledgeEntry.path("answer").asText()).isNotBlank();
                    assertThat(knowledgeEntry.path("sourceName").asText()).isNotBlank();
                });
            }

            if (node.has("historyTurns")) {
                assertThat(node.path("historyTurns").isArray()).isTrue();
                node.path("historyTurns").forEach(historyTurn -> {
                    assertThat(historyTurn.path("user").asText()).isNotBlank();
                    assertThat(historyTurn.path("assistant").asText()).isNotBlank();
                });
            }

            if (node.has("modeExpectations")) {
                assertThat(node.path("modeExpectations").isObject()).isTrue();
                node.path("modeExpectations").properties().forEach(modeEntry -> {
                    JsonNode modeExpectation = modeEntry.getValue();
                    assertThat(modeEntry.getKey()).isNotBlank();
                    if (modeExpectation.has("expectedPath")) {
                        assertThat(modeExpectation.path("expectedPath").asText()).isNotBlank();
                    }
                    if (modeExpectation.has("expectedRetrievalHit")) {
                        assertThat(modeExpectation.path("expectedRetrievalHit").isBoolean()).isTrue();
                    }
                    if (modeExpectation.has("expectedToolUsed")) {
                        assertThat(modeExpectation.path("expectedToolUsed").isBoolean()).isTrue();
                    }
                    if (modeExpectation.has("expectedTopSource")) {
                        assertThat(modeExpectation.path("expectedTopSource").asText()).isNotBlank();
                    }
                });
            }
        } catch (IOException exception) {
            throw new AssertionError("Invalid JSONL line: " + line, exception);
        }
    }

    private String caseId(String line) {
        try {
            return objectMapper.readTree(line).path("caseId").asText();
        } catch (IOException exception) {
            throw new AssertionError("Invalid JSONL line: " + line, exception);
        }
    }

    private String caseType(String line) {
        try {
            return objectMapper.readTree(line).path("type").asText();
        } catch (IOException exception) {
            throw new AssertionError("Invalid JSONL line: " + line, exception);
        }
    }
}
