'use client';

import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useMemo,
  useState,
} from 'react';
import { DEFAULT_THEME, applyTheme, persistTheme, readTheme, type ThemeState } from '@/lib/theme';

interface ThemeContextValue extends ThemeState {
  setTheme: (id: string) => void;
  /** hex = 自定义强调色;null = 回到预设 */
  setAccent: (hex: string | null) => void;
  /** hex = 自定义背景色(会被自动压深);null = 回到默认底色 */
  setBg: (hex: string | null) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext);
  if (!value) {
    throw new Error('useTheme 必须在 <ThemeProvider> 内部使用');
  }
  return value;
}

/**
 * 主题状态容器。
 *
 * 首屏主题由 layout 里的内联脚本直接写在 <html> 上(先于首次绘制,所以不闪)。
 * 这里要再做一次的原因有两个:
 * 1. 开发模式下 React Strict Mode 会重挂载组件,并把 <html> 的属性重置为 JSX 里声明的那些,
 *    内联脚本写上去的 `data-theme` / `data-accent` 会被清掉;
 * 2. 运行时的切换需要 React 状态来驱动 UI(选择器里的高亮)。
 *
 * 初始 state 故意用默认值而不是直接读 localStorage:那样服务端与客户端首帧不一致,
 * 会造成 hydration 报错。视觉上的主题由脚本负责,不受这里影响。
 */
export default function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<ThemeState>({ theme: DEFAULT_THEME, accent: null, bg: null });

  useLayoutEffect(() => {
    const stored = readTheme();
    setState(stored);
    applyTheme(stored);
  }, []);

  const update = useCallback((next: ThemeState) => {
    setState(next);
    applyTheme(next);
    persistTheme(next);
  }, []);

  const value = useMemo<ThemeContextValue>(
    () => ({
      ...state,
      // 选预设即清掉自定义强调色,否则"预设"只改了个名字、颜色却还是自定义的,容易误解。
      // 背景色**不动**:预设定义的是"光"(强调色),底色是另一维配置,各有各的复位按钮。
      setTheme: (id: string) => update({ theme: id, accent: null, bg: state.bg }),
      setAccent: (hex: string | null) => update({ ...state, accent: hex }),
      setBg: (hex: string | null) => update({ ...state, bg: hex }),
    }),
    [state, update],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
