package com.ainotes.ai.dto;

import com.ainotes.ai.Conversation;
import java.time.Instant;
import java.util.UUID;

/** 会话元信息(侧栏列表 / 新建 / 重命名的返回)。 */
public record ConversationView(
        UUID id,
        UUID workspaceId,
        String title,
        Instant createdAt,
        Instant updatedAt) {

    public static ConversationView from(Conversation conversation) {
        return new ConversationView(
                conversation.getId(),
                conversation.getWorkspaceId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
