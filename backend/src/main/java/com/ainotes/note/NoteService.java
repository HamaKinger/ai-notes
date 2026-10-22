package com.ainotes.note;

import com.ainotes.common.Hashing;
import com.ainotes.common.NotFoundException;
import com.ainotes.note.dto.NoteCreateRequest;
import com.ainotes.note.dto.NoteUpdateRequest;
import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {

    private final NoteRepository notes;
    private final NoteVersionRepository versions;
    private final ApplicationEventPublisher events;

    public NoteService(NoteRepository notes,
                       NoteVersionRepository versions,
                       ApplicationEventPublisher events) {
        this.notes = notes;
        this.versions = versions;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<Note> list(UUID workspaceId, String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return notes.findByWorkspaceIdAndDeletedAtIsNull(workspaceId, pageable);
        }
        return notes.findByWorkspaceIdAndDeletedAtIsNullAndTitleContainingIgnoreCase(
                workspaceId, query.trim(), pageable);
    }

    /** 按给定 id 集合分页(标签过滤走这条路径)。 */
    @Transactional(readOnly = true)
    public Page<Note> listByIds(UUID workspaceId, Collection<UUID> ids, Pageable pageable) {
        if (ids.isEmpty()) {
            return Page.empty(pageable);
        }
        return notes.findByWorkspaceIdAndDeletedAtIsNullAndIdIn(workspaceId, ids, pageable);
    }

    @Transactional(readOnly = true)
    public Note get(UUID id) {
        return notes.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("note not found: " + id));
    }

    @Transactional
    public Note create(UUID workspaceId, NoteCreateRequest request) {
        String content = request.contentMd() == null ? "" : request.contentMd();
        Note note = new Note(
                workspaceId,
                request.title(),
                uniqueSlug(workspaceId, request.title()),
                content,
                Hashing.sha256Hex(content));
        note.setFolderId(request.folderId());
        Note saved = notes.save(note);
        versions.save(new NoteVersion(saved.getId(), 1, content));
        events.publishEvent(NoteChangedEvent.upsert(saved.getId(), null));
        return saved;
    }

    @Transactional
    public Note update(UUID id, NoteUpdateRequest request) {
        Note note = notes.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("note not found: " + id));

        String previousTitle = note.getTitle();
        boolean changed = false;

        if (request.title() != null && !request.title().isBlank() && !request.title().equals(note.getTitle())) {
            note.setTitle(request.title());
            note.touch();
            changed = true;
        }
        if (request.folderId() != null && !request.folderId().equals(note.getFolderId())) {
            note.setFolderId(request.folderId());
            note.touch();
            changed = true;
        }
        if (request.summary() != null && !request.summary().equals(note.getSummary())) {
            note.setSummary(request.summary());
            note.touch();
            changed = true;
        }
        if (request.contentMd() != null) {
            String hash = Hashing.sha256Hex(request.contentMd());
            if (!hash.equals(note.getContentHash())) {
                note.applyContent(request.contentMd(), hash);
                int next = (int) versions.countByNoteId(note.getId()) + 1;
                versions.save(new NoteVersion(note.getId(), next, request.contentMd()));
                changed = true;
            }
        }

        // 只有真的改了才发事件。这条事件会触发"删旧块 + 重新分块 + 重新调嵌入接口"的整篇重建,
        // 而前端每次保存都会把 title 和 contentMd 一起 PATCH 上来 —— 无条件发就等于
        // "内容一个字没改也重跑一遍嵌入",白烧 token 也白等延迟。
        //
        // 例外:索引状态不是 ready 时照发。否则一篇之前索引失败的笔记会因为"内容没变"永远
        // 修不回来(保存是它唯一能自愈的时机)。
        if (changed || !"ready".equals(note.getIndexStatus())) {
            events.publishEvent(NoteChangedEvent.upsert(note.getId(), previousTitle));
        }
        return note;
    }

    @Transactional
    public void delete(UUID id) {
        Note note = notes.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("note not found: " + id));
        note.softDelete();
        events.publishEvent(NoteChangedEvent.delete(id, note.getTitle()));
    }

    @Transactional(readOnly = true)
    public List<NoteVersion> history(UUID id) {
        get(id);
        return versions.findByNoteIdOrderByVersionDesc(id);
    }

    @Transactional(readOnly = true)
    public NoteVersion version(UUID noteId, int version) {
        get(noteId);
        return versions.findByNoteIdAndVersion(noteId, version)
                .orElseThrow(() -> new NotFoundException("version not found: " + noteId + "#" + version));
    }

    /**
     * 把历史版本回滚成当前内容。
     *
     * <p>不回删历史:回滚本身也会作为一个新版本追加,保证版本序列只增不减。
     */
    @Transactional
    public Note restore(UUID noteId, int version) {
        Note note = get(noteId);
        NoteVersion target = version(noteId, version);

        String hash = Hashing.sha256Hex(target.getContentMd());
        if (!hash.equals(note.getContentHash())) {
            note.applyContent(target.getContentMd(), hash);
            int next = (int) versions.countByNoteId(noteId) + 1;
            versions.save(new NoteVersion(noteId, next, target.getContentMd()));
            events.publishEvent(NoteChangedEvent.upsert(noteId, note.getTitle()));
        }
        return note;
    }

    private String uniqueSlug(UUID workspaceId, String title) {
        String base = slugify(title);
        if (base.isBlank()) {
            base = "note";
        }
        String candidate = base;
        int suffix = 2;
        while (notes.existsByWorkspaceIdAndSlugAndDeletedAtIsNull(workspaceId, candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static String slugify(String input) {
        if (input == null) {
            return "";
        }
        return Normalizer.normalize(input, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
