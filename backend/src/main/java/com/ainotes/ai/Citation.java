package com.ainotes.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 回答里的一处引用:某条助手消息用到了哪一段笔记片段(V1 建表 + V6 补快照字段)。
 *
 * <p>{@code ord} 是回答里 {@code [1]}、{@code [2]} 的编号,引用 chip 按它排序 ——
 * 主键是随机 UUID,排不出顺序来,不能拿它当序号。
 *
 * <p>{@code noteTitle} / {@code headingPath} / {@code snippet} 是**回答那一刻的快照**:
 * 渲染引用、点击跳转都不再回查 note 表与 ES,笔记后来改名/删除也不影响这条历史记录。
 */
@Entity
@Table(name = "citation")
public class Citation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "note_id")
    private UUID noteId;

    @Column(name = "chunk_id")
    private String chunkId;

    private Float score;

    private Integer ord;

    @Column(name = "note_title")
    private String noteTitle;

    @Column(name = "heading_path")
    private String headingPath;

    private String snippet;

    protected Citation() {
    }

    public Citation(UUID messageId, ChatSource source) {
        this.messageId = messageId;
        this.noteId = source.noteId();
        this.chunkId = source.chunkId();
        this.score = (float) source.score();
        this.ord = source.index();
        this.noteTitle = source.noteTitle();
        this.headingPath = source.headingPath();
        this.snippet = source.snippet();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getNoteId() {
        return noteId;
    }

    public String getChunkId() {
        return chunkId;
    }

    public Float getScore() {
        return score;
    }

    public Integer getOrd() {
        return ord;
    }

    public String getNoteTitle() {
        return noteTitle;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public String getSnippet() {
        return snippet;
    }
}
