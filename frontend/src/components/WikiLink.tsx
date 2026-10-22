'use client';

import { createContext, useContext, type ComponentPropsWithoutRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import type { WikiResolution } from '@/lib/types';

/**
 * 解析表的状态。**必须区分"未创建"和"还不知道"** —— 后端没这个接口(或请求还没回来)时,
 * 解析表里查不到任何标题;这时把链接当成"未创建"会诱导用户点一下,而点一下就是
 * **凭空多出一篇重复笔记**。所以"查不到"只有在 `ready` 时才等于"未创建"。
 */
export type WikiResolveState = 'pending' | 'ready' | 'failed';

/** 链接的三种状态:已挂到笔记 / 确认未创建(可建) / 还不知道(不可建) */
export type WikiLinkState = 'linked' | 'missing' | 'unknown';

export function wikiLinkState(
  resolved: WikiResolution | undefined,
  resolveState: WikiResolveState,
): WikiLinkState {
  if (resolved) return resolved.noteId ? 'linked' : 'missing';
  return resolveState === 'ready' ? 'missing' : 'unknown';
}

/**
 * 预览里 `[[双链]]` 的"宿主环境":解析表、点击、悬停都由 `MarkdownEditor` 提供。
 *
 * 用 Context 而不是 props,是为了让 `components={{ a: WikiAnchor }}` 能保持**模块级常量**
 * —— 每次渲染新建一个对象会让 react-markdown 重建整条 markdown 处理管线。
 */
export interface WikiLinkHost {
  /** 标题 → 解析结果;查不到 = 未创建(`ready` 时)或还没解析出来 */
  resolve: (target: string) => WikiResolution | undefined;
  /** 解析表可不可信 —— 不可信时**不许**凭"查不到"建笔记 */
  resolveState: WikiResolveState;
  /** 点击:已创建就跳过去(带 `#小节` 时定位到那一节),没创建就地建一篇 */
  open: (target: string, heading: string | null) => void;
  /** 鼠标停在链接上:anchor 是链接的视口矩形,卡片据此定位 */
  hover: (target: string, anchor: DOMRect) => void;
  leave: () => void;
}

const WikiLinkContext = createContext<WikiLinkHost | null>(null);

export function WikiLinkProvider({
  host,
  children,
}: {
  host: WikiLinkHost | null;
  children: ReactNode;
}) {
  return <WikiLinkContext.Provider value={host}>{children}</WikiLinkContext.Provider>;
}

/**
 * 预览里的 `[[双链]]`。由 `remarkWikiLink` 造成 `<a data-wiki="…">`,这里接管成真正的行为。
 *
 * 普通 markdown 链接(`[文字](url)`)也会走到这个组件(react-markdown 按标签名分发),
 * 判断依据就是有没有 `data-wiki` —— 没有就原样透传。
 */
export function WikiAnchor(props: AnchorProps) {
  const host = useContext(WikiLinkContext);
  const wiki = props as WikiDataProps;
  const target = typeof wiki['data-wiki'] === 'string' ? wiki['data-wiki'] : null;
  if (!target || !host) {
    return <a {...domProps(props)}>{props.children}</a>;
  }

  const heading =
    typeof wiki['data-wiki-heading'] === 'string' ? wiki['data-wiki-heading'] : '';
  const state = wikiLinkState(host.resolve(target), host.resolveState);
  const className =
    state === 'linked'
      ? 'wiki-link'
      : state === 'missing'
        ? 'wiki-link wiki-link--missing'
        : 'wiki-link wiki-link--unknown';

  return (
    <a
      {...domProps(props)}
      className={className}
      role="link"
      tabIndex={0}
      onClick={(event) => {
        event.preventDefault();
        host.open(target, heading || null);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          host.open(target, heading || null);
        }
      }}
      onMouseEnter={(event) => host.hover(target, event.currentTarget.getBoundingClientRect())}
      onMouseLeave={() => host.leave()}
    >
      {props.children}
    </a>
  );
}

export interface WikiHoverState {
  x: number;
  y: number;
  target: string;
}

/**
 * 悬停卡片。**portal 到 body**:预览区是 `overflow-y: auto`,挂在里面会被裁掉
 * (而且 `overflow` 一旦不是 visible,另一个方向也会跟着变成 auto)。
 */
export function WikiHoverCard({
  state,
  resolved,
  resolveState,
}: {
  state: WikiHoverState | null;
  resolved: WikiResolution | undefined;
  resolveState: WikiResolveState;
}) {
  if (!state) {
    return null;
  }
  const linkState = wikiLinkState(resolved, resolveState);
  return createPortal(
    <div
      className="glass-pop pointer-events-none fixed z-[60] w-72 rounded-xl px-3 py-2.5 text-xs"
      style={{ left: state.x, top: state.y }}
    >
      <div className="flex items-center gap-1.5">
        <span className="truncate font-medium text-ink">{resolved?.noteTitle ?? state.target}</span>
        {linkState === 'missing' ? (
          <span className="shrink-0 rounded-full bg-accent/20 px-1.5 py-px text-[10px] text-accent">
            未创建
          </span>
        ) : null}
        {linkState === 'unknown' ? (
          <span className="glass-soft shrink-0 rounded-full px-1.5 py-px text-[10px] text-faint">
            解析中
          </span>
        ) : null}
      </div>
      <p className="mt-1.5 line-clamp-4 leading-relaxed text-muted">
        {resolved?.snippet
          ? resolved.snippet
          : linkState === 'missing'
            ? '点击创建这篇笔记'
            : linkState === 'unknown' && resolveState === 'failed'
              ? '解析不了这篇链接(后端可能没重启,缺 /links/resolve)'
              : '正在解析这篇链接…'}
      </p>
    </div>,
    document.body,
  );
}

/** react-markdown 传进来的 props(和它的 `Components` 类型一致)+ 我们塞在 data 上的两个字段 */
type AnchorProps = ComponentPropsWithoutRef<'a'> & { node?: unknown };

type WikiDataProps = { 'data-wiki'?: unknown; 'data-wiki-heading'?: unknown };

/**
 * react-markdown 会往组件里塞 `node`(hast 元素),不能原样落到 DOM 上(React 会就未知属性
 * 报警)。`data-wiki` / `data-wiki-heading` 则**保留**:它们是合法的 data 属性,留在 DOM 上
 * 等于把"这个链接指向哪儿"写在明面上 —— 排查和自动化检查都用得上。
 */
function domProps(props: AnchorProps): ComponentPropsWithoutRef<'a'> {
  const rest: Record<string, unknown> = { ...props };
  delete rest.children;
  delete rest.node;
  return rest as ComponentPropsWithoutRef<'a'>;
}
