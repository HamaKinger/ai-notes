package com.ainotes.note.dto;

import com.ainotes.note.Note;
import java.time.Instant;
import java.util.UUID;

public record NoteResponse(
        UUID id,
        UUID workspaceId,
        UUID folderId,
        String title,
        String slug,
        String contentMd,
        String summary,
        String indexStatus,
        Instant createdAt,
        Instant updatedAt) {

    public static NoteResponse from(Note note) {
        return new NoteResponse(
                note.getId(),
                note.getWorkspaceId(),
                note.getFolderId(),
                note.getTitle(),
                note.getSlug(),
                note.getContentMd(),
                note.getSummary(),
                note.getIndexStatus(),
                note.getCreatedAt(),
                note.getUpdatedAt());
    }
}
