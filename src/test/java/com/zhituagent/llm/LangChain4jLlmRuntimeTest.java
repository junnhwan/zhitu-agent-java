package com.zhituagent.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zhituagent.config.LlmProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LangChain4jLlmRuntimeTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCallConfiguredOpenAiCompatibleEndpointForGenerate() throws Exception {
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        startServer(requests);

        LlmProperties properties = new LlmProperties();
        properties.setMockMode(false);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
        properties.setApiKey("test-chat-key");
        properties.setModelName("demo-chat-model");

        LangChain4jLlmRuntime runtime = new LangChain4jLlmRuntime(properties);

        String answer = runtime.generate(
                "你是测试助手",
                List.of("USER: 你好，请介绍一下第一版目标"),
                Map.of("requestId", "req_10001")
        );

        assertThat(answer).isEqualTo("真实模型回答");
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path()).isEqualTo("/v1/chat/completions");
        assertThat(requests.getFirst().authorization()).isEqualTo("Bearer test-chat-key");
        assertThat(requests.getFirst().body()).contains("\"demo-chat-model\"");
        assertThat(requests.getFirst().body()).contains("\"你好，请介绍一下第一版目标\"");
    }

    @Test
    void shouldStreamTokensFromConfiguredOpenAiCompatibleEndpoint() throws Exception {
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        startServer(requests);

        LlmProperties properties = new LlmProperties();
        properties.setMockMode(false);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
        properties.setApiKey("test-chat-key");
        properties.setModelName("demo-chat-model");

        LangChain4jLlmRuntime runtime = new LangChain4jLlmRuntime(properties);
        List<String> tokens = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        runtime.stream(
                "你是测试助手",
                List.of("USER: 流式介绍一下当前方案"),
                Map.of(),
                tokens::add,
                () -> completed.set(true)
        );

        assertThat(tokens).isNotNull();
        assertThat(completed).isTrue();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path()).isEqualTo("/v1/chat/completions");
        assertThat(requests.getFirst().body()).contains("\"stream\" : true");
    }

    @Test
    void shouldLogWhenUsingMockFallbackForGenerate(CapturedOutput output) {
        LlmProperties properties = new LlmProperties();
        properties.setMockMode(true);

        LangChain4jLlmRuntime runtime = new LangChain4jLlmRuntime(properties);
        String answer = runtime.generate("你是测试助手", List.of("USER: 你好"), Map.of());

        assertThat(answer).isEqualTo("Mock runtime: USER: 你好");
        assertThat(output).contains("llm.generate.completed provider=mock messageCount=1");
    }

    private void startServer(List<CapturedRequest> requests) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> handleChatCompletion(exchange, requests));
        server.start();
    }

    private void handleChatCompletion(HttpExchange exchange, List<CapturedRequest> requests) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body
        ));

        if (body.contains("\"stream\":true")) {
            String response = """
                    data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1714185600,"model":"demo-chat-model","choices":[{"index":0,"delta":{"role":"assistant","content":"流式"},"finish_reason":null}]}

                    data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1714185600,"model":"demo-chat-model","choices":[{"index":0,"delta":{"content":"回答"},"finish_reason":null}]}

                    data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1714185600,"model":"demo-chat-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """;
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        String response = """
                {
                  "id": "chatcmpl-1",
                  "object": "chat.completion",
                  "created": 1714185600,
                  "model": "demo-chat-model",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "真实模型回答"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 4,
                    "total_tokens": 16
                  }
                }
                """;

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record CapturedRequest(String path, String authorization, String body) {
    }
}
