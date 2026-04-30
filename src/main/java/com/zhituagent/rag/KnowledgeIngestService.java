package com.zhituagent.rag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeIngestService {

    private final DocumentSplitter documentSplitter;
    private final KnowledgeStore knowledgeStore;
    private final ContextualChunkAnnotator contextualAnnotator;

    @Autowired
    public KnowledgeIngestService(DocumentSplitter documentSplitter,
                                  KnowledgeStore knowledgeStore,
                                  ContextualChunkAnnotator contextualAnnotator) {
        this.documentSplitter = documentSplitter;
        this.knowledgeStore = knowledgeStore;
        this.contextualAnnotator = contextualAnnotator;
    }

    public KnowledgeIngestService(DocumentSplitter documentSplitter, KnowledgeStore knowledgeStore) {
        this(documentSplitter, knowledgeStore, null);
    }

    public KnowledgeIngestService(DocumentSplitter documentSplitter) {
        this(documentSplitter, new InMemoryKnowledgeStore(), null);
    }

    public void ingest(String question, String answer, String sourceName) {
        String document = "Q: " + question + "\nA: " + answer;
        List<String> chunks = documentSplitter.split(document);
        if (chunks.isEmpty()) {
            return;
        }

        knowledgeStore.addAll(chunks.stream()
                .map(chunk -> new KnowledgeChunk(
                        sourceName,
                        KnowledgeStoreIds.computeChunkId(sourceName, chunk),
                        chunk,
                        annotateForEmbedding(document, chunk)
                ))
                .toList());
    }

    public List<KnowledgeSnippet> search(String query, int limit) {
        return knowledgeStore.search(query, limit);
    }

    public List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
        return knowledgeStore.lexicalSearch(query, limit);
    }

    private String annotateForEmbedding(String document, String chunk) {
        if (contextualAnnotator == null || !contextualAnnotator.isEnabled()) {
            return null;
        }
        String embedText = contextualAnnotator.annotate(document, chunk);
        return embedText == null || embedText.equals(chunk) ? null : embedText;
    }
}
