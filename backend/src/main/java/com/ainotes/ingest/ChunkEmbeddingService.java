package com.ainotes.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 分块与查询文本的向量化(对应设计文档 §6.2 / §6.3)。
 *
 * <p>把"调嵌入模型"收在这一处,顺带做一件很关键的事:<b>在源头校验向量</b>。
 * {@code dense_vector} 的维度在 Elasticsearch mapping 里是写死的,模型换了、维度对不上时,
 * 若不做校验,表现会是"写入被 ES 拒绝"或"向量检索永远为空"——都属于最难定位的那类问题。
 * 所以在拿到向量的第一时间就比对配置,不一致直接抛出可读的错误;
 * 顺便也拦住 NaN/Infinity(ES 会拒绝,但报错含糊,而且写进去会污染 HNSW 图)。
 *
 * <p>维度来源:{@code ai-notes.search.embedding-dimensions},默认值与
 * {@code resources/elasticsearch/chunk-index.json} 里的 {@code embedding.dims} 一致。
 */
@Service
public class ChunkEmbeddingService {

    /**
     * 单次请求送入的文本条数。取值偏保守:部分服务商对 {@code /v1/embeddings} 的批量条数有上限
     * (阿里百炼 text-embedding-v3 为 10),超限会整批失败,而索引阶段的失败会把笔记标记为
     * {@code index_status=failed}——宁可多发几次请求。
     */
    private static final int BATCH_SIZE = 10;

    private final EmbeddingModel embeddingModel;
    private final int dimensions;
    private final String model;

    public ChunkEmbeddingService(EmbeddingModel embeddingModel,
                                 @Value("${ai-notes.search.embedding-dimensions:1024}") int dimensions,
                                 @Value("${spring.ai.openai.embedding.options.model:unknown}") String model) {
        this.embeddingModel = embeddingModel;
        this.dimensions = dimensions;
        this.model = model;
    }

    /** 配置期望的向量维度(必须等于 ES mapping 里 {@code embedding.dims})。 */
    public int dimensions() {
        return dimensions;
    }

    /** 当前配置的嵌入模型名,仅用于日志与运维接口展示。 */
    public String model() {
        return model;
    }

    /**
     * 构造送入嵌入模型的文本。
     *
     * <p>不只送 chunk 正文,而是把标题与标题路径作为上下文前缀一起送进去:
     * 片段被切短之后,"它讲的是哪篇笔记的哪一节"这个信息只存在于标题里,
     * 补上它能显著改善短片段与查询的语义匹配。
     * 注意存进 ES 的 {@code content} 仍然是纯正文,只有送模型的文本被增强。
     */
    public static String embeddingInput(String title, String headingPath, String content) {
        StringBuilder input = new StringBuilder();
        if (title != null && !title.isBlank()) {
            input.append(title.trim()).append('\n');
        }
        if (headingPath != null && !headingPath.isBlank()) {
            input.append(headingPath.trim()).append('\n');
        }
        input.append(content == null ? "" : content);
        return input.toString();
    }

    /** 批量向量化,返回顺序与入参严格一一对应。 */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + BATCH_SIZE));
            List<float[]> embedded = request(batch);
            for (float[] vector : embedded) {
                verifyVector(vector);
                vectors.add(vector);
            }
        }
        return vectors;
    }

    /** 单条文本向量化(检索时对 query 用,入参已经是完整查询串,不再拼标题)。 */
    public float[] embedOne(String text) {
        return embedAll(List.of(text)).get(0);
    }

    private List<float[]> request(List<String> texts) {
        EmbeddingResponse response = embeddingModel.call(
                new EmbeddingRequest(texts, EmbeddingOptions.builder().build()));
        return orderedOutputs(response.getResults(), texts.size());
    }

    /**
     * 还原向量顺序,并校验条数。
     *
     * <p>OpenAI 协议里 {@code index} 才是权威顺序。只有当 index 恰好是 {@code 0..n-1} 的一个排列时
     * 才据此重排;其它情况(例如服务商返回 null)说明没有可用的顺序信息,
     * 此时保持返回顺序——与 Spring AI 自身的假设一致。
     */
    private static List<float[]> orderedOutputs(List<Embedding> results, int expected) {
        if (results == null || results.size() != expected) {
            throw new IllegalStateException("嵌入服务返回的条数异常:期望 " + expected + " 条,实际 "
                    + (results == null ? "null" : results.size() + " 条"));
        }
        List<Embedding> ordered = new ArrayList<>(results);
        if (hasSequentialIndices(ordered)) {
            ordered.sort(Comparator.comparingInt(Embedding::getIndex));
        }
        List<float[]> outputs = new ArrayList<>(ordered.size());
        for (Embedding embedding : ordered) {
            float[] output = embedding.getOutput();
            if (output == null || output.length == 0) {
                throw new IllegalStateException("嵌入服务返回了空向量");
            }
            outputs.add(output);
        }
        return outputs;
    }

    private static boolean hasSequentialIndices(List<Embedding> results) {
        boolean[] seen = new boolean[results.size()];
        for (Embedding embedding : results) {
            Integer index = embedding.getIndex();
            if (index == null || index < 0 || index >= seen.length || seen[index]) {
                return false;
            }
            seen[index] = true;
        }
        return true;
    }

    /**
     * 校验向量:维度必须与索引一致,且每个分量都得是有限值。
     *
     * <p>NaN/Infinity 也要在源头拦掉:Elasticsearch 的 {@code dense_vector} 会拒绝 NaN,
     * 但报错发生在写入阶段、信息很含糊({@code failed to parse field [embedding]}),
     * 而万一写进去了,整个 HNSW 图都会被污染。已知本地 Ollama 的 {@code bge-m3}
     * 在部分文本上会返回 NaN(ollama#14657),所以这里不能只查长度。
     */
    private void verifyVector(float[] vector) {
        if (vector.length != dimensions) {
            throw new IllegalStateException(String.format(
                    "嵌入维度不匹配:模型 [%s] 返回 %d 维,当前配置是 %d 维。"
                            + "请把 AI_EMBEDDING_DIMENSIONS 与 resources/elasticsearch/chunk-index.json 里的 "
                            + "embedding.dims 一并改成 %d,然后删除并重建索引"
                            + "(dense_vector 的维度不可修改,只能重建)。",
                    model, vector.length, dimensions, vector.length));
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(String.format(
                        "嵌入模型 [%s] 返回了非法数值(NaN/Infinity),写入索引会污染向量检索。"
                                + "已知本地 Ollama 的 bge-m3 在少数文本上会出现这种情况;"
                                + "可换用其它嵌入模型/服务商,然后重建索引。", model));
            }
        }
    }
}
