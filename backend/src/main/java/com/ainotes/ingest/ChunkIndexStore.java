package com.ainotes.ingest;

import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.ainotes.search.RetrievalPort.SearchQuery;
import com.ainotes.search.RetrievedChunk;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Elasticsearch 检索索引的读写(对应设计文档 §5.3 / §6.3)。
 *
 * <p>刻意使用低层 {@link Rest5Client#performRequest} + 原生 JSON:
 * 查询 DSL 与设计文档 1:1 对应,将来若换用 9.x + platinum 许可的原生 {@code rrf} retriever,
 * 两路查询语句可以原样搬过去。
 */
@Component
public class ChunkIndexStore {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexStore.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Rest5Client client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String index;

    public ChunkIndexStore(Rest5Client client,
                           @Value("${ai-notes.search.index:ai-notes-chunk}") String index) {
        this.client = client;
        this.index = index;
    }

    public String indexName() {
        return index;
    }

    public boolean indexExists() {
        try {
            // 注意:低层 Rest5Client 对 404 也**不抛异常**,状态码就在 Response 里,直接判即可。
            // 这里 catch 的只是连不上、超时之类的 IO 问题。
            return client.performRequest(new Request("HEAD", "/" + index)).getStatusCode() == 200;
        } catch (IOException unavailable) {
            return false;
        }
    }

    public void createIndex(String mappingJson) {
        perform("PUT", "/" + index, mappingJson);
    }

    /**
     * 用最新的分块整体替换一篇笔记的索引文档(删除旧块 → 批量写入 → refresh)。
     *
     * @param embeddings 与 {@code chunks} 一一对应的向量;传 {@code null} 表示这次只写关键词索引
     *                   (嵌入接口不可用时的降级路径:这些文档不会有 {@code embedding} 字段,
     *                   向量检索召不回它们,关键词检索完全不受影响)
     */
    public void replaceNoteChunks(UUID workspaceId,
                                  UUID noteId,
                                  String title,
                                  String summary,
                                  List<String> tags,
                                  List<ChunkPayload> chunks,
                                  List<float[]> embeddings) {
        deleteByNoteId(noteId);
        if (chunks.isEmpty()) {
            return;
        }
        if (embeddings != null && embeddings.size() != chunks.size()) {
            throw new IllegalStateException("分块数与向量数不一致: " + chunks.size() + " vs " + embeddings.size());
        }

        StringBuilder ndjson = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkPayload chunk = chunks.get(i);
            ObjectNode action = NODES.objectNode();
            ObjectNode meta = action.putObject("index");
            meta.put("_index", index);
            meta.put("_id", noteId + ":" + chunk.ord());
            ndjson.append(mapper.writeValueAsString(action)).append('\n');

            ObjectNode doc = NODES.objectNode();
            doc.put("workspace_id", workspaceId.toString());
            doc.put("note_id", noteId.toString());
            doc.put("title", title == null ? "" : title);
            if (summary != null && !summary.isBlank()) {
                doc.put("summary", summary);
            }
            if (chunk.headingPath() != null) {
                doc.put("heading_path", chunk.headingPath());
            }
            doc.put("content", chunk.content());
            doc.put("ord", chunk.ord());
            if (tags != null && !tags.isEmpty()) {
                ArrayNode tagArray = doc.putArray("tags");
                tags.forEach(tagArray::add);
            }
            if (embeddings != null) {
                ArrayNode vector = doc.putArray("embedding");
                for (float value : embeddings.get(i)) {
                    vector.add(value);
                }
            }
            ndjson.append(mapper.writeValueAsString(doc)).append('\n');
        }

        // _bulk 即使个别文档失败也会返回 200,错误藏在 body 的 errors/items 里,必须自己看。
        JsonNode bulk = performJson("POST", "/_bulk", ndjson.toString());
        if (bulk.path("errors").asBoolean(false)) {
            throw new IllegalStateException("Elasticsearch 批量写入存在失败项: " + firstBulkFailure(bulk));
        }
        refresh();
    }

    public void deleteByNoteId(UUID noteId) {
        if (!indexExists()) {
            return;
        }
        ObjectNode body = NODES.objectNode();
        body.putObject("query").putObject("term").put("note_id", noteId.toString());
        perform("POST", "/" + index + "/_delete_by_query?refresh=true", mapper.writeValueAsString(body));
    }

    /** 仅关键词(BM25)检索;向量分支见 {@link #knnSearch},两路的融合在应用层({@code RrfFuser})完成。 */
    public List<RetrievedChunk> keywordSearch(SearchQuery query) {
        if (!indexExists()) {
            return List.of();
        }

        ObjectNode root = NODES.objectNode();
        root.put("size", Math.max(1, query.topK()));

        ObjectNode bool = root.putObject("query").putObject("bool");

        ArrayNode filter = bool.putArray("filter");
        filter.addObject().putObject("term").put("workspace_id", query.workspaceId().toString());
        if (query.tags() != null && !query.tags().isEmpty()) {
            ArrayNode values = filter.addObject().putObject("terms").putArray("tags");
            query.tags().forEach(values::add);
        }

        ArrayNode must = bool.putArray("must");
        ObjectNode multiMatch = must.addObject().putObject("multi_match");
        multiMatch.put("query", query.text() == null ? "" : query.text());
        ArrayNode fields = multiMatch.putArray("fields");
        fields.add("title^3").add("heading_path^2").add("content");

        root.putObject("highlight").putObject("fields").putObject("content");

        JsonNode response = performJson("POST", "/" + index + "/_search", mapper.writeValueAsString(root));
        List<RetrievedChunk> results = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            results.add(toChunk(hit));
        }
        return results;
    }

    /**
     * 向量(kNN)检索,走 ES 原生 {@code knn},索引侧用 mapping 里配置的 HNSW。
     *
     * <p>这里刻意**不使用** ES 原生的 {@code rrf} retriever(它可以把两路一起查完):
     * 该功能属于 platinum 许可,目标集群是 basic 许可会被直接拒绝(§14),
     * 所以两路分别查、在应用层融合。
     *
     * <p>没有 {@code embedding} 字段的文档不会被召回,也不会报错。若要限制"只看某个标签",
     * 用 pre-filter({@code knn.filter})而不是后置过滤,否则会先在全局取 k 条再被过滤掉。
     */
    public List<RetrievedChunk> knnSearch(SearchQuery query, float[] queryVector) {
        if (!indexExists()) {
            return List.of();
        }

        ObjectNode root = NODES.objectNode();
        root.put("size", Math.max(1, query.topK()));

        ObjectNode knn = root.putObject("knn");
        knn.put("field", "embedding");
        ArrayNode vector = knn.putArray("query_vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        knn.put("k", Math.max(1, query.topK()));
        knn.put("num_candidates", numCandidates(query.topK()));

        ArrayNode filter = knn.putArray("filter");
        filter.addObject().putObject("term").put("workspace_id", query.workspaceId().toString());
        if (query.tags() != null && !query.tags().isEmpty()) {
            ArrayNode values = filter.addObject().putObject("terms").putArray("tags");
            query.tags().forEach(values::add);
        }

        ArrayNode source = root.putArray("_source");
        source.add("note_id").add("title").add("heading_path").add("content");

        JsonNode response = performJson("POST", "/" + index + "/_search", mapper.writeValueAsString(root));
        List<RetrievedChunk> results = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            results.add(toChunk(hit));
        }
        return results;
    }

    /** num_candidates 越大召回越稳、越慢;k 很小时给一个下限,避免候选池太小漏掉近邻。 */
    private static int numCandidates(int topK) {
        return Math.min(10_000, Math.max(50, topK * 10));
    }

    /** 把一个 ES 命中转成检索结果;有关键词高亮时优先生效(向量分支没有高亮,自然退回全文)。 */
    private static RetrievedChunk toChunk(JsonNode hit) {
        JsonNode source = hit.path("_source");
        String snippet = textOrNull(source.path("content"));
        JsonNode highlighted = hit.path("highlight").path("content");
        if (highlighted.isArray() && highlighted.size() > 0) {
            snippet = highlighted.get(0).asText();
        }
        return new RetrievedChunk(
                hit.path("_id").asText(),
                UUID.fromString(source.path("note_id").asText()),
                source.path("title").asText(),
                textOrNull(source.path("heading_path")),
                snippet,
                hit.path("_score").asDouble());
    }

    private void refresh() {
        perform("POST", "/" + index + "/_refresh", null);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /**
     * 发一个请求并**校验状态码**。
     *
     * <p>坑:低层 {@link Rest5Client#performRequest} 对 4xx/5xx 既不抛异常也不返回 null,
     * 只是把状态码放在 {@link Response} 里。不判状态码的话,建索引失败、查询语句写错、
     * 认证失败等全部会被当成成功吞掉(表现为:日志说"已创建"、检索却永远是空)。
     */
    private Response perform(String method, String endpoint, String jsonBody) {
        Request request = new Request(method, endpoint);
        if (jsonBody != null) {
            request.setJsonEntity(jsonBody);
        }
        Response response;
        try {
            response = client.performRequest(request);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Elasticsearch 请求失败 " + method + " " + endpoint + ": " + e.getMessage(), e);
        }
        int status = response.getStatusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Elasticsearch 返回 " + status
                    + " (" + method + " " + endpoint + "): " + readBody(response));
        }
        return response;
    }

    /** 读响应体用于报错。读不到也不能因此再抛异常,否则会掩盖真正的错误。 */
    private String readBody(Response response) {
        try {
            var entity = response.getEntity();
            if (entity == null) {
                return "";
            }
            try (InputStream in = entity.getContent()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            return "<响应体读取失败: " + e.getMessage() + ">";
        }
    }

    /** 从 _bulk 响应里挑出第一个失败项,便于定位。 */
    private static String firstBulkFailure(JsonNode bulk) {
        for (JsonNode item : bulk.path("items")) {
            for (JsonNode op : item) {
                JsonNode error = op.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    return op.path("_id").asText() + " -> " + error;
                }
            }
        }
        return "<未定位到具体失败项>";
    }

    private JsonNode performJson(String method, String endpoint, String jsonBody) {
        Response response = perform(method, endpoint, jsonBody);
        try (InputStream in = response.getEntity().getContent()) {
            return mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("解析 Elasticsearch 响应失败: {}", e.getMessage());
            throw new IllegalStateException("解析 Elasticsearch 响应失败", e);
        }
    }
}
