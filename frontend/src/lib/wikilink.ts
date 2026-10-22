/**
 * 预览里的 `[[双链]]`。
 *
 * 后端把 `[[...]]` 解析成 `link` 表(出链/反链/图谱),但预览是 `ReactMarkdown` 渲染的,
 * 它不认这种扩展语法 —— 于是 `[[标题]]` 在预览里就是一段死文本。这里补上两件事:
 *
 * 1. {@link remarkWikiLink}:在 mdast 层把文本节点里的 `[[...]]` 换成可点的节点
 *    (靠 `data.hName` 直接造成 `<a>`,不走 mdast 的 `link` 节点 —— 见下面的说明);
 * 2. {@link parseWikiBody} / {@link collectWikiTargets}:标题解析,规则与后端
 *    `NoteContentParser` **逐条对齐**(别名、`#小节`、`目录/` 前缀、`![[嵌入]]`、代码块内不算),
 *    否则会出现"预览说是可点的、关系面板却说是未创建"这种自相矛盾。
 */

import type { Parent, PhrasingContent, Root } from 'mdast';

/** `[[body]]`;body 里不允许再有方括号或换行(与后端正则一致) */
function wikiPattern(): RegExp {
  // 每次新建:共用实例会踩 `lastIndex` 残留的坑
  return /\[\[([^\[\]\n]+)\]\]/g;
}

export interface WikiTarget {
  /** 目标标题(已去掉 `|别名`、`#小节`、`目录/` 前缀,并 trim) */
  target: string;
  /** 显示文字:有别名用别名,否则用目标标题 */
  label: string;
  /** `[[目标#小节]]` 里的小节;没有就是 null */
  heading: string | null;
}

/**
 * 解析 `[[...]]` 里的正文(不含方括号),解析不出来返回 null。
 *
 * 切分顺序与后端 `NoteContentParser.normalizeTarget` 一致:先 `|别名`,再 `#小节`,
 * 最后只留 `目录/` 的最后一段。顺序不能反 —— `[[目标#小节|别名]]` 里的 `#` 属于目标那半边。
 */
export function parseWikiBody(raw: string): WikiTarget | null {
  const body = raw.trim();
  const pipe = body.indexOf('|');
  const head = (pipe >= 0 ? body.slice(0, pipe) : body).trim();
  const alias = pipe >= 0 ? body.slice(pipe + 1).trim() : '';

  const hash = head.indexOf('#');
  const heading = hash >= 0 ? head.slice(hash + 1).trim() : '';
  const beforeHash = (hash >= 0 ? head.slice(0, hash) : head).trim();

  const slash = beforeHash.lastIndexOf('/');
  const target = (slash >= 0 ? beforeHash.slice(slash + 1) : beforeHash).trim();
  if (!target) {
    return null;
  }
  return { target, label: alias || target, heading: heading || null };
}

/**
 * 收集正文里的双链目标标题(去重,保序)。用于"一次问一批"的标题解析。
 *
 * 先抹掉围栏代码块与行内代码 —— 与后端"代码里的 `[[示例]]` 不算双链"保持一致的**结果**,
 * 虽然手段不同(那边是走 AST)。
 */
export function collectWikiTargets(markdown: string, limit = 60): string[] {
  if (!markdown) {
    return [];
  }
  const cleaned = markdown.replace(/```[\s\S]*?```/g, ' ').replace(/`[^`\n]*`/g, ' ');
  const seen = new Set<string>();
  const titles: string[] = [];

  for (const match of cleaned.matchAll(wikiPattern())) {
    const at = match.index ?? 0;
    if (at > 0 && cleaned[at - 1] === '!') {
      continue; // `![[嵌入]]` 是嵌入,不算双链
    }
    const parsed = parseWikiBody(match[1]);
    if (!parsed) {
      continue;
    }
    const key = parsed.target.toLowerCase();
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    titles.push(parsed.target);
    if (titles.length >= limit) {
      break;
    }
  }
  return titles;
}

/** remark 插件:把文本节点里的 `[[...]]` 拆成"文字 + 可点节点" */
export function remarkWikiLink() {
  return (tree: Root): void => {
    split(tree);
  };
}

function split(parent: Parent): void {
  const children = parent.children;
  for (let i = 0; i < children.length; i += 1) {
    const child = children[i];
    if (child.type === 'text') {
      const parts = splitText(child.value);
      if (parts.length > 1) {
        children.splice(i, 1, ...parts);
        i += parts.length - 1;
      }
      continue;
    }
    // mdast 里 `code` / `inlineCode` 只有 value、没有 children —— 这一句顺手把它们跳过,
    // 和后端"代码块里的 `[[示例]]` 不算双链"是同一个结果
    if ('children' in child) {
      split(child as Parent);
    }
  }
}

function splitText(value: string): PhrasingContent[] {
  const parts: PhrasingContent[] = [];
  let last = 0;

  for (const match of value.matchAll(wikiPattern())) {
    const at = match.index ?? 0;
    if (at > 0 && value[at - 1] === '!') {
      continue;
    }
    const parsed = parseWikiBody(match[1]);
    if (!parsed) {
      continue;
    }
    if (at > last) {
      parts.push({ type: 'text', value: value.slice(last, at) });
    }
    parts.push(wikiNode(parsed));
    last = at + match[0].length;
  }

  if (last === 0) {
    return [{ type: 'text', value }];
  }
  if (last < value.length) {
    parts.push({ type: 'text', value: value.slice(last) });
  }
  return parts;
}

/**
 * 造一个会被渲染成 `<a>` 的自定义节点。
 *
 * 用 `data.hName` 而不是 mdast 的 `link` 节点:后者必须有 URL,而 react-markdown 的
 * `urlTransform` 白名单(防 `javascript:`)会把自造协议抹成空字符串 —— 与其为了绕开它
 * 编一个假协议、再在组件里解析回来,不如干脆不要 href,把目标标题放在 `data-wiki` 上。
 * 可点性由组件的 `onClick` / `role="link"` / `tabIndex` 提供。
 */
function wikiNode({ target, label, heading }: WikiTarget): PhrasingContent {
  return {
    type: 'wikiLink',
    data: {
      hName: 'a',
      hProperties: {
        className: 'wiki-link',
        role: 'link',
        tabIndex: 0,
        'data-wiki': target,
        'data-wiki-heading': heading ?? '',
      },
    },
    children: [{ type: 'text', value: label }],
  } as unknown as PhrasingContent;
}
