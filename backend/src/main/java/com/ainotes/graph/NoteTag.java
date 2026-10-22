package com.ainotes.graph;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "note_tag")
public class NoteTag {

    @EmbeddedId
    private NoteTagId id;

    protected NoteTag() {
    }

    public NoteTag(UUID noteId, UUID tagId) {
        this.id = new NoteTagId(noteId, tagId);
    }

    public NoteTagId getId() {
        return id;
    }
}
