package com.zhituagent.llm;

import com.zhituagent.config.LlmProperties;
import com.zhituagent.metrics.AiMetricsRecorder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class LangChain4jLlmRuntime implements LlmRuntime {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmRuntime.class);
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(180);
    private static final long STREAM_TIMEOUT_SECONDS = 240;

    private final LlmProperties properties;
    private final AiMetricsRecorder aiMetricsRecorder;
    private final LlmRateLimiter rateLimiter;
    private volatile ChatModel chatModel;
    private volatile StreamingChatModel streamingChatModel;

    public LangChain4jLlmRuntime(LlmProperties properties) {
        this(properties, AiMetricsRecorder.noop(), LlmRateLimiter.disabled());
    }

    public LangChain4jLlmRuntime(LlmProperties properties, AiMetricsRecorder aiMetricsRecorder) {
        this(properties, aiMetricsRecorder, LlmRateLimiter.disabled());
    }

    @Autowired
    public LangChain4jLlmRuntime(LlmProperties properties,
                                 AiMetricsRecorder aiMetricsRecorder,
                                 LlmRateLimiter rateLimiter) {
        this.properties = properties;
        this.aiMetricsRecorder = aiMetricsRecorder;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
        long startNanos = System.nanoTime();
        if (shouldUseRealProvider()) {
            rateLimiter.acquire("generate");
            try {
                ChatResponse response = getChatModel().chat(toChatMessages(systemPrompt, messages));
                String answer = response.aiMessage() == null || response.aiMessage().text() == null
                        ? ""
                        : response.aiMessage().text();
                long latencyMs = elapsedMillis(startNanos);
                aiMetricsRecorder.recordRequest(properties.getModelName(), "generate", true, latencyMs);
                log.info(
                        "模型调用完成 llm.generate.completed provider=openai-compatible messageCount={} model={} latencyMs={}",
                        messages.size(),
                        properties.getModelName(),
                        latencyMs
                );
                return answer;
            } catch (IllegalArgumentException exception) {
                if (isNullContentResponse(exception)) {
                    String answer = generateByStreamingFallback(systemPrompt, messages);
                    long latencyMs = elapsedMillis(startNanos);
                    aiMetricsRecorder.recordRequest(properties.getModelName(), "generate", true, latencyMs);
                    log.warn(
                            "模型同步返回空内容，已回退到流式聚合 llm.generate.stream_fallback model={} messageCount={} message={}",
                            properties.getModelName(),
                            messages.size(),
                            exception.getMessage()
                    );
                    log.info(
                            "模型调用完成 llm.generate.completed provider=openai-compatible-stream-fallback messageCount={} model={} latencyMs={}",
                            messages.size(),
                            properties.getModelName(),
                            latencyMs
                    );
                    return answer;
                }
                aiMetricsRecorder.recordRequest(properties.getModelName(), "generate", false, elapsedMillis(startNanos));
                throw exception;
            } catch (RuntimeException exception) {
                aiMetricsRecorder.recordRequest(properties.getModelName(), "generate", false, elapsedMillis(startNanos));
                throw exception;
            }
        }
        String answer = fallbackAnswer(messages);
        long latencyMs = elapsedMillis(startNanos);
        aiMetricsRecorder.recordRequest(properties.getModelName(), "generate", true, latencyMs);
        log.info(
                "模型调用完成 llm.generate.completed provider=mock messageCount={} model={} latencyMs={}",
                messages.size(),
                properties.getModelName(),
                latencyMs
        );
        return answer;
    }

    @Override
    public ChatTurnResult generateWithTools(String systemPrompt,
                                            List<String> messages,
                                            List<ToolSpecification> tools,
                                            Map<String, Object> metadata) {
        long startNanos = System.nanoTime();
        if (shouldUseRealProvider()) {
            rateLimiter.acquire("generateWithTools");
            try {
                ChatRequest.Builder builder = ChatRequest.builder()
                        .messages(toChatMessages(systemPrompt, messages));
                if (tools != null && !tools.isEmpty()) {
                    builder.toolSpecifications(tools);
                }
                ChatResponse response = getChatModel().chat(builder.build());
                AiMessage aiMessage = response.aiMessage();
                String text = aiMessage == null || aiMessage.text() == null ? "" : aiMessage.text();
                List<ToolExecutionRequest> toolCalls = aiMessage != null && aiMessage.hasToolExecutionRequests()
                        ? aiMessage.toolExecutionRequests()
                        : List.of();
                long latencyMs = elapsedMillis(startNanos);
                aiMetricsRecorder.recordRequest(properties.getModelName(), "generate-tools", true, latencyMs);
                log.info(
                        "模型工具调用完成 llm.generate_with_tools.completed provider=openai-compatible toolCount={} toolCalls={} model={} latencyMs={}",
                        tools == null ? 0 : tools.size(),
                        toolCalls.size(),
                        properties.getModelName(),
                        latencyMs
                );
                return new ChatTurnResult(text, toolCalls);
            } catch (RuntimeException exception) {
                aiMetricsRecorder.recordRequest(properties.getModelName(), "generate-tools", false, elapsedMillis(startNanos));
                throw exception;
            }
        }
        ChatTurnResult mocked = mockToolSelection(messages, tools);
        long latencyMs = elapsedMillis(startNanos);
        aiMetricsRecorder.recordRequest(properties.getModelName(), "generate-tools", true, latencyMs);
        log.info(
                "模型工具调用完成 llm.generate_with_tools.completed provider=mock toolCount={} toolCalls={} model={} latencyMs={}",
                tools == null ? 0 : tools.size(),
                mocked.toolCalls().size(),
                properties.getModelName(),
                latencyMs
        );
        return mocked;
    }

    @Override
    public ChatTurnResult generateChatTurn(String systemPrompt,
                                           List<ChatMessage> messages,
                                           List<ToolSpecification> tools,
                                           Map<String, Object> metadata) {
        long startNanos = System.nanoTime();
        if (shouldUseRealProvider()) {
            rateLimiter.acquire("generateChatTurn");
            try {
                List<ChatMessage> typedMessages = new ArrayList<>();
                if (isNotBlank(systemPrompt)) {
                    typedMessages.add(SystemMessage.from(systemPrompt));
                }
                if (messages != null) {
                    typedMessages.addAll(messages);
                }
                ChatRequest.Builder builder = ChatRequest.builder().messages(typedMessages);
                if (tools != null && !tools.isEmpty()) {
                    builder.toolSpecifications(tools);
                }
                ChatResponse response = getChatModel().chat(builder.build());
                AiMessage aiMessage = response.aiMessage();
                String text = aiMessage == null || aiMessage.text() == null ? "" : aiMessage.text();
                List<ToolExecutionRequest> toolCalls = aiMessage != null && aiMessage.hasToolExecutionRequests()
                        ? aiMessage.toolExecutionRequests()
                        : List.of();
                long latencyMs = elapsedMillis(startNanos);
                aiMetricsRecorder.recordRequest(properties.getModelName(), "generate-chat-turn", true, latencyMs);
                log.info(
                        "模型多轮调用完成 llm.generate_chat_turn.completed provider=openai-compatible messageCount={} toolCalls={} model={} latencyMs={}",
                        typedMessages.size(),
                        toolCalls.size(),
                        properties.getModelName(),
                        latencyMs
                );
                return new ChatTurnResult(text, toolCalls);
            } catch (RuntimeException exception) {
                aiMetricsRecorder.recordRequest(properties.getModelName(), "generate-chat-turn", false, elapsedMillis(startNanos));
                throw exception;
            }
        }
        return LlmRuntime.super.generateChatTurn(systemPrompt, messages, tools, metadata);
    }

    private ChatTurnResult mockToolSelection(List<String> messages, List<ToolSpecification> tools) {
        String latestMessage = messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        String stripped = latestMessage == null ? "" : latestMessage.toLowerCase();
        boolean timeAvailable = tools != null && tools.stream().anyMatch(spec -> "time".equals(spec.name()));
        if (timeAvailable && (stripped.contains("几点")
                || stripped.contains("星期几")
                || stripped.contains("周几")
                || stripped.contains("几号")
                || stripped.contains("几月几号")
                || stripped.contains("几月几日")
                || stripped.contains("日期")
                || stripped.contains("day of week")
                || stripped.contains("what day")
                || stripped.contains("time")
                || stripped.contains("date"))) {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("mock-time-" + System.nanoTime())
                    .name("time")
                    .arguments("{}")
                    .build();
            return ChatTurnResult.ofToolCalls(List.of(request));
        }
        return ChatTurnResult.ofText(fallbackAnswer(messages));
    }

    @Override
    public void stream(String systemPrompt,
                       List<String> messages,
                       Map<String, Object> metadata,
                       Consumer<String> onToken,
                       Runnable onComplete) {
        long startNanos = System.nanoTime();
        if (shouldUseRealProvider()) {
            rateLimiter.acquire("stream");
            CountDownLatch completionLatch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            AtomicBoolean emittedPartialToken = new AtomicBoolean(false);

            getStreamingChatModel().chat(toChatMessages(systemPrompt, messages), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (partialResponse != null && !partialResponse.isBlank()) {
                        emittedPartialToken.set(true);
                        onToken.accept(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse chatResponse) {
                    if (!emittedPartialToken.get()
                            && chatResponse != null
                            && chatResponse.aiMessage() != null
                            && chatResponse.aiMessage().text() != null
                            && !chatResponse.aiMessage().text().isBlank()) {
                        onToken.accept(chatResponse.aiMessage().text());
                    }
                    onComplete.run();
                    completionLatch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    errorRef.set(error);
                    completionLatch.countDown();
                }
            });

            awaitStreamingCompletion(completionLatch, errorRef);
            long latencyMs = elapsedMillis(startNanos);
            aiMetricsRecorder.recordRequest(properties.getModelName(), "stream", true, latencyMs);
            log.info(
                    "模型流式调用完成 llm.stream.completed provider=openai-compatible messageCount={} model={} latencyMs={}",
                    messages.size(),
                    properties.getModelName(),
                    latencyMs
            );
            return;
        }

        String answer = fallbackAnswer(messages);
        for (String segment : answer.split("(?<=。)|(?<=！)|(?<=？)|(?<=,)|(?<=，)|(?<= )")) {
            if (!segment.isBlank()) {
                onToken.accept(segment);
            }
        }
        onComplete.run();
        long latencyMs = elapsedMillis(startNanos);
        aiMetricsRecorder.recordRequest(properties.getModelName(), "stream", true, latencyMs);
        log.info(
                "模型流式调用完成 llm.stream.completed provider=mock messageCount={} model={} latencyMs={}",
                messages.size(),
                properties.getModelName(),
                latencyMs
        );
    }

    private String fallbackAnswer(List<String> messages) {
        String latestMessage = messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        return "Mock runtime: " + latestMessage;
    }

    private boolean shouldUseRealProvider() {
        return !properties.isMockMode()
                && isNotBlank(properties.getBaseUrl())
                && isNotBlank(properties.getApiKey())
                && isNotBlank(properties.getModelName());
    }

    private ChatModel getChatModel() {
        ChatModel local = chatModel;
        if (local == null) {
            synchronized (this) {
                local = chatModel;
                if (local == null) {
                    local = OpenAiChatModel.builder()
                            .baseUrl(OpenAiCompatibleBaseUrlNormalizer.normalize(properties.getBaseUrl()))
                            .apiKey(properties.getApiKey())
                            .modelName(properties.getModelName())
                            .timeout(MODEL_TIMEOUT)
                            .build();
                    chatModel = local;
                }
            }
        }
        return local;
    }

    private StreamingChatModel getStreamingChatModel() {
        StreamingChatModel local = streamingChatModel;
        if (local == null) {
            synchronized (this) {
                local = streamingChatModel;
                if (local == null) {
                    local = OpenAiStreamingChatModel.builder()
                            .baseUrl(OpenAiCompatibleBaseUrlNormalizer.normalize(properties.getBaseUrl()))
                            .apiKey(properties.getApiKey())
                            .modelName(properties.getModelName())
                            .timeout(MODEL_TIMEOUT)
                            .build();
                    streamingChatModel = local;
                }
            }
        }
        return local;
    }

    private List<ChatMessage> toChatMessages(String systemPrompt, List<String> messages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        if (isNotBlank(systemPrompt)) {
            chatMessages.add(SystemMessage.from(systemPrompt));
        }

        for (String message : messages) {
            if (message == null || message.isBlank()) {
                continue;
            }
            if (message.startsWith("SYSTEM:")) {
                continue;
            }
            if (message.startsWith("SUMMARY:")) {
                chatMessages.add(SystemMessage.from("Conversation summary: " + message.substring("SUMMARY:".length()).trim()));
                continue;
            }
            if (message.startsWith("EVIDENCE:")) {
                chatMessages.add(SystemMessage.from("Reference evidence:\n" + message.substring("EVIDENCE:".length()).trim()));
                continue;
            }
            if (message.startsWith("USER:")) {
                chatMessages.add(UserMessage.from(message.substring("USER:".length()).trim()));
                continue;
            }
            if (message.startsWith("ASSISTANT:")) {
                chatMessages.add(AiMessage.from(message.substring("ASSISTANT:".length()).trim()));
                continue;
            }
            chatMessages.add(UserMessage.from(message));
        }
        return List.copyOf(chatMessages);
    }

    private String generateByStreamingFallback(String systemPrompt, List<String> messages) {
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean emittedPartialToken = new AtomicBoolean(false);
        StringBuilder answerBuilder = new StringBuilder();

        getStreamingChatModel().chat(toChatMessages(systemPrompt, messages), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null && !partialResponse.isBlank()) {
                    emittedPartialToken.set(true);
                    answerBuilder.append(partialResponse);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse chatResponse) {
                if (!emittedPartialToken.get()
                        && chatResponse != null
                        && chatResponse.aiMessage() != null
                        && chatResponse.aiMessage().text() != null
                        && !chatResponse.aiMessage().text().isBlank()) {
                    answerBuilder.append(chatResponse.aiMessage().text());
                }
                completionLatch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                completionLatch.countDown();
            }
        });

        awaitStreamingCompletion(completionLatch, errorRef);
        return answerBuilder.toString();
    }

    private void awaitStreamingCompletion(CountDownLatch completionLatch, AtomicReference<Throwable> errorRef) {
        try {
            boolean completed = completionLatch.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                throw new IllegalStateException("streaming chat completion timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("streaming chat completion interrupted", exception);
        }

        Throwable error = errorRef.get();
        if (error != null) {
            aiMetricsRecorder.recordRequest(properties.getModelName(), "stream", false, 0);
            throw new IllegalStateException("streaming chat completion failed", error);
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isNullContentResponse(IllegalArgumentException exception) {
        return exception != null
                && exception.getMessage() != null
                && exception.getMessage().contains("text cannot be null");
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
