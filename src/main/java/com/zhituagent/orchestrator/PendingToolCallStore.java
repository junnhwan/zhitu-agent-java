package com.zhituagent.orchestrator;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory parking lot for tool calls that need user approval before they
 * actually run. Used by {@link ToolCallExecutor} when a tool's
 * {@code requiresApproval()} is true.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Executor sees an unknown {@code (toolCallId, approvedToolCallId)} pair
 *       and {@link #register} the call with status {@code PENDING}.
 *   <li>Operator hits {@code POST /api/tool-calls/{id}/approve} or {@code /deny}.
 *   <li>Client retries the chat request with {@code metadata.approvedToolCallId = id};
 *       the executor now sees an APPROVED entry and runs the tool. {@link #consume}
 *       is invoked at this point so a single approval grants exactly one execution.
 * </ol>
 *
 * <p>Stale entries older than {@link #ENTRY_TTL_MILLIS} are evicted lazily on each
 * lookup — no scheduler needed. For multi-instance deployments swap this with a
 * Redis-backed implementation; the public surface is small enough to wrap.
 */
@Component
public class PendingToolCallStore {

    public enum Status { PENDING, APPROVED, DENIED, CONSUMED }

    /** 15 minutes — long enough for a human to come back from coffee, short enough to bound memory. */
    static final long ENTRY_TTL_MILLIS = 15L * 60L * 1000L;

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    public PendingToolCall register(String sessionId,
                                    String toolName,
                                    String rawArguments,
                                    Map<String, Object> arguments) {
        evictStale();
        String id = UUID.randomUUID().toString();
        Entry entry = new Entry(
                id,
                sessionId,
                toolName,
                rawArguments,
                arguments == null ? Map.of() : Map.copyOf(arguments),
                Instant.now().toEpochMilli(),
                Status.PENDING
        );
        entries.put(id, entry);
        return entry.toRecord();
    }

    public Optional<PendingToolCall> get(String pendingId) {
        evictStale();
        Entry entry = entries.get(pendingId);
        return entry == null ? Optional.empty() : Optional.of(entry.toRecord());
    }

    public Optional<PendingToolCall> approve(String pendingId) {
        return mutate(pendingId, Status.PENDING, Status.APPROVED);
    }

    public Optional<PendingToolCall> deny(String pendingId) {
        return mutate(pendingId, Status.PENDING, Status.DENIED);
    }

    /**
     * Atomic check-and-flip: if {@code pendingId} is APPROVED, mark it CONSUMED and
     * return true. Used by the executor to make a single approval cover exactly one
     * tool invocation, so a leaked approval token cannot be replayed.
     */
    public boolean consumeIfApproved(String pendingId) {
        if (pendingId == null) {
            return false;
        }
        java.util.concurrent.atomic.AtomicBoolean flipped = new java.util.concurrent.atomic.AtomicBoolean(false);
        entries.computeIfPresent(pendingId, (id, current) -> {
            if (current.status == Status.APPROVED) {
                flipped.set(true);
                return current.withStatus(Status.CONSUMED);
            }
            return current;
        });
        return flipped.get();
    }

    public List<PendingToolCall> listPending() {
        evictStale();
        List<PendingToolCall> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.status == Status.PENDING) {
                result.add(entry.toRecord());
            }
        }
        return List.copyOf(result);
    }

    int size() {
        return entries.size();
    }

    private Optional<PendingToolCall> mutate(String pendingId, Status expected, Status target) {
        if (pendingId == null) {
            return Optional.empty();
        }
        Entry updated = entries.computeIfPresent(pendingId, (id, current) ->
                current.status == expected ? current.withStatus(target) : current
        );
        if (updated == null || updated.status != target) {
            return Optional.empty();
        }
        return Optional.of(updated.toRecord());
    }

    private void evictStale() {
        long now = Instant.now().toEpochMilli();
        entries.entrySet().removeIf(e -> now - e.getValue().createdEpochMillis > ENTRY_TTL_MILLIS);
    }

    private record Entry(
            String id,
            String sessionId,
            String toolName,
            String rawArguments,
            Map<String, Object> arguments,
            long createdEpochMillis,
            Status status
    ) {
        Entry withStatus(Status next) {
            return new Entry(id, sessionId, toolName, rawArguments, arguments, createdEpochMillis, next);
        }

        PendingToolCall toRecord() {
            return new PendingToolCall(
                    Objects.requireNonNull(id),
                    sessionId == null ? "" : sessionId,
                    toolName == null ? "" : toolName,
                    rawArguments == null ? "" : rawArguments,
                    arguments,
                    createdEpochMillis,
                    status
            );
        }
    }
}
