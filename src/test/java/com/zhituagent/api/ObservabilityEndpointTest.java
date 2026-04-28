package com.zhituagent.api;

import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.metrics.AiMetricsRecorder;
import com.zhituagent.rag.RerankClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {ZhituAgentApplication.class, ObservabilityEndpointTest.StubConfig.class},
        properties = {
                "management.health.redis.enabled=false",
                "management.defaults.metrics.export.enabled=true",
                "management.prometheus.metrics.export.enabled=true",
                "management.endpoints.access.default=read-only",
                "management.endpoint.prometheus.access=read-only",
                "management.endpoints.web.exposure.include=health,prometheus",
                "zhitu.infrastructure.redis-enabled=false",
                "zhitu.rerank.enabled=true",
                "zhitu.rerank.url=https://router.tumuer.me/v1/rerank",
                "zhitu.rerank.api-key=demo-key",
                "zhitu.rerank.model-name=Qwen/Qwen3-Reranker-8B",
                "zhitu.rag.hybrid-enabled=true"
        }
)
@AutoConfigureMockMvc
class ObservabilityEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        LlmRuntime testLlmRuntime(AiMetricsRecorder aiMetricsRecorder) {
            return new LlmRuntime() {
                @Override
                public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                    aiMetricsRecorder.recordRequest("observability-mock-model", "generate", true, 1);
                    return "observability mock answer";
                }

                @Override
                public void stream(String systemPrompt,
                                   List<String> messages,
                                   Map<String, Object> metadata,
                                   Consumer<String> onToken,
                                   Runnable onComplete) {
                    aiMetricsRecorder.recordRequest("observability-mock-model", "stream", true, 1);
                    onToken.accept("observability mock stream");
                    onComplete.run();
                }
            };
        }

        @Bean
        @Primary
        RerankClient testRerankClient() {
            return (query, candidates, topN) -> new RerankClient.RerankResponse(
                    "Qwen/Qwen3-Reranker-8B",
                    List.of(new RerankClient.RerankResult(0, 0.96))
            );
        }
    }

    @Test
    void shouldExposePrometheusMetricsAfterChatAndRagRequests() throws Exception {
        mockMvc.perform(post("/api/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "第一阶段先做什么？",
                                  "answer": "第一阶段先做好最简单的 Context 管理策略、记忆机制、RAG 检索、会话管理、SSE 对话问答、ToolUse。",
                                  "sourceName": "phase-two-plan"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "第二阶段重点补什么？",
                                  "answer": "第二阶段优先补齐评估基线、Rerank、Hybrid Retrieval、Prometheus 指标，以及 Redis 记忆并发保护。",
                                  "sourceName": "phase-two-execution"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "metric_sess_1",
                                  "userId": "metric_user_1",
                                  "message": "第一阶段先做什么？"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "metric_sess_2",
                                  "userId": "metric_user_2",
                                  "message": "现在几点了？"
                                }
                                """))
                .andExpect(status().isOk());

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(metrics).contains("zhitu_chat_requests_total");
        assertThat(metrics).contains("zhitu_chat_request_duration_seconds");
        assertThat(metrics).contains("zhitu_llm_requests_total");
        assertThat(metrics).contains("zhitu_llm_request_duration_seconds");
        assertThat(metrics).contains("zhitu_rag_retrieval_total");
        assertThat(metrics).contains("zhitu_rag_retrieval_duration_seconds");
        assertThat(metrics).contains("zhitu_rag_recall_size");
        assertThat(metrics).contains("zhitu_rerank_requests_total");
        assertThat(metrics).contains("zhitu_tool_invocations_total");
        assertThat(metrics).contains("zhitu_memory_compression_total");
    }

    @Test
    void shouldExposeActuatorHealthEndpoint() throws Exception {
        String response = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("UP");
    }

    @Test
    void shouldExposeErrorClassificationMetrics() throws Exception {
        mockMvc.perform(get("/api/sessions/{sessionId}", "sess_missing_for_metrics"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_invalid",
                                  "userId": "metric_user_3",
                                  "message": ""
                                }
                                """))
                .andExpect(status().isBadRequest());

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(metrics).contains("zhitu_api_errors_total");
        assertThat(metrics).contains("category=\"business\"");
        assertThat(metrics).contains("code=\"SESSION_NOT_FOUND\"");
        assertThat(metrics).contains("category=\"validation\"");
        assertThat(metrics).contains("code=\"INVALID_ARGUMENT\"");
    }
}
