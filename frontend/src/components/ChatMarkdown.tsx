'use client';

import { useMemo } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ChatCitation } from '@/lib/types';

/**
 * `remark-gfm`:回答里常有表格/删除线/任务列表,这些都不在 CommonMark 本体里。
 * 提成模块级常量 —— 每次渲染传新数组会让 react-markdown 重建处理管线。
 */
const REMARK_PLUGINS = [remarkGfm];

/** 代码片段(`` ` `` / ``` ``` ```):这些地方不能改,否则会把示例代码里的 `[1]` 变成链接 */
const CODE_SPANS = /(`{1,3}[\s\S]*?`{1,3})/g;

interface Props {
  content: string;
  citations: ChatCitation[];
  onOpenCitation: (citation: ChatCitation) => void;
}

/** 引用 chip / 悬浮提示上的文字:`笔记标题 › 章节路径` */
export function citationLabel(citation: ChatCitation): string {
  const title = citation.noteTitle?.trim() || '未命名笔记';
  // headingPath 的第一段往往就是笔记的一级标题(等于标题),`标题 › 标题` 只是重复;
  // 去掉它,chip 上留下的才是真正有用的章节层级
  const segments = (citation.headingPath ?? '')
    .split('/')
    .map((part) => part.trim())
    .filter(Boolean);
  if (segments.length > 0 && segments[0].toLowerCase() === title.toLowerCase()) {
    segments.shift();
  }
  return segments.length > 0 ? `${title} › ${segments.join(' / ')}` : title;
}

/**
 * 把正文里的 `[1]`、`[2]` 变成可点的站内链接(`#cite-1`)。
 *
 * <p>为什么走 markdown 链接而不是渲染后再改 DOM:预览区的教训(见 DESIGN §13.6)——
 * react-markdown 的输出归 React 管,事后插节点会在下一次重渲染时消失。改**输入文本**则
 * 每次渲染都稳定。只在编号确实出现在引用列表里时才改:模型若自己写了 `[3]` 而只有两段依据,
 * 那多半是它编的,不该给一个点了没反应的链接。
 */
export function withCitationLinks(content: string, citations: ChatCitation[]): string {
  if (!content || citations.length === 0) {
    return content;
  }
  const known = new Set(citations.map((citation) => citation.index));
  return content
    .split(CODE_SPANS)
    .map((part, index) =>
      // 奇数下标是"被捕获的代码片段",原样保留
      index % 2 === 1
        ? part
        : part.replace(/\[(\d{1,2})\]/g, (whole, digits: string) =>
            known.has(Number(digits)) ? `[${digits}](#cite-${digits})` : whole,
          ),
    )
    .join('');
}

/**
 * 助手回答的 Markdown 渲染。
 *
 * 排版直接复用预览那套 `.markdown-body`(代码块/表格/引用的样式一份就够),
 * 外面再叠一个 `.markdown-chat` 把字号和间距收紧(侧栏只有 20rem 宽)。
 */
export default function ChatMarkdown({ content, citations, onOpenCitation }: Props) {
  const components = useMemo<Components>(
    () => ({
      a: ({ href, children }) => {
        const index = href && href.startsWith('#cite-') ? Number(href.slice(6)) : null;
        const citation = index == null ? undefined : citations.find((item) => item.index === index);
        if (citation) {
          return (
            <button
              type="button"
              onClick={() => onOpenCitation(citation)}
              title={`跳到《${citationLabel(citation)}》`}
              className="mx-0.5 rounded bg-accent/20 px-1 text-[10px] font-medium text-accent transition-colors hover:bg-accent/35"
            >
              {children}
            </button>
          );
        }
        // 模型给的其它链接:一律新窗口打开,不把面板导航走
        return (
          <a
            href={href}
            target="_blank"
            rel="noreferrer"
            className="text-accent underline decoration-dotted underline-offset-2"
          >
            {children}
          </a>
        );
      },
    }),
    [citations, onOpenCitation],
  );

  return (
    <div className="markdown-body markdown-chat">
      <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={components}>
        {withCitationLinks(content, citations)}
      </ReactMarkdown>
    </div>
  );
}
