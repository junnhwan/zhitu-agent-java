package com.zhituagent.orchestrator;

import java.util.Map;

/**
 * Snapshot of a tool call awaiting human approval. Returned by
 * {@link PendingToolCallStore} and surfaced via the {@code /api/tool-calls}
 * endpoints + the {@code tool_call_pending} SSE event.
 */
public record PendingToolCall(
        String id,
        String sessionId,
        String toolName,
        String rawArguments,
        Map<String, Object> arguments,
        long createdEpochMillis,
        PendingToolCallStore.Status status
) {
}
