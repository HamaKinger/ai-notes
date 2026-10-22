'use client';

import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api, streamInline } from '@/lib/api';
import type { TextAction } from '@/lib/types';

/**
 * `remark-gfm`:结果里常有列表、表格、删除线;不挂它,`- 一项` 就只是一行带横杠的字。
 * 提成模块级常量 —— 每次渲染传新数组会让 react-markdown 重建处理管线。
 */
const REMARK_PLUGINS = [remarkGfm];

/** 编辑器里的一段选区。`start` / `end` 是**源码下标**:生成结果要按它替换 / 插回正文 */
export interface EditorSelection {
  start: number;
  end: number;
  text: string;
}

const ACTIONS: { action: TextAction; label: string; hint: string }[] = [
  { action: 'polish', label: '润色', hint: '改掉错别字与病句,保持原意' },
  { action: 'translate', label: '翻译', hint: '按原文语言中英互译' },
  { action: 'continue', label: '续写', hint: '顺着这段继续写 1~2 段' },
  { action: 'summarize', label: '总结', hint: '压成最多 5 条要点' },
  { action: 'explain', label: '解释', hint: '解释这段在说什么(不写回笔记)' },
];

/**
 * 一次 AI 文本任务。三种任务共用一个面板结构 —— 只做一件事,但每一步的结果都要**让用户
 * 看过再落库**:替换选中、插到段后、保存摘要、写入标签,都是按钮,不是自动行为。
 */
interface Task {
  kind: 'inline' | 'summary' | 'tags';
  /** 面板标题(中文动作名,先本地占位,`meta` 回来后以后端为准) */
  label: string;
  /** 已经收到的正文(流式)/ 生成好的摘要 */
  text: string;
  /** inline:发起时的选区快照 —— 结果一出来就按它写回 */
  range?: { start: number; end: number };
  action?: TextAction;
  /** 解释类结果不写回笔记。这条由后端 `meta.writesBack` 决定,前端不自己判 */
  writesBack: boolean;
  tags?: string[];
  appliedTags?: string[];
  state: 'running' | 'done' | 'error';
  error?: string | null;
  tokenIn?: number | null;
  tokenOut?: number | null;
  /** 已经替换 / 插入 / 保存过:按钮不再重复触发 */
  applied?: boolean;
}

interface Props {
  noteId: string;
  title: string;
  content: string;
  /** 当前选区;`null` = 没选(预览模式下永远是 null) */
  selection: EditorSelection | null;
  /** 预览模式没有 textarea:选区操作不可用,但摘要 / 标签仍然可用 */
  canSelect: boolean;
  /** 工作区里已有的标签:传给模型只为"尽量复用",不是过滤 */
  existingTags?: string[];
  /**
   * 改动正文。**由外层负责保存**:替换 / 插入 / 写标签都走和手打一样的保存链路
   * (自动保存、版本、索引重建),AI 这里不另开一条写库的路。
   */
  onEdit: (next: string, caret: number, message: string) => void;
  /** 摘要已保存(带上服务端返回的值,外层只管更新 current.summary) */
  onSummarySaved: (summary: string) => void;
  /** 编辑器状态栏文案 */
  onStatus: (message: string) => void;
}

/** 翻译方向:中文原文 → 英文,其它 → 中文(与后端 `targetOr` 的默认一致,这里提前显示出来) */
function inferTarget(text: string): string {
  return /[\u4e00-\u9fff]/.test(text) ? '英文' : '中文';
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** 插到选区之后时的连接符:接在段落里就给空格,独立成段就给空行 */
function joinerAt(base: string, at: number, inline: boolean): string {
  if (at <= 0) return '';
  const before = base.slice(0, at);
  if (/\n\s*\n$/.test(before)) return '';
  if (/\n$/.test(before)) return '';
  if (!inline) return '\n\n';
  const last = before.slice(-1);
  // 中文(含标点)后面不加空格:中文里 `检索词 例如` 看着别扭;英文单词之间才要
  return /[\s\u3000-\u303f\uff00-\uffef\u4e00-\u9fff]/.test(last) ? '' : ' ';
}

/**
 * 编辑器里的 AI 文本工具条与结果面板(设计文档 §2.3「选中文本操作」「自动标签 / 摘要」)。
 *
 * <p>放在编辑器里而不是 AI 侧栏:这些操作的输入是**用户正看着的那段文字**,结果也要写回
 * 同一段文字;跑到另一个面板里回答,用户还得自己找回来。
 *
 * <p>三种任务都只是"把文字变成文字",**不碰数据库**:要不要替换、要不要存,是用户点按钮
 * 之后由外层走普通保存链路完成的。这样 AI 出错最多是"这次白等了",不会悄悄改掉笔记。
 */
export default function AiTextTools({
  noteId,
  title,
  content,
  selection,
  canSelect,
  existingTags = [],
  onEdit,
  onSummarySaved,
  onStatus,
}: Props) {
  const [task, setTask] = useState<Task | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  /** 每次发起请求 +1:晚到的回调不许写进新请求的面板(换笔记 / 连点两个动作) */
  const ticketRef = useRef(0);

  // 换笔记(或卸载):掐掉还在跑的请求,并让旧请求的回调作废 ——
  // 否则上一篇的润色结果会落到这一篇上,甚至把上一篇的摘要存到这一篇
  useEffect(() => {
    ticketRef.current += 1;
    setTask(null);
    return () => abortRef.current?.abort();
  }, [noteId]);

  const busy = task?.state === 'running';

  function start(kind: Task['kind'], label: string, initial: Partial<Task> = {}) {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const ticket = ++ticketRef.current;
    setTask({ kind, label, text: '', writesBack: true, state: 'running', ...initial });
    return { ticket, signal: controller.signal };
  }

  /** 只接受"当前这次请求"的更新 */
  function update(ticket: number, patch: (current: Task) => Task) {
    setTask((current) => (current && ticketRef.current === ticket ? patch(current) : current));
  }

  async function runInline(action: TextAction, label: string) {
    if (!selection) return;
    const { ticket, signal } = start('inline', label, {
      action,
      range: { start: selection.start, end: selection.end },
      writesBack: action !== 'explain',
    });

    try {
      await streamInline(
        {
          text: selection.text,
          action,
          to: action === 'translate' ? inferTarget(selection.text) : undefined,
        },
        {
          onMeta: (meta) =>
            update(ticket, (current) => ({ ...current, label: meta.label, writesBack: meta.writesBack })),
          onToken: (delta) => update(ticket, (current) => ({ ...current, text: current.text + delta })),
          onDone: (done) =>
            update(ticket, (current) => ({
              ...current,
              state: 'done',
              tokenIn: done.tokenIn,
              tokenOut: done.tokenOut,
            })),
          onError: (error) =>
            update(ticket, (current) => ({ ...current, state: 'error', error: error.message })),
        },
        signal,
      );
      // 流读完了却没收到 done(服务端提前关连接):已经收到的部分照样能用
      update(ticket, (current) => (current.state === 'running' ? { ...current, state: 'done' } : current));
    } catch (error) {
      if (ticketRef.current !== ticket) return;
      if (signal.aborted) {
        // 用户点了停止:保留已经生成的部分,由他决定要不要用
        update(ticket, (current) => ({ ...current, state: 'done' }));
        onStatus('已停止生成,保留了已经生成的部分');
        return;
      }
      update(ticket, (current) => ({ ...current, state: 'error', error: messageOf(error) }));
    }
  }

  async function runSummary() {
    const { ticket, signal } = start('summary', '摘要');
    try {
      const { summary } = await api.summarize(title, content, signal);
      update(ticket, (current) => ({ ...current, text: summary, state: 'done' }));
    } catch (error) {
      if (ticketRef.current !== ticket) return;
      if (signal.aborted) {
        setTask(null);
        return;
      }
      update(ticket, (current) => ({ ...current, state: 'error', error: messageOf(error) }));
    }
  }

  async function runTags() {
    const { ticket, signal } = start('tags', '标签');
    try {
      const { tags } = await api.suggestTags(title, content, existingTags, signal);
      update(ticket, (current) => ({ ...current, tags, state: 'done' }));
    } catch (error) {
      if (ticketRef.current !== ticket) return;
      if (signal.aborted) {
        setTask(null);
        return;
      }
      update(ticket, (current) => ({ ...current, state: 'error', error: messageOf(error) }));
    }
  }

  /** 停:只掐请求,已经生成的部分留在面板里 */
  function stop() {
    abortRef.current?.abort();
  }

  function close() {
    abortRef.current?.abort();
    ticketRef.current += 1; // 还在路上的回调作废,别再弹一句"已停止"
    setTask(null);
  }

  function replaceSelection() {
    if (!task?.range || !task.text) return;
    // 生成期间用户可能改过正文:范围夹到当前长度,至少不会插到越界位置
    const from = Math.min(Math.max(task.range.start, 0), content.length);
    const to = Math.min(Math.max(task.range.end, from), content.length);
    const next = content.slice(0, from) + task.text + content.slice(to);
    onEdit(next, from + task.text.length, `已替换选中文本(${task.text.length} 字)`);
    setTask((current) => (current ? { ...current, applied: true } : current));
  }

  function insertAfterSelection() {
    if (!task?.range || !task.text) return;
    const at = Math.min(Math.max(task.range.end, 0), content.length);
    const joiner = joinerAt(content, at, task.action === 'continue');
    const next = content.slice(0, at) + joiner + task.text + content.slice(at);
    onEdit(next, at + joiner.length + task.text.length, '已插入到选中文本之后');
    setTask((current) => (current ? { ...current, applied: true } : current));
  }

  /** 标签写到正文末尾:那里最不容易打断正在写的段落,也是 `#标签` 的常规位置 */
  function appendTags(tags: string[]) {
    const pending = tags.filter((tag) => !(task?.appliedTags ?? []).includes(tag));
    if (pending.length === 0) return;
    const addition = pending.map((tag) => `#${tag}`).join(' ');
    const base = content.replace(/\s+$/, '');
    const next = base ? `${base}\n\n${addition}` : addition;
    onEdit(next, next.length, `已写入 ${pending.length} 个标签`);
    setTask((current) =>
      current ? { ...current, appliedTags: [...(current.appliedTags ?? []), ...pending] } : current,
    );
  }

  async function saveSummary() {
    if (!task?.text) return;
    const ticket = ticketRef.current;
    try {
      const saved = await api.updateNote(noteId, { summary: task.text });
      if (ticketRef.current !== ticket) return;
      update(ticket, (current) => ({ ...current, applied: true }));
      onSummarySaved(saved.summary ?? task.text);
      onStatus('已保存摘要');
    } catch (error) {
      if (ticketRef.current !== ticket) return;
      onStatus(`摘要保存失败:${messageOf(error)}`);
    }
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      onStatus('已复制到剪贴板');
    } catch {
      onStatus('复制失败,请手动选中复制');
    }
  }

  const appliedTags = task?.appliedTags ?? [];

  return (
    <>
      <div className="flex shrink-0 flex-wrap items-center gap-x-1.5 gap-y-1 border-b border-white/10 px-4 py-1.5">
        <span className="mr-0.5 text-[11px] font-medium text-accent/90">✦ 文本工具</span>

        {selection ? (
          <>
            {ACTIONS.map(({ action, label, hint }) => (
              <button
                key={action}
                type="button"
                title={action === 'translate' ? `翻译成${inferTarget(selection.text)}` : hint}
                // 别让 textarea 失焦:按钮按下就触发,选区原样留着
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => void runInline(action, label)}
                className={`glass-soft sheen rounded-full px-2.5 py-0.5 text-[11px] transition-colors hover:text-ink ${
                  task?.kind === 'inline' && task.action === action && busy
                    ? 'glass-active text-ink'
                    : 'text-muted'
                }`}
              >
                {label}
              </button>
            ))}
            <span className="ml-0.5 text-[10px] text-faint">已选 {selection.text.trim().length} 字</span>
          </>
        ) : (
          <span className="text-[11px] text-faint">
            {canSelect
              ? '选中一段文字:润色 / 翻译 / 续写 / 总结 / 解释'
              : '切到「编辑」或「分栏」后可对选中文本操作'}
          </span>
        )}

        <span className="ml-auto flex items-center gap-1.5">
          <button
            type="button"
            title="用一句话概括这篇笔记,可保存为摘要"
            onClick={() => void runSummary()}
            disabled={!content.trim()}
            className="glass-soft sheen rounded-full px-2.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
          >
            摘要
          </button>
          <button
            type="button"
            title="推荐 3~5 个标签,点一下就写进笔记"
            onClick={() => void runTags()}
            disabled={!content.trim()}
            className="glass-soft sheen rounded-full px-2.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
          >
            标签
          </button>
        </span>
      </div>

      {task && (
        <div className="glass-pop mx-3 mt-2 mb-1 flex max-h-[45%] shrink-0 flex-col overflow-hidden rounded-2xl">
          <div className="flex shrink-0 items-center gap-2 border-b border-white/10 px-3 py-1.5">
            <span className="text-[11px] font-medium text-ink">✦ {task.label}</span>
            {busy && (
              <span className="flex items-center gap-1.5 text-[11px] text-faint">
                <span className="dot-pulse h-1.5 w-1.5 rounded-full bg-accent" />
                生成中…
              </span>
            )}
            {task.state === 'done' && task.tokenOut != null && (
              <span className="text-[10px] text-faint">
                in {task.tokenIn ?? '?'} / out {task.tokenOut}
              </span>
            )}
            {task.state === 'error' && (
              <span className="min-w-0 flex-1 truncate text-[11px] text-red-300/90" title={task.error ?? ''}>
                {task.error}
              </span>
            )}
            <span className="ml-auto flex shrink-0 items-center gap-1.5">
              {busy && (
                <button
                  type="button"
                  onClick={stop}
                  className="glass-soft rounded-full px-2.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink"
                >
                  停止
                </button>
              )}
              <button
                type="button"
                onClick={close}
                title="关闭"
                className="rounded-full px-1.5 text-[11px] text-faint transition-colors hover:text-ink"
              >
                ✕
              </button>
            </span>
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto px-3 py-2">
            {task.kind === 'tags' ? (
              task.tags?.length ? (
                <div className="flex flex-wrap gap-1.5">
                  {task.tags.map((tag) => {
                    const used = appliedTags.includes(tag);
                    return (
                      <button
                        key={tag}
                        type="button"
                        onClick={() => appendTags([tag])}
                        disabled={used}
                        title={used ? '已写入' : `把 #${tag} 写到笔记末尾`}
                        className={`rounded-full border px-2.5 py-0.5 text-[11px] transition-all ${
                          used
                            ? 'border-white/10 bg-white/5 text-faint'
                            : 'border-accent/50 bg-accent/15 text-accent hover:bg-accent/25'
                        }`}
                      >
                        {used ? '✓ ' : '#'}
                        {tag}
                      </button>
                    );
                  })}
                </div>
              ) : (
                <p className="text-[11px] text-faint">
                  {busy ? '正在读这篇笔记…' : '没有推荐出合适的标签'}
                </p>
              )
            ) : task.text ? (
              <div className="markdown-body markdown-chat">
                <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>{task.text}</ReactMarkdown>
              </div>
            ) : (
              <p className="text-[11px] text-faint">
                {busy ? '正在生成…' : task.state === 'error' ? '这次没有生成内容' : '空'}
              </p>
            )}
          </div>

          <div className="flex shrink-0 flex-wrap items-center gap-1.5 border-t border-white/10 px-3 py-1.5">
            {task.kind === 'inline' && (
              <>
                {!task.writesBack ? (
                  <span className="text-[10px] text-faint">解释只供参考,不写回笔记</span>
                ) : task.action === 'continue' ? (
                  <button
                    type="button"
                    onClick={insertAfterSelection}
                    disabled={busy || !task.text || task.applied}
                    className="rounded-full border border-accent/50 bg-accent/15 px-3 py-0.5 text-[11px] text-accent transition-all hover:bg-accent/25 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    接在选区后
                  </button>
                ) : (
                  <>
                    <button
                      type="button"
                      onClick={replaceSelection}
                      disabled={busy || !task.text || task.applied}
                      className="rounded-full border border-accent/50 bg-accent/15 px-3 py-0.5 text-[11px] text-accent transition-all hover:bg-accent/25 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      替换选中
                    </button>
                    <button
                      type="button"
                      onClick={insertAfterSelection}
                      disabled={busy || !task.text || task.applied}
                      className="glass-soft rounded-full px-2.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      插到选区后
                    </button>
                  </>
                )}
                <button
                  type="button"
                  onClick={() => void copy(task.text)}
                  disabled={!task.text}
                  className="glass-soft rounded-full px-2.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
                >
                  复制
                </button>
                {busy && task.writesBack && <span className="text-[10px] text-faint">生成完再替换</span>}
              </>
            )}

            {task.kind === 'summary' && (
              <>
                <button
                  type="button"
                  onClick={() => void saveSummary()}
                  disabled={busy || !task.text || task.applied}
                  className="rounded-full border border-accent/50 bg-accent/15 px-3 py-0.5 text-[11px] text-accent transition-all hover:bg-accent/25 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {task.applied ? '已保存为摘要' : '保存为摘要'}
                </button>
                <button
                  type="button"
                  onClick={() => void copy(task.text)}
                  disabled={!task.text}
                  className="glass-soft rounded-full px-2.5 py-0.5 text-[11px] text-muted transition-colors hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
                >
                  复制
                </button>
                <span className="text-[10px] text-faint">摘要会显示在双链预览和检索结果里</span>
              </>
            )}

            {task.kind === 'tags' && (
              <>
                <button
                  type="button"
                  onClick={() => appendTags(task.tags ?? [])}
                  disabled={busy || !task.tags?.length || appliedTags.length === (task.tags?.length ?? 0)}
                  className="rounded-full border border-accent/50 bg-accent/15 px-3 py-0.5 text-[11px] text-accent transition-all hover:bg-accent/25 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  全部写到笔记末尾
                </button>
                <span className="text-[10px] text-faint">也可以点上面的标签逐个写入</span>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}
