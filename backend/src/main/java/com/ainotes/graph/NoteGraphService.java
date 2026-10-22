package com.ainotes.graph;

import com.ainotes.common.NotFoundException;
import com.ainotes.note.Note;
import com.ainotes.note.NoteRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 双链与标签(设计文档 §2.1、§2.2)。
 *
 * <p>数据来源是笔记正文里的 `[[标题]]` 与 `#标签`,由 {@link NoteContentParser} 解析。
 * 与检索索引一样,这些都是**可从正文重建的派生数据**:写入失败不会影响笔记本身。
 */
@Service
public class NoteGraphService {

    private final NoteRepository notes;
    private final LinkRepository links;
    private final TagRepository tags;
    private final NoteTagRepository noteTags;
    private final NoteContentParser parser;

    public NoteGraphService(NoteRepository notes,
                            LinkRepository links,
                            TagRepository tags,
                            NoteTagRepository noteTags,
                            NoteContentParser parser) {
        this.notes = notes;
        this.links = links;
        this.tags = tags;
        this.noteTags = noteTags;
        this.parser = parser;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 重建某篇笔记的出链与标签。previousTitle 为改名前标题(新建时为 null)。 */
    @Transactional
    public void rebuild(UUID noteId, String previousTitle) {
        Note note = notes.findByIdAndDeletedAtIsNull(noteId).orElse(null);
        if (note == null) {
            remove(noteId);
            return;
        }

        ParsedNote parsed = parser.parse(note.getContentMd());
        syncLinks(note, parsed.links());
        syncTags(note, parsed.tags());

        if (previousTitle != null && !previousTitle.equals(note.getTitle())) {
            // 改名:指向旧标题的链接先解绑,再由下面的 attach 按新标题重新挂接
            detachTarget(noteId);
        }
        attachTarget(note.getWorkspaceId(), note.getTitle(), noteId);
    }

    /** 笔记被删除:清掉它的出链与标签,并把指向它的链接标记为"未解析"。 */
    @Transactional
    public void remove(UUID noteId) {
        links.deleteBySourceNoteId(noteId);
        clearTags(noteId);
        detachTarget(noteId);
    }

    private void syncLinks(Note note, List<WikiLink> refs) {
        Map<String, Link> existing = new HashMap<>();
        for (Link link : links.findBySourceNoteId(note.getId())) {
            existing.put(key(link.getTargetTitle()), link);
        }

        for (WikiLink ref : refs) {
            UUID targetId = resolve(note.getWorkspaceId(), ref.target());
            Link link = existing.remove(key(ref.target()));
            if (link == null) {
                links.save(new Link(note.getWorkspaceId(), note.getId(), targetId, ref.target(), ref.context()));
            } else {
                link.refresh(targetId, ref.context());
            }
        }
        // 正文里已经删掉的引用,对应行一并清掉
        if (!existing.isEmpty()) {
            links.deleteAll(existing.values());
        }
    }

    private void syncTags(Note note, List<String> names) {
        clearTags(note.getId());

        for (String name : new LinkedHashSet<>(names)) {
            Tag tag = tags.findByWorkspaceIdAndNameIgnoreCase(note.getWorkspaceId(), name)
                    .orElseGet(() -> tags.save(new Tag(note.getWorkspaceId(), name)));
            noteTags.save(new NoteTag(note.getId(), tag.getId()));
        }
    }

    /**
     * 清掉某篇笔记的标签关联。
     *
     * <p>注意:用 find + deleteAll 而不是 `deleteByNoteId` ——
     * note_tag 是复合主键 `(noteId, tagId)`,根属性是 {@code id},
     * 派生查询必须写成 {@code id.noteId},否则 Spring Data 解析方法名时直接抛
     * PropertyReferenceException(已由 RepositoryQueryTest 兜住)。
     */
    private void clearTags(UUID noteId) {
        List<NoteTag> existing = noteTags.findByIdNoteId(noteId);
        if (existing.isEmpty()) {
            return;
        }
        noteTags.deleteAll(existing);
        // 先落 DELETE,否则 flush 时 INSERT 可能排在同主键的 DELETE 之前
        noteTags.flush();
    }

    private UUID resolve(UUID workspaceId, String title) {
        return notes.findFirstByWorkspaceIdAndTitleIgnoreCaseAndDeletedAtIsNull(workspaceId, title)
                .map(Note::getId)
                .orElse(null);
    }

    private void attachTarget(UUID workspaceId, String title, UUID noteId) {
        for (Link link : links.findByWorkspaceIdAndTargetTitleIgnoreCase(workspaceId, title)) {
            if (!noteId.equals(link.getTargetNoteId())) {
                link.attachTarget(noteId);
            }
        }
    }

    private void detachTarget(UUID noteId) {
        for (Link link : links.findByTargetNoteId(noteId)) {
            link.detachTarget();
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public NoteRelations relations(UUID noteId) {
        Note note = notes.findByIdAndDeletedAtIsNull(noteId)
                .orElseThrow(() -> new NotFoundException("note not found: " + noteId));

        List<Link> out = links.findBySourceNoteId(note.getId());
        List<Link> in = links.findByTargetNoteId(note.getId());
        Map<UUID, Note> referenced = loadNotes(Stream.concat(
                        out.stream().map(Link::getTargetNoteId),
                        in.stream().map(Link::getSourceNoteId))
                .filter(Objects::nonNull)
                .toList());

        List<RelatedLink> outgoing = out.stream()
                .map(link -> {
                    Note target = link.getTargetNoteId() == null ? null : referenced.get(link.getTargetNoteId());
                    return new RelatedLink(
                            link.getTargetNoteId(),
                            target == null ? link.getTargetTitle() : target.getTitle(),
                            link.getContext());
                })
                .toList();

        List<RelatedLink> backlinks = in.stream()
                .map(link -> {
                    Note source = referenced.get(link.getSourceNoteId());
                    return source == null ? null : new RelatedLink(source.getId(), source.getTitle(), link.getContext());
                })
                .filter(Objects::nonNull)
                .toList();

        return new NoteRelations(tagNamesOf(note.getId()), outgoing, backlinks);
    }

    /**
     * 把一批标题解析成笔记 —— 预览里的 `[[双链]]` 靠它决定"可点 / 未创建"。
     *
     * <p>语义与 {@link #resolve} 一致(同名笔记取第一条),区别只是**一次问一批**:一篇长笔记
     * 里可能有几十个链接,逐个查就是几十次往返。顺带返回一段目标笔记的预览文本,给悬停卡片用
     * —— 不然用户点进去之前完全不知道那篇写了什么。
     */
    @Transactional(readOnly = true)
    public List<ResolvedLink> resolveTitles(UUID workspaceId, List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return List.of();
        }
        Map<String, Note> byTitle = new HashMap<>();
        for (Note note : notes.findByWorkspaceIdAndDeletedAtIsNull(workspaceId)) {
            byTitle.putIfAbsent(key(note.getTitle()), note);
        }

        List<ResolvedLink> result = new ArrayList<>();
        for (String title : new LinkedHashSet<>(titles)) {
            String wanted = title == null ? "" : title.trim();
            if (wanted.isEmpty()) {
                continue;
            }
            Note note = byTitle.get(key(wanted));
            result.add(new ResolvedLink(
                    wanted,
                    note == null ? null : note.getId(),
                    note == null ? null : note.getTitle(),
                    note == null ? null : snippetOf(note)));
        }
        return result;
    }

    /** 悬停卡片用的预览文本:优先摘要,没有就取正文开头,并抹掉常见 markdown 标记。 */
    private static String snippetOf(Note note) {
        String summary = note.getSummary();
        String source = summary == null || summary.isBlank() ? note.getContentMd() : summary;
        String flat = source
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]", "$1")
                .replaceAll("[`*>|_~]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    @Transactional(readOnly = true)
    public List<TagCount> tagsOfWorkspace(UUID workspaceId) {
        List<TagCount> result = new ArrayList<>();
        for (Tag tag : tags.findByWorkspaceId(workspaceId)) {
            long count = noteTags.countByIdTagId(tag.getId());
            if (count > 0) {
                result.add(new TagCount(tag.getName(), count));
            }
        }
        result.sort(Comparator.comparingLong(TagCount::count).reversed().thenComparing(TagCount::name));
        return result;
    }

    /** 按标签名找笔记(用于笔记列表的标签过滤)。 */
    @Transactional(readOnly = true)
    public List<UUID> noteIdsWithTag(UUID workspaceId, String tagName) {
        return tags.findByWorkspaceIdAndNameIgnoreCase(workspaceId, tagName)
                .map(tag -> noteTags.findByIdTagId(tag.getId()).stream()
                        .map(noteTag -> noteTag.getId().getNoteId())
                        .toList())
                .orElseGet(List::of);
    }

    /** 工作区知识图谱:节点=未删除的笔记,边=已解析成功的双链。 */
    @Transactional(readOnly = true)
    public GraphView graph(UUID workspaceId) {
        List<Note> all = notes.findByWorkspaceIdAndDeletedAtIsNull(workspaceId);
        Set<UUID> ids = all.stream().map(Note::getId).collect(java.util.stream.Collectors.toSet());

        List<GraphNode> nodes = all.stream().map(note -> new GraphNode(note.getId(), note.getTitle())).toList();

        List<GraphEdge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Link link : links.findByWorkspaceId(workspaceId)) {
            UUID target = link.getTargetNoteId();
            if (target == null || target.equals(link.getSourceNoteId())) {
                continue;
            }
            if (!ids.contains(link.getSourceNoteId()) || !ids.contains(target)) {
                continue;
            }
            if (seen.add(link.getSourceNoteId() + ">" + target)) {
                edges.add(new GraphEdge(link.getSourceNoteId(), target));
            }
        }
        return new GraphView(workspaceId, nodes, edges);
    }

    private Map<UUID, Note> loadNotes(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Note> byId = new HashMap<>();
        for (Note note : notes.findAllById(ids)) {
            byId.put(note.getId(), note);
        }
        return byId;
    }

    private List<String> tagNamesOf(UUID noteId) {
        List<UUID> tagIds = noteTags.findByIdNoteId(noteId).stream()
                .map(noteTag -> noteTag.getId().getTagId())
                .toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tags.findAllById(tagIds).stream().map(Tag::getName).sorted().toList();
    }

    private static String key(String title) {
        return title.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 对外返回结构
    // ------------------------------------------------------------------

    public record RelatedLink(UUID noteId, String title, String context) {
    }

    /**
     * 一条标题的解析结果。`noteId` 为 null 表示**还没有这篇笔记** —— 前端把它画成"未创建",
     * 点一下就地建出来(与关系面板的「创建」按钮同一套行为)。
     */
    public record ResolvedLink(String title, UUID noteId, String noteTitle, String snippet) {
    }

    public record NoteRelations(List<String> tags, List<RelatedLink> outgoing, List<RelatedLink> backlinks) {
    }

    public record TagCount(String name, long count) {
    }

    public record GraphNode(UUID id, String title) {
    }

    public record GraphEdge(UUID source, UUID target) {
    }

    public record GraphView(UUID workspaceId, List<GraphNode> nodes, List<GraphEdge> edges) {
    }
}
