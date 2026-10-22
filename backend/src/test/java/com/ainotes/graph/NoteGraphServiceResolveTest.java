package com.ainotes.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ainotes.common.Hashing;
import com.ainotes.note.Note;
import com.ainotes.note.NoteRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 预览里的 `[[双链]]` 靠 `/links/resolve` 决定自己长什么样:能点(已创建)还是虚线
 * (未创建),以及悬停卡片上显示什么。规则错了,界面上就会出现"预览说是未创建、
 * 关系面板却能点"这种自相矛盾 —— 而这两处走的是不同的代码路径,没法互相兜底。
 */
class NoteGraphServiceResolveTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final NoteRepository notes = mock(NoteRepository.class);
    private final LinkRepository links = mock(LinkRepository.class);
    private final TagRepository tags = mock(TagRepository.class);
    private final NoteTagRepository noteTags = mock(NoteTagRepository.class);
    private final NoteContentParser parser = new NoteContentParser();

    private final NoteGraphService service =
            new NoteGraphService(notes, links, tags, noteTags, parser);

    private static Note note(String title, String content) {
        Note note = new Note(WORKSPACE, title, "slug", content, Hashing.sha256Hex(content));
        ReflectionTestUtils.setField(note, "id", UUID.randomUUID());
        return note;
    }

    private void givenNotes(Note... all) {
        when(notes.findByWorkspaceIdAndDeletedAtIsNull(WORKSPACE)).thenReturn(List.of(all));
    }

    @Test
    void resolvesTitlesIgnoringCaseAndSurroundingSpaces() {
        Note target = note("启动手册", "正文");
        givenNotes(target);

        List<NoteGraphService.ResolvedLink> result =
                service.resolveTitles(WORKSPACE, List.of("  启动手册 "));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).noteId()).isEqualTo(target.getId());
        assertThat(result.get(0).noteTitle()).isEqualTo("启动手册");
    }

    @Test
    void marksAnUnknownTitleAsNotCreated() {
        givenNotes(note("启动手册", "正文"));

        List<NoteGraphService.ResolvedLink> result =
                service.resolveTitles(WORKSPACE, List.of("还没写的笔记"));

        assertThat(result.get(0).title()).isEqualTo("还没写的笔记");
        assertThat(result.get(0).noteId()).isNull();
        assertThat(result.get(0).snippet()).isNull();
    }

    @Test
    void asksEachTitleOnlyOnce() {
        givenNotes(note("启动手册", "正文"));

        List<NoteGraphService.ResolvedLink> result =
                service.resolveTitles(WORKSPACE, List.of("启动手册", "启动手册", "  ", "启动手册"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("启动手册");
    }

    @Test
    void keepsTheFirstNoteWhenTwoShareATitle() {
        // 与单条 resolve() 同语义:同名取第一条。两边不一致就会"预览里点得到 A、关系面板指向 B"
        Note first = note("启动手册", "第一份");
        Note second = note("启动手册", "第二份");
        givenNotes(first, second);

        List<NoteGraphService.ResolvedLink> result =
                service.resolveTitles(WORKSPACE, List.of("启动手册"));

        assertThat(result.get(0).noteId()).isEqualTo(first.getId());
    }

    @Test
    void prefersTheSummaryForTheHoverCard() {
        Note target = note("启动手册", "# 启动手册\n\n正文一段");
        target.setSummary("这是一句摘要");
        givenNotes(target);

        List<NoteGraphService.ResolvedLink> result =
                service.resolveTitles(WORKSPACE, List.of("启动手册"));

        assertThat(result.get(0).snippet()).isEqualTo("这是一句摘要");
    }

    @Test
    void flattensMarkdownWhenThereIsNoSummary() {
        Note target = note("启动手册", "# 启动手册\n\n- **要点**:先跑 `docker compose up`\n- 见 [[另一篇]]\n");
        givenNotes(target);

        List<NoteGraphService.ResolvedLink> result =
                service.resolveTitles(WORKSPACE, List.of("启动手册"));

        assertThat(result.get(0).snippet()).isEqualTo("启动手册 要点 :先跑 docker compose up 见 另一篇");
    }

    @Test
    void truncatesALongPreview() {
        Note target = note("启动手册", "字".repeat(400));
        givenNotes(target);

        List<NoteGraphService.ResolvedLink> result =
                service.resolveTitles(WORKSPACE, List.of("启动手册"));

        assertThat(result.get(0).snippet()).hasSize(161).endsWith("…");
    }

    @Test
    void returnsNothingForAnEmptyRequest() {
        assertThat(service.resolveTitles(WORKSPACE, List.of())).isEmpty();
        assertThat(service.resolveTitles(WORKSPACE, null)).isEmpty();
    }
}
