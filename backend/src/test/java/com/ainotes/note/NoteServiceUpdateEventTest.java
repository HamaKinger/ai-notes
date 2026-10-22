package com.ainotes.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ainotes.common.Hashing;
import com.ainotes.common.NotFoundException;
import com.ainotes.note.dto.NoteUpdateRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 守住"没改就不重建索引"这条收敛:每次保存都重建一遍是该省的成本。
 *
 * <p>前端保存笔记时会把 {@code title} 与 {@code contentMd} **一起** PATCH 上来。如果
 * {@code update()} 无条件发 {@code NoteChangedEvent},那么"我只点了下别处、正文一个字没动"
 * 也会触发一次完整的"删旧块 → 重新分块 → 重新调嵌入接口"。嵌入是外部按量计费的接口,
 * 白跑一次就是白烧一次 token 和延迟。
 *
 * <p>但**不能只看变化**:{@code index_status != ready} 的笔记(索引失败过、或还没轮到它)
 * 必须照发事件 —— 保存是它唯一能自愈的时机,否则一篇失败的笔记会因为"内容没变"永远修不回来。
 */
class NoteServiceUpdateEventTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NOTE = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final String CONTENT = "正文内容";

    private final NoteRepository notes = mock(NoteRepository.class);
    private final NoteVersionRepository versions = mock(NoteVersionRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final NoteService service = new NoteService(notes, versions, events);

    /** 造一篇已索引好的笔记(索引状态默认是 pending,那会走"自愈"分支,这里显式置为 ready)。 */
    private static Note givenNote(String title, String indexStatus) {
        Note note = new Note(WORKSPACE, title, "slug", CONTENT, Hashing.sha256Hex(CONTENT));
        ReflectionTestUtils.setField(note, "id", NOTE);
        note.setIndexStatus(indexStatus);
        return note;
    }

    private Note givenSavedNote(String title, String indexStatus) {
        Note note = givenNote(title, indexStatus);
        when(notes.findByIdAndDeletedAtIsNull(NOTE)).thenReturn(Optional.of(note));
        when(versions.countByNoteId(NOTE)).thenReturn(0L);
        return note;
    }

    private void verifyPublished() {
        ArgumentCaptor<NoteChangedEvent> captor = ArgumentCaptor.forClass(NoteChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(NoteChangedEvent.Action.UPSERT);
        assertThat(captor.getValue().noteId()).isEqualTo(NOTE);
    }

    @Test
    void doesNotPublishWhenTitleAndContentAreIdentical() {
        givenSavedNote("启动手册", "ready");

        Note updated = service.update(NOTE, new NoteUpdateRequest("启动手册", CONTENT, null, null));

        verify(events, never()).publishEvent(any(NoteChangedEvent.class));
        verify(versions, never()).save(any(NoteVersion.class));
        assertThat(updated.getTitle()).isEqualTo("启动手册");
    }

    @Test
    void publishesWhenTheContentActuallyChanged() {
        Note note = givenSavedNote("启动手册", "ready");

        service.update(NOTE, new NoteUpdateRequest("启动手册", CONTENT + "补一句", null, null));

        verifyPublished();
        // 内容变了才应该有新版本
        verify(versions).save(any(NoteVersion.class));
        assertThat(note.getContentMd()).isEqualTo(CONTENT + "补一句");
        assertThat(note.getContentHash()).isEqualTo(Hashing.sha256Hex(CONTENT + "补一句"));
    }

    @Test
    void publishesWhenOnlyTheTitleChanged() {
        // 标题会进 ES 文档与嵌入输入,所以只改标题也必须重建
        givenSavedNote("启动手册", "ready");

        service.update(NOTE, new NoteUpdateRequest("启动手册 v2", CONTENT, null, null));

        verifyPublished();
        verify(versions, never()).save(any(NoteVersion.class));
    }

    @Test
    void publishesForAnUnchangedSaveWhenTheIndexIsNotReadyYet() {
        // 索引失败过的笔记:即使内容没变也要重试一次,否则永远是"搜不到"状态
        givenSavedNote("启动手册", "failed");

        service.update(NOTE, new NoteUpdateRequest("启动手册", CONTENT, null, null));

        verifyPublished();
    }

    @Test
    void ignoresABlankTitleInsteadOfWritingIt() {
        Note note = givenSavedNote("启动手册", "ready");

        service.update(NOTE, new NoteUpdateRequest("   ", CONTENT, null, null));

        assertThat(note.getTitle()).isEqualTo("启动手册");
        verify(events, never()).publishEvent(any(NoteChangedEvent.class));
    }

    @Test
    void doesNotTouchAnythingWhenTheRequestIsEmpty() {
        Note note = givenSavedNote("启动手册", "ready");
        long before = note.getUpdatedAt().toEpochMilli();

        service.update(NOTE, new NoteUpdateRequest(null, null, null, null));

        assertThat(note.getTitle()).isEqualTo("启动手册");
        // 空请求连 updated_at 都不该动(旧实现里"标题非空就 touch"会把每次保存都算成一次更新)
        assertThat(note.getUpdatedAt().toEpochMilli()).isEqualTo(before);
        verify(events, never()).publishEvent(any(NoteChangedEvent.class));
    }

    @Test
    void rejectsAnUnknownNote() {
        when(notes.findByIdAndDeletedAtIsNull(NOTE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(NOTE, new NoteUpdateRequest(null, "任意内容", null, null)))
                .isInstanceOf(NotFoundException.class);
        verify(events, never()).publishEvent(any(NoteChangedEvent.class));
    }
}
