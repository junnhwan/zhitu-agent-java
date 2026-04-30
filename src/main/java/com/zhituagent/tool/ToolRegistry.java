package com.zhituagent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> toolsByName;

    public ToolRegistry(List<ToolDefinition> tools) {
        this.toolsByName = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) {
            this.toolsByName.put(tool.name(), tool);
        }
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    public List<String> names() {
        return List.copyOf(toolsByName.keySet());
    }

    public List<ToolSpecification> specifications() {
        return toolsByName.values().stream()
                .map(ToolDefinition::toolSpecification)
                .toList();
    }
}
