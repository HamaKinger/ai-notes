package com.ainotes.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** 侧栏列表:最近说过话的排前面。 */
    List<Conversation> findByWorkspaceIdOrderByUpdatedAtDesc(UUID workspaceId);

    Optional<Conversation> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
