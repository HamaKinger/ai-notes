package com.ainotes.ai.dto;

import com.ainotes.ai.Citation;
import java.util.UUID;

/**
 * 回答里的一处引用。
 *
 * <p>{@code index} 与回答正文里的 {@code [n]} 一一对应;{@code headingPath} + {@code snippet}
 * 是点击跳转要用的锚点(与全文检索同一套:先按章节路径对齐标题,再在章节内找那段文字)。
 */
public record CitationView(
        int index,
        UUID noteId,
        String noteTitle,
        String headingPath,
        String snippet,
        double score) {

    public static CitationView from(Citation citation) {
        return new CitationView(
                citation.getOrd() == null ? 0 : citation.getOrd(),
                citation.getNoteId(),
                citation.getNoteTitle(),
                citation.getHeadingPath(),
                citation.getSnippet(),
                citation.getScore() == null ? 0 : citation.getScore());
    }
}
