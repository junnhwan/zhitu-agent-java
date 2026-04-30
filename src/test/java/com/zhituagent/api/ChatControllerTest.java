package com.zhituagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.llm.LlmRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {ZhituAgentApplication.class, ChatControllerTest.StubConfig.class},
        properties = {
                "zhitu.trace-archive.enabled=true",
                "zhitu.trace-archive.dir=target/test-traces/chat-controller"
        }
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ChatControllerTest {

    private static final Path TRACE_DIR = Path.of("target", "test-traces", "chat-controller");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTraceDir() throws IOException {
        if (!Files.exists(TRACE_DIR)) {
            return;
        }
        try (var paths = Files.walk(TRACE_DIR)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        if (!path.equals(TRACE_DIR)) {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        }
                    });
        }
    }

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        LlmRuntime testLlmRuntime() {
            return new LlmRuntime() {
                @Override
                public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                    if (messages.stream().anyMatch(message -> message.contains("触发失败"))) {
                        throw new IllegalStateException("mock llm failure");
                    }
                    return "Mock answer";
                }

                @Override
                public void stream(String systemPrompt,
                                   List<String> messages,
                                   Map<String, Object> metadata,
                                   Consumer<String> onToken,
                                   Runnable onComplete) {
                    if (messages.stream().anyMatch(message -> message.contains("触发流式失败"))) {
                        throw new IllegalStateException("mock llm stream failure");
                    }
                    onToken.accept("第一版");
                    onToken.accept("建议先把主链路跑通");
                    onComplete.run();
                }
            };
        }
    }

    @Test
    void shouldReturnMockAnswerForChatEndpoint() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_10001",
                                  "userId": "user_20001",
                                  "message": "介绍一下第一版目标"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess_10001"))
                .andExpect(jsonPath("$.answer").value("Mock answer"))
                .andExpect(jsonPath("$.trace.path").value("direct-answer"))
                .andExpect(jsonPath("$.trace.retrievalHit").value(false))
                .andExpect(jsonPath("$.trace.toolUsed").value(false))
                .andExpect(jsonPath("$.trace.retrievalMode").value("none"))
                .andExpect(jsonPath("$.trace.contextStrategy").value("recent-summary"))
                .andExpect(jsonPath("$.trace.requestId").isNotEmpty())
                .andExpect(jsonPath("$.trace.latencyMs").isNumber())
                .andExpect(jsonPath("$.trace.snippetCount").value(0))
                .andExpect(jsonPath("$.trace.topSource").value(""))
                .andExpect(jsonPath("$.trace.topScore").value(0.0))
                .andExpect(jsonPath("$.trace.retrievalCandidateCount").value(0))
                .andExpect(jsonPath("$.trace.rerankModel").value(""))
                .andExpect(jsonPath("$.trace.rerankTopScore").value(0.0))
                .andExpect(jsonPath("$.trace.factCount").value(0))
                .andExpect(jsonPath("$.trace.inputTokenEstimate").isNumber())
                .andExpect(jsonPath("$.trace.outputTokenEstimate").isNumber());
    }

    @Test
    void shouldArchiveTraceForChatEndpoint() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_trace_chat_10001",
                                  "userId": "user_20001",
                                  "message": "介绍一下第一版目标"
                                }
                                """))
                .andExpect(status().isOk());

        JsonNode entry = readLatestTraceEntry();
        org.assertj.core.api.Assertions.assertThat(entry.get("event").asText()).isEqualTo("chat.completed");
        org.assertj.core.api.Assertions.assertThat(entry.get("stream").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(entry.get("sessionId").asText()).isEqualTo("sess_trace_chat_10001");
        org.assertj.core.api.Assertions.assertThat(entry.get("userMessage").asText()).isEqualTo("介绍一下第一版目标");
        org.assertj.core.api.Assertions.assertThat(entry.get("path").asText()).isEqualTo("direct-answer");
        org.assertj.core.api.Assertions.assertThat(entry.get("retrievalMode").asText()).isEqualTo("none");
        org.assertj.core.api.Assertions.assertThat(entry.get("contextStrategy").asText()).isEqualTo("recent-summary");
        org.assertj.core.api.Assertions.assertThat(entry.get("answerPreview").asText()).contains("Mock answer");
    }

    @Test
    void shouldExposeFactCountInTraceWhenStableFactIsCaptured() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_fact_10001",
                                  "userId": "user_20001",
                                  "message": "我在杭州做 Java Agent 后端开发"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace.contextStrategy").value("recent-summary-facts"))
                .andExpect(jsonPath("$.trace.factCount").value(1));
    }

    @Test
    void shouldEmitStartTokenAndCompleteEventsForStreamEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/streamChat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_10001",
                                  "userId": "user_20001",
                                  "message": "流式介绍一下当前方案"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        Thread.sleep(250);

        assertThat(mvcResult.getResponse().getStatus(), org.hamcrest.Matchers.is(200));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("event:start"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("event:token"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("event:complete"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"retrievalMode\":\"none\""));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"contextStrategy\":\"recent-summary\""));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"retrievalCandidateCount\":0"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"rerankModel\":\"\""));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"rerankTopScore\":0.0"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"factCount\":0"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"inputTokenEstimate\":"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"outputTokenEstimate\":"));
    }

    @Test
    void shouldArchiveTraceForStreamEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/streamChat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_trace_stream_10001",
                                  "userId": "user_20001",
                                  "message": "流式介绍一下当前方案"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        Thread.sleep(250);

        assertThat(mvcResult.getResponse().getStatus(), org.hamcrest.Matchers.is(200));
        JsonNode entry = readLatestTraceEntry();
        org.assertj.core.api.Assertions.assertThat(entry.get("event").asText()).isEqualTo("chat.stream.completed");
        org.assertj.core.api.Assertions.assertThat(entry.get("stream").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(entry.get("sessionId").asText()).isEqualTo("sess_trace_stream_10001");
        org.assertj.core.api.Assertions.assertThat(entry.get("path").asText()).isEqualTo("direct-answer");
        org.assertj.core.api.Assertions.assertThat(entry.get("retrievalHit").asBoolean()).isFalse();
    }

    @Test
    void shouldArchiveFailureTraceForChatEndpoint() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_trace_error_10001",
                                  "userId": "user_20001",
                                  "message": "请触发失败"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        JsonNode entry = readLatestTraceEntry();
        org.assertj.core.api.Assertions.assertThat(entry.get("event").asText()).isEqualTo("chat.failed");
        org.assertj.core.api.Assertions.assertThat(entry.get("stream").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(entry.get("sessionId").asText()).isEqualTo("sess_trace_error_10001");
        org.assertj.core.api.Assertions.assertThat(entry.get("errorMessage").asText()).contains("mock llm failure");
    }

    @Test
    void shouldLogChatRouteDecision(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_10001",
                                  "userId": "user_20001",
                                  "message": "介绍一下第一版目标"
                                }
                                """))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(output).contains("chat.completed sessionId=sess_10001 path=direct-answer retrievalHit=false toolUsed=false");
    }

    private JsonNode readLatestTraceEntry() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (Files.exists(TRACE_DIR)) {
                try (var files = Files.list(TRACE_DIR)) {
                    Path traceFile = files.findFirst().orElse(null);
                    if (traceFile != null) {
                        List<String> lines = Files.readAllLines(traceFile);
                        if (!lines.isEmpty()) {
                            return objectMapper.readTree(lines.getLast());
                        }
                    }
                }
            }
            Thread.sleep(50);
        }
        org.assertj.core.api.Assertions.fail("trace 归档文件未生成");
        return null;
    }
}
