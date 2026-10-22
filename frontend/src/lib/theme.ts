/**
 * 主题:预设配色 + 用户自定义强调色。
 *
 * 设计取舍:
 * - 颜色真正生效的地方是 CSS 变量(`--accent` / `--accent-2`),由 `globals.css` 的
 *   `@theme inline` 暴露成 Tailwind 工具类(`bg-accent` / `text-accent` / `border-accent/40`)。
 *   所以换主题只是换一组变量,不需要重新生成 CSS。
 * - 预设只覆盖这两个变量,不碰背景与文字色 —— 否则"换个颜色整个界面就散了"。
 * - 自定义颜色写成 `<html>` 上的**行内**变量:行内样式优先级高于 `html[data-theme]`
 *   选择器,所以自定义必然覆盖预设;清掉行内变量就回到预设。
 * - 副色不做 JS 颜色运算,而是交给 CSS `color-mix`(见 globals.css 的
 *   `html[data-accent="custom"]`)。原因:把函数源码 `toString()` 后塞进内联脚本,
 *   在压缩打包后函数可能被内联/改名,运行时会直接 ReferenceError —— 这种"看起来能用、
 *   上了生产才炸"的写法不值得。
 * - 首屏由 layout 里的内联脚本读 localStorage 并写入,先于首次绘制,所以不会闪。
 */

export const THEME_KEY = 'ai-notes.theme';
/** 自定义强调色(hex);不存在 = 跟随预设 */
export const ACCENT_KEY = 'ai-notes.accent';
/** 自定义背景色(hex,任意颜色);不存在 = 跟随主题默认底色 */
export const BG_KEY = 'ai-notes.bg';

export const DEFAULT_THEME = 'aurora';

export interface ThemePreset {
  id: string;
  name: string;
  hint: string;
  /** 选择器里的色板展示,与 globals.css 中的 oklch 取值视觉一致 */
  swatch: [string, string];
}

export const THEMES: ThemePreset[] = [
  { id: 'aurora', name: '极光', hint: '青 × 紫', swatch: ['#6ad5e8', '#a98bfa'] },
  { id: 'blossom', name: '樱', hint: '粉 × 品红', swatch: ['#f79ac4', '#e29ae8'] },
  { id: 'ember', name: '余烬', hint: '琥珀 × 砖红', swatch: ['#f4b76a', '#e8735a'] },
  { id: 'moss', name: '苔原', hint: '翠 × 黄绿', swatch: ['#6fd9a4', '#c3dd7e'] },
  { id: 'abyss', name: '深海', hint: '靛蓝 × 天青', swatch: ['#7aa6f0', '#6fd0e0'] },
  { id: 'graphite', name: '石墨', hint: '近乎无彩,适合长文', swatch: ['#cfd2d8', '#a0a7b8'] },
];

export function findTheme(id: string | null | undefined): ThemePreset {
  return THEMES.find((t) => t.id === id) ?? THEMES[0];
}

export interface BackgroundPreset {
  name: string;
  value: string;
  hint: string;
}

/**
 * 背景色板。
 *
 * 这些取值都停在"深色区"里:用户自定义背景时颜色会被自动压深
 * (见 globals.css 的 `html[data-bg="custom"]`),而这里的值本来就在该范围内,
 * 所以**色板显示的颜色就是生效的颜色**(深色不会被压缩)。
 */
export const BG_PRESETS: BackgroundPreset[] = [
  { name: '近黑', value: '#14161d', hint: '默认底色的近似值' },
  { name: '靛', value: '#101a2e', hint: '偏冷的深蓝' },
  { name: '绛', value: '#1b1024', hint: '暗紫' },
  { name: '松', value: '#0e1a16', hint: '暗绿' },
  { name: '炭', value: '#191919', hint: '中性灰' },
  { name: '褐', value: '#1c1512', hint: '暖褐' },
];

/** 取色器的兜底值:没设自定义背景时输入框总得显示点什么 */
export const DEFAULT_BG = BG_PRESETS[0].value;

export function isValidHex(value: string): boolean {
  return /^#[0-9a-f]{6}$/i.test(value.trim());
}

export interface ThemeState {
  theme: string;
  /** null = 跟随预设 */
  accent: string | null;
  /** null = 跟随默认底色 */
  bg: string | null;
}

export function readTheme(): ThemeState {
  if (typeof window === 'undefined') {
    return { theme: DEFAULT_THEME, accent: null, bg: null };
  }
  try {
    const theme = window.localStorage.getItem(THEME_KEY) ?? DEFAULT_THEME;
    const accent = window.localStorage.getItem(ACCENT_KEY);
    const bg = window.localStorage.getItem(BG_KEY);
    return {
      theme,
      accent: accent && isValidHex(accent) ? accent : null,
      bg: bg && isValidHex(bg) ? bg : null,
    };
  } catch {
    return { theme: DEFAULT_THEME, accent: null, bg: null };
  }
}

export function persistTheme({ theme, accent, bg }: ThemeState): void {
  try {
    window.localStorage.setItem(THEME_KEY, theme);
    if (accent) window.localStorage.setItem(ACCENT_KEY, accent);
    else window.localStorage.removeItem(ACCENT_KEY);
    if (bg) window.localStorage.setItem(BG_KEY, bg);
    else window.localStorage.removeItem(BG_KEY);
  } catch {
    // localStorage 不可用(隐私模式等):静默降级,本次会话内仍然生效
  }
}

/**
 * 把主题写到 <html> 上。
 *
 * 两个 `data-*` 是给 CSS 用的开关:`data-accent="custom"` 决定副色怎么推、
 * `data-bg="custom"` 决定底色怎么压深。写入的都是**用户选的原值**,
 * 压深/混色全部交给 CSS `color-mix` —— 这样"派生规则"只有一处,
 * 首屏内联脚本也只需要照抄一行赋值,不需要第二份颜色运算。
 */
export function applyTheme({ theme, accent, bg }: ThemeState): void {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  root.setAttribute('data-theme', theme);
  if (accent && isValidHex(accent)) {
    root.setAttribute('data-accent', 'custom');
    root.style.setProperty('--accent', accent);
  } else {
    root.removeAttribute('data-accent');
    root.style.removeProperty('--accent');
  }
  if (bg && isValidHex(bg)) {
    root.setAttribute('data-bg', 'custom');
    root.style.setProperty('--bg-pick', bg);
  } else {
    root.removeAttribute('data-bg');
    root.style.removeProperty('--bg-pick');
  }
}

/**
 * 首屏内联脚本:在 HTML 解析阶段(首次绘制之前)写入主题,避免"先亮后彩"的闪烁。
 * 与 `readTheme` 读同一份 localStorage,所以两边永远一致。
 */
export const THEME_INIT_SCRIPT = `(function(){try{
var r=document.documentElement;
r.setAttribute('data-theme',localStorage.getItem(${JSON.stringify(THEME_KEY)})||${JSON.stringify(DEFAULT_THEME)});
var a=localStorage.getItem(${JSON.stringify(ACCENT_KEY)});
if(a&&/^#[0-9a-f]{6}$/i.test(a)){r.setAttribute('data-accent','custom');r.style.setProperty('--accent',a);}
var b=localStorage.getItem(${JSON.stringify(BG_KEY)});
if(b&&/^#[0-9a-f]{6}$/i.test(b)){r.setAttribute('data-bg','custom');r.style.setProperty('--bg-pick',b);}
}catch(e){}})()`;
