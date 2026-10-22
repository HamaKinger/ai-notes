package com.ainotes.graph;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findByWorkspaceIdAndNameIgnoreCase(UUID workspaceId, String name);

    List<Tag> findByWorkspaceId(UUID workspaceId);
}
