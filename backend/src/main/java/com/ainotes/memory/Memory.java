package com.ainotes.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一条长期记忆:用户明确让 Agent 记住的一件事(V1 建表)。
 *
 * <p>内容只存在这里;向量在 ES 的 {@code ai-notes-memory} 索引里,两边用同一个 id 对应。
 */
@Entity
@Table(name = "memory")
public class Memory {

    public static final String KIND_FACT = "fact";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    private String kind;

    @Column(nullable = false)
    private String content;

    @Column(name = "es_doc_id")
    private String esDocId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Memory() {
    }

    public Memory(UUID workspaceId, String content) {
        this.workspaceId = workspaceId;
        this.kind = KIND_FACT;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
