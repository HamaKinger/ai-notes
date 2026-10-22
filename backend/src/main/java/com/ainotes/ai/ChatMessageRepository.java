package com.ainotes.ai;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** 整个会话的消息,按时间正序(同一毫秒时用 id 兜底,保证顺序稳定)。 */
    List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);

    /** 最近 N 条(倒序取,调用方再翻回来)—— 喂给模型的历史窗口用。 */
    List<ChatMessage> findByConversationIdOrderByCreatedAtDescIdAsc(UUID conversationId, Pageable pageable);

    void deleteByConversationId(UUID conversationId);
}
