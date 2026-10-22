/**
 * 编辑器/预览区查找(Ctrl+F)用的两个纯函数式工具。
 *
 * 两个区域要的数据形状不一样:
 * - 编辑区是 `textarea`,只能按**字符下标**选中匹配段(没有 DOM 文本节点可切);
 * - 预览区是渲染后的 DOM,要高亮就得按**文本节点 + Range**。
 *
 * 大小写按"不敏感"处理(和浏览器原生查找的默认行为一致);不做分词,
 * 因为这里找的是"我眼前这份文本里的字面量",不是检索。
 */

export interface FindMatch {
  start: number;
  end: number;
}

/** 匹配数上限:防止一个单字符查询在大文档上把主线程拖死 */
export const MAX_FIND_MATCHES = 2000;

/** 在纯文本里找所有匹配段(用于 textarea 的 setSelectionRange) */
export function findMatches(text: string, query: string): FindMatch[] {
  if (!text || !query) {
    return [];
  }
  const haystack = text.toLowerCase();
  const needle = query.toLowerCase();
  const matches: FindMatch[] = [];
  let from = 0;
  for (;;) {
    const at = haystack.indexOf(needle, from);
    if (at < 0) break;
    matches.push({ start: at, end: at + needle.length });
    from = at + needle.length;
    if (matches.length >= MAX_FIND_MATCHES) break;
  }
  return matches;
}

/**
 * 在 DOM 子树里找所有匹配段(用于预览区)。
 *
 * 每段匹配必须落在**同一个文本节点**内 —— 跨节点的匹配(比如 `**加**粗` 渲染成
 * `<strong>` 后关键词被切开)这里不处理:要做代价是要拼接整棵子树的文本再反查节点,
 * 收益极小,而且会把"高亮"变成"猜位置"。
 */
export function collectTextRanges(root: HTMLElement | null, query: string): Range[] {
  if (!root || !query) {
    return [];
  }
  const needle = query.toLowerCase();
  const ranges: Range[] = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let node = walker.nextNode();
  while (node) {
    const text = node.nodeValue ?? '';
    if (text) {
      const haystack = text.toLowerCase();
      let from = 0;
      for (;;) {
        const at = haystack.indexOf(needle, from);
        if (at < 0) break;
        const range = document.createRange();
        range.setStart(node, at);
        range.setEnd(node, at + needle.length);
        ranges.push(range);
        from = at + needle.length;
        if (ranges.length >= MAX_FIND_MATCHES) return ranges;
      }
    }
    node = walker.nextNode();
  }
  return ranges;
}

/** CSS Custom Highlight API 的注册表;浏览器不支持时返回 undefined(调用方走降级路径) */
export function highlightRegistry(): {
  set(name: string, value: unknown): void;
  delete(name: string): void;
} | undefined {
  const css = (globalThis as { CSS?: { highlights?: unknown } }).CSS;
  return css?.highlights as
    | { set(name: string, value: unknown): void; delete(name: string): void }
    | undefined;
}

/** `new Highlight(...ranges)` 的构造器;注意 TS 的 DOM 类型不一定带它,所以按需取 */
export function highlightCtor(): (new (...ranges: Range[]) => unknown) | undefined {
  return (globalThis as { Highlight?: new (...ranges: Range[]) => unknown }).Highlight;
}
