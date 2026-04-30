package com.zhituagent.rag;

import com.zhituagent.config.RagProperties;
import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualChunkAnnotatorTest {

    @Test
    void shouldReturnRawChunkWhenDisabled() {
        RagProperties properties = new RagProperties();
        properties.setContextualEnabled(false);
        ContextualChunkAnnotator annotator = new ContextualChunkAnnotator(failingLlm(), properties);

        String result = annotator.annotate("doc text", "the chunk");

        assertThat(result).isEqualTo("the chunk");
        assertThat(annotator.isEnabled()).isFalse();
    }

    @Test
    void shouldEmbedPrefixWhenEnabledAndLlmReturnsContext() {
        RagProperties properties = new RagProperties();
        properties.setContextualEnabled(true);
        AtomicInteger calls = new AtomicInteger();
        LlmRuntime llm = stubLlm("This chunk explains caching layers in the data pipeline.", calls);
        ContextualChunkAnnotator annotator = new ContextualChunkAnnotator(llm, properties);

        String result = annotator.annotate("Q: cache?\nA: There are 3 caching layers...", "There are 3 caching layers");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result).startsWith("This chunk explains caching layers in the data pipeline.");
        assertThat(result).contains("\n\nThere are 3 caching layers");
    }

    @Test
    void shouldFallBackToRawChunkWhenLlmThrows() {
        RagProperties properties = new RagProperties();
        properties.setContextualEnabled(true);
        ContextualChunkAnnotator annotator = new ContextualChunkAnnotator(failingLlm(), properties);

        String result = annotator.annotate("doc", "raw chunk");

        assertThat(result).isEqualTo("raw chunk");
    }

    @Test
    void shouldFallBackToRawChunkWhenLlmReturnsBlank() {
        RagProperties properties = new RagProperties();
        properties.setContextualEnabled(true);
        ContextualChunkAnnotator annotator = new ContextualChunkAnnotator(stubLlm("   ", new AtomicInteger()), properties);

        String result = annotator.annotate("doc", "raw chunk");

        assertThat(result).isEqualTo("raw chunk");
    }

    @Test
    void shouldReturnInputForBlankChunk() {
        RagProperties properties = new RagProperties();
        properties.setContextualEnabled(true);
        ContextualChunkAnnotator annotator = new ContextualChunkAnnotator(failingLlm(), properties);

        assertThat(annotator.annotate("doc", null)).isNull();
        assertThat(annotator.annotate("doc", "")).isEqualTo("");
    }

    private static LlmRuntime stubLlm(String response, AtomicInteger callCounter) {
        return new LlmRuntime() {
            @Override
            public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                callCounter.incrementAndGet();
                return response;
            }

            @Override
            public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata,
                               Consumer<String> onToken, Runnable onComplete) {
                onToken.accept(response);
                onComplete.run();
            }

            @Override
            public ChatTurnResult generateWithTools(String systemPrompt, List<String> messages,
                                                    List<ToolSpecification> tools, Map<String, Object> metadata) {
                return ChatTurnResult.ofText(generate(systemPrompt, messages, metadata));
            }
        };
    }

    private static LlmRuntime failingLlm() {
        return new LlmRuntime() {
            @Override
            public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                throw new RuntimeException("simulated llm failure");
            }

            @Override
            public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata,
                               Consumer<String> onToken, Runnable onComplete) {
                throw new RuntimeException("simulated llm failure");
            }

            @Override
            public ChatTurnResult generateWithTools(String systemPrompt, List<String> messages,
                                                    List<ToolSpecification> tools, Map<String, Object> metadata) {
                throw new RuntimeException("simulated llm failure");
            }
        };
    }
}
