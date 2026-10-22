'use client';

import { useState } from 'react';
import { indexStatusLabel, indexStatusTone } from '@/lib/labels';
import type { Note, TagCount } from '@/lib/types';

interface Props {
  notes: Note[];
  tags: TagCount[];
  activeTag: string | null;
  loading: boolean;
  currentId: string | null;
  query: string;
  onQueryChange: (value: string) => void;
  onTagChange: (tag: string | null) => void;
  onOpenNote: (noteId: string) => void;
  onCreate: () => void;
  /** 删除笔记;失败要抛出,好让二次确认框留在原地 */
  onDelete: (note: Note) => Promise<void>;
}

const TONE_CLASS: Record<string, string> = {
  ok: 'text-accent',
  warn: 'text-amber-300/90',
  bad: 'text-red-300/90',
  idle: 'text-faint',
};

/**
 * 左栏:标题过滤 + 标签 + 笔记列表。
 *
 * 全文检索不在这里 —— 它是顶栏的 `FullTextSearch`(带结果浮层)。左栏只做"按标题过滤
 * 当前工作区的笔记",所以没有"标题 / 全文"的模式切换:两种检索的输入位置和结果呈现
 * 完全不同,硬塞进同一个输入框只会让两边都别扭。
 */
export default function NoteList({
  notes,
  tags,
  activeTag,
  loading,
  currentId,
  query,
  onQueryChange,
  onTagChange,
  onOpenNote,
  onCreate,
  onDelete,
}: Props) {
  const empty = notes.length === 0;
  /** 正在二次确认删除的笔记 id */
  const [confirmId, setConfirmId] = useState<string | null>(null);
  /** 已发出删除请求、等后端返回的笔记 id */
  const [deletingId, setDeletingId] = useState<string | null>(null);

  async function confirmDelete(note: Note) {
    setDeletingId(note.id);
    try {
      await onDelete(note);
      setConfirmId(null);
    } catch {
      // 失败时保留确认框,错误由父级展示在编辑区
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <aside className="glass flex w-80 shrink-0 flex-col border-y-0 border-l-0">
      <div className="flex flex-col gap-2.5 border-b border-white/10 p-3">
        <div className="flex items-center gap-2">
          <div className="relative min-w-0 flex-1">
            <input
              value={query}
              onChange={(e) => onQueryChange(e.target.value)}
              placeholder="按标题过滤…"
              aria-label="按标题过滤笔记"
              className="glass-soft w-full rounded-xl py-2 pl-8 pr-2.5 text-sm text-ink outline-none transition-colors placeholder:text-faint focus:border-accent/50"
            />
            <span className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-sm text-faint">
              ⌕
            </span>
          </div>
          <button
            type="button"
            onClick={onCreate}
            title="新建笔记"
            className="btn-accent sheen grid h-9 w-9 shrink-0 place-items-center rounded-xl text-lg leading-none"
          >
            +
          </button>
        </div>

        {tags.length > 0 && (
          <div className="flex max-h-24 flex-wrap gap-1 overflow-y-auto">
            {activeTag && (
              <button
                type="button"
                onClick={() => onTagChange(null)}
                title="清除标签筛选"
                className="rounded-full border border-accent/50 bg-accent/15 px-2 py-0.5 text-[11px] text-accent"
              >
                #{activeTag} ✕
              </button>
            )}
            {tags
              .filter((tag) => tag.name !== activeTag)
              .map((tag) => (
                <button
                  key={tag.name}
                  type="button"
                  onClick={() => onTagChange(tag.name)}
                  className="glass-soft rounded-full px-2 py-0.5 text-[11px] text-muted transition-colors hover:text-ink"
                >
                  #{tag.name}
                  <span className="ml-1 text-faint">{tag.count}</span>
                </button>
              ))}
          </div>
        )}
      </div>

      <ul className="flex-1 space-y-1 overflow-y-auto p-2">
        {loading && empty && (
          <li className="flex items-center gap-2 px-3 py-5 text-sm text-muted">
            <span className="dot-pulse h-1.5 w-1.5 rounded-full bg-accent" />
            加载中…
          </li>
        )}

        {!loading && empty && (
          <li className="px-3 py-5 text-sm leading-relaxed text-faint">
            {query.trim() ? '没有标题匹配的笔记' : '还没有笔记,点右上角 + 新建'}
          </li>
        )}

        {notes.map((note) => {
          const active = note.id === currentId;
          const label = note.title || '未命名';

          if (confirmId === note.id) {
            return (
              <li key={note.id}>
                <div className="glass-soft rounded-xl border border-red-400/25 px-3 py-2">
                  <p className="truncate text-sm text-ink">删除「{label}」?</p>
                  <p className="mt-1 text-[10px] leading-relaxed text-faint">
                    界面与检索里立刻消失,历史版本仍留在数据库。
                  </p>
                  <div className="mt-2 flex items-center gap-2">
                    <button
                      type="button"
                      disabled={deletingId === note.id}
                      onClick={() => void confirmDelete(note)}
                      className="sheen rounded-lg border border-red-400/40 bg-red-500/20 px-2.5 py-1 text-[11px] font-medium text-red-100 transition-colors hover:bg-red-500/30 disabled:opacity-50"
                    >
                      {deletingId === note.id ? '删除中…' : '删除'}
                    </button>
                    <button
                      type="button"
                      disabled={deletingId === note.id}
                      onClick={() => setConfirmId(null)}
                      className="rounded-lg px-2.5 py-1 text-[11px] text-muted transition-colors hover:text-ink disabled:opacity-50"
                    >
                      取消
                    </button>
                  </div>
                </div>
              </li>
            );
          }

          return (
            <li key={note.id}>
              {/* 删除键是主按钮的兄弟节点而不是子节点 —— <button> 里不能再套 <button> */}
              <div className="group relative">
                <button
                  type="button"
                  onClick={() => onOpenNote(note.id)}
                  className={`w-full rounded-xl px-3 py-2 pr-9 text-left transition-all ${
                    active ? 'glass-active' : 'glass-soft'
                  }`}
                >
                  <span
                    className={`block truncate text-sm font-medium ${active ? 'text-ink' : 'text-ink/85'}`}
                  >
                    {label}
                  </span>
                  <span className="mt-1 flex items-center gap-2 text-[10px] text-faint">
                    <span>{new Date(note.updatedAt).toLocaleString()}</span>
                    <span
                      className={`flex items-center gap-1 ${TONE_CLASS[indexStatusTone(note.indexStatus)]}`}
                    >
                      <span className="h-1 w-1 rounded-full bg-current" />
                      {indexStatusLabel(note.indexStatus)}
                    </span>
                  </span>
                </button>
                <button
                  type="button"
                  title="删除笔记"
                  aria-label={`删除 ${label}`}
                  onClick={() => setConfirmId(note.id)}
                  className="absolute right-1.5 top-1.5 grid h-6 w-6 place-items-center rounded-lg text-xs text-faint opacity-0 transition-all hover:bg-red-500/20 hover:text-red-200 focus-visible:opacity-100 group-hover:opacity-100"
                >
                  ✕
                </button>
              </div>
            </li>
          );
        })}
      </ul>
    </aside>
  );
}
