package com.ainotes.workspace;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    List<Workspace> findAllByIdIn(Collection<UUID> ids);
}
