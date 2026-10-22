package com.ainotes.search;

import java.util.UUID;

/**
 * 一条检索命中的片段(对应 Elasticsearch 中的一个 chunk 文档)。
 */
public record RetrievedChunk(
        String chunkId,
        UUID noteId,
        String noteTitle,
        String headingPath,
        String content,
        double score) {
}
