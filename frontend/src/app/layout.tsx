import type { Metadata } from 'next';
import './globals.css';
import ThemeProvider from '@/components/ThemeProvider';
import { DEFAULT_THEME, THEME_INIT_SCRIPT } from '@/lib/theme';

export const metadata: Metadata = {
  title: 'ai-notes',
  description: '以本地知识库为长期记忆的 AI Agent',
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN" data-theme={DEFAULT_THEME} suppressHydrationWarning>
      <head>
        {/* 首屏主题必须在首次绘制前写好,否则会先看到默认色再跳到用户选的颜色。
            脚本同步执行于 HTML 解析阶段,所以不会有闪烁。 */}
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
      <body className="h-full antialiased">
        {/* 背景光场:固定在视口底部,纯装饰,不参与交互 */}
        <div className="aurora-field" aria-hidden="true">
          <div className="aurora-blob aurora-blob-1" />
          <div className="aurora-blob aurora-blob-2" />
          <div className="aurora-blob aurora-blob-3" />
        </div>
        <div className="aurora-veil" aria-hidden="true" />

        <ThemeProvider>{children}</ThemeProvider>
      </body>
    </html>
  );
}
