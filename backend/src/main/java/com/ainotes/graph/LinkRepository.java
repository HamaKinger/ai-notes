package com.ainotes.graph;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkRepository extends JpaRepository<Link, UUID> {

    List<Link> findBySourceNoteId(UUID sourceNoteId);

    List<Link> findByTargetNoteId(UUID targetNoteId);

    List<Link> findByWorkspaceId(UUID workspaceId);

    List<Link> findByWorkspaceIdAndTargetTitleIgnoreCase(UUID workspaceId, String targetTitle);

    void deleteBySourceNoteId(UUID sourceNoteId);
}
