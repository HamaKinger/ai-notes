'use client';

import { useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { NoteRelations } from '@/lib/types';

interface Props {
  noteId: string;
  /** 每次保存成功后自增;后端是异步重建关系,所以刷新会稍作延迟 */
  refreshKey: number;
  onOpenNote: (noteId: string) => void;
  onCreateNote: (title: string) => void;
  onSelectTag: (tag: string) => void;
}

/** 关系面板:本篇的标签 / 出链 / 反链。 */
export default function RelationsPanel({
  noteId,
  refreshKey,
  onOpenNote,
  onCreateNote,
  onSelectTag,
}: Props) {
  const [relations, setRelations] = useState<NoteRelations | null>(null);
  const [open, setOpen] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const next = await api.relations(noteId);
        if (active) {
          setRelations(next);
          setError(null);
        }
      } catch (e) {
        if (active) setError(e instanceof ApiError ? e.message : String(e));
      }
    })();
    return () => {
      active = false;
    };
  }, [noteId, refreshKey]);

  const tags = relations?.tags ?? [];
  const outgoing = relations?.outgoing ?? [];
  const backlinks = relations?.backlinks ?? [];

  return (
    <div className="border-t border-white/10 text-xs">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2 px-4 py-2 text-left transition-colors hover:bg-white/5"
      >
        <span className="text-[10px] font-semibold uppercase tracking-[0.18em] text-faint">
          关系
        </span>
        <span className="flex items-center gap-2 text-faint">
          <span className="glass-soft rounded-full px-2 py-0.5">
            {outgoing.length} 出链
          </span>
          <span className="glass-soft rounded-full px-2 py-0.5">
            {backlinks.length} 反链
          </span>
        </span>
        <span className="ml-auto text-faint">{open ? '收起' : '展开'}</span>
      </button>

      {open && (
        <div className="max-h-60 space-y-3 overflow-y-auto px-4 pb-3.5">
          {error && <p className="text-red-300">{error}</p>}

          {tags.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {tags.map((tag) => (
                <button
                  key={tag}
                  type="button"
                  onClick={() => onSelectTag(tag)}
                  title={`筛选 #${tag}`}
                  className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[11px] text-muted transition-all hover:border-accent/50 hover:bg-accent/15 hover:text-accent"
                >
                  #{tag}
                </button>
              ))}
            </div>
          )}

          <section>
            <h4 className="mb-1.5 text-[10px] uppercase tracking-[0.18em] text-faint">出链</h4>
            {outgoing.length === 0 ? (
              <p className="text-faint">还没有 {'[[ 链接 ]]'}</p>
            ) : (
              <ul className="space-y-1">
                {outgoing.map((link, index) => (
                  <li key={`${link.title}-${index}`} className="flex items-center gap-2">
                    {link.noteId ? (
                      <button
                        type="button"
                        onClick={() => onOpenNote(link.noteId as string)}
                        className="truncate text-left text-muted transition-colors hover:text-accent"
                      >
                        → {link.title}
                      </button>
                    ) : (
                      <>
                        <span className="truncate text-faint">→ {link.title}</span>
                        <button
                          type="button"
                          onClick={() => onCreateNote(link.title)}
                          title="创建这篇笔记"
                          className="glass-soft shrink-0 rounded-full px-2 py-0.5 text-[10px] text-muted transition-colors hover:text-ink"
                        >
                          创建
                        </button>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section>
            <h4 className="mb-1.5 text-[10px] uppercase tracking-[0.18em] text-faint">反链</h4>
            {backlinks.length === 0 ? (
              <p className="text-faint">暂无其它笔记引用本篇</p>
            ) : (
              <ul className="space-y-1.5">
                {backlinks.map((link, index) => (
                  <li key={`${link.noteId}-${index}`}>
                    <button
                      type="button"
                      onClick={() => link.noteId && onOpenNote(link.noteId)}
                      className="block w-full truncate text-left text-muted transition-colors hover:text-accent"
                    >
                      {link.title}
                    </button>
                    {link.context && (
                      <p className="truncate text-[10px] text-faint">{link.context}</p>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
