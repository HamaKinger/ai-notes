package com.ainotes.graph;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GraphController {

    private final NoteGraphService graphService;

    public GraphController(NoteGraphService graphService) {
        this.graphService = graphService;
    }

    /** 某篇笔记的标签、出链与反链。 */
    @GetMapping("/notes/{id}/relations")
    public NoteGraphService.NoteRelations relations(@PathVariable UUID id) {
        return graphService.relations(id);
    }

    /** 工作区标签及使用次数。 */
    @GetMapping("/workspaces/{workspaceId}/tags")
    public List<NoteGraphService.TagCount> tags(@PathVariable UUID workspaceId) {
        return graphService.tagsOfWorkspace(workspaceId);
    }

    /**
     * 批量把 `[[标题]]` 解析成笔记。预览里的双链据此决定渲染成"可点"还是"未创建",
     * 顺带拿到一段预览文本给悬停卡片 —— 所以这里是 POST:标题列表可能很长,不该塞进 URL。
     */
    @PostMapping("/workspaces/{workspaceId}/links/resolve")
    public List<NoteGraphService.ResolvedLink> resolve(
            @PathVariable UUID workspaceId, @RequestBody ResolveRequest request) {
        return graphService.resolveTitles(workspaceId, request.titles());
    }

    public record ResolveRequest(List<String> titles) {
    }

    /** 工作区知识图谱。 */
    @GetMapping("/workspaces/{workspaceId}/graph")
    public NoteGraphService.GraphView graph(@PathVariable UUID workspaceId) {
        return graphService.graph(workspaceId);
    }
}
