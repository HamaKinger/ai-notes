/**
 * 把全文检索的命中"跳进正文"用的锚点工具。
 *
 * 命中的单位是分块(chunk),它不携带源码偏移 —— 就算带上也没用:预览是渲染后的 DOM,
 * 没有行号可对。所以定位走**文本锚点**,两级递进:
 *
 *   1. `headingPath`("架构设计 / 检索层 / 混合检索")在预览里按文档顺序对齐标题 → 章节锚点;
 *   2. `snippet` 里能提取到命中词时,在章节内(退化为全文)找第一个匹配 → 段落锚点。
 *
 * 全是纯函数 + DOM 遍历,不依赖后端返回新字段。高亮的注册与滚动在组件里做
 * (那部分要和 `previewRef` 的生命周期绑定)。
 */

/** 一条"跳进正文"的目标 */
export interface LocateTarget {
  noteId: string;
  headingPath: string | null;
  snippet: string | null;
  /** 同一条命中重复点击也要能重新定位 —— 用它当触发信号,别用对象引用 */
  nonce: number;
}

const HEADING_SELECTOR = 'h1,h2,h3,h4,h5,h6';

/** 单行锚点最长取这么多字符:够独特,又不至于把整段拖进来 */
const ANCHOR_MAX = 24;

/** 锚点最短长度:太短(如"检索")在长文档里到处都有,定位会飘 */
const ANCHOR_MIN = 2;

/**
 * 把 markdown 源码文本和渲染后的文本对齐到同一个形状。
 *
 * 为什么要这一步:`headingPath` 与 `snippet` 来自**源码**(`## **重点**`、`- 列表项`、`| 表格 |`),
 * 而预览里这些标记已经变成元素或者消失。不去标记就没法比较。
 */
export function normalizeText(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`{1,3}/g, '')
    .replace(/\*\*|__/g, '')
    .replace(/^\s*(#{1,6}|[-*+]\s|\d+\.\s|>)\s*/gm, '')
    .replace(/\[\[([^\]|]+)(?:\|[^\]]*)?\]\]/g, '$1')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[|]/g, ' ')
    .replace(/[*_~]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** `"架构设计 / 检索层 / 混合检索"` → `["架构设计","检索层","混合检索"]` */
export function parseHeadingPath(path: string | null | undefined): string[] {
  if (!path) return [];
  return path
    .split('/')
    .map((part) => part.trim())
    .filter(Boolean);
}

/**
 * 从命中片段里挑"可以拿去正文里搜"的字面量,按可信度排序。
 *
 * 两种片段形状都要照顾:
 * - 关键词分支带后端高亮的 `<em>` —— 命中词最干净,优先用;
 * - 向量分支给的是 chunk 原文(没有 `<em>`),退化为"挑一段够长的纯文本"。
 */
export function anchorTerms(snippet: string | null | undefined, limit = 3): string[] {
  if (!snippet) return [];
  const terms: string[] = [];

  for (const match of snippet.matchAll(/<em>(.*?)<\/em>/gi)) {
    const term = normalizeText(match[1]);
    if (term.length >= ANCHOR_MIN) terms.push(term);
  }
  if (terms.length > 0) return unique(terms).slice(0, limit);

  // 向量分支给的是 chunk 原文(没有高亮):挑一段够长的纯文本当锚点。
  // 表格行要单独处理 —— `| a | b |` 在预览里变成 `<th>/<td>`,竖线不存在、单元格之间
  // 也没有空格,整行拿去搜必然落空;所以先挑非表格行,退而求其次才用单元格文本。
  const plain: string[] = [];
  const table: string[] = [];
  for (const line of snippet.split('\n')) {
    const trimmed = line.trim();
    if (trimmed.startsWith('|')) {
      const cell = trimmed
        .split('|')
        .map((part) => normalizeText(part))
        .find((part) => part.length >= ANCHOR_MIN * 4);
      if (cell) table.push(cell.slice(0, ANCHOR_MAX));
      continue;
    }
    const piece = normalizeText(line);
    if (piece.length >= ANCHOR_MIN * 4) plain.push(piece.slice(0, ANCHOR_MAX));
  }
  return unique([...plain, ...table]).slice(0, limit);
}

/**
 * 按 `headingPath` 的层级在预览里**顺序**对齐标题,返回最深一级的标题元素。
 *
 * 不比较标题级别:笔记可能从 `##` 起写,`headingPath` 里只有文本层级。
 * 要求整条路径都对上才算命中 —— 只对上一半就可能把人带进隔壁章节。
 */
export function findHeadingPath(root: HTMLElement, steps: string[]): HTMLElement | null {
  if (steps.length === 0) return null;
  const wanted = steps.map((step) => step.toLowerCase());
  let index = 0;
  let found: HTMLElement | null = null;

  for (const heading of root.querySelectorAll<HTMLElement>(HEADING_SELECTOR)) {
    if (index >= wanted.length) break;
    if (normalizeText(heading.textContent ?? '').toLowerCase() === wanted[index]) {
      found = heading;
      index += 1;
    }
  }
  return index === wanted.length ? found : null;
}

/**
 * 在子树里找 `needle` 的第一个匹配,**允许跨文本节点**。
 *
 * `lib/find.ts` 的 `collectTextRanges` 只认单节点匹配(它服务于 Ctrl+F 的字面量查找,
 * 够用且便宜);这里不行 —— 命中词常常被 `**加粗**`、`[[双链]]` 切开,拼起来的整段文本
 * 才是用户眼里的那句话。
 */
export function findTextRange(root: HTMLElement, needle: string): Range | null {
  const target = squeeze(needle).toLowerCase();
  if (!target) return null;

  // 两边都把空白去掉再比:`snippet` 来自 markdown 源码(表格竖线、缩进、软换行、列表符号),
  // 预览里这些要么变成元素边界、要么整个消失 —— 不去空白基本对不上。
  // `offsets` 负责把"去掉空白后的下标"还原回"拼接串上的下标"。
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const nodes: Text[] = [];
  const starts: number[] = [];
  const offsets: number[] = [];
  let full = '';
  let squeezed = '';

  let node = walker.nextNode() as Text | null;
  while (node) {
    const value = node.nodeValue ?? '';
    if (value) {
      starts.push(full.length);
      nodes.push(node);
      for (let i = 0; i < value.length; i += 1) {
        if (/\s/.test(value[i])) continue;
        squeezed += value[i];
        offsets.push(full.length + i);
      }
      full += value;
    }
    node = walker.nextNode() as Text | null;
  }

  const at = squeezed.toLowerCase().indexOf(target);
  if (at < 0) return null;

  const start = resolve(nodes, starts, offsets[at]);
  const end = resolve(nodes, starts, offsets[at + target.length - 1] + 1);
  if (!start || !end) return null;

  const range = document.createRange();
  range.setStart(start[0], start[1]);
  range.setEnd(end[0], end[1]);
  return range;
}

/** 去掉所有空白 —— "忽略空白"匹配用 */
function squeeze(text: string): string {
  return text.replace(/\s+/g, '');
}

/** 把"拼接串上的偏移"还原成"某个文本节点 + 节点内偏移" */
function resolve(nodes: Text[], starts: number[], offset: number): [Text, number] | null {
  for (let i = nodes.length - 1; i >= 0; i -= 1) {
    if (offset >= starts[i]) {
      const length = (nodes[i].nodeValue ?? '').length;
      return [nodes[i], Math.min(offset - starts[i], length)];
    }
  }
  return null;
}

function unique(values: string[]): string[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = value.toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
