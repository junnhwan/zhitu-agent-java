package com.zhituagent.tool.builtin;

import com.zhituagent.api.dto.SessionDetailResponse;
import com.zhituagent.session.SessionService;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SessionInspectTool implements ToolDefinition {

    private final SessionService sessionService;

    public SessionInspectTool(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String name() {
        return "session-inspect";
    }

    @Override
    public String description() {
        return "Inspect a chat session by id and return its current summary plus the count of recent messages. "
                + "Use when the user wants to know what was discussed in a specific session, or asks the agent to introspect prior conversation state. "
                + "Requires the sessionId parameter.";
    }

    @Override
    public JsonObjectSchema parameterSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("sessionId", "Identifier of the chat session to inspect. Use the active session id when the user refers to 'this conversation'.")
                .required("sessionId")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String sessionId = String.valueOf(arguments.getOrDefault("sessionId", ""));
        SessionDetailResponse detail = sessionService.getSession(sessionId);
        return new ToolResult(
                name(),
                true,
                "session loaded: " + sessionId,
                Map.of(
                        "summary", detail.summary(),
                        "recentMessages", detail.recentMessages().size()
                )
        );
    }
}
