package com.ainotes.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ainotes.ingest.ChunkEmbeddingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 长期记忆的三条约定:去重、召回阈值、写入失败不留"僵尸"记录。
 */
class MemoryServiceTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final float[] VECTOR = {0.1f, 0.2f};

    private final MemoryRepository memories = mock(MemoryRepository.class);
    private final MemoryIndexStore index = mock(MemoryIndexStore.class);
    private final ChunkEmbeddingService embeddings = mock(ChunkEmbeddingService.class);
    private MemoryService service;

    @BeforeEach
    void setUp() {
        service = new MemoryService(memories, index, embeddings, 5, 0.7, 0.95);
        when(embeddings.embedOne(anyString())).thenReturn(VECTOR);
        when(memories.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aNewFactIsSavedAndIndexedWithItsVector() {
        when(index.knn(eq(WORKSPACE), any(), eq(1))).thenReturn(List.of());

        MemoryService.Outcome outcome = service.remember(WORKSPACE, "  用户的周报通常周五下午写  ");

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.content()).isEqualTo("用户的周报通常周五下午写");
        verify(index).index(isNull(), eq(WORKSPACE), eq("用户的周报通常周五下午写"), eq(VECTOR));
    }

    @Test
    void aNearlyIdenticalFactIsNotRememberedTwice() {
        when(index.knn(eq(WORKSPACE), any(), eq(1)))
                .thenReturn(List.of(new MemoryIndexStore.Hit(UUID.randomUUID(), "用户的周报通常周五下午写", 0.97)));

        MemoryService.Outcome outcome = service.remember(WORKSPACE, "周报一般周五下午写");

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.content()).isEqualTo("用户的周报通常周五下午写");
        verify(memories, never()).save(any());
        verify(index, never()).index(any(), any(), any(), any());
    }

    @Test
    void anIndexFailureRollsBackTheRowSoNoUnreachableMemoryIsLeftBehind() {
        when(index.knn(eq(WORKSPACE), any(), eq(1))).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("ES 挂了"))
                .when(index).index(any(), any(), any(), any());

        assertThatThrownBy(() -> service.remember(WORKSPACE, "一条事实"))
                .isInstanceOf(IllegalStateException.class);
        verify(memories).delete(any(Memory.class));
    }

    @Test
    void anEmptyOrOverlongFactIsRejectedBeforeAnythingIsCalled() {
        assertThatThrownBy(() -> service.remember(WORKSPACE, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.remember(WORKSPACE, "字".repeat(MemoryService.MAX_CHARS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(embeddings, never()).embedOne(anyString());
    }

    @Test
    void recallKeepsOnlyHitsAboveTheMinimumScoreInOrder() {
        when(index.knn(eq(WORKSPACE), any(), eq(5))).thenReturn(List.of(
                new MemoryIndexStore.Hit(UUID.randomUUID(), "高相关", 0.9),
                new MemoryIndexStore.Hit(UUID.randomUUID(), "只是沾边", 0.5)));

        assertThat(service.recallFor(WORKSPACE, "这周的周报")).containsExactly("高相关");
    }

    @Test
    void recallWithoutEmbeddingsReturnsNothingInsteadOfFailingTheQuestion() {
        when(embeddings.embedOne(anyString())).thenThrow(new IllegalStateException("嵌入服务不可用"));

        assertThat(service.recallFor(WORKSPACE, "随便问一句")).isEmpty();
    }
}
