package com.zhituagent.orchestrator;

import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallApprovalGateTest {

    @Test
    void shouldParkApprovalRequiringToolWhenNoApprovalProvided() {
        AtomicInteger executions = new AtomicInteger();
        ToolDefinition guarded = approvalRequiringTool("knowledge-write", executions);
        ToolRegistry registry = new ToolRegistry(List.of(guarded));
        PendingToolCallStore store = new PendingToolCallStore();
        ToolCallExecutor executor = new ToolCallExecutor(registry, store);

        List<ToolCallExecutor.ToolExecution> result = executor.executeAll(List.of(
                ToolExecutionRequest.builder().id("t1").name("knowledge-write").arguments("{}").build()
        ), Map.of(ToolCallExecutor.METADATA_SESSION_ID, "session-A"));

        assertThat(result).hasSize(1);
        ToolResult observation = result.get(0).result();
        assertThat(observation.success()).isFalse();
        assertThat(observation.summary()).contains("awaiting_approval");
        assertThat(observation.payload()).containsEntry("status", "AWAITING_APPROVAL");
        assertThat(observation.payload().get("pendingId")).isInstanceOf(String.class);
        assertThat(executions.get()).isZero();
        assertThat(store.listPending()).hasSize(1);

        executor.shutdown();
    }

    @Test
    void shouldExecuteWhenMetadataCarriesApprovedToolCallId() {
        AtomicInteger executions = new AtomicInteger();
        ToolDefinition guarded = approvalRequiringTool("knowledge-write", executions);
        ToolRegistry registry = new ToolRegistry(List.of(guarded));
        PendingToolCallStore store = new PendingToolCallStore();
        ToolCallExecutor executor = new ToolCallExecutor(registry, store);

        // First request parks a pending call
        ToolCallExecutor.ToolExecution first = executor.executeAll(List.of(
                ToolExecutionRequest.builder().id("t1").name("knowledge-write").arguments("{}").build()
        ), Map.of(ToolCallExecutor.METADATA_SESSION_ID, "session-A")).get(0);
        String pendingId = (String) first.result().payload().get("pendingId");

        // Operator approves out-of-band
        store.approve(pendingId);

        // Second request carries approval token; loop detector counts as second invocation,
        // but still under the threshold (which is 3) so it proceeds.
        List<ToolCallExecutor.ToolExecution> second = executor.executeAll(List.of(
                ToolExecutionRequest.builder().id("t2").name("knowledge-write").arguments("{}").build()
        ), Map.of(
                ToolCallExecutor.METADATA_SESSION_ID, "session-A",
                ToolCallExecutor.METADATA_APPROVED_ID, pendingId
        ));

        assertThat(second).hasSize(1);
        assertThat(second.get(0).result().success()).isTrue();
        assertThat(executions.get()).isEqualTo(1);
        assertThat(store.consumeIfApproved(pendingId))
                .as("approval is consumed exactly once after the gate runs the tool")
                .isFalse();

        executor.shutdown();
    }

    @Test
    void shouldNotAffectToolsThatDoNotRequireApproval() {
        AtomicInteger executions = new AtomicInteger();
        ToolDefinition vanilla = new ToolDefinition() {
            @Override public String name() { return "echo"; }
            @Override public JsonObjectSchema parameterSchema() { return JsonObjectSchema.builder().build(); }
            @Override public ToolResult execute(Map<String, Object> args) {
                executions.incrementAndGet();
                return new ToolResult("echo", true, "ok", Map.of());
            }
            // requiresApproval defaults to false
        };
        ToolRegistry registry = new ToolRegistry(List.of(vanilla));
        ToolCallExecutor executor = new ToolCallExecutor(registry, new PendingToolCallStore());

        List<ToolCallExecutor.ToolExecution> result = executor.executeAll(List.of(
                ToolExecutionRequest.builder().id("t1").name("echo").arguments("{}").build()
        ), Map.of());

        assertThat(result.get(0).result().success()).isTrue();
        assertThat(executions.get()).isEqualTo(1);

        executor.shutdown();
    }

    @Test
    void shouldRejectStaleApprovedTokenForUnrelatedRequest() {
        AtomicInteger executions = new AtomicInteger();
        ToolDefinition guarded = approvalRequiringTool("knowledge-write", executions);
        ToolRegistry registry = new ToolRegistry(List.of(guarded));
        PendingToolCallStore store = new PendingToolCallStore();
        ToolCallExecutor executor = new ToolCallExecutor(registry, store);

        // No prior approval registered, so a forged metadata token should fail to consume.
        List<ToolCallExecutor.ToolExecution> result = executor.executeAll(List.of(
                ToolExecutionRequest.builder().id("t1").name("knowledge-write").arguments("{}").build()
        ), Map.of(ToolCallExecutor.METADATA_APPROVED_ID, "forged-id"));

        assertThat(result.get(0).result().success()).isFalse();
        assertThat(result.get(0).result().summary()).contains("awaiting_approval");
        assertThat(executions.get()).isZero();

        executor.shutdown();
    }

    private static ToolDefinition approvalRequiringTool(String name, AtomicInteger counter) {
        return new ToolDefinition() {
            @Override public String name() { return name; }
            @Override public JsonObjectSchema parameterSchema() { return JsonObjectSchema.builder().build(); }
            @Override public boolean requiresApproval() { return true; }
            @Override public ToolResult execute(Map<String, Object> args) {
                counter.incrementAndGet();
                return new ToolResult(name, true, "executed", Map.of());
            }
        };
    }
}
