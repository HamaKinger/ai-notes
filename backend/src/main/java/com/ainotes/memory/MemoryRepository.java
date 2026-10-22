package com.ainotes.memory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryRepository extends JpaRepository<Memory, UUID> {

    List<Memory> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Optional<Memory> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
