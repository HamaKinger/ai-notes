'use client';

import { useEffect, useRef, useState } from 'react';
import { useTheme } from '@/components/ThemeProvider';
import { BG_PRESETS, DEFAULT_BG, THEMES, findTheme } from '@/lib/theme';

/** 主题选择器:预设色板 + 自定义强调色 + 自定义背景色。 */
export default function ThemeMenu() {
  const { theme, accent, bg, setTheme, setAccent, setBg } = useTheme();
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLDivElement | null>(null);

  const preset = findTheme(theme);

  // 点外面 / 按 Esc 关掉
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  return (
    <div ref={boxRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="dialog"
        aria-expanded={open}
        title="主题颜色"
        className="glass-soft sheen flex h-7 items-center gap-1.5 rounded-full px-2.5 text-[11px] text-muted hover:text-ink"
      >
        <span
          className="h-3 w-3 rounded-full ring-1 ring-white/25"
          style={{ background: `linear-gradient(135deg, ${accent ?? preset.swatch[0]}, ${preset.swatch[1]})` }}
        />
        <span className="hidden sm:inline">{accent ? '自定义' : preset.name}</span>
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="主题颜色"
          className="glass-pop absolute right-0 top-9 z-50 w-60 rounded-2xl p-3 text-xs"
        >
          <p className="mb-2 px-1 text-[10px] font-semibold uppercase tracking-widest text-faint">
            预设
          </p>
          <div className="grid grid-cols-3 gap-2">
            {THEMES.map((item) => {
              const active = !accent && item.id === theme;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setTheme(item.id)}
                  title={item.hint}
                  className={`glass-hover flex flex-col items-center gap-1.5 rounded-xl px-2 py-2 ${
                    active ? 'glass-active' : ''
                  }`}
                >
                  <span
                    className="h-6 w-6 rounded-full ring-1 ring-white/20"
                    style={{
                      background: `linear-gradient(135deg, ${item.swatch[0]}, ${item.swatch[1]})`,
                    }}
                  />
                  <span className={active ? 'text-ink' : 'text-muted'}>{item.name}</span>
                </button>
              );
            })}
          </div>

          <div className="my-3 h-px bg-white/10" />

          <p className="mb-2 px-1 text-[10px] font-semibold uppercase tracking-widest text-faint">
            自定义强调色
          </p>
          <div className="flex items-center gap-2">
            <input
              type="color"
              aria-label="自定义强调色"
              value={accent ?? preset.swatch[0]}
              onChange={(event) => setAccent(event.target.value)}
              className="h-8 w-10 cursor-pointer rounded-lg border border-white/15 bg-transparent p-0.5"
            />
            <button
              type="button"
              onClick={() => setAccent(null)}
              disabled={!accent}
              className="glass-soft flex-1 rounded-lg px-2 py-1.5 text-muted hover:text-ink disabled:opacity-40"
            >
              跟随预设
            </button>
          </div>
          <p className="mt-2 px-1 text-[10px] leading-relaxed text-faint">
            渐变副色会由你的颜色自动混合产生。设置会存在本机浏览器里。
          </p>

          <div className="my-3 h-px bg-white/10" />

          <p className="mb-2 px-1 text-[10px] font-semibold uppercase tracking-widest text-faint">
            背景色
          </p>
          <div className="grid grid-cols-6 gap-1.5">
            {BG_PRESETS.map((item) => {
              const active = bg === item.value;
              return (
                <button
                  key={item.name}
                  type="button"
                  onClick={() => setBg(item.value)}
                  title={`${item.name} · ${item.hint}`}
                  aria-label={`背景 ${item.name}`}
                  className={`flex h-7 items-center justify-center rounded-lg ring-1 transition-all ${
                    active ? 'ring-accent/70' : 'ring-white/10 hover:ring-white/30'
                  }`}
                >
                  <span className="h-3.5 w-3.5 rounded-full" style={{ background: item.value }} />
                </button>
              );
            })}
          </div>

          <div className="mt-2 flex items-center gap-2">
            <input
              type="color"
              aria-label="自定义背景色"
              value={bg ?? DEFAULT_BG}
              onChange={(event) => setBg(event.target.value)}
              className="h-8 w-10 cursor-pointer rounded-lg border border-white/15 bg-transparent p-0.5"
            />
            <button
              type="button"
              onClick={() => setBg(null)}
              disabled={!bg}
              className="glass-soft flex-1 rounded-lg px-2 py-1.5 text-muted hover:text-ink disabled:opacity-40"
            >
              默认底色
            </button>
          </div>
          <p className="mt-2 flex items-start gap-1.5 px-1 text-[10px] leading-relaxed text-faint">
            {/* 直接读 --bg-deep:颜色一变这个点就跟着变,不需要 JS 读计算样式 */}
            <span
              aria-hidden
              className="mt-0.5 inline-block h-2.5 w-2.5 shrink-0 rounded-full ring-1 ring-white/20"
              style={{ background: 'var(--bg-deep)' }}
            />
            <span>左侧圆点是实际生效的底色。偏亮的颜色会被自动压深(保留色相),以保住文字对比度。</span>
          </p>
        </div>
      )}
    </div>
  );
}
