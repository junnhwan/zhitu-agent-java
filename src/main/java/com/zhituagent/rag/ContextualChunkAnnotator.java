package com.zhituagent.rag;

import com.zhituagent.config.RagProperties;
import com.zhituagent.llm.LlmRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Generates a short contextual prefix for a chunk based on the full document,
 * following Anthropic's Contextual Retrieval recipe.
 *
 * <p>Embedding the chunk as {@code "<prefix>\n\n<chunk>"} significantly reduces
 * "lost in the middle" failures where a chunk in isolation lacks the disambiguating
 * context that surrounding text provides. Lexical search and the user-facing
 * evidence still use the raw chunk — only the dense vector benefits from the
 * prefix.
 *
 * <p>This component is always wired but only does work when
 * {@code zhitu.rag.contextual-enabled=true}. When disabled (default) or when the
 * LLM call fails it returns the original chunk so embedding falls back to the
 * legacy behaviour — safe to leave on in production once tuned, easy to disable
 * for offline/eval runs.
 */
@Component
public class ContextualChunkAnnotator {

    private static final Logger log = LoggerFactory.getLogger(ContextualChunkAnnotator.class);
    private static final String SYSTEM_PROMPT = "You generate short retrieval-time context for document chunks. "
            + "Reply in 1-2 sentences (under 60 Chinese characters or 80 English words). "
            + "Output the context only — no preamble, no quotes.";
    private static final String USER_PROMPT_TEMPLATE = "<document>\n%s\n</document>\n\n"
            + "Here is the chunk we want to situate within the whole document:\n<chunk>\n%s\n</chunk>\n\n"
            + "Please give a short succinct context to situate this chunk within the overall "
            + "document for the purposes of improving search retrieval of the chunk. "
            + "Answer only with the succinct context and nothing else.";

    private final LlmRuntime llmRuntime;
    private final RagProperties ragProperties;

    public ContextualChunkAnnotator(LlmRuntime llmRuntime, RagProperties ragProperties) {
        this.llmRuntime = llmRuntime;
        this.ragProperties = ragProperties;
    }

    public boolean isEnabled() {
        return ragProperties != null && ragProperties.isContextualEnabled();
    }

    /**
     * Returns the embed text to associate with this chunk. When contextual mode
     * is on and the LLM produces a usable prefix, this is {@code prefix + "\n\n" + chunk};
     * otherwise it returns the raw chunk so callers can pass the result straight
     * through.
     */
    public String annotate(String fullDocument, String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return chunk;
        }
        if (!isEnabled()) {
            return chunk;
        }

        String prefix = generatePrefix(fullDocument, chunk);
        if (prefix == null || prefix.isBlank()) {
            return chunk;
        }
        return prefix.trim() + "\n\n" + chunk;
    }

    private String generatePrefix(String fullDocument, String chunk) {
        try {
            String userPrompt = String.format(USER_PROMPT_TEMPLATE, safeText(fullDocument), chunk);
            String response = llmRuntime.generate(SYSTEM_PROMPT, List.of("USER: " + userPrompt), Map.of(
                    "purpose", "contextual-retrieval-annotate"
            ));
            return response;
        } catch (RuntimeException exception) {
            log.warn("contextual annotator failed, falling back to raw chunk: {}", exception.getMessage());
            return null;
        }
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }
}
