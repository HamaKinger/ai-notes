package com.ainotes.ingest;

/**
 * 一个待写入 Elasticsearch 的检索片段。
 *
 * @param ord         块序号(同一笔记内递增),与 noteId 组成 ES 文档 _id
 * @param headingPath 标题路径,如 "架构设计 / 检索层 / 混合检索",作为上下文前缀
 * @param content     正文内容
 */
public record ChunkPayload(int ord, String headingPath, String content) {

    public int length() {
        return content.length();
    }
}
