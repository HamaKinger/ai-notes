package com.ainotes.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用层 Reciprocal Rank Fusion(对应设计文档 §6.3)。
 *
 * <p>为什么不直接用 Elasticsearch 原生的 {@code rrf} retriever:该功能属于 platinum 许可。
 * 本项目验证用的集群是 <b>basic 许可</b>,直接请求会得到
 * {@code current license is non-compliant for [Reciprocal Rank Fusion (RRF)]}(见 §14),
 * 所以融合放在应用层做。
 *
 * <p>公式:{@code score(d) = Σ 1 / (rankConstant + rank_i(d))},rank 从 1 开始。
 * 只用名次、不用原始分数,因此 BM25 的分数与余弦相似度可以直接融合,不需要任何归一化——
 * 这正是不用"加权求和"的原因。
 */
public final class RrfFuser {

    private RrfFuser() {
    }

    /**
     * 融合多路召回结果。
     *
     * @param branches     各路结果,必须已按相关度降序(branch 内部的顺序即名次)
     * @param rankConstant 平滑常数,经验值 60;越小则头部结果的优势越明显
     * @param topK         最终返回条数
     */
    public static List<RetrievedChunk> fuse(List<List<RetrievedChunk>> branches, int rankConstant, int topK) {
        int constant = Math.max(1, rankConstant);
        Map<String, Scored> merged = new LinkedHashMap<>();

        for (List<RetrievedChunk> branch : branches) {
            if (branch == null) {
                continue;
            }
            int rank = 0;
            for (RetrievedChunk chunk : branch) {
                rank++;
                double contribution = 1.0 / (constant + rank);
                Scored existing = merged.get(chunk.chunkId());
                if (existing == null) {
                    // 先到的一路胜出:同名次时保留它的正文/高亮片段(关键词分支的 <em> 高亮更利于展示)
                    merged.put(chunk.chunkId(), new Scored(chunk, contribution));
                } else {
                    existing.score += contribution;
                }
            }
        }

        List<RetrievedChunk> fused = new ArrayList<>(merged.size());
        for (Scored scored : merged.values()) {
            fused.add(scored.toChunk());
        }
        // 分数相同的按 chunkId 兜底,保证同一查询的结果顺序稳定、可复现
        fused.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(RetrievedChunk::chunkId));
        return fused.size() <= topK ? fused : new ArrayList<>(fused.subList(0, Math.max(1, topK)));
    }

    private static final class Scored {

        private final RetrievedChunk chunk;
        private double score;

        private Scored(RetrievedChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }

        private RetrievedChunk toChunk() {
            return new RetrievedChunk(
                    chunk.chunkId(),
                    chunk.noteId(),
                    chunk.noteTitle(),
                    chunk.headingPath(),
                    chunk.content(),
                    score);
        }
    }
}
