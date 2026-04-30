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

    default ToolSpecification toolSpecification() {
        return ToolSpecification.builder()
                .name(name())
                .description(description())
                .parameters(parameterSchema())
                .build();
    }
}
