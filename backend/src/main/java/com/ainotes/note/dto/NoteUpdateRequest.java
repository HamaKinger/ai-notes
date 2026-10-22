package com.ainotes.note.dto;

import java.util.UUID;

/**
 * 局部更新:传 null 表示该字段不变。
 */
public record NoteUpdateRequest(
        String title,
        String contentMd,
        UUID folderId,
        String summary) {
}
