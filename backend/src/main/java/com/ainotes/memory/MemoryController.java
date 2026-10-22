package com.ainotes.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆的查看与删除。记忆只由 Agent 的 {@code rememberFact} 写入(经确认),这里只管"看得见、删得掉"。
 */
@RestController
@RequestMapping("/api/v1")
public class MemoryController {

    private final MemoryService memory;

    public MemoryController(MemoryService memory) {
        this.memory = memory;
    }

    @GetMapping("/workspaces/{workspaceId}/memories")
    public List<MemoryView> list(@PathVariable UUID workspaceId) {
        return memory.list(workspaceId).stream()
                .map(row -> new MemoryView(row.getId(), row.getContent(), row.getCreatedAt()))
                .toList();
    }

    @DeleteMapping("/workspaces/{workspaceId}/memories/{id}")
    public void forget(@PathVariable UUID workspaceId, @PathVariable UUID id) {
        memory.forget(workspaceId, id);
    }

    public record MemoryView(UUID id, String content, Instant createdAt) {
    }
}
