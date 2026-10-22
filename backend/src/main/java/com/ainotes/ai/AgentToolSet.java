package com.ainotes.ai;

import com.ainotes.ai.AgentRunContext.ToolOutcome;
import com.ainotes.graph.NoteGraphService;
import com.ainotes.memory.MemoryService;
import com.ainotes.note.Note;
import com.ainotes.note.NoteRepository;
import com.ainotes.note.NoteService;
import com.ainotes.note.dto.NoteCreateRequest;
import com.ainotes.note.dto.NoteUpdateRequest;
import com.ainotes.search.RetrievalPort;
import com.ainotes.search.RetrievedChunk;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 一次运行的工具集(M5 Step 1)。**每次运行现建一个实例**:工具要绑工作区上下文,
 * 而模型给的参数只有"标题、关键词、笔记 id"这些。
 *
 * <p>工具方法的返回值就是喂回模型的那段文本,所以每段文本都按"模型能不能据此继续"来写:
 * 列表要带 id(否则它没法接着 getNote/updateNote)、检索要带编号(否则没法在回答里标引用)、
 * 失败要说清"哪一步为什么失败"(否则模型会原样重试)。
 *
 * <p><b>写操作在这里照常执行</b>,拦截发生在更上层({@link AgentService}):模型要求写时,
 * 服务层先记一条 pending 的 {@code tool_invocation}、把控制权交回用户,**根本不会调到这些方法**;
 * 用户点了确认之后才由续跑调进来。所以这里不需要"我是不是被确认过"这类判断 ——
 * 能走到这里,就说明已经获准执行了。
 *
 * <p>与设计文档 §7.2 的差异:{@code createLink} 没做(它等价于 {@code updateNote}+appendText,
 * 多加一个工具只是让模型多一个选择);{@code summarizeNote} / {@code runMcpTool} / {@code webSearch}
 * 分别归到 M5 后两步与"可选"。见 §13.12。
 */
public class AgentToolSet {

    /** 一次检索最多返回几段 */
    private static final int SEARCH_MAX = 12;
    /** 一次列目录最多返回几篇 */
    private static final int LIST_MAX = 50;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final AgentRunContext ctx;
    private final RetrievalPort retrieval;
    private final NoteRepository notes;
    private final NoteService noteService;
    private final NoteGraphService graph;
    private final MemoryService memory;
    private final int ragTopK;
    private final int maxNoteChars;

    public AgentToolSet(AgentRunContext ctx,
                        RetrievalPort retrieval,
                        NoteRepository notes,
                        NoteService noteService,
                        NoteGraphService graph,
                        MemoryService memory,
                        int ragTopK,
                        int maxNoteChars) {
        this.ctx = ctx;
        this.retrieval = retrieval;
        this.notes = notes;
        this.noteService = noteService;
        this.graph = graph;
        this.memory = memory;
        this.ragTopK = ragTopK;
        this.maxNoteChars = maxNoteChars;
    }

    // ---- 读 --------------------------------------------------------

    @Tool(name = "searchNotes", description = """
            在当前工作区的笔记里做混合检索(BM25 关键词 + 向量语义),返回最相关的若干片段。
            想知道"笔记里有没有提过某事"就问它。回答时用返回的 [编号] 标注依据。""")
    public String searchNotes(
            @ToolParam(description = "检索词或一句话问题,例如「本周的工作记录」") String query,
            @ToolParam(required = false, description = "返回几段,默认 6,最多 12") Integer topK) {
        if (query == null || query.isBlank()) {
            return fail("关键词为空", "检索失败:query 不能为空");
        }
        int k = topK == null ? ragTopK : Math.max(1, Math.min(SEARCH_MAX, topK));
        List<RetrievedChunk> chunks;
        try {
            chunks = retrieval.retrieve(new RetrievalPort.SearchQuery(ctx.workspaceId(), query.strip(), null, k));
        } catch (RuntimeException error) {
            // 检索层自己也有降级(嵌入/ES 单路失败用另一路);到这儿说明两路都不行
            return fail("检索失败", "检索失败:" + error.getMessage() + "(可以稍后重试,或改用 listNotes)");
        }
        if (chunks.isEmpty()) {
            ctx.record(ToolOutcome.ok("没有命中"));
            return "没有命中任何片段。换个关键词再试,或者用 listNotes 看看有哪些笔记。";
        }

        StringBuilder out = new StringBuilder("命中 ").append(chunks.size())
                .append(" 段(回答时用方括号里的编号标注依据):\n");
        for (RetrievedChunk chunk : chunks) {
            ChatSource source = ChatSource.of(ctx.nextIndex(), chunk, titleOf(chunk));
            ctx.addSource(source);
            out.append('[').append(source.index()).append("] 《").append(titleOf(source)).append('》');
            if (source.headingPath() != null && !source.headingPath().isBlank()) {
                out.append(" › ").append(source.headingPath().strip());
            }
            out.append('\n').append(source.content() == null ? "" : source.content().strip()).append("\n\n");
        }
        String text = out.toString();
        ctx.record(ToolOutcome.ok("命中 " + chunks.size() + " 段片段"));
        return text;
    }

    @Tool(name = "getNote", description = """
            读一篇笔记的完整正文。noteId 要用 listNotes / searchNotes 返回的那种 UUID;
            不知道 id(或只是想按标题找)就只给 title,别自己编 id。""")
    public String getNote(
            @ToolParam(required = false, description = "笔记 id,必须是 listNotes 返回的那种 UUID") String noteId,
            @ToolParam(required = false, description = "笔记标题,不知道 id 时用它按标题找") String title) {
        Note note = resolve(noteId, title);
        if (note == null) {
            return fail("没找到这篇笔记", "没找到笔记(" + (noteId != null && !noteId.isBlank() ? "id=" + noteId : "标题=" + title)
                    + ")。先用 listNotes 或 searchNotes 拿到 id(是一串 UUID,不是序号),或直接给标题。");
        }
        String body = note.getContentMd() == null ? "" : note.getContentMd();
        boolean clipped = body.length() > maxNoteChars;
        if (clipped) {
            body = body.substring(0, maxNoteChars) + "\n…(正文过长,已截断)";
        }
        List<String> tags = graph.relations(note.getId()).tags();
        String text = "《" + note.getTitle() + "》(id=" + note.getId() + ")\n"
                + "标签:" + (tags.isEmpty() ? "无" : tags.stream().map(t -> "#" + t).reduce((a, b) -> a + " " + b).orElse("")) + "\n"
                + "更新时间:" + DAY.format(note.getUpdatedAt()) + "\n\n"
                + body;
        ctx.record(ToolOutcome.ok("读取《" + note.getTitle() + "》" + (clipped ? "(已截断)" : "")));
        return text;
    }

    @Tool(name = "listNotes", description = """
            列出笔记(按更新时间倒序,只给标题/id/摘要,不含正文)。
            「最近写的」「本周的」这类按时间找笔记的需求用它,再对感兴趣的几篇调 getNote。""")
    public String listNotes(
            @ToolParam(required = false, description = "最多列几篇,默认 20,最多 50") Integer limit,
            @ToolParam(required = false, description = "只看带这个标签的笔记(不需要 # 号)") String tag,
            @ToolParam(required = false, description = "只看最近几天更新过的,例如本周填 7;不填或填 0 表示不限时间") Integer sinceDays) {
        int max = limit == null ? 20 : Math.max(1, Math.min(LIST_MAX, limit));
        Set<UUID> byTag = null;
        if (tag != null && !tag.isBlank()) {
            byTag = new LinkedHashSet<>(graph.noteIdsWithTag(ctx.workspaceId(), tag.strip().replaceFirst("^#", "")));
            if (byTag.isEmpty()) {
                ctx.record(ToolOutcome.ok("没有这个标签的笔记"));
                return "没有带 #" + tag.strip() + " 的笔记。";
            }
        }
        // sinceDays <= 0 视为"不限时间":实测模型会填 0 来表达"不筛选",若按"最近 0 天"算
        // 就只剩今天更新的笔记 —— 它会以为工作区里只有一篇,然后开始乱编 id。
        Instant after = (sinceDays == null || sinceDays <= 0) ? null : Instant.now().minusSeconds(sinceDays * 86400L);

        List<Note> matched = new ArrayList<>();
        for (Note note : notes.findByWorkspaceIdAndDeletedAtIsNull(ctx.workspaceId())) {
            if (after != null && note.getUpdatedAt() != null && note.getUpdatedAt().isBefore(after)) {
                continue;
            }
            if (byTag != null && !byTag.contains(note.getId())) {
                continue;
            }
            matched.add(note);
        }
        matched.sort((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()));
        if (matched.isEmpty()) {
            ctx.record(ToolOutcome.ok("没有匹配的笔记"));
            return "没有符合条件的笔记。";
        }

        int shown = Math.min(max, matched.size());
        StringBuilder out = new StringBuilder("共 ").append(matched.size()).append(" 篇符合条件,列出最近 ")
                .append(shown).append(" 篇:\n");
        for (int i = 0; i < shown; i++) {
            Note note = matched.get(i);
            out.append("- 《").append(note.getTitle()).append("》 id=").append(note.getId())
                    .append(" 更新于 ").append(DAY.format(note.getUpdatedAt()));
            if (note.getSummary() != null && !note.getSummary().isBlank()) {
                out.append(" 摘要:").append(note.getSummary().strip());
            }
            out.append('\n');
        }
        ctx.record(ToolOutcome.ok("列出 " + shown + " 篇笔记"));
        return out.toString();
    }

    // ---- 写(需要用户在确认卡片上点一下) ------------------------------

    @Tool(name = "createNote", description = "新建一篇笔记。正文用 Markdown。把整理结果落成新笔记时用它。")
    public String createNote(
            @ToolParam(description = "标题") String title,
            @ToolParam(description = "正文(Markdown)") String contentMd,
            @ToolParam(required = false, description = "标签,多个用空格或逗号分隔,例如「周报 工作」") String tags) {
        if (title == null || title.isBlank()) {
            return fail("标题为空", "新建失败:标题不能为空。");
        }
        String content = contentMd == null ? "" : contentMd.strip();
        String tagLine = tagLine(tags);
        if (!tagLine.isEmpty()) {
            content = content.isEmpty() ? tagLine : content + "\n\n" + tagLine;
        }
        Note saved = noteService.create(ctx.workspaceId(), new NoteCreateRequest(title.strip(), content, null));
        ctx.record(ToolOutcome.ok("新建《" + saved.getTitle() + "》"));
        return "已创建《" + saved.getTitle() + "》 id=" + saved.getId() + "(" + content.length() + " 字)。"
                + "它已经进入索引,稍后就能被检索到。";
    }

    @Tool(name = "updateNote", description = """
            修改一篇已有笔记。contentMd 是**整篇替换**,appendText 是**追加到正文末尾**;
            追加时只给 appendText,别把原文一起带上。改了哪篇要先用 getNote 确认。""")
    public String updateNote(
            @ToolParam(description = "笔记 id") String noteId,
            @ToolParam(required = false, description = "新的正文(整篇替换,慎用)") String contentMd,
            @ToolParam(required = false, description = "追加到正文末尾的内容(用这个更安全)") String appendText) {
        Note note = resolve(noteId, null);
        if (note == null) {
            return fail("没找到这篇笔记", "没找到笔记 id=" + noteId + ",先用 listNotes 确认。");
        }
        if ((contentMd == null || contentMd.isBlank()) && (appendText == null || appendText.isBlank())) {
            return fail("没有给要写入的内容", "修改失败:contentMd 与 appendText 至少要给一个。");
        }
        String next;
        boolean append = contentMd == null || contentMd.isBlank();
        if (append) {
            String base = note.getContentMd() == null ? "" : note.getContentMd();
            next = base.isBlank() ? appendText.strip() : base.stripTrailing() + "\n\n" + appendText.strip();
        } else {
            next = contentMd;
        }
        Note saved = noteService.update(note.getId(), new NoteUpdateRequest(null, next, null, null));
        ctx.record(ToolOutcome.ok((append ? "追加到《" : "改写《") + saved.getTitle() + "》"));
        return "已更新《" + saved.getTitle() + "》(" + (append ? "追加 " : "替换为 ") + next.length() + " 字)。";
    }

    @Tool(name = "addTags", description = "给一篇已有笔记打标签。标签会作为 #标签 写进正文末尾,可直接被检索。")
    public String addTags(
            @ToolParam(description = "笔记 id") String noteId,
            @ToolParam(description = "标签,多个用空格或逗号分隔") String tags) {
        Note note = resolve(noteId, null);
        if (note == null) {
            return fail("没找到这篇笔记", "没找到笔记 id=" + noteId + ",先用 listNotes 确认。");
        }
        String tagLine = tagLine(tags);
        if (tagLine.isEmpty()) {
            return fail("没有有效的标签", "打标签失败:没有可用的标签(标签是 2~12 个字、不含空格)。");
        }
        String base = note.getContentMd() == null ? "" : note.getContentMd();
        String next = base.isBlank() ? tagLine : base.stripTrailing() + "\n\n" + tagLine;
        Note saved = noteService.update(note.getId(), new NoteUpdateRequest(null, next, null, null));
        ctx.record(ToolOutcome.ok("给《" + saved.getTitle() + "》打标签 " + tagLine));
        return "已给《" + saved.getTitle() + "》加上 " + tagLine;
    }

    @Tool(name = "rememberFact", description = """
            把一件事记进用户的长期记忆,以后每次提问都会按相关性自动带上它。
            只在用户明确说"记住……""以后……""别忘了……"时才用;一次只记一件事,写成一句完整的陈述。""")
    public String rememberFact(
            @ToolParam(description = "要记住的一件事,一句完整的话,例如「用户的周报通常周五下午写」") String fact) {
        try {
            MemoryService.Outcome outcome = memory.remember(ctx.workspaceId(), fact);
            if (!outcome.created()) {
                ctx.record(ToolOutcome.ok("这条已经记过了"));
                return "这条和已有的记忆几乎一样,没有重复记:「" + outcome.content() + "」。";
            }
            ctx.record(ToolOutcome.ok("已记住"));
            return "已记住:「" + outcome.content() + "」。之后的对话会按相关性带上它。";
        } catch (IllegalArgumentException invalid) {
            return fail("记不下来", "没有记住:" + invalid.getMessage() + "。");
        } catch (IllegalStateException unavailable) {
            return fail("记忆服务不可用", "没有记住:" + unavailable.getMessage()
                    + "。这件事请直接告诉用户,不要假装已经记下。");
        }
    }

    // ---- 内部 ------------------------------------------------------

    /** 按 id 或标题找笔记;**必须属于当前工作区**,否则等于隔着工作区读写别人的笔记。 */
    private Note resolve(String noteId, String title) {
        if (noteId != null && !noteId.isBlank()) {
            UUID id = parseUuid(noteId.strip());
            if (id != null) {
                Note found = notes.findByIdAndDeletedAtIsNull(id)
                        .filter(note -> ctx.workspaceId().equals(note.getWorkspaceId()))
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            }
            // id 找不到(或压根不是 UUID)时**退一步按标题找**:实测模型很爱自己编一个序号当 id
            // (「1」「5」「11」),同时把正确的标题也带上 —— 直接失败会白白烧掉一步。
        }
        if (title != null && !title.isBlank()) {
            return notes.findFirstByWorkspaceIdAndTitleIgnoreCaseAndDeletedAtIsNull(ctx.workspaceId(), title.strip())
                    .orElse(null);
        }
        return null;
    }

    private String fail(String summary, String text) {
        ctx.record(ToolOutcome.error(summary));
        return text;
    }

    /**
     * 把"标签"参数整理成一行 `#标签`。
     *
     * <p>模型给标签的写法很不统一(数组、逗号、空格、带 # 号、带编号),与其在 schema 上强卡类型,
     * 不如在这里宽松解析 —— 与 M4 的标签工具同一个思路(§13.11 第 5 节)。
     */
    static String tagLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String piece : raw.split("[\\s,、;，；]+")) {
            String tag = piece.strip().replaceFirst("^[#\\-*\\d.、]+", "").strip();
            if (!tag.isEmpty() && tag.length() <= 12 && !tag.contains("#")) {
                tags.add('#' + tag);
            }
        }
        return String.join(" ", tags);
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** 片段所属笔记的标题:优先用索引里的,缺失才回查 note 表(与 ChatService 同一套)。 */
    private String titleOf(RetrievedChunk chunk) {
        if (chunk.noteTitle() != null && !chunk.noteTitle().isBlank()) {
            return chunk.noteTitle();
        }
        return chunk.noteId() == null ? null
                : notes.findById(chunk.noteId()).map(Note::getTitle).orElse(null);
    }

    private static String titleOf(ChatSource source) {
        return source.noteTitle() == null || source.noteTitle().isBlank() ? "未命名笔记" : source.noteTitle();
    }
}
