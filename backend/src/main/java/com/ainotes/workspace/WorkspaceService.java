package com.ainotes.workspace;

import com.ainotes.common.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;

    public WorkspaceService(WorkspaceRepository workspaces, WorkspaceMemberRepository members) {
        this.workspaces = workspaces;
        this.members = members;
    }

    /** userId 为 null(未登录且未开启演示回落)时返回全部,便于本地排查。 */
    @Transactional(readOnly = true)
    public List<Workspace> list(UUID userId) {
        if (userId == null) {
            return workspaces.findAll();
        }
        List<UUID> ids = members.findByIdUserId(userId).stream()
                .map(member -> member.getId().getWorkspaceId())
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return workspaces.findAllByIdIn(ids);
    }

    @Transactional(readOnly = true)
    public Workspace get(UUID id) {
        return workspaces.findById(id)
                .orElseThrow(() -> new NotFoundException("workspace not found: " + id));
    }

    @Transactional
    public Workspace create(UUID ownerId, String name) {
        Workspace workspace = workspaces.save(new Workspace(name));
        if (ownerId != null) {
            members.save(new WorkspaceMember(workspace.getId(), ownerId, "owner"));
        }
        return workspace;
    }
}
