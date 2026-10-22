package com.ainotes.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "workspace_member")
public class WorkspaceMember {

    @EmbeddedId
    private WorkspaceMemberId id;

    /** owner | editor | viewer */
    @Column(nullable = false)
    private String role = "editor";

    protected WorkspaceMember() {
    }

    public WorkspaceMember(UUID workspaceId, UUID userId, String role) {
        this.id = new WorkspaceMemberId(workspaceId, userId);
        this.role = role;
    }

    public WorkspaceMemberId getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
