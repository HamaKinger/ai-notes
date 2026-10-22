package com.ainotes.note;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "note")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(nullable = false)
    private String title;

    private String slug;

    @Column(name = "content_md", nullable = false)
    private String contentMd = "";

    private String summary;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    /** pending | indexing | ready | failed */
    @Column(name = "index_status", nullable = false)
    private String indexStatus = "pending";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Note() {
    }

    public Note(UUID workspaceId, String title, String slug, String contentMd, String contentHash) {
        this.workspaceId = workspaceId;
        this.title = title;
        this.slug = slug;
        this.contentMd = contentMd;
        this.contentHash = contentHash;
    }

    /** 内容变化时统一更新指纹与索引状态,并刷新更新时间。 */
    public void applyContent(String contentMd, String contentHash) {
        this.contentMd = contentMd;
        this.contentHash = contentHash;
        this.indexStatus = "pending";
        this.updatedAt = Instant.now();
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** 仅元数据变化(标题/目录/摘要)时刷新更新时间。 */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getContentMd() {
        return contentMd;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(String indexStatus) {
        this.indexStatus = indexStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
