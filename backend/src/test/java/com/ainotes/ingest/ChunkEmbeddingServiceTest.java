package com.ainotes.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 嵌入封装的单元测试(不需要真的调用模型服务)。
 *
 * <p>主要守住"向量维度"这件事:{@code dense_vector} 的维度写死在 ES mapping 里,
 * 一旦模型返回的维度和索引对不上,不做校验的话表现是"写入被 ES 拒绝"或"向量检索永远为空",
 * 两者都极难定位。这里断言它必须抛出带修复指引的错误。
 */
class ChunkEmbeddingServiceTest {

    private static float[] vector(float seed, int dims) {
        float[] values = new float[dims];
        for (int i = 0; i < dims; i++) {
            values[i] = seed + i;
        }
        return values;
    }

    /** 造一个"服务端按请求顺序返回、index 从 0 递增"的假模型。 */
    private static EmbeddingModel sequentialModel(int dims) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            List<String> texts = request.getInstructions();
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(vector(i + 1, dims), i));
            }
            return new EmbeddingResponse(embeddings);
        });
        return model;
    }

    private static ChunkEmbeddingService service(EmbeddingModel model, int dims) {
        return new ChunkEmbeddingService(model, dims, "text-embedding-v3");
    }

    @Test
    void returnsVectorsInRequestOrder() {
        List<float[]> vectors = service(sequentialModel(4), 4).embedAll(List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)).containsExactly(1f, 2f, 3f, 4f);
        assertThat(vectors.get(2)).containsExactly(3f, 4f, 5f, 6f);
    }

    @Test
    void reordersResultsByIndexWhenTheServerAnswersOutOfOrder() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of(
                new Embedding(new float[] {9, 9, 9, 9}, 1),
                new Embedding(new float[] {1, 1, 1, 1}, 0))));

        List<float[]> vectors = service(model, 4).embedAll(List.of("a", "b"));

        assertThat(vectors.get(0)).containsExactly(1f, 1f, 1f, 1f);
        assertThat(vectors.get(1)).containsExactly(9f, 9f, 9f, 9f);
    }

    @Test
    void keepsResponseOrderWhenIndicesAreNotUsable() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of(
                new Embedding(new float[] {1, 1, 1, 1}, null),
                new Embedding(new float[] {2, 2, 2, 2}, null))));

        List<float[]> vectors = service(model, 4).embedAll(List.of("a", "b"));

        assertThat(vectors.get(0)).containsExactly(1f, 1f, 1f, 1f);
        assertThat(vectors.get(1)).containsExactly(2f, 2f, 2f, 2f);
    }

    @Test
    void failsLoudlyWhenTheModelDimensionDoesNotMatchTheIndex() {
        ChunkEmbeddingService service = service(sequentialModel(1536), 1024);

        assertThatThrownBy(() -> service.embedOne("架构"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1536")
                .hasMessageContaining("1024")
                .hasMessageContaining("chunk-index.json");
    }

    /** 已知 Ollama 的 bge-m3 在少数文本上会吐 NaN(ollama#14657),必须在这里拦住而不是让 ES 报错。 */
    @Test
    void rejectsNonFiniteValues() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of(
                new Embedding(new float[] {0.1f, Float.NaN, 0.3f, 0.4f}, 0))));

        assertThatThrownBy(() -> service(model, 4).embedOne("架构"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NaN")
                .hasMessageContaining("非法数值");
    }

    @Test
    void failsWhenTheServerReturnsTheWrongNumberOfVectors() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(vector(1, 4), 0))));

        assertThatThrownBy(() -> service(model, 4).embedAll(List.of("a", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("条数");
    }

    /** 百炼 text-embedding-v3 单次最多 10 条,批次切分错了会整批 400。 */
    @Test
    void splitsIntoBatchesOfTen() {
        EmbeddingModel model = sequentialModel(4);
        ChunkEmbeddingService service = service(model, 4);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            texts.add("text-" + i);
        }

        assertThat(service.embedAll(texts)).hasSize(12);
        verify(model, times(2)).call(any(EmbeddingRequest.class));
    }

    @Test
    void doesNotCallTheModelForEmptyInput() {
        EmbeddingModel model = sequentialModel(4);

        assertThat(service(model, 4).embedAll(List.of())).isEmpty();
        verify(model, times(0)).call(any(EmbeddingRequest.class));
    }

    @Test
    void embeddingInputCarriesTitleAndHeadingPathAsContext() {
        assertThat(ChunkEmbeddingService.embeddingInput("架构设计", "检索层 / 混合检索", "正文"))
                .isEqualTo("架构设计\n检索层 / 混合检索\n正文");
    }

    @Test
    void embeddingInputSkipsBlankContext() {
        assertThat(ChunkEmbeddingService.embeddingInput(null, "  ", "正文")).isEqualTo("正文");
        assertThat(ChunkEmbeddingService.embeddingInput("架构设计", null, "正文"))
                .isEqualTo("架构设计\n正文");
    }
}
