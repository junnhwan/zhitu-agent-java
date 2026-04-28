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

            assertThat(lines).hasSizeGreaterThanOrEqualTo(4);
            assertThat(lines).allSatisfy(this::assertValidCase);
            assertThat(lines).anySatisfy(line -> assertThat(caseType(line)).isEqualTo("direct-answer"));
            assertThat(lines).anySatisfy(line -> assertThat(caseType(line)).isEqualTo("rag-answer"));
            assertThat(lines).anySatisfy(line -> assertThat(caseType(line)).isEqualTo("tool-answer"));
            assertThat(lines).anySatisfy(line -> assertThat(caseType(line)).isEqualTo("long-context"));
        }
    }

    private void assertValidCase(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            assertThat(node.path("caseId").asText()).isNotBlank();
            assertThat(node.path("type").asText()).isNotBlank();
            assertThat(node.path("message").asText()).isNotBlank();
            assertThat(node.path("expectedPath").asText()).isNotBlank();
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
