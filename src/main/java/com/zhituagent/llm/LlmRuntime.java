package com.zhituagent.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface LlmRuntime {

    String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata);

    void stream(String systemPrompt,
                List<String> messages,
                Map<String, Object> metadata,
                Consumer<String> onToken,
                Runnable onComplete);

    default ChatTurnResult generateWithTools(String systemPrompt,
                                             List<String> messages,
                                             List<ToolSpecification> tools,
                                             Map<String, Object> metadata) {
        return ChatTurnResult.ofText(generate(systemPrompt, messages, metadata));
    }

    /**
     * Multi-turn variant taking typed {@link ChatMessage} objects so callers can
     * round-trip {@code AiMessage} (with tool_calls) and
     * {@code ToolExecutionResultMessage} between LLM turns. Used by
     * {@code AgentLoop} for the ReAct loop.
     *
     * <p>Default falls back to {@link #generateWithTools} after stringifying the
     * messages — fine for stubs and the legacy single-shot path, but real
     * function-calling-aware runtimes must override to preserve tool_call IDs.
     */
    default ChatTurnResult generateChatTurn(String systemPrompt,
                                            List<ChatMessage> messages,
                                            List<ToolSpecification> tools,
                                            Map<String, Object> metadata) {
        List<String> stringified = messages == null
                ? List.of()
                : messages.stream()
                        .map(message -> message.type() + ": " + textOf(message))
                        .toList();
        return generateWithTools(systemPrompt, stringified, tools, metadata);
    }

    private static String textOf(ChatMessage message) {
        if (message instanceof dev.langchain4j.data.message.UserMessage user) {
            return user.singleText();
        }
        if (message instanceof dev.langchain4j.data.message.AiMessage ai) {
            return ai.text() == null ? "" : ai.text();
        }
        if (message instanceof dev.langchain4j.data.message.SystemMessage system) {
            return system.text();
        }
        if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tool) {
            return tool.text();
        }
        return message.toString();
    }
}
