package com.ainotes.workspace;

import com.ainotes.auth.AppUser;
import com.ainotes.auth.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService service;
    private final CurrentUser currentUser;

    public WorkspaceController(WorkspaceService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<WorkspaceResponse> list() {
        return service.list(currentUserId()).stream().map(WorkspaceResponse::from).toList();
    }

    @GetMapping("/{id}")
    public WorkspaceResponse get(@PathVariable UUID id) {
        return WorkspaceResponse.from(service.get(id));
    }

    @PostMapping
    public WorkspaceResponse create(@RequestBody CreateWorkspaceRequest request) {
        return WorkspaceResponse.from(service.create(currentUserId(), request.name()));
    }

    private UUID currentUserId() {
        return currentUser.findWithDevFallback().map(AppUser::getId).orElse(null);
    }

    public record CreateWorkspaceRequest(@NotBlank String name) {
    }

    public record WorkspaceResponse(UUID id, String name, String storageMode, Instant createdAt) {
        static WorkspaceResponse from(Workspace workspace) {
            return new WorkspaceResponse(
                    workspace.getId(),
                    workspace.getName(),
                    workspace.getStorageMode(),
                    workspace.getCreatedAt());
        }
    }
}
