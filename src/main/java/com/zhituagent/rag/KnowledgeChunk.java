package com.zhituagent.rag;

/**
 * @param source         logical knowledge source name
 * @param chunkId        deterministic id (see {@link KnowledgeStoreIds#computeChunkId})
 * @param content        the original chunk text — what we surface as evidence and what
 *                       lexical search matches against
 * @param embedText      optional alternative payload used purely for dense embedding,
 *                       e.g. {@code "<contextual prefix>\n\n<content>"} when Anthropic-style
 *                       Contextual Retrieval is enabled. {@code null} or blank means embed
 *                       the raw {@code content}.
 */
public record KnowledgeChunk(
        String source,
        String chunkId,
        String content,
        String embedText
) {

    public KnowledgeChunk(String source, String chunkId, String content) {
        this(source, chunkId, content, null);
    }

    public String effectiveEmbedText() {
        return embedText != null && !embedText.isBlank() ? embedText : content;
    }
}
