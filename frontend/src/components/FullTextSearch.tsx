'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '@/lib/api';
import type { SearchHit } from '@/lib/types';

const PANEL_ID = 'fulltext-search-panel';

interface Props {
  workspaceId: string;
  /** 带上命中的那一条:调用方据此把正文定位到命中的章节/段落(见 lib/locate.ts) */
  onOpenNote: (noteId: string, hit?: SearchHit) => void;
  className?: string;
}

/**
 * 只允许 `<em>` 高亮标签通过:其余 HTML 全部转义,避免笔记内容里的标签被当作 HTML 执行。
 */
function highlightSnippet(raw: string): string {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/&lt;em&gt;/g, '<em>')
    .replace(/&lt;\/em&gt;/g, '</em>');
}

/**
 * 命中片段:高亮标签由后端给(`<em>`),这里只管把它画成高亮 —— 分组行里只显示
 * 分数最高那条,展开后才逐条列。
 */
function Snippet({ html, clamp }: { html: string; clamp?: boolean }) {
  return (
    <span
      className={`mt-1 block text-[11px] leading-snug text-muted [&_em]:rounded [&_em]:bg-accent/25 [&_em]:px-0.5 [&_em]:not-italic [&_em]:text-ink ${
        clamp ? 'line-clamp-2' : ''
      }`}
      dangerouslySetInnerHTML={{ __html: highlightSnippet(html) }}
    />
  );
}

/**
 * 命中按**笔记**归并。
 *
 * 检索返回的单位是分块(chunk):一篇长笔记会被切成很多块,分开列出来一篇就能刷满整屏,
 * 看着像"重复索引",其实只是同一篇的不同段落(见 DESIGN §13.6 第 1 点)。归并后一篇一行、
 * 行内先给分最高的那条,想看全部再展开;每一条都能单独点开。
 *
 * 高分在前(hits 已按 RRF 分数降序),Map 保持插入顺序 —— 组顺序自然就是"最好的那条"的顺序。
 */
interface HitGroup {
  noteId: string;
  title: string;
  hits: SearchHit[];
}

function groupHits(hits: SearchHit[]): HitGroup[] {
  const groups = new Map<string, HitGroup>();
  for (const hit of hits) {
    const existing = groups.get(hit.noteId);
    if (existing) {
      existing.hits.push(hit);
    } else {
      groups.set(hit.noteId, { noteId: hit.noteId, title: hit.noteTitle || '未命名', hits: [hit] });
    }
  }
  return [...groups.values()];
}

/**
 * 顶栏全文检索:输入即搜,结果以浮层挂在输入框下方(不占用左栏的笔记列表)。
 *
 * 关键词之间有 300ms 防抖;请求用闭包里的 `stale` 标记防竞态 —— 后发的查询先返回时,
 * 先发的那次结果会被丢弃,否则列表会"跳回"旧关键词的命中。
 *
 * 浮层必须用 `.glass-pop`(接近不透明):它盖在正文上,用 `.glass` 的透明度会让底下的
 * 字透上来。层叠上下文由 header 的 `relative z-30` 负责(见 globals.css 的说明)。
 */
export default function FullTextSearch({ workspaceId, onOpenNote, className }: Props) {
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 键盘高亮的命中项下标(按笔记分组后的行号) */
  const [active, setActive] = useState(0);
  /** 展开看全部命中的笔记 id */
  const [expanded, setExpanded] = useState<string[]>([]);
  const boxRef = useRef<HTMLDivElement | null>(null);

  const groups = useMemo(() => groupHits(hits), [hits]);

  // 防抖检索:关键词变化后 300ms 才打后端
  useEffect(() => {
    const q = query.trim();
    if (!q) {
      setHits([]);
      setError(null);
      setLoading(false);
      setOpen(false);
      return;
    }

    let stale = false;
    setLoading(true);
    const timer = setTimeout(() => {
      void api
        .search(workspaceId, q)
        .then((result) => {
          if (stale) return;
          setHits(result);
          setActive(0);
          setExpanded([]);
          setError(null);
          setOpen(true);
        })
        .catch((e: unknown) => {
          if (stale) return;
          setHits([]);
          setError(e instanceof Error ? e.message : String(e));
          setOpen(true);
        })
        .finally(() => {
          if (!stale) setLoading(false);
        });
    }, 300);

    return () => {
      stale = true;
      clearTimeout(timer);
    };
  }, [query, workspaceId]);

  // 点浮层外面就收起
  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: MouseEvent) {
      if (boxRef.current && !boxRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [open]);

  function select(hit: SearchHit) {
    onOpenNote(hit.noteId, hit);
    setOpen(false);
    setQuery('');
    setHits([]);
    setExpanded([]);
  }

  function toggleExpanded(noteId: string) {
    setExpanded((list) => (list.includes(noteId) ? list.filter((id) => id !== noteId) : [...list, noteId]));
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      setOpen(false);
      setQuery('');
      return;
    }
    if (!open || groups.length === 0) return;

    const current = groups[Math.min(active, groups.length - 1)];
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((i) => (i + 1) % groups.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((i) => (i - 1 + groups.length) % groups.length);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      select(current.hits[0]);
    } else if (event.key === 'ArrowRight') {
      // 展开后焦点留在输入框里,列表仍然是"键盘可翻"的
      event.preventDefault();
      if (current.hits.length > 1 && !expanded.includes(current.noteId)) toggleExpanded(current.noteId);
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      if (expanded.includes(current.noteId)) toggleExpanded(current.noteId);
    }
  }

  const showPanel = open && query.trim().length > 0;
  const activeId = showPanel && groups.length > 0 ? `${PANEL_ID}-opt-${active}` : undefined;
  const hasGrouped = groups.some((group) => group.hits.length > 1);

  return (
    <div ref={boxRef} className={`relative ${className ?? ''}`}>
      <div className="relative">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => {
            if (query.trim()) setOpen(true);
          }}
          onKeyDown={onKeyDown}
          role="combobox"
          aria-label="全文检索正文"
          aria-expanded={showPanel}
          aria-controls={PANEL_ID}
          aria-activedescendant={activeId}
          aria-autocomplete="list"
          placeholder="全文检索…"
          className="glass-soft w-full rounded-xl py-1.5 pl-7 pr-2.5 text-xs text-ink outline-none transition-colors placeholder:text-faint focus:border-accent/50"
        />
        <span className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-xs text-faint">
          ⌕
        </span>
      </div>

      {showPanel && (
        <div
          id={PANEL_ID}
          role="listbox"
          aria-label="全文检索结果"
          className="glass-pop absolute right-0 top-9 z-50 w-[30rem] max-w-[calc(100vw-2rem)] overflow-hidden rounded-xl p-1.5"
        >
          <div className="max-h-[min(60vh,24rem)] overflow-y-auto">
            {loading && hits.length === 0 && (
              <p className="flex items-center gap-2 px-3 py-3 text-xs text-muted">
                <span className="dot-pulse h-1.5 w-1.5 rounded-full bg-accent" />
                检索中…
              </p>
            )}

            {error && <p className="px-3 py-3 text-xs text-red-300/90">{error}</p>}

            {!loading && !error && hits.length === 0 && (
              <p className="px-3 py-3 text-xs text-faint">没有匹配的内容</p>
            )}

            {groups.map((group, index) => {
              const isExpanded = expanded.includes(group.noteId);
              const best = group.hits[0];
              const isActive = index === active;
              return (
                <div
                  key={group.noteId}
                  className={`rounded-lg transition-colors ${isActive ? 'glass-active' : 'hover:bg-white/5'}`}
                >
                  <div className="flex items-start">
                    <button
                      id={`${PANEL_ID}-opt-${index}`}
                      type="button"
                      role="option"
                      aria-selected={isActive}
                      onMouseEnter={() => setActive(index)}
                      onClick={() => select(best)}
                      className="min-w-0 flex-1 rounded-lg px-3 py-2 text-left"
                    >
                      <span className="flex items-center gap-2">
                        <span className="truncate text-xs font-medium text-ink">{group.title}</span>
                        {group.hits.length > 1 && (
                          <span className="glass-soft shrink-0 rounded-full px-1.5 py-0.5 text-[10px] text-accent">
                            {group.hits.length} 处
                          </span>
                        )}
                      </span>
                      {best.headingPath && (
                        <span className="mt-0.5 block truncate text-[10px] text-faint">
                          {best.headingPath}
                        </span>
                      )}
                      {best.snippet && <Snippet html={best.snippet} clamp />}
                    </button>

                    {/* 展开按钮是那一行的**兄弟**:按钮不能嵌按钮(和删除笔记同一个理由) */}
                    {group.hits.length > 1 && (
                      <button
                        type="button"
                        onClick={() => toggleExpanded(group.noteId)}
                        aria-expanded={isExpanded}
                        aria-label={
                          isExpanded ? `收起「${group.title}」的其余命中` : `展开「${group.title}」的全部命中`
                        }
                        className="mr-1.5 mt-2 shrink-0 rounded-md px-1.5 py-0.5 text-[10px] text-faint transition-colors hover:text-ink"
                      >
                        {isExpanded ? '▴' : '▾'}
                      </button>
                    )}
                  </div>

                  {isExpanded && (
                    <div className="mb-1.5 space-y-0.5 pl-3 pr-2">
                      {group.hits.map((hit, hitIndex) => (
                        <button
                          key={hit.chunkId}
                          type="button"
                          onClick={() => select(hit)}
                          className="glass-soft block w-full rounded-lg px-2.5 py-1.5 text-left transition-all hover:brightness-110"
                        >
                          <span className="block truncate text-[10px] text-faint">
                            {hitIndex + 1}. {hit.headingPath || '正文'}
                          </span>
                          {hit.snippet && <Snippet html={hit.snippet} clamp />}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {groups.length > 0 && (
            <p className="flex items-center gap-2 border-t border-white/10 px-3 pb-1 pt-2 text-[10px] text-faint">
              <span>
                {groups.length} 篇笔记 · {hits.length} 处命中
              </span>
              <span className="ml-auto">
                {hasGrouped ? '↑↓ 选择 · Enter 打开 · ←→ 展开 · Esc 关闭' : '↑↓ 选择 · Enter 打开 · Esc 关闭'}
              </span>
            </p>
          )}
        </div>
      )}
    </div>
  );
}
