package com.ainotes.ingest;

import com.ainotes.note.Note;
import com.ainotes.note.NoteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手工重建整个工作区的检索索引(设计文档 §6.5)。
 *
 * <p>为什么必须有这个接口:Elasticsearch 里的数据是<b>可重建的派生数据</b>,
 * 而它和 PostgreSQL 之间没有自动对账。以下场景都会让两者不一致:
 * <ul>
 *   <li>索引被删掉重建(mapping 变了、要启用 IK、要改维度);</li>
 *   <li>换了嵌入模型(旧向量还在索引里,与新查询不在同一向量空间);</li>
 *   <li>某次嵌入接口故障,笔记被标成 {@code index_status=failed}。</li>
 * </ul>
 * 这时没有接口就只能"把每篇笔记重新保存一遍",所以补上它。
 *
 * <p>逐篇走 {@link IndexingService#reindex},每篇一个独立事务:一篇失败不影响其它篇,
 * 也不会因为整个工作区共用一个长事务而炸掉连接池。笔记数量大时这是同步调用,
 * 后续可以换成后台任务队列(见 {@code index_job} 表)。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/reindex")
public class ReindexController {

    private static final Logger log = LoggerFactory.getLogger(ReindexController.class);
    private static final int MAX_REPORTED_FAILURES = 20;

    private final IndexingService indexing;
    private final NoteRepository notes;
    private final ChunkIndexStore store;
    private final ChunkEmbeddingService embeddings;

    public ReindexController(IndexingService indexing,
                             NoteRepository notes,
                             ChunkIndexStore store,
                             ChunkEmbeddingService embeddings) {
        this.indexing = indexing;
        this.notes = notes;
        this.store = store;
        this.embeddings = embeddings;
    }

    @PostMapping
    public ReindexResult reindex(@PathVariable UUID workspaceId) {
        List<Note> targets = notes.findByWorkspaceIdAndDeletedAtIsNull(workspaceId);
        int ready = 0;
        int keywordOnly = 0;
        List<String> failed = new ArrayList<>();

        for (Note note : targets) {
            IndexingService.IndexOutcome outcome = indexing.reindex(note.getId());
            switch (outcome) {
                case READY -> ready++;
                case KEYWORD_ONLY -> keywordOnly++;
                case FAILED -> failed.add(note.getId().toString());
            }
        }

        if (!failed.isEmpty()) {
            log.warn("工作区 {} 重建索引完成:{} 篇完全就绪,{} 篇只有关键词索引,{} 篇彻底失败",
                    workspaceId, ready, keywordOnly, failed.size());
        } else {
            log.info("工作区 {} 重建索引完成:{} 篇完全就绪,{} 篇只有关键词索引",
                    workspaceId, ready, keywordOnly);
        }

        return new ReindexResult(
                store.indexName(),
                embeddings.model(),
                embeddings.dimensions(),
                targets.size(),
                ready,
                keywordOnly,
                failed.size(),
                failed.size() > MAX_REPORTED_FAILURES
                        ? failed.subList(0, MAX_REPORTED_FAILURES)
                        : failed);
    }

    /**
     * @param ready        分块与向量都写好的笔记数
     * @param keywordOnly  只写进了关键词索引(向量生成失败)的笔记数 —— 这类笔记关键词能搜到、向量搜不到
     * @param failed       Elasticsearch 写入失败的笔记数
     */
    public record ReindexResult(
            String index,
            String embeddingModel,
            int dimensions,
            int total,
            int ready,
            int keywordOnly,
            int failed,
            List<String> failedNoteIds) {
    }
}
