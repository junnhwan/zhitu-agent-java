package com.zhituagent.orchestrator;

import com.zhituagent.context.ContextBundle;
import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.rag.KnowledgeSnippet;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import com.zhituagent.trace.SpanCollector;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-turn ReAct loop. Replaces the single-shot "decide → execute → answer"
 * pipeline with a {@code while (!done && iter < maxIters)} cycle:
 * <pre>
 *   plan(LLM) ──► hasToolCalls ? execute → observe → loop
 *                              : finalAnswer
 * </pre>
 *
 * <p>Each iteration is a span ({@code agent.iter}) under the parent
 * {@code chat.turn} span; tool calls and observations get their own spans for
 * the trace tree. Loop detection is delegated to {@link ToolCallExecutor}'s
 * existing {@link LoopDetector}.
 *
 * <p>Conceptually a degenerate StateGraph with two nodes (LlmCall, CallTool)
 * and a self-loop. Kept as a single class to ship a thin v2 — when
 * Plan/Reflect/Self-RAG nodes land we'll graduate to a real graph.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final int DEFAULT_MAX_ITERS = 4;

    private final LlmRuntime llmRuntime;
    private final ToolRegistry toolRegistry;
    private final ToolCallExecutor toolCallExecutor;
    private final SpanCollector spanCollector;

    public AgentLoop(LlmRuntime llmRuntime,
                     ToolRegistry toolRegistry,
                     ToolCallExecutor toolCallExecutor,
                     SpanCollector spanCollector) {
        this.llmRuntime = llmRuntime;
        this.toolRegistry = toolRegistry;
        this.toolCallExecutor = toolCallExecutor;
        this.spanCollector = spanCollector;
    }

    public LoopResult run(String systemPrompt,
                          String userMessage,
                          ContextBundle contextBundle,
                          Map<String, Object> metadata) {
        return run(systemPrompt, userMessage, contextBundle, metadata, DEFAULT_MAX_ITERS);
    }

    public LoopResult run(String systemPrompt,
                          String userMessage,
                          ContextBundle contextBundle,
                          Map<String, Object> metadata,
                          int maxIters) {
        List<ChatMessage> conversation = bootstrap(contextBundle, userMessage);
        List<ToolSpecification> specs = toolRegistry.specifications();
        Map<String, ToolResult> firstResultByTool = new LinkedHashMap<>();
        List<ToolCallExecutor.ToolExecution> allExecutions = new ArrayList<>();
        int iter = 0;
        String finalText = "";
        boolean done = false;

        while (iter < maxIters && !done) {
            iter++;
            String iterSpan = spanCollector.startSpan("agent.iter", "agent", Map.of("iteration", iter));
            try {
                String planSpan = spanCollector.startSpan("agent.llm_call", "llm");
                ChatTurnResult turn = ChatTurnResult.ofText("");
                try {
                    turn = llmRuntime.generateChatTurn(systemPrompt, conversation, specs, metadata);
                } finally {
                    spanCollector.endSpan(planSpan, "ok", Map.of(
                            "toolCallCount", turn == null ? 0 : turn.toolCalls().size(),
                            "answerLength", turn == null || turn.text() == null ? 0 : turn.text().length()
                    ));
                }

                if (turn == null || !turn.hasToolCalls()) {
                    finalText = turn == null ? "" : turn.text();
                    done = true;
                    continue;
                }

                conversation.add(turn.text() == null || turn.text().isBlank()
                        ? AiMessage.from(turn.toolCalls())
                        : AiMessage.from(turn.text(), turn.toolCalls()));

                String toolSpan = spanCollector.startSpan("agent.tool_calls", "tool", Map.of(
                        "toolCount", turn.toolCalls().size(),
                        "toolNames", turn.toolCalls().stream().map(ToolExecutionRequest::name).toList()
                ));
                List<ToolCallExecutor.ToolExecution> executions;
                try {
                    executions = toolCallExecutor.executeAll(turn.toolCalls());
                } finally {
                    spanCollector.endSpan(toolSpan, "ok", Map.of("executedCount", turn.toolCalls().size()));
                }

                allExecutions.addAll(executions);
                for (ToolCallExecutor.ToolExecution execution : executions) {
                    firstResultByTool.putIfAbsent(execution.result().toolName(), execution.result());
                    conversation.add(ToolExecutionResultMessage.from(
                            execution.request(),
                            execution.result().summary()
                    ));
                }
            } finally {
                spanCollector.endSpan(iterSpan, done ? "ok" : "continue");
            }
        }

        if (!done) {
            log.warn("agent.loop.exhausted iterations={} maxIters={}", iter, maxIters);
            finalText = composeStepLimitFallback(allExecutions);
        }

        log.info("agent.loop.completed iterations={} toolsUsed={} finalLength={}",
                iter,
                allExecutions.stream().map(execution -> execution.result().toolName()).toList(),
                finalText.length());

        return new LoopResult(finalText, iter, done, List.copyOf(allExecutions), firstResultByTool);
    }

    private List<ChatMessage> bootstrap(ContextBundle contextBundle, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (contextBundle != null && contextBundle.modelMessages() != null) {
            for (String raw : contextBundle.modelMessages()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                if (raw.startsWith("SYSTEM:")) {
                    continue;
                }
                if (raw.startsWith("SUMMARY:")) {
                    messages.add(SystemMessage.from("Conversation summary: " + raw.substring("SUMMARY:".length()).trim()));
                    continue;
                }
                if (raw.startsWith("EVIDENCE:")) {
                    messages.add(SystemMessage.from("Reference evidence:\n" + raw.substring("EVIDENCE:".length()).trim()));
                    continue;
                }
                if (raw.startsWith("USER:")) {
                    messages.add(UserMessage.from(raw.substring("USER:".length()).trim()));
                    continue;
                }
                if (raw.startsWith("ASSISTANT:")) {
                    messages.add(AiMessage.from(raw.substring("ASSISTANT:".length()).trim()));
                    continue;
                }
                messages.add(UserMessage.from(raw));
            }
        }
        // Ensure the immediate user query is present (it usually already is via context bundle).
        if (messages.isEmpty() || !(messages.get(messages.size() - 1) instanceof UserMessage)) {
            messages.add(UserMessage.from(userMessage));
        }
        return messages;
    }

    private String composeStepLimitFallback(List<ToolCallExecutor.ToolExecution> executions) {
        if (executions.isEmpty()) {
            return "[reached step limit] I could not produce an answer within the iteration budget.";
        }
        StringBuilder builder = new StringBuilder("[reached step limit] partial observations: ");
        for (ToolCallExecutor.ToolExecution execution : executions) {
            ToolResult result = execution.result();
            builder.append("[").append(result.toolName()).append("] ").append(result.summary()).append("; ");
        }
        return builder.toString().trim();
    }

    public record LoopResult(
            String finalAnswer,
            int iterations,
            boolean converged,
            List<ToolCallExecutor.ToolExecution> executions,
            Map<String, ToolResult> firstResultByTool
    ) {

        public LoopResult {
            executions = executions == null ? List.of() : List.copyOf(executions);
            firstResultByTool = firstResultByTool == null ? Map.of() : Map.copyOf(firstResultByTool);
        }

        public List<String> toolsUsed() {
            return executions.stream().map(execution -> execution.result().toolName()).toList();
        }

        public ToolResult firstSuccessfulResult() {
            return executions.stream()
                    .map(ToolCallExecutor.ToolExecution::result)
                    .filter(ToolResult::success)
                    .findFirst()
                    .orElse(null);
        }

        public KnowledgeSnippet[] retrievedSnippets() {
            return new KnowledgeSnippet[0];
        }
    }
}
