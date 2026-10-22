'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import FindBar, { type FindScope } from './FindBar';
import AiTextTools, { type EditorSelection } from './AiTextTools';
import {
  WikiHoverCard,
  WikiLinkProvider,
  WikiAnchor,
  type WikiHoverState,
  type WikiLinkHost,
  type WikiResolveState,
} from './WikiLink';
import {
  anchorTerms,
  findHeadingPath,
  findTextRange,
  parseHeadingPath,
  type LocateTarget,
} from '@/lib/locate';
import { remarkWikiLink } from '@/lib/wikilink';
import type { WikiResolution } from '@/lib/types';
import {
  collectTextRanges,
  findMatches,
  highlightCtor,
  highlightRegistry,
  type FindMatch,
} from '@/lib/find';
import { indexStatusLabel, indexStatusTone } from '@/lib/labels';

/**
 * 预览用的 remark 插件。
 *
 * `remark-gfm` 打开 GFM 扩展:表格、删除线、任务列表、自动链接 —— 这些**都不在 CommonMark
 * 本体里**,不挂它,表格语法就只是几行带竖线的普通段落(渲染成一堆 `| 项目 | 内容 |`)。
 *
 * `remarkWikiLink` 把 `[[标题]]` 变成可点的节点(预览渲染见 `WikiLink.tsx`)。
 *
 * 提成模块级常量:每次渲染都传新数组会让 react-markdown 重建 markdown 处理管线。
 */
const REMARK_PLUGINS = [remarkGfm, remarkWikiLink];

/**
 * 预览的组件映射。**必须是模块级常量** —— 每次渲染新建对象会让 react-markdown
 * 重建 markdown 处理管线。需要的数据(双链解析表、点击回调)走 Context,不走 props。
 */
const PREVIEW_COMPONENTS: Components = { a: WikiAnchor };

interface Props {
  /**
   * 当前笔记 id。只用来判断"换笔记了":换了就按 `defaultMode` 复位视图 ——
   * 不重挂载编辑器,否则定位高亮、查找栏这些都会跟着重置。
   */
  noteId: string;
  title: string;
  content: string;
  status: string;
  indexStatus?: string;
  onTitleChange: (value: string) => void;
  onContentChange: (value: string) => void;
  onOpenHistory?: () => void;
  /** 输入 `[[` 时用当前前缀做标题联想 */
  onLinkSearch?: (prefix: string) => Promise<{ id: string; title: string }[]>;
  /**
   * 从全文检索点进来时的定位目标(章节路径 + 命中词)。只有 `noteId` 与当前笔记一致时
   * 才该传进来 —— 否则会在旧内容上跳一次。定位只做一次,见下面那个 effect。
   */
  locate?: LocateTarget | null;
  /** 正文里 `[[标题]]` 的解析结果(小写标题为键);没有的键就是"未创建" */
  wikiLinks?: Record<string, WikiResolution>;
  /** 解析表的状态:不可信(pending/failed)时,链接一律不许点出"新建" */
  wikiResolveState?: WikiResolveState;
  /** 点预览里的 `[[双链]]` */
  onOpenWikiLink?: (target: string, heading: string | null) => void;
  /**
   * 打开这一篇时的默认视图:新建的笔记给 `split`(边写边看),已有的给 `preview`(先读)。
   * 只在"换笔记"那一刻取用(见上面的渲染期复位),用户手点过的视图不会被打断。
   */
  defaultMode?: Mode;
  /** 摘要已保存(来自 AI 文本工具):外层只需更新 current.summary */
  onSummarySaved?: (summary: string) => void;
  /** 编辑器状态栏文案:AI 的"已替换 / 已写入"这类提示也走这条 */
  onStatus?: (message: string) => void;
  /** 工作区里已有的标签,传给「推荐标签」以便复用 */
  existingTags?: string[];
}

export type Mode = 'edit' | 'split' | 'preview';

interface LinkQuery {
  /** `[[` 在正文里的起始下标 */
  start: number;
  /** 当前光标位置 */
  caret: number;
  prefix: string;
}

const TONE_CLASS: Record<string, string> = {
  ok: 'text-accent',
  warn: 'text-amber-300/90',
  bad: 'text-red-300/90',
  idle: 'text-faint',
};

/** CSS Custom Highlight API 里的两个名字,和 globals.css 的 `::highlight()` 选择器一一对应 */
const FIND_ALL = 'note-find';
const FIND_CURRENT = 'note-find-current';
/** 全文检索跳转时的高亮:和查找分开,好让它自己淡出而不影响查找的选中项 */
const LOCATE = 'note-locate';
/** 跳转高亮停留时长:它是"你看,在这儿"的提示,不是第二套查找 */
const LOCATE_FLASH_MS = 2600;

/** 单栏模式没有可切的目标:把作用域钉死在当前能看见的那一栏 */
function clampScope(scope: FindScope, mode: Mode): FindScope {
  if (mode === 'edit') return 'edit';
  if (mode === 'preview') return 'preview';
  return scope;
}

/** 把一个元素整体包成 Range(标题定位用;拿不到文本就返回 null) */
function contentsRange(element: HTMLElement | null): Range | null {
  if (!element || !element.firstChild) return null;
  const range = document.createRange();
  range.selectNodeContents(element);
  return range;
}

/**
 * 编辑器:Markdown 源码模式 + 实时预览 + `[[` 双链联想。
 *
 * 仍是 textarea,不引入富文本编辑器(见 M6 规划:可选替换为 Tiptap)。
 */
export default function MarkdownEditor({
  noteId,
  title,
  content,
  status,
  indexStatus,
  onTitleChange,
  onContentChange,
  onOpenHistory,
  onLinkSearch,
  locate,
  wikiLinks,
  wikiResolveState = 'pending',
  onOpenWikiLink,
  defaultMode = 'preview',
  onSummarySaved,
  onStatus,
  existingTags,
}: Props) {
  /**
   * 当前视图。初值来自 `defaultMode`(新建 → 分栏,已有 → 预览),之后归用户手点。
   * 换笔记时**在渲染期复位**(React 的"调整 state"写法,不重挂载编辑器):
   * 这样第一次提交就是正确的视图,定位高亮那种"只跑一次"的效果不会先在最窄的
   * 「编辑」视图里白跑一趟;重挂载反而会让它跑完又被卸载清理掉(实测过)。
   */
  const [modeNoteId, setModeNoteId] = useState(noteId);
  const [mode, setMode] = useState<Mode>(defaultMode);
  if (modeNoteId !== noteId) {
    setModeNoteId(noteId);
    setMode(defaultMode);
  }
  const [linkQuery, setLinkQuery] = useState<LinkQuery | null>(null);
  const [linkOptions, setLinkOptions] = useState<{ id: string; title: string }[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const previewRef = useRef<HTMLDivElement | null>(null);
  /** 已经跳转过的 nonce:同一目标不重复跳(重复点同一条命中会换 nonce,所以会重新跳) */
  const locatedRef = useRef(0);

  // ---------- 选中文本(AI 文本工具的输入) ----------
  // 只跟踪 textarea 里的选区:预览区是渲染后的 DOM,它的选区和 Markdown 源码偏移对不上,
  // 硬映射会替换错位置 —— 所以预览模式下选区操作直接不可用(见 AiTextTools 的 canSelect)。
  const [selection, setSelection] = useState<EditorSelection | null>(null);

  // ---------- 预览里的 `[[双链]]` ----------
  // 链接本体由 remarkWikiLink + WikiAnchor 渲染;这里管两件事:悬停卡片的开关,
  // 以及把"解析表 + 点击/悬停回调"打包成 Context 值 —— 走 Context 才能让
  // `PREVIEW_COMPONENTS` 保持模块级常量(否则每渲染一次都要重建 markdown 处理管线)。
  const [wikiCard, setWikiCard] = useState<WikiHoverState | null>(null);
  const wikiCardTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!linkQuery || !onLinkSearch) {
      setLinkOptions([]);
      return;
    }
    let active = true;
    const timer = setTimeout(() => {
      void onLinkSearch(linkQuery.prefix)
        .then((options) => {
          if (active) setLinkOptions(options);
        })
        .catch(() => {
          if (active) setLinkOptions([]);
        });
    }, 150);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [linkQuery, onLinkSearch]);

  // ---------- 编辑器 / 预览区查找(Ctrl+F) ----------
  // 一次只开一个查找条,作用域由 findScope 决定;Ctrl+F 落在哪一栏靠"焦点/最近一次
  // 鼠标在哪一栏"推断(lastPaneRef)。两栏的高亮手段不同,原因见 lib/find.ts 的说明。
  const [findOpen, setFindOpen] = useState(false);
  const [findQuery, setFindQuery] = useState('');
  const [findScope, setFindScope] = useState<FindScope>('edit');
  const [findIndex, setFindIndex] = useState(0);
  const [previewCount, setPreviewCount] = useState(0);
  const findInputRef = useRef<HTMLInputElement | null>(null);
  const lastPaneRef = useRef<FindScope>('edit');
  /** 最近一次的鼠标位置:Ctrl+F 时用它判断"指针停在哪一栏"(比 hover 事件可靠) */
  const pointerRef = useRef<{ x: number; y: number } | null>(null);
  /** 预览区上次跳转的「查询#序号」:同一个位置不重复 scrollIntoView */
  const lastJumpRef = useRef('');

  const editMatches = useMemo(
    () => (findScope === 'edit' ? findMatches(content, findQuery) : []),
    [content, findQuery, findScope],
  );
  const findCount = findScope === 'edit' ? editMatches.length : previewCount;
  const shownIndex = findCount === 0 ? 0 : Math.min(findIndex, findCount - 1);

  /**
   * 预览区高亮:用 CSS Custom Highlight API,**不往 DOM 里插 `<mark>`**。
   * 预览是 ReactMarkdown 每次输入都重渲染的产物,插节点等于和 React 抢 DOM。
   * 不支持该 API 时降级成"用系统选区标出当前匹配"——只是看不见全部匹配,但跳转还在。
   */
  const applyPreviewHighlight = useCallback((ranges: Range[], current: number) => {
    const registry = highlightRegistry();
    const Highlight = highlightCtor();
    if (registry && Highlight) {
      registry.delete(FIND_ALL);
      registry.delete(FIND_CURRENT);
      // 查找一开始就把"检索跳转"的提示高亮收掉,免得两个高亮叠在一起打架
      registry.delete(LOCATE);
      if (ranges.length === 0) return;
      registry.set(FIND_ALL, new Highlight(...ranges));
      registry.set(FIND_CURRENT, new Highlight(ranges[Math.min(current, ranges.length - 1)]));
      return;
    }

    const selection = window.getSelection();
    if (!selection) return;
    const anchor = selection.anchorNode;
    const insidePreview = !!anchor && !!previewRef.current?.contains(anchor);
    if (ranges.length === 0) {
      if (insidePreview) selection.removeAllRanges();
      return;
    }
    selection.removeAllRanges();
    selection.addRange(ranges[Math.min(current, ranges.length - 1)]);
  }, []);

  // 预览区:查询/正文/作用域变了就重新走一遍 DOM,并把当前匹配滚进视野。
  // 正文(=`content`)也在依赖里:预览重渲染会丢掉旧 Range,必须重建。
  useEffect(() => {
    if (!findOpen || findScope !== 'preview' || !findQuery) {
      setPreviewCount(0);
      applyPreviewHighlight([], 0);
      return;
    }
    const ranges = collectTextRanges(previewRef.current, findQuery);
    setPreviewCount(ranges.length);
    if (ranges.length === 0) {
      applyPreviewHighlight([], 0);
      return;
    }
    const current = Math.min(findIndex, ranges.length - 1);
    applyPreviewHighlight(ranges, current);

    const jumpKey = `${findQuery}#${current}`;
    if (lastJumpRef.current !== jumpKey) {
      lastJumpRef.current = jumpKey;
      ranges[current].startContainer.parentElement?.scrollIntoView({ block: 'center' });
    }
  }, [findOpen, findScope, findQuery, findIndex, content, mode, applyPreviewHighlight]);

  // 卸载时把高亮清掉:否则关掉笔记后全局注册表里还挂着指向死 DOM 的 Range
  useEffect(() => () => applyPreviewHighlight([], 0), [applyPreviewHighlight]);

  // 单栏模式下把作用域钉回来(比如从分栏切到"预览"时,正在查编辑区)
  useEffect(() => {
    if (findOpen) setFindScope((scope) => clampScope(scope, mode));
  }, [mode, findOpen]);

  /**
   * 编辑区跳转:只能交给 `textarea.setSelectionRange`。
   *
   * 代价是焦点会离开查找框(否则非聚焦的 textarea 不显示选区,用户看不到跳没跳对)。
   * 所以查找框里的 Enter 只能在自己有焦点时生效,离开之后用 F3 / Shift+F3 继续翻 ——
   * 这两条路都在下面的 document 监听里。
   */
  const focusEditMatch = useCallback(
    (list: FindMatch[], index: number) => {
      const textarea = textareaRef.current;
      const match = list[index];
      if (!textarea || !match || mode === 'preview') {
        return;
      }
      lastPaneRef.current = 'edit';
      textarea.focus();
      textarea.setSelectionRange(match.start, match.end);
    },
    [mode],
  );

  const closeFind = useCallback(() => {
    setFindOpen(false);
    setPreviewCount(0);
    applyPreviewHighlight([], 0);
    lastJumpRef.current = '';
  }, [applyPreviewHighlight]);

  const openFind = useCallback(
    (scope: FindScope) => {
      setFindScope(clampScope(scope, mode));
      setFindOpen(true);
      // 第一次打开时输入框还没挂载,交给 autoFocus;已经开着就直接把焦点收回来
      findInputRef.current?.select();
    },
    [mode],
  );

  const stepFind = useCallback(
    (delta: number) => {
      if (findCount === 0) {
        return;
      }
      const next = (shownIndex + delta + findCount) % findCount;
      setFindIndex(next);
      if (findScope === 'edit') {
        focusEditMatch(editMatches, next);
      }
    },
    [findCount, shownIndex, findScope, editMatches, focusEditMatch],
  );

  const changeQuery = useCallback(
    (value: string) => {
      setFindQuery(value);
      setFindIndex(0);
      lastJumpRef.current = '';
      if (findScope === 'edit') {
        focusEditMatch(findMatches(content, value), 0);
      }
    },
    [findScope, content, focusEditMatch],
  );

  // Ctrl+F / Cmd+F 抢过来做"当前区域查找";F3 / Shift+F3 翻下一处。
  // 焦点在标题、左栏、顶栏检索框等别处时**不抢**,浏览器原生查找照常工作。
  //
  // "查哪一栏"的判定顺序:焦点在正文里 → 正文;否则看**指针停在哪一栏**(预览区不是可聚焦
  // 元素,Ctrl+F 的 event.target 会是 body,光看焦点判不出来);再不行才回退到"最近一次
  // 交互过的那一栏"。指针位置用 mousemove 自己记 —— 不依赖 hover 事件,省得和 React 的
  // mouseenter 合成机制纠缠。
  useEffect(() => {
    function onMouseMove(event: MouseEvent) {
      pointerRef.current = { x: event.clientX, y: event.clientY };
    }
    document.addEventListener('mousemove', onMouseMove, { passive: true });
    return () => document.removeEventListener('mousemove', onMouseMove);
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as Node | null;
      const inTextarea = !!target && target === textareaRef.current;
      const inPreview = !!target && !!previewRef.current && previewRef.current.contains(target);

      if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'f') {
        const point = pointerRef.current;
        const hovered = point ? document.elementFromPoint(point.x, point.y) : null;
        const overPreview = !!hovered && !!previewRef.current && previewRef.current.contains(hovered);

        let scope: FindScope | null = null;
        if (findOpen) scope = findScope;
        else if (inTextarea) scope = 'edit';
        else if (inPreview || overPreview) scope = 'preview';
        else if (target === null || target === document.body) scope = lastPaneRef.current;
        if (!scope) return;
        event.preventDefault();
        openFind(scope);
        return;
      }

      if (event.key === 'F3' && findOpen) {
        event.preventDefault();
        stepFind(event.shiftKey ? -1 : 1);
      }
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [findOpen, findScope, openFind, stepFind]);

  // ---------- 从全文检索跳进正文 ----------
  // 命中不带源码偏移(带了也没用:预览是渲染后的 DOM,没有行号可对),所以按 headingPath
  // 找章节、再在章节里找命中词 —— 见 lib/locate.ts。两级都有退化:整条路径对不上就只认
  // 最深一级标题;章节里找不到那个词就在全文里找。
  //
  // 高亮用**另一个**名字(不是查找的 note-find):它自己两秒多后消失,不会假装成
  // "查找还开着";查找开始时也会主动把它收掉。
  useEffect(() => {
    if (!locate || locate.nonce === locatedRef.current) return;
    // 预览不在场就先切过去;mode 变了这个 effect 会再跑一次,那时预览 DOM 已经在了
    if (mode === 'edit') {
      setMode('preview');
      return;
    }
    const preview = previewRef.current;
    if (!preview) return;
    locatedRef.current = locate.nonce;

    const steps = parseHeadingPath(locate.headingPath);
    const heading =
      findHeadingPath(preview, steps) ??
      (steps.length > 1 ? findHeadingPath(preview, steps.slice(-1)) : null);

    let range: Range | null = null;
    for (const term of anchorTerms(locate.snippet)) {
      range = findTextRange(heading ?? preview, term) ?? findTextRange(preview, term);
      if (range) break;
    }
    const focus = range ?? contentsRange(heading);
    if (!focus) return;

    // 把目标放到视口上方 1/4 处:比 scrollIntoView 可控,也不会顺带把整个页面推走
    const rect = focus.getBoundingClientRect();
    const box = preview.getBoundingClientRect();
    preview.scrollTop =
      Math.max(0, preview.scrollTop + (rect.top - box.top) - preview.clientHeight * 0.25);

    const registry = highlightRegistry();
    const Highlight = highlightCtor();
    if (registry && Highlight) {
      registry.set(LOCATE, new Highlight(focus));
      window.setTimeout(() => registry.delete(LOCATE), LOCATE_FLASH_MS);
    }
  }, [locate, mode]);

  // 卸载时别把高亮留在注册表里(里面会留着指向已卸载 DOM 的 Range)
  useEffect(() => () => highlightRegistry()?.delete(LOCATE), []);

  // ---------- 双链的宿主环境 ----------
  /** 悬停 200ms 才弹卡片:扫过一排链接时不会一路闪 */
  const showWikiCard = useCallback((target: string, anchor: DOMRect) => {
    if (wikiCardTimer.current) clearTimeout(wikiCardTimer.current);
    wikiCardTimer.current = setTimeout(() => {
      const width = 288;
      const left = Math.max(8, Math.min(anchor.left, window.innerWidth - width - 8));
      // 卡片高度估个 170px:宁可略微抬高,也不要贴着视口下沿被切掉
      const top = Math.max(8, Math.min(anchor.bottom + 8, window.innerHeight - 170));
      setWikiCard({ x: left, y: top, target });
    }, 200);
  }, []);

  const hideWikiCard = useCallback(() => {
    if (wikiCardTimer.current) {
      clearTimeout(wikiCardTimer.current);
      wikiCardTimer.current = null;
    }
    setWikiCard(null);
  }, []);

  useEffect(() => () => {
    if (wikiCardTimer.current) clearTimeout(wikiCardTimer.current);
  }, []);

  const wikiHost = useMemo<WikiLinkHost | null>(
    () =>
      onOpenWikiLink
        ? {
            resolve: (target) => wikiLinks?.[target.trim().toLowerCase()],
            resolveState: wikiResolveState,
            open: (target, heading) => {
              // 点完就把卡片收掉:跳转之后指针还停在原来的位置,不主动收它就会一直挂着
              hideWikiCard();
              onOpenWikiLink(target, heading);
            },
            hover: showWikiCard,
            leave: hideWikiCard,
          }
        : null,
    [wikiLinks, wikiResolveState, onOpenWikiLink, showWikiCard, hideWikiCard],
  );

  /**
   * 悬停卡片只在"鼠标还停在那个链接上"时才该存在。换笔记、换视图、正文重排之后
   * 它的坐标就失效了(预览是渲染后的 DOM,重排一下链接就换位置了)。
   */
  useEffect(() => {
    hideWikiCard();
  }, [noteId, mode, content, hideWikiCard]);

  /**
   * 预览正文**记下来**:否则上面那个 Context 值一变,整篇 markdown 就要重新解析一遍。
   * 记住元素后,Context 变化只会重渲染真正用到它的链接组件(React 的 context 传播
   * 不受元素引用相等的影响)。
   */
  const previewBody = useMemo(
    () => (
      <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={PREVIEW_COMPONENTS}>
        {content}
      </ReactMarkdown>
    ),
    [content],
  );

  function detectLinkQuery(value: string, caret: number): LinkQuery | null {
    const before = value.slice(0, caret);
    const open = before.lastIndexOf('[[');
    if (open < 0) {
      return null;
    }
    const between = before.slice(open + 2);
    if (between.includes(']]') || between.includes('\n')) {
      return null;
    }
    return { start: open, caret, prefix: between };
  }

  function handleContentChange(event: React.ChangeEvent<HTMLTextAreaElement>) {
    const value = event.target.value;
    onContentChange(value);
    setLinkQuery(detectLinkQuery(value, event.target.selectionStart ?? value.length));
  }

  // ---------- 选中文本 → AI 文本工具 ----------

  /** 读一次 textarea 的选区;纯空白当作"没选"(模型拿一段空格什么也做不了) */
  function readSelection(element: HTMLTextAreaElement | null): EditorSelection | null {
    if (!element) return null;
    const start = element.selectionStart ?? 0;
    const end = element.selectionEnd ?? 0;
    if (end <= start) {
      return null;
    }
    const text = element.value.slice(start, end);
    return text.trim() ? { start, end, text } : null;
  }

  /** 选区没变就不更新 state —— 否则鼠标每动一下、每敲一个键都白渲染一遍 */
  function refreshSelection() {
    const next = readSelection(textareaRef.current);
    setSelection((prev) =>
      prev && next && prev.start === next.start && prev.end === next.end ? prev : next,
    );
  }

  /**
   * AI 结果写回正文。
   *
   * 先走正常保存链路(`onContentChange`),再把光标放到结果末尾。用 `setTimeout(0)` 而不是
   * `requestAnimationFrame`:后台标签页里 rAF 不跑,光标会落在旧位置(分栏滚动同步踩过同一个坑)。
   */
  function handleAiEdit(next: string, caret: number, message: string) {
    onContentChange(next);
    onStatus?.(message);
    setSelection(null);
    setTimeout(() => {
      const element = textareaRef.current;
      if (!element) return;
      element.focus();
      element.setSelectionRange(caret, caret);
    }, 0);
  }

  // 预览模式没有 textarea:选区一并清掉,免得切回来时对着一段不存在的选区操作
  useEffect(() => {
    if (mode === 'preview') {
      setSelection(null);
    }
  }, [mode]);

  function insertLink(target: string) {
    if (!linkQuery) {
      return;
    }
    const next = `${content.slice(0, linkQuery.start)}[[${target}]]${content.slice(linkQuery.caret)}`;
    onContentChange(next);
    setLinkQuery(null);
    textareaRef.current?.focus();
  }

  const tone = indexStatusTone(indexStatus);

  /**
   * 分栏时两栏按**滚动比例**互相跟随。
   * 不按行号也不按像素:左边是 Markdown 源码、右边是渲染结果,两边高度天然不同,
   * 只有"滚到百分之几"这个量是可比的。
   *
   * 防回声用的是**无状态**判断:若目标栏已经落在对应比例上,说明这个 scroll 事件就是
   * 我们上一步写进去造成的 —— 直接返回,不写就不会再派发事件,循环自然终止。
   * 刻意不用 ref/state 做"锁":锁得靠 `requestAnimationFrame` 或定时器解开,而后台/被遮挡的
   * 标签页里 rAF 根本不跑,锁一旦合上同步就**永久失效**(实测踩到:窗口不可见时只同步了一次)。
   */
  function syncScroll(from: HTMLElement | null, to: HTMLElement | null) {
    if (!from || !to) {
      return;
    }
    const fromMax = from.scrollHeight - from.clientHeight;
    const toMax = to.scrollHeight - to.clientHeight;
    if (fromMax <= 0 || toMax <= 0) {
      return;
    }
    const target = (from.scrollTop / fromMax) * toMax;
    if (Math.abs(to.scrollTop - target) < 1) {
      return;
    }
    to.scrollTop = target;
  }

  // 布局要点:section 必须带 `min-h-0`。它是列方向 flex 项,而 flex 项默认 min-height:auto
  // 意思是"不许矮于自身内容",于是它会被内容撑高、溢出到底下的关系面板之外;里面的预览也因此
  // 永远与内容等高、没有可滚余量 —— 表现就是"编辑区能滚、预览区滚不动,底部内容永远看不到"。
  return (
    <section className="relative flex min-h-0 min-w-0 flex-1 flex-col">
      <header className="flex items-center gap-3 border-b border-white/10 px-4 py-2.5">
        <input
          value={title}
          onChange={(e) => onTitleChange(e.target.value)}
          placeholder="标题"
          className="min-w-0 flex-1 bg-transparent text-lg font-semibold tracking-tight text-ink outline-none placeholder:text-faint"
        />

        {indexStatus && (
          <span
            className={`glass-soft flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10px] ${TONE_CLASS[tone]}`}
            title="Elasticsearch 索引状态"
          >
            <span className="h-1.5 w-1.5 rounded-full bg-current" />
            {indexStatusLabel(indexStatus)}
          </span>
        )}

        <span className="text-[11px] text-faint">{status}</span>

        {onOpenHistory && (
          <button
            type="button"
            onClick={onOpenHistory}
            className="glass-soft sheen rounded-full px-3 py-1 text-[11px] text-muted transition-colors hover:text-ink"
          >
            历史
          </button>
        )}

        <div className="glass-soft flex rounded-full p-0.5 text-[11px]">
          {(['edit', 'split', 'preview'] as Mode[]).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMode(m)}
              className={`rounded-full px-2.5 py-1 transition-all ${
                mode === m ? 'glass-active text-ink' : 'text-muted hover:text-ink'
              }`}
            >
              {m === 'edit' ? '编辑' : m === 'split' ? '分栏' : '预览'}
            </button>
          ))}
        </div>
      </header>

      {linkQuery && mode !== 'preview' && (
        <div className="glass-pop absolute left-4 right-4 top-14 z-20 max-h-56 overflow-y-auto rounded-2xl p-1">
          {linkOptions.length === 0 ? (
            <p className="px-3 py-2 text-xs text-faint">
              {linkQuery.prefix ? '没有匹配的笔记标题' : '输入标题以联想已有笔记'}
            </p>
          ) : (
            linkOptions.map((option) => (
              <button
                key={option.id}
                type="button"
                onMouseDown={(e) => {
                  e.preventDefault();
                  insertLink(option.title);
                }}
                className="glass-soft block w-full truncate rounded-xl px-3 py-1.5 text-left text-xs text-muted transition-colors hover:text-ink"
              >
                {option.title}
              </button>
            ))
          )}
        </div>
      )}

      {findOpen && (
        <FindBar
          inputRef={findInputRef}
          query={findQuery}
          onQueryChange={changeQuery}
          scope={findScope}
          onScopeChange={(scope) => {
            setFindScope(scope);
            setFindIndex(0);
            lastJumpRef.current = '';
          }}
          scopeSwitcher={mode === 'split'}
          count={findCount}
          index={shownIndex}
          onPrev={() => stepFind(-1)}
          onNext={() => stepFind(1)}
          onClose={closeFind}
        />
      )}

      <AiTextTools
        noteId={noteId}
        title={title}
        content={content}
        selection={mode === 'preview' ? null : selection}
        canSelect={mode !== 'preview'}
        existingTags={existingTags}
        onEdit={handleAiEdit}
        onSummarySaved={(summary) => onSummarySaved?.(summary)}
        onStatus={(message) => onStatus?.(message)}
      />

      <div className="flex min-h-0 flex-1">
        {mode !== 'preview' && (
          <textarea
            ref={textareaRef}
            value={content}
            onChange={handleContentChange}
            spellCheck={false}
            onSelect={refreshSelection}
            onMouseUp={refreshSelection}
            onKeyUp={refreshSelection}
            onScroll={() => syncScroll(textareaRef.current, previewRef.current)}
            onFocus={() => {
              lastPaneRef.current = 'edit';
            }}
            onMouseEnter={() => {
              lastPaneRef.current = 'edit';
            }}
            onKeyDown={(event) => {
              // 光标在正文里时 Esc 收起查找条(和编辑器一致),不影响别的按键
              if (findOpen && event.key === 'Escape') {
                event.preventDefault();
                closeFind();
              }
            }}
            placeholder="开始输入 Markdown…用 [[标题]] 建立双链,用 #标签 归类"
            className="font-mono min-w-0 flex-1 resize-none bg-transparent p-5 text-sm leading-relaxed text-ink/90 outline-none placeholder:text-faint"
          />
        )}
        {mode === 'split' && <div className="w-px bg-white/10" />}
        {mode !== 'edit' && (
          <div
            ref={previewRef}
            onScroll={() => {
              // 卡片是按链接当时的视口位置摆的,预览一滚就不准了 —— 直接收掉
              hideWikiCard();
              syncScroll(previewRef.current, textareaRef.current);
            }}
            onMouseEnter={() => {
              lastPaneRef.current = 'preview';
            }}
            onMouseDown={() => {
              lastPaneRef.current = 'preview';
            }}
            className="markdown-body min-w-0 flex-1 overflow-y-auto p-5 text-sm"
          >
            <WikiLinkProvider host={wikiHost}>{previewBody}</WikiLinkProvider>
          </div>
        )}
      </div>

      <WikiHoverCard
        state={wikiCard}
        resolved={wikiCard ? wikiLinks?.[wikiCard.target.trim().toLowerCase()] : undefined}
        resolveState={wikiResolveState}
      />
    </section>
  );
}
