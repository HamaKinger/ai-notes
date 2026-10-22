package com.ainotes.ai;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    /** 会话历史要一次性给多条回答挂上各自的运行轨迹(避免前端逐条再问一遍)。 */
    List<AgentRun> findByAssistantMessageIdIn(Collection<UUID> assistantMessageIds);

    Optional<AgentRun> findByAssistantMessageId(UUID assistantMessageId);
}
