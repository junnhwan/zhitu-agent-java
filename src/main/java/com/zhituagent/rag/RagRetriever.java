package com.zhituagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    private final KnowledgeIngestService knowledgeIngestService;

    public RagRetriever(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    public List<KnowledgeSnippet> retrieve(String query, int limit) {
        long startNanos = System.nanoTime();
        List<KnowledgeSnippet> snippets = knowledgeIngestService.search(query, limit);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (snippets.isEmpty()) {
            log.info(
                    "RAG 检索完成 rag.search.completed resultCount=0 limit={} latencyMs={} queryPreview={}",
                    limit,
                    latencyMs,
                    preview(query)
            );
            return snippets;
        }

        KnowledgeSnippet top = snippets.getFirst();
        log.info(
                "RAG 检索完成 rag.search.completed resultCount={} limit={} latencyMs={} topSource={} topScore={} queryPreview={}",
                snippets.size(),
                limit,
                latencyMs,
                top.source(),
                String.format("%.4f", top.score()),
                preview(query)
        );
        return snippets;
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= 48 ? text : text.substring(0, 48) + "...";
    }
}
