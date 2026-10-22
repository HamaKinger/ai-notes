package com.ainotes.memory;

import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 长期记忆的向量索引({@code ai-notes-memory})。只做三件事:写一条、删一条、按向量找近邻。
 *
 * <p>和 {@link com.ainotes.ingest.ChunkIndexStore} 一样用低层 {@link Rest5Client} 与原生 JSON,
 * 并且**校验状态码**(低层客户端对 4xx/5xx 不抛异常,不判就会把失败当成功吞掉)。
 */
@Component
public class MemoryIndexStore {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Rest5Client client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String index;

    public MemoryIndexStore(Rest5Client client,
                            @Value("${ai-notes.memory.index:ai-notes-memory}") String index) {
        this.client = client;
        this.index = index;
    }

    public String indexName() {
        return index;
    }

    public boolean indexExists() {
        try {
            return client.performRequest(new Request("HEAD", "/" + index)).getStatusCode() == 200;
        } catch (IOException unavailable) {
            return false;
        }
    }

    public void createIndex(String mappingJson) {
        perform("PUT", "/" + index, mappingJson);
    }

    /** 写入(覆盖同 id 的旧文档)并立即刷新,这样紧接着的去重 / 召回能看见它。 */
    public void index(UUID memoryId, UUID workspaceId, String content, float[] vector) {
        ObjectNode doc = NODES.objectNode();
        doc.put("workspace_id", workspaceId.toString());
        doc.put("memory_id", memoryId.toString());
        doc.put("content", content);
        ArrayNode embedding = doc.putArray("embedding");
        for (float value : vector) {
            embedding.add(value);
        }
        perform("PUT", "/" + index + "/_doc/" + memoryId + "?refresh=true", write(doc));
    }

    /** 删除;文档本来就不在(404)也算成功 —— 删除要能重试。 */
    public void delete(UUID memoryId) {
        Response response;
        try {
            response = client.performRequest(new Request("DELETE", "/" + index + "/_doc/" + memoryId + "?refresh=true"));
        } catch (IOException e) {
            throw new IllegalStateException("Elasticsearch 请求失败 DELETE: " + e.getMessage(), e);
        }
        int status = response.getStatusCode();
        if (status != 404 && (status < 200 || status >= 300)) {
            throw new IllegalStateException("Elasticsearch 删除记忆失败,状态码 " + status);
        }
    }

    /** 同一工作区内的向量近邻;过滤条件走 pre-filter,不会先全局取 k 条再丢掉别的工作区的。 */
    public List<Hit> knn(UUID workspaceId, float[] vector, int k) {
        if (!indexExists()) {
            return List.of();
        }
        int size = Math.max(1, k);
        ObjectNode root = NODES.objectNode();
        root.put("size", size);
        ObjectNode knn = root.putObject("knn");
        knn.put("field", "embedding");
        ArrayNode query = knn.putArray("query_vector");
        for (float value : vector) {
            query.add(value);
        }
        knn.put("k", size);
        knn.put("num_candidates", Math.min(10_000, Math.max(50, size * 10)));
        knn.putArray("filter").addObject().putObject("term").put("workspace_id", workspaceId.toString());
        root.putArray("_source").add("memory_id").add("content");

        JsonNode response = performJson("POST", "/" + index + "/_search", write(root));
        List<Hit> hits = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            hits.add(new Hit(
                    UUID.fromString(hit.path("_source").path("memory_id").asText()),
                    hit.path("_source").path("content").asText(),
                    hit.path("_score").asDouble()));
        }
        return hits;
    }

    /** 一条近邻结果。{@code score} 是 ES 的相似度分(cosine 下约在 0~1,越大越像)。 */
    public record Hit(UUID memoryId, String content, double score) {
    }

    private String write(JsonNode node) {
        return mapper.writeValueAsString(node);
    }

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
            throw new IllegalStateException("Elasticsearch 返回 " + status + " (" + method + " " + endpoint + ")");
        }
        return response;
    }

    private JsonNode performJson(String method, String endpoint, String jsonBody) {
        Response response = perform(method, endpoint, jsonBody);
        try (InputStream in = response.getEntity().getContent()) {
            return mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("解析 Elasticsearch 响应失败", e);
        }
    }
}
