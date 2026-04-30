package com.zhituagent.api;

import com.zhituagent.orchestrator.PendingToolCall;
import com.zhituagent.orchestrator.PendingToolCallStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HitlControllerTest {

    @Test
    void shouldApprovePendingCall() {
        PendingToolCallStore store = new PendingToolCallStore();
        PendingToolCall pending = store.register("session-A", "knowledge-write", "{}", Map.of());
        HitlController controller = new HitlController(store);

        ResponseEntity<PendingToolCall> response = controller.approve(pending.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(PendingToolCallStore.Status.APPROVED);
    }

    @Test
    void shouldDenyPendingCall() {
        PendingToolCallStore store = new PendingToolCallStore();
        PendingToolCall pending = store.register("session-A", "knowledge-write", "{}", Map.of());
        HitlController controller = new HitlController(store);

        ResponseEntity<PendingToolCall> response = controller.deny(pending.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(PendingToolCallStore.Status.DENIED);
    }

    @Test
    void shouldReturn404ForUnknownGet() {
        HitlController controller = new HitlController(new PendingToolCallStore());

        ResponseEntity<PendingToolCall> response = controller.get("nope");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturn409WhenApprovingAlreadyDenied() {
        PendingToolCallStore store = new PendingToolCallStore();
        PendingToolCall pending = store.register("session-A", "knowledge-write", "{}", Map.of());
        store.deny(pending.id());
        HitlController controller = new HitlController(store);

        ResponseEntity<PendingToolCall> response = controller.approve(pending.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shouldListPendingExcludingApproved() {
        PendingToolCallStore store = new PendingToolCallStore();
        PendingToolCall p1 = store.register("session-A", "knowledge-write", "{}", Map.of());
        store.register("session-B", "knowledge-write", "{}", Map.of());
        store.approve(p1.id());
        HitlController controller = new HitlController(store);

        assertThat(controller.listPending()).hasSize(1);
    }
}
