'use client';

import type { RefObject } from 'react';

export type FindScope = 'edit' | 'preview';

interface Props {
  inputRef: RefObject<HTMLInputElement | null>;
  query: string;
  onQueryChange: (value: string) => void;
  scope: FindScope;
  onScopeChange: (scope: FindScope) => void;
  /** 只有分栏模式才让切作用域:单栏时另一栏根本不存在 */
  scopeSwitcher: boolean;
  count: number;
  index: number;
  onPrev: () => void;
  onNext: () => void;
  onClose: () => void;
}

const SCOPE_LABEL: Record<FindScope, string> = { edit: '编辑', preview: '预览' };

/**
 * 编辑器/预览区的查找条(`MarkdownEditor` 内部的 Ctrl+F)。
 *
 * 定位在 `.glass-pop` + `z-30`:它浮在正文之上、盖住 header 下方的区域,
 * 用 `.glass` 的透明度会让底下的字透上来(理由见 globals.css)。
 *
 * Enter / Shift+Enter 在这里处理(输入框有焦点时);**离开输入框之后**还能用的
 * 只有 F3 / Shift+F3 —— 那是编辑器留的"手不用离开键盘"的后路,所以在
 * `MarkdownEditor` 的 document 监听里,不在这里。
 */
export default function FindBar({
  inputRef,
  query,
  onQueryChange,
  scope,
  onScopeChange,
  scopeSwitcher,
  count,
  index,
  onPrev,
  onNext,
  onClose,
}: Props) {
  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (event.shiftKey) onPrev();
      else onNext();
    }
  }

  const counter = count === 0 ? (query ? '无匹配' : '—') : `${index + 1} / ${count}`;

  return (
    <div
      role="search"
      className="glass-pop absolute right-4 top-14 z-30 flex items-center gap-1 rounded-xl py-1.5 pl-2.5 pr-1.5"
    >
      <input
        ref={inputRef}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        onKeyDown={onKeyDown}
        autoFocus
        spellCheck={false}
        aria-label="在当前区域查找"
        placeholder="当前区域查找…"
        className="w-40 bg-transparent text-xs text-ink outline-none placeholder:text-faint"
      />

      <span
        className={`w-14 text-center text-[10px] tabular-nums ${count === 0 && query ? 'text-amber-300/90' : 'text-faint'}`}
      >
        {counter}
      </span>

      <button
        type="button"
        onClick={onPrev}
        disabled={count === 0}
        aria-label="上一处"
        className="glass-soft rounded-md px-1.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink disabled:opacity-40"
      >
        ↑
      </button>
      <button
        type="button"
        onClick={onNext}
        disabled={count === 0}
        aria-label="下一处"
        className="glass-soft rounded-md px-1.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink disabled:opacity-40"
      >
        ↓
      </button>

      {scopeSwitcher && (
        <div className="glass-soft ml-0.5 flex rounded-full p-0.5 text-[10px]">
          {(['edit', 'preview'] as FindScope[]).map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => onScopeChange(s)}
              className={`rounded-full px-2 py-0.5 transition-all ${
                scope === s ? 'glass-active text-ink' : 'text-muted hover:text-ink'
              }`}
            >
              {SCOPE_LABEL[s]}
            </button>
          ))}
        </div>
      )}

      <button
        type="button"
        onClick={onClose}
        aria-label="关闭查找"
        className="glass-soft ml-0.5 rounded-md px-1.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink"
      >
        ✕
      </button>
    </div>
  );
}
