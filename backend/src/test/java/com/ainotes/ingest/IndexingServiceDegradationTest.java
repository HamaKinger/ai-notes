package com.ainotes.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ainotes.note.Note;
import com.ainotes.note.NoteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 守住 M3 的一条设计决定:**嵌入失败不能阻断关键词索引**。
 *
 * <p>如果嵌入一失败就整篇不写 Elasticsearch,那么外部 AI 接口(密钥过期、限流、网络抖动)
 * 一挂,整个知识库就搜不到任何东西了 —— 连纯关键词都搜不到。这比"只有关键词检索"糟糕得多。
 * 所以嵌入失败时:关键词索引照写、文档不带 {@code embedding} 字段,
 * 但状态必须置为 {@code failed} 并写明原因(不能静默)。
 */
class IndexingServiceDegradationTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NOTE = UUID.fromString("00000000-0000-0000-0000-000000000011");

    private final NoteRepository notes = mock(NoteRepository.class);
    private final IndexJobRepository jobs = mock(IndexJobRepository.class);
    private final ChunkIndexStore store = mock(ChunkIndexStore.class);
    private final ChunkEmbeddingService embeddings = mock(ChunkEmbeddingService.class);

    private final IndexingService service =
            new IndexingService(notes, jobs, store, embeddings, 800);

    /** 造一篇带 id 的笔记(实体没有 id setter,测试里用反射塞一个)。 */
    private Note givenNote(String contentMd) {
        Note note = new Note(WORKSPACE, "欢迎使用 ai-notes", "welcome", contentMd, "hash");
        ReflectionTestUtils.setField(note, "id", NOTE);
        when(notes.findByIdAndDeletedAtIsNull(NOTE)).thenReturn(Optional.of(note));
        when(jobs.save(any(IndexJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return note;
    }

    private static List<float[]> vectorsFor(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            vectors.add(new float[] {0.1f, 0.2f, 0.3f});
        }
        return vectors;
    }

    @Test
    void embeddingFailureStillWritesTheKeywordIndex() {
        Note note = givenNote("# 标题\n\n正文内容。");
        when(embeddings.embedAll(anyList()))
                .thenThrow(new IllegalStateException("401: Incorrect API key provided."));

        IndexingService.IndexOutcome outcome = service.upsert(NOTE);

        assertThat(outcome).isEqualTo(IndexingService.IndexOutcome.KEYWORD_ONLY);
        // 仍然调用了 ES 写入,只是向量为 null
        ArgumentCaptor<List<float[]>> vectors = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ChunkPayload>> chunks = ArgumentCaptor.forClass(List.class);
        verify(store).replaceNoteChunks(eq(WORKSPACE), eq(NOTE), any(), any(), anyList(),
                chunks.capture(), vectors.capture());
        assertThat(chunks.getValue()).isNotEmpty();
        assertThat(vectors.getValue()).isNull();
        // 但状态不能是 ready —— 半成品必须留痕
        assertThat(note.getIndexStatus()).isEqualTo("failed");
    }

    @Test
    void writesVectorsAndMarksReadyWhenEmbeddingSucceeds() {
        Note note = givenNote("# 标题\n\n正文内容。");
        when(embeddings.embedAll(anyList()))
                .thenAnswer(invocation -> vectorsFor(invocation.getArgument(0)));

        assertThat(service.upsert(NOTE)).isEqualTo(IndexingService.IndexOutcome.READY);

        ArgumentCaptor<List<float[]>> vectors = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ChunkPayload>> chunks = ArgumentCaptor.forClass(List.class);
        verify(store).replaceNoteChunks(eq(WORKSPACE), eq(NOTE), any(), any(), anyList(),
                chunks.capture(), vectors.capture());
        assertThat(vectors.getValue()).hasSameSizeAs(chunks.getValue());
        assertThat(note.getIndexStatus()).isEqualTo("ready");
    }

    @Test
    void elasticsearchFailureIsReportedAsFailed() {
        Note note = givenNote("# 标题\n\n正文内容。");
        when(embeddings.embedAll(anyList()))
                .thenAnswer(invocation -> vectorsFor(invocation.getArgument(0)));
        org.mockito.Mockito.doThrow(new IllegalStateException("es: 503"))
                .when(store).replaceNoteChunks(any(), any(), any(), any(), anyList(), anyList(), anyList());

        assertThat(service.upsert(NOTE)).isEqualTo(IndexingService.IndexOutcome.FAILED);
        assertThat(note.getIndexStatus()).isEqualTo("failed");
    }

    @Test
    void doesNotCallTheEmbeddingModelForANoteWithNoContent() {
        givenNote("");

        assertThat(service.upsert(NOTE)).isEqualTo(IndexingService.IndexOutcome.READY);
        verify(embeddings, never()).embedAll(anyList());
    }
}
