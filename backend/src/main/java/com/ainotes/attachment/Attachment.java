package com.ainotes.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 附件元数据。文件内容落在 {@code ai-notes.storage.root} 指向的目录下,
 * 数据库只存 {@code storage_key}。
 */
@Entity
@Table(name = "attachment")
public class Attachment {

    /** 由服务端预先生成,便于先算出 storage_key 再落库 */
    @Id
    private UUID id;

    @Column(name = "note_id")
    private UUID noteId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Attachment() {
    }

    public Attachment(UUID id, UUID noteId, String filename, String mimeType, Long sizeBytes, String storageKey) {
        this.id = id;
        this.noteId = noteId;
        this.filename = filename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNoteId() {
        return noteId;
    }

    public String getFilename() {
        return filename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
