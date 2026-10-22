package com.ainotes.search;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public List<Hit> search(@PathVariable UUID workspaceId, @RequestBody SearchRequest request) {
        RetrievalPort.SearchQuery query = new RetrievalPort.SearchQuery(
                workspaceId,
                request.q(),
                request.tags(),
                request.topK() == null ? 0 : request.topK());
        return searchService.retrieve(query).stream().map(Hit::from).toList();
    }

    @GetMapping("/suggest")
    public List<SuggestItem> suggest(
            @PathVariable UUID workspaceId,
            @RequestParam("q") String q,
            @RequestParam(defaultValue = "8") int limit) {
        return searchService.suggest(workspaceId, q, limit).stream()
                .map(note -> new SuggestItem(note.getId(), note.getTitle()))
                .toList();
    }

    public record SearchRequest(String q, List<String> tags, Integer topK) {
    }

    public record Hit(
            String chunkId,
            UUID noteId,
            String noteTitle,
            String headingPath,
            String snippet,
            double score) {

        static Hit from(RetrievedChunk chunk) {
            return new Hit(
                    chunk.chunkId(),
                    chunk.noteId(),
                    chunk.noteTitle(),
                    chunk.headingPath(),
                    chunk.content(),
                    chunk.score());
        }
    }

    public record SuggestItem(UUID id, String title) {
    }
}
