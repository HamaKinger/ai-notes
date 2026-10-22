package com.ainotes.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    List<WorkspaceMember> findByIdUserId(UUID userId);

    long countByIdWorkspaceId(UUID workspaceId);
}
