package com.zhituagent.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class KnowledgeStoreIds {

    private static final int CONTENT_HASH_PREFIX_HEX = 16;

    private KnowledgeStoreIds() {
    }

    public static String toEmbeddingId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Deterministic chunk identifier so the same (source, content) pair always
     * resolves to the same row. Repeated ingest becomes UPSERT instead of
     * inserting duplicates — both pgvector EmbeddingStore.addAll and our
     * InMemoryKnowledgeStore key on this id.
     */
    public static String computeChunkId(String source, String content) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        byte[] hash = sha256(content);
        StringBuilder hex = new StringBuilder(CONTENT_HASH_PREFIX_HEX);
        for (int i = 0; i < CONTENT_HASH_PREFIX_HEX / 2; i++) {
            hex.append(String.format("%02x", hash[i]));
        }
        return source + "#" + hex;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", exception);
        }
    }
}
