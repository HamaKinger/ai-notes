package com.ainotes.ingest;

import com.ainotes.note.Note;
import com.ainotes.note.NoteRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 PostgreSQL 里的笔记同步到 Elasticsearch 检索索引(设计文档 §6.5)。
 *
 * <p>要点:
 * <ul>
 *   <li>Elasticsearch 是**可重建**的派生数据,任何写入失败都只标记 {@code index_status=failed},
 *       不阻断笔记的读写;</li>
 *   <li>每次同步落一条 {@link IndexJob} 记录,便于观测与后续重试;</li>
 *   <li>M3 起每个 chunk 都会调 {@link ChunkEmbeddingService} 生成向量后一并写入;</li>
 *   <li><b>嵌入失败与 ES 写入失败区别对待</b>:嵌入只是一个可以缺的增强项(密钥不对、限流、
 *       维度不匹配),挂了仍然写入关键词索引,只是没有向量,状态置为 {@code failed} 并记下原因;
 *       搜索不该因为一个外部 AI 接口不可用就整体失效。ES 本身写失败才是真的什么都没落库。</li>
 * </ul>
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final NoteRepository notes;
    private final IndexJobRepository jobs;
    private final ChunkIndexStore store;
    private final ChunkEmbeddingService embeddings;
    private final int maxCharsPerChunk;

    public IndexingService(NoteRepository notes,
                           IndexJobRepository jobs,
                           ChunkIndexStore store,
                           ChunkEmbeddingService embeddings,
                           @Value("${ai-notes.search.max-chars-per-chunk:800}") int maxCharsPerChunk) {
        this.notes = notes;
        this.jobs = jobs;
        this.store = store;
        this.embeddings = embeddings;
        this.maxCharsPerChunk = maxCharsPerChunk;
    }

    /**
     * 一次索引同步的结果。
     *
     * <p>把"完全成功"和"只写进了关键词索引"分开,是因为这两种情况的检索表现完全不同,
     * 而运维接口(重建索引)需要如实反映——否则会出现"5 篇全部失败"但其实全都能关键词搜到
     * 这种误导性输出。
     */
    public enum IndexOutcome {
        /** 分块与向量都写入成功。 */
        READY,
        /** 关键词索引已写入,但向量生成失败:只有关键词检索能搜到这篇笔记。 */
        KEYWORD_ONLY,
        /** Elasticsearch 写入失败:索引里没有这篇笔记的任何片段。 */
        FAILED
    }

    /** 笔记保存后的增量同步(由 {@code NoteIndexListener} 在事务提交后异步调用)。 */
    @Transactional
    public IndexOutcome upsert(UUID noteId) {
        return index(noteId, "incremental");
    }

    /**
     * 强制重建索引:与 {@link #upsert} 相同,只是任务类型记成 {@code reindex_embeddings},
     * 供"补向量/换嵌入模型"后的批量回填使用。
     */
    @Transactional
    public IndexOutcome reindex(UUID noteId) {
        return index(noteId, "reindex_embeddings");
    }

    private IndexOutcome index(UUID noteId, String jobType) {
        Note note = notes.findByIdAndDeletedAtIsNull(noteId).orElse(null);
        if (note == null) {
            remove(noteId);
            return IndexOutcome.FAILED;
        }

        IndexJob job = jobs.save(new IndexJob(noteId, jobType));
        job.setStatus("running");
        try {
            List<ChunkPayload> chunks = MarkdownChunker.chunk(note.getContentMd(), maxCharsPerChunk);
            warnIfNothingToIndex(note, chunks);

            // 嵌入失败与 ES 写入失败要分开处理。嵌入只是一个"可选增强":它挂了不该连带把
            // 关键词检索也弄没——那样嵌入了的外部 API 一旦不可用,整个搜索就全空了。
            // 所以分块照常写入,只是不带向量;同时把状态置成 failed 并写明原因,
            // 避免"看着正常、其实只能关键词搜"的静默降级。
            String embedError = null;
            List<float[]> vectors = null;
            try {
                vectors = embedChunks(note, chunks);
            } catch (RuntimeException e) {
                embedError = e.getMessage();
                log.warn("笔记 {} 的向量生成失败,本次只写入关键词索引(向量检索搜不到它): {}",
                        noteId, embedError);
            }

            store.replaceNoteChunks(
                    note.getWorkspaceId(),
                    note.getId(),
                    note.getTitle(),
                    note.getSummary(),
                    List.of(),
                    chunks,
                    vectors);

            if (embedError == null) {
                note.setIndexStatus("ready");
                job.setStatus("done");
                log.debug("已索引笔记 {}:{} 个片段(含 {} 维向量)",
                        noteId, chunks.size(), embeddings.dimensions());
                return IndexOutcome.READY;
            }
            note.setIndexStatus("failed");
            job.incrementAttempts();
            job.setError(truncate(embedError));
            job.setStatus("failed");
            return IndexOutcome.KEYWORD_ONLY;
        } catch (RuntimeException e) {
            // 到这里就是 Elasticsearch 本身写失败了,索引里没有这篇笔记的任何片段。
            log.warn("索引笔记 {} 失败(该笔记检索不可用,正文不受影响): {}", noteId, e.getMessage());
            note.setIndexStatus("failed");
            job.incrementAttempts();
            job.setError(truncate(e.getMessage()));
            job.setStatus("failed");
            return IndexOutcome.FAILED;
        }
    }

    /**
     * 正文非空却切不出任何片段时告警。
     *
     * <p>真实踩到过:一篇笔记的正文整篇只有一行(`V2__seed.sql` 把换行写成了字面量 "\n"),
     * 分块器逐行找 ATX 标题,于是整篇被当成一个标题、正文缓冲区为空 → 0 个片段。
     * 索引照旧报"成功",但这篇笔记永远搜不到,属于最不该出现的静默失败。
     * 正文本来就是空笔记的不告警。
     */
    private static void warnIfNothingToIndex(Note note, List<ChunkPayload> chunks) {
        if (!chunks.isEmpty()) {
            return;
        }
        String content = note.getContentMd();
        if (content != null && !content.isBlank()) {
            log.warn("笔记 {} 正文有 {} 个字符却切不出任何检索片段(通常是整篇没有换行,"
                            + "被整体当成了一个标题),该笔记将搜索不到",
                    note.getId(), content.length());
        }
    }

    private List<float[]> embedChunks(Note note, List<ChunkPayload> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        List<String> texts = chunks.stream()
                .map(chunk -> ChunkEmbeddingService.embeddingInput(
                        note.getTitle(), chunk.headingPath(), chunk.content()))
                .toList();
        return embeddings.embedAll(texts);
    }

    @Transactional
    public void remove(UUID noteId) {
        try {
            store.deleteByNoteId(noteId);
        } catch (RuntimeException e) {
            log.warn("删除笔记 {} 的索引失败: {}", noteId, e.getMessage());
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
