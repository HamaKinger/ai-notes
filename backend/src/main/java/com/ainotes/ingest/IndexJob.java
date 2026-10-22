package com.ainotes.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 索引任务:记录"某篇笔记需要写入 Elasticsearch"的排队状态。
 *
 * <p>M0 仅落表;M1/M3 由 {@code IndexJobWorker} 消费。
 */
@Entity
@Table(name = "index_job")
public class IndexJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "note_id")
    private UUID noteId;

    /** full | incremental | reindex_embeddings */
    @Column(nullable = false)
    private String type;

    /** queued | running | done | failed */
    @Column(nullable = false)
    private String status = "queued";

    @Column(nullable = false)
    private int attempts;

    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected IndexJob() {
    }

    public IndexJob(UUID noteId, String type) {
        this.noteId = noteId;
        this.type = type;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNoteId() {
        return noteId;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        this.attempts++;
        this.updatedAt = Instant.now();
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
