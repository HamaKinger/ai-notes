package com.ainotes.search;

import java.util.List;
import java.util.UUID;

/**
 * 检索端口。
 *
 * <p>这是"可插拔检索层"的抽象边界:默认实现是
 * {@code VectorRetriever}(Spring AI VectorStore)+ {@code KeywordRetriever}(Elasticsearch BM25)
 * 再经应用层 RRF 融合。后续要换成 Elasticsearch 原生 {@code rrf} retriever 时,
 * 只需新增一个实现类,业务代码无需改动(见 docs/DESIGN.md §6.3)。
 *
 * <p>M3 落地。
 */
public interface RetrievalPort {

    List<RetrievedChunk> retrieve(SearchQuery query);

    record SearchQuery(
            UUID workspaceId,
            String text,
            List<String> tags,
            int topK) {
    }
}
