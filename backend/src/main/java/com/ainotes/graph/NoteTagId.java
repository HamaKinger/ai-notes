package com.ainotes.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class NoteTagId implements Serializable {

    @Column(name = "note_id")
    private UUID noteId;

    @Column(name = "tag_id")
    private UUID tagId;

    protected NoteTagId() {
    }

    public NoteTagId(UUID noteId, UUID tagId) {
        this.noteId = noteId;
        this.tagId = tagId;
    }

    public UUID getNoteId() {
        return noteId;
    }

    public UUID getTagId() {
        return tagId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NoteTagId that)) {
            return false;
        }
        return Objects.equals(noteId, that.noteId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(noteId, tagId);
    }
}
