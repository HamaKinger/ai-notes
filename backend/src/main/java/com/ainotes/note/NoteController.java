package com.ainotes.note;

import com.ainotes.common.PageResponse;
import com.ainotes.graph.NoteGraphService;
import com.ainotes.note.dto.NoteCreateRequest;
import com.ainotes.note.dto.NoteResponse;
import com.ainotes.note.dto.NoteUpdateRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class NoteController {

    private final NoteService service;
    private final NoteGraphService graphService;

    public NoteController(NoteService service, NoteGraphService graphService) {
        this.service = service;
        this.graphService = graphService;
    }

    @GetMapping("/workspaces/{workspaceId}/notes")
    public PageResponse<NoteResponse> list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Note> result = tag != null && !tag.isBlank()
                ? service.listByIds(workspaceId, graphService.noteIdsWithTag(workspaceId, tag.trim()), pageable)
                : service.list(workspaceId, q, pageable);
        return PageResponse.of(result, NoteResponse::from);
    }

    @PostMapping("/workspaces/{workspaceId}/notes")
    public NoteResponse create(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody NoteCreateRequest request) {
        return NoteResponse.from(service.create(workspaceId, request));
    }

    @GetMapping("/notes/{id}")
    public NoteResponse get(@PathVariable UUID id) {
        return NoteResponse.from(service.get(id));
    }

    @PatchMapping("/notes/{id}")
    public NoteResponse update(@PathVariable UUID id, @RequestBody NoteUpdateRequest request) {
        return NoteResponse.from(service.update(id, request));
    }

    @DeleteMapping("/notes/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/notes/{id}/versions")
    public List<VersionResponse> versions(@PathVariable UUID id) {
        return service.history(id).stream()
                .map(v -> new VersionResponse(v.getId(), v.getVersion(), v.getCreatedAt()))
                .toList();
    }

    @GetMapping("/notes/{id}/versions/{version}")
    public VersionDetailResponse version(@PathVariable UUID id, @PathVariable int version) {
        NoteVersion found = service.version(id, version);
        return new VersionDetailResponse(found.getId(), found.getVersion(), found.getContentMd(), found.getCreatedAt());
    }

    /** 回滚到指定版本(回滚动作本身也会追加一个新版本)。 */
    @PostMapping("/notes/{id}/versions/{version}/restore")
    public NoteResponse restore(@PathVariable UUID id, @PathVariable int version) {
        return NoteResponse.from(service.restore(id, version));
    }

    public record VersionResponse(UUID id, int version, Instant createdAt) {
    }

    public record VersionDetailResponse(UUID id, int version, String contentMd, Instant createdAt) {
    }
}
