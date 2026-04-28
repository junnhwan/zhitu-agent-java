package com.zhituagent.llm;

import com.zhituagent.config.LlmProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);
    private static final long STREAM_TIMEOUT_SECONDS = 90;

    private final LlmProperties properties;
    private volatile ChatModel chatModel;
    private volatile StreamingChatModel streamingChatModel;

    public LangChain4jLlmRuntime(LlmProperties properties) {
        this.properties = properties;
    }

    @Override
    public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
        long startNanos = System.nanoTime();
        if (shouldUseRealProvider()) {
            ChatResponse response = getChatModel().chat(toChatMessages(systemPrompt, messages));
            String answer = response.aiMessage() == null || response.aiMessage().text() == null
                    ? ""
                    : response.aiMessage().text();
            log.info(
                    "模型调用完成 llm.generate.completed provider=openai-compatible messageCount={} model={} latencyMs={}",
                    messages.size(),
                    properties.getModelName(),
                    elapsedMillis(startNanos)
            );
            return answer;
        }
        String answer = fallbackAnswer(messages);
        log.info(
                "模型调用完成 llm.generate.completed provider=mock messageCount={} model={} latencyMs={}",
                messages.size(),
                properties.getModelName(),
                elapsedMillis(startNanos)
        );
        return answer;
    }

    @Override
    public void stream(String systemPrompt,
                       List<String> messages,
                       Map<String, Object> metadata,
                       Consumer<String> onToken,
                       Runnable onComplete) {
        long startNanos = System.nanoTime();
        if (shouldUseRealProvider()) {
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
            log.info(
                    "模型流式调用完成 llm.stream.completed provider=openai-compatible messageCount={} model={} latencyMs={}",
                    messages.size(),
                    properties.getModelName(),
                    elapsedMillis(startNanos)
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
        log.info(
                "模型流式调用完成 llm.stream.completed provider=mock messageCount={} model={} latencyMs={}",
                messages.size(),
                properties.getModelName(),
                elapsedMillis(startNanos)
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
            throw new IllegalStateException("streaming chat completion failed", error);
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
