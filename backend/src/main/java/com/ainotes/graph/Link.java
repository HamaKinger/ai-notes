package com.ainotes.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一条指向其它笔记的引用(出链)。
 *
 * <p>{@code targetNoteId} 是按标题解析出来的目标,允许为 null(目标笔记还不存在);
 * 目标笔记改名/删除/新建时会通过 {@link #attachTarget}/{@link #detachTarget} 重新挂接。
 */
@Entity
@Table(name = "link")
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "source_note_id", nullable = false)
    private UUID sourceNoteId;

    @Column(name = "target_note_id")
    private UUID targetNoteId;

    /** 原始引用文本(标题),也是解析目标的依据 */
    @Column(name = "target_title", nullable = false)
    private String targetTitle;

    private String context;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Link() {
    }

    public Link(UUID workspaceId, UUID sourceNoteId, UUID targetNoteId, String targetTitle, String context) {
        this.workspaceId = workspaceId;
        this.sourceNoteId = sourceNoteId;
        this.targetNoteId = targetNoteId;
        this.targetTitle = targetTitle;
        this.context = context;
    }

    public void refresh(UUID resolvedTargetId, String context) {
        this.targetNoteId = resolvedTargetId;
        this.context = context;
    }

    public void attachTarget(UUID targetId) {
        this.targetNoteId = targetId;
    }

    public void detachTarget() {
        this.targetNoteId = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getSourceNoteId() {
        return sourceNoteId;
    }

    public UUID getTargetNoteId() {
        return targetNoteId;
    }

    public String getTargetTitle() {
        return targetTitle;
    }

    public String getContext() {
        return context;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
