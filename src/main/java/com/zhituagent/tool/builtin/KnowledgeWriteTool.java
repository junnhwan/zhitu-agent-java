package com.zhituagent.tool.builtin;

import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class KnowledgeWriteTool implements ToolDefinition {

    private final KnowledgeIngestService knowledgeIngestService;

    public KnowledgeWriteTool(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    @Override
    public String name() {
        return "knowledge-write";
    }

    @Override
    public String description() {
        return "Persist a question/answer pair into the project knowledge base so future RAG queries can retrieve it. "
                + "Use when the user explicitly asks to remember new factual material, project notes, or FAQ-style content. "
                + "All three string parameters are required.";
    }

    @Override
    public JsonObjectSchema parameterSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("question", "The question or topic this knowledge entry answers. Use the user's natural phrasing.")
                .addStringProperty("answer", "The full answer / fact body to store. Should be self-contained.")
                .addStringProperty("sourceName", "Logical source identifier, e.g. 'project-notes' or 'meeting-2026-04-30'. Used for citation and dedup.")
                .required("question", "answer", "sourceName")
                .additionalProperties(false)
                .build();
    }

    @Override
    public boolean requiresApproval() {
        // Writing to the knowledge store mutates retrieval results for every future
        // chat turn — gate it behind explicit user approval.
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String question = asString(arguments.get("question"));
        String answer = asString(arguments.get("answer"));
        String sourceName = asString(arguments.get("sourceName"));

        knowledgeIngestService.ingest(question, answer, sourceName);
        return new ToolResult(
                name(),
                true,
                "knowledge stored for " + sourceName,
                Map.of(
                        "question", question,
                        "sourceName", sourceName
                )
        );
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
