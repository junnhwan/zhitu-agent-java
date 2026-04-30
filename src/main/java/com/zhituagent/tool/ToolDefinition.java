package com.zhituagent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

public interface ToolDefinition {

    String name();

    ToolResult execute(Map<String, Object> arguments);

    default String description() {
        return name();
    }

    default JsonObjectSchema parameterSchema() {
        return JsonObjectSchema.builder().build();
    }

    /**
     * Whether this tool needs human approval before each call. Defaults to {@code false}.
     * Override to {@code true} for tools with side effects the user may want to vet
     * (writes to the knowledge base, outbound mutations, anything that costs money).
     *
     * <p>The {@code ToolCallExecutor} consults this flag before invoking {@link #execute};
     * pending calls are parked in {@code PendingToolCallStore} until the operator approves
     * via the {@code /api/tool-calls/{id}/approve} endpoint.
     */
    default boolean requiresApproval() {
        return false;
    }

    default ToolSpecification toolSpecification() {
        return ToolSpecification.builder()
                .name(name())
                .description(description())
                .parameters(parameterSchema())
                .build();
    }
}
