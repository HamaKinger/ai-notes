package com.ainotes.note.dto;

import jakarta.validation.constraints.NotBlank;

public record NoteCreateRequest(
        @NotBlank String title,
        String contentMd,
        java.util.UUID folderId) {
}
