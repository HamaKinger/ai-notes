package com.ainotes.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RRF 融合是纯函数,不依赖 ES 与嵌入模型,所以直接测。
 *
 * <p>重点守住两条容易写错的性质:名字只算名次、不算原始分数(BM25 与余弦相似度量纲完全不同);
 * 两路都命中的文档必须排在只命中一路的文档前面。
 */
class RrfFuserTest {

    private static final UUID NOTE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static RetrievedChunk chunk(String id, double score) {
        return new RetrievedChunk(id, NOTE, "架构设计", "检索层", "正文-" + id, score);
    }

    @Test
    void documentHitByBothBranchesOutranksDocumentHitByOne() {
        // a 是关键词分支第一、向量分支第二;b 在关键词分支第二、向量分支没出现
        List<RetrievedChunk> keyword = List.of(chunk("a", 9.5), chunk("b", 9.4));
        List<RetrievedChunk> vector = List.of(chunk("c", 0.99), chunk("a", 0.71));

        List<RetrievedChunk> fused = RrfFuser.fuse(List.of(keyword, vector), 60, 10);

        assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly("a", "c", "b");
    }

    @Test
    void rankNotRawScoreDecidesTheOrder() {
        // 关键词分支的名次 c,b,a;向量分支也给出同样的名次 —— 融合后顺序应完全一致,
        // 与两路的分数大小无关(b 的 BM25 分数远高于 c,但名次在 c 之后)
        List<RetrievedChunk> keyword = List.of(chunk("c", 0.1), chunk("b", 8.0), chunk("a", 7.0));
        List<RetrievedChunk> vector = List.of(chunk("c", 0.7), chunk("b", 0.6), chunk("a", 0.5));

        List<RetrievedChunk> fused = RrfFuser.fuse(List.of(keyword, vector), 60, 10);

        assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly("c", "b", "a");
    }

    @Test
    void keepsTheFirstBranchesSnippetSoKeywordHighlightsSurvive() {
        List<RetrievedChunk> keyword = List.of(chunk("a", 5.0));
        List<RetrievedChunk> vector = List.of(new RetrievedChunk(
                "a", NOTE, "架构设计", "检索层", "被截断的正文", 0.9));

        List<RetrievedChunk> fused = RrfFuser.fuse(List.of(keyword, vector), 60, 10);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).content()).isEqualTo("正文-a");
    }

    @Test
    void truncatesToTopK() {
        List<RetrievedChunk> keyword = List.of(chunk("a", 3), chunk("b", 2), chunk("c", 1));

        assertThat(RrfFuser.fuse(List.of(keyword), 60, 2))
                .extracting(RetrievedChunk::chunkId)
                .containsExactly("a", "b");
    }

    @Test
    void isOrderStableWhenScoresTie() {
        List<RetrievedChunk> first = List.of(chunk("a", 1), chunk("b", 1));
        List<RetrievedChunk> second = List.of(chunk("b", 1), chunk("a", 1));

        assertThat(RrfFuser.fuse(List.of(first, second), 60, 10))
                .extracting(RetrievedChunk::chunkId)
                .containsExactly("a", "b");
    }

    @Test
    void toleratesMissingAndEmptyBranches() {
        assertThat(RrfFuser.fuse(List.of(List.of(), List.of()), 60, 10)).isEmpty();
        assertThat(RrfFuser.fuse(java.util.Arrays.asList(null, List.of(chunk("a", 1))), 60, 10))
                .extracting(RetrievedChunk::chunkId)
                .containsExactly("a");
    }
}
