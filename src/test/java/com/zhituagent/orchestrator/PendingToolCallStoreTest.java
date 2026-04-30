package com.zhituagent.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PendingToolCallStoreTest {

    @Test
    void shouldRegisterPendingCallAndReturnIt() {
        PendingToolCallStore store = new PendingToolCallStore();

        PendingToolCall pending = store.register("session-1", "knowledge-write", "{\"q\":\"x\"}", Map.of("q", "x"));

        assertThat(pending.id()).isNotBlank();
        assertThat(pending.toolName()).isEqualTo("knowledge-write");
        assertThat(pending.status()).isEqualTo(PendingToolCallStore.Status.PENDING);
        assertThat(pending.arguments()).containsEntry("q", "x");
        assertThat(store.get(pending.id())).isPresent();
    }

    @Test
    void shouldApprovePendingAndConsumeOnce() {
        PendingToolCallStore store = new PendingToolCallStore();
        PendingToolCall pending = store.register("s", "knowledge-write", "{}", Map.of());

        Optional<PendingToolCall> approved = store.approve(pending.id());

        assertThat(approved).isPresent();
        assertThat(approved.get().status()).isEqualTo(PendingToolCallStore.Status.APPROVED);
        assertThat(store.consumeIfApproved(pending.id())).isTrue();
        assertThat(store.consumeIfApproved(pending.id()))
                .as("a single approval grants exactly one execution")
                .isFalse();
    }

    @Test
    void shouldDenyPending() {
        PendingToolCallStore store = new PendingToolCallStore();
        PendingToolCall pending = store.register("s", "knowledge-write", "{}", Map.of());

        Optional<PendingToolCall> denied = store.deny(pending.id());

        assertThat(denied).isPresent();
        assertThat(denied.get().status()).isEqualTo(PendingToolCallStore.Status.DENIED);
        assertThat(store.consumeIfApproved(pending.id())).isFalse();
    }

    @Test
    void shouldRefuseToApproveWhenAlreadyDenied() {
        PendingToolCallStore store = new PendingToolCallStore();
        PendingToolCall pending = store.register("s", "knowledge-write", "{}", Map.of());
        store.deny(pending.id());

        Optional<PendingToolCall> approveAttempt = store.approve(pending.id());

        assertThat(approveAttempt).isEmpty();
    }

    @Test
    void shouldExposePendingEntriesViaListPending() {
        PendingToolCallStore store = new PendingToolCallStore();
        PendingToolCall a = store.register("s1", "knowledge-write", "{}", Map.of());
        store.register("s2", "knowledge-write", "{}", Map.of());
        store.approve(a.id());

        List<PendingToolCall> pending = store.listPending();

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).status()).isEqualTo(PendingToolCallStore.Status.PENDING);
    }

    @Test
    void shouldReturnEmptyForUnknownId() {
        PendingToolCallStore store = new PendingToolCallStore();

        assertThat(store.get("unknown")).isEmpty();
        assertThat(store.approve("unknown")).isEmpty();
        assertThat(store.deny("unknown")).isEmpty();
        assertThat(store.consumeIfApproved("unknown")).isFalse();
    }
}
