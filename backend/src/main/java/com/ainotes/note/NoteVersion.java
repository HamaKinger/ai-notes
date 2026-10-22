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
@Table(name = "note_version")
public class NoteVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "note_id", nullable = false)
    private UUID noteId;

    @Column(nullable = false)
    private int version;

    @Column(name = "content_md", nullable = false)
    private String contentMd;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected NoteVersion() {
    }

    public NoteVersion(UUID noteId, int version, String contentMd) {
        this.noteId = noteId;
        this.version = version;
        this.contentMd = contentMd;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNoteId() {
        return noteId;
    }

    public int getVersion() {
        return version;
    }

    public String getContentMd() {
        return contentMd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
