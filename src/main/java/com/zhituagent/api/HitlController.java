package com.zhituagent.api;

import com.zhituagent.orchestrator.PendingToolCall;
import com.zhituagent.orchestrator.PendingToolCallStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Human-in-the-loop endpoints for tools that opt into
 * {@code requiresApproval()}. The frontend HitlConfirmPanel calls these to
 * approve or deny a pending tool call after the {@code tool_call_pending}
 * SSE event surfaces it; the next chat request (with
 * {@code metadata.approvedToolCallId} set) then runs through
 * {@link com.zhituagent.orchestrator.ToolCallExecutor} which consumes the
 * approval.
 */
@RestController
@RequestMapping("/api/tool-calls")
public class HitlController {

    private final PendingToolCallStore pendingToolCallStore;

    public HitlController(PendingToolCallStore pendingToolCallStore) {
        this.pendingToolCallStore = pendingToolCallStore;
    }

    @GetMapping("/pending")
    public List<PendingToolCall> listPending() {
        return pendingToolCallStore.listPending();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PendingToolCall> get(@PathVariable String id) {
        return pendingToolCallStore.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<PendingToolCall> approve(@PathVariable String id) {
        return pendingToolCallStore.approve(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @PostMapping("/{id}/deny")
    public ResponseEntity<PendingToolCall> deny(@PathVariable String id) {
        return pendingToolCallStore.deny(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }
}
