package com.zhituagent.tool.builtin;

import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class TimeTool implements ToolDefinition {

    private final Clock clock;

    public TimeTool() {
        this(Clock.systemDefaultZone());
    }

    public TimeTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "Returns the current wall-clock time in ISO 8601 format (with timezone offset). "
                + "Use this whenever the user asks about the current time, today's date, or anything time-sensitive. "
                + "Takes no arguments.";
    }

    @Override
    public JsonObjectSchema parameterSchema() {
        return JsonObjectSchema.builder().build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String now = ZonedDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new ToolResult(
                name(),
                true,
                "current time is " + now,
                Map.of("time", now)
        );
    }
}
