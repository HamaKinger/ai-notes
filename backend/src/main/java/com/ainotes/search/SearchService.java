package com.ainotes.search;

import com.ainotes.ingest.ChunkEmbeddingService;
import com.ainotes.ingest.ChunkIndexStore;
import com.ainotes.note.Note;
import com.ainotes.note.NoteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 检索服务 —— {@link RetrievalPort} 的默认实现:M1 的关键词(BM25 + IK)一路、
 * M3 的向量(kNN)一路,两路在应用层用 {@link RrfFuser} 融合。
 *
 * <p>降级策略(很关键,决定了故障时的表现):
 * <ul>
 *   <li><b>查询期</b>允许降级:嵌入接口调不通、ES 报错时只告警,能用的一路照常返回结果。
 *       用户最差也还是"关键词检索",不会是空页面;</li>
 *   <li>但绝<b>静默</b>降级:每次降级都写 WARN 日志,并且日志里带上原因。</li>
 * </ul>
 *
 * <p>索引期也是同样的取向(见 {@code IndexingService}):嵌入失败时仍然把关键词索引写进去,
 * 只是不带向量并把 {@code index_status} 置为 {@code failed}。
 */
@Service
public class SearchService implements RetrievalPort {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;
    /** 单路召回条数上限。融合前多取一些,好让"只在某一路靠后"的结果也有机会被提上来。 */
    private static final int MAX_BRANCH_SIZE = 100;
    /** 进入融合前每路取 topK 的倍数。 */
    private static final int BRANCH_MULTIPLIER = 4;

    private final ChunkIndexStore store;
    private final NoteRepository notes;
    private final ChunkEmbeddingService embeddings;
    private final int rankConstant;

    public SearchService(ChunkIndexStore store,
                         NoteRepository notes,
                         ChunkEmbeddingService embeddings,
                         @Value("${ai-notes.search.rrf-rank-constant:60}") int rankConstant) {
        this.store = store;
        this.notes = notes;
        this.embeddings = embeddings;
        this.rankConstant = rankConstant;
    }

    @Override
    public List<RetrievedChunk> retrieve(SearchQuery query) {
        SearchQuery bounded = withBoundedTopK(query);
        SearchQuery branchQuery = new SearchQuery(
                bounded.workspaceId(), bounded.text(), bounded.tags(), branchSize(bounded.topK()));

        List<List<RetrievedChunk>> branches = new ArrayList<>(2);
        try {
            branches.add(store.keywordSearch(branchQuery));
        } catch (RuntimeException e) {
            // 检索失败不让整个页面挂掉,但必须留痕——之前把 ES 的 4xx 当成功吞掉,
            // 结果"所有检索都返回空"这种故障在日志里完全查不到。
            log.warn("关键词分支失败,本次只用向量分支: {}", e.getMessage());
        }
        List<RetrievedChunk> vectorHits = vectorBranch(branchQuery);
        if (!vectorHits.isEmpty()) {
            branches.add(vectorHits);
        }

        return RrfFuser.fuse(branches, rankConstant, bounded.topK());
    }

    /** 向量分支:查询串也要走同一个嵌入模型,否则两边的向量不在同一空间,余弦相似度没有意义。 */
    private List<RetrievedChunk> vectorBranch(SearchQuery query) {
        String text = query.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return store.knnSearch(query, embeddings.embedOne(text));
        } catch (RuntimeException e) {
            log.warn("向量分支不可用(本次降级为纯关键词检索): {}", e.getMessage());
            return List.of();
        }
    }

    /** 标题联想(直接查 PostgreSQL,不依赖 ES 与嵌入服务是否可用)。 */
    @Transactional(readOnly = true)
    public List<Note> suggest(UUID workspaceId, String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        int size = limit <= 0 ? 8 : Math.min(limit, 20);
        return notes.findByWorkspaceIdAndDeletedAtIsNullAndTitleContainingIgnoreCase(
                workspaceId, prefix.trim(), PageRequest.of(0, size)).getContent();
    }

    private static int branchSize(int topK) {
        return Math.min(MAX_BRANCH_SIZE, Math.max(topK, topK * BRANCH_MULTIPLIER));
    }

    private static SearchQuery withBoundedTopK(SearchQuery query) {
        int topK = query.topK() <= 0 ? DEFAULT_TOP_K : Math.min(query.topK(), MAX_TOP_K);
        return new SearchQuery(query.workspaceId(), query.text(), query.tags(), topK);
    }
}
