package com.ainotes.graph;

import java.util.List;

/**
 * 一篇笔记解析出的关系数据。
 *
 * <p>{@code tags} 与 {@code links} 都已按去重后的顺序排列(稳定输出,便于对账)。
 */
public record ParsedNote(List<String> tags, List<WikiLink> links) {

    public static ParsedNote empty() {
        return new ParsedNote(List.of(), List.of());
    }
}
