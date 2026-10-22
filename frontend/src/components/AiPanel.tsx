'use client';

import { useEffect, useRef, useState } from 'react';
import ChatMarkdown, { citationLabel } from './ChatMarkdown';
import { AgentConfirmCard, AgentTrace } from './AgentTrace';
import { api, confirmAgent, streamAgent, streamChat } from '@/lib/api';
import type { AgentConfirm, AgentDone, AgentMeta, AgentToolCall, AgentToolResult } from '@/lib/api';
import type { AgentToolStep, ChatCitation, ChatMessageItem, ChatMode, Conversation } from '@/lib/types';

interface Props {
  workspaceId: string;
  /** 点引用 / 引用 chip:打开那篇笔记并定位到章节(复用全文检索的定位机制,见 page.tsx) */
  onOpenCitation: (citation: ChatCitation) => void;
}

/** 面板的三种模式:`agent` 不在 ChatMode 里 —— 它走的是另一个端点(Agent 运行) */
type PanelMode = ChatMode | 'agent';

const MODES: { id: PanelMode; label: string; title: string }[] = [
  { id: 'rag', label: '知识库', title: '先检索笔记再回答,带引用' },
  { id: 'chat', label: '聊天', title: '不查知识库的普通聊天' },
  { id: 'agent', label: '工具', title: '让 AI 自己查、自己写:写操作会先让你点确认' },
];

/**
 * 面板里的一条气泡。后端历史与"正在流的那条"共用这个结构 —— 少一套分支,
 * 流式结束后也不用再转一次格式。
 */
interface Bubble {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations: ChatCitation[];
  /** 正在流式生成(显示光标 / 思考中) */
  streaming?: boolean;
  /** 这一条的错误(模型失败、被中止) */
  error?: string | null;
  tokenIn?: number | null;
  tokenOut?: number | null;
  /** Agent 的工具轨迹(按发生顺序) */
  trace?: AgentToolStep[];
  /** 正在等用户确认的写操作(空数组 = 没有) */
  pending?: AgentToolStep[];
  /** 这次运行的 id:续跑(确认/取消)要用它 */
  runId?: string | null;
  /** 运行状态:running / awaiting_confirmation / succeeded / failed */
  runStatus?: string | null;
  steps?: number | null;
}

const SUGGESTIONS: Record<PanelMode, string[]> = {
  rag: ['这个知识库里有哪些内容?', '帮我总结一下最近写的想法', '混合检索是怎么融合的?'],
  chat: ['随便聊聊:怎么给笔记起好标题?', '用一句话解释什么是 RRF'],
  agent: [
    '把最近一周的笔记整理成一篇周报,新建一篇笔记叫「本周周报」',
    '新建一篇笔记叫「今日待办」,写三行今天要做的事,加标签 待办',
    '先看看有哪些笔记,再给「架构设计」加上标签 架构',
  ],
};

function fromApi(row: ChatMessageItem): Bubble {
  const run = row.run ?? null;
  return {
    id: row.id,
    role: row.role === 'assistant' ? 'assistant' : 'user',
    content: row.content ?? '',
    citations: row.citations ?? [],
    tokenIn: row.tokenIn,
    tokenOut: row.tokenOut,
    trace: run?.tools ?? [],
    // 刷新之后卡片要还在:待确认的那几步就在轨迹里(status=pending)
    pending: run?.tools.filter((step) => step.status === 'pending') ?? [],
    runId: run?.id ?? null,
    runStatus: run?.status ?? null,
    steps: run?.steps ?? null,
  };
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** 会话列表里的相对时间:只用最粗的刻度,精确到秒在这个位置没人看 */
function relativeTime(iso: string): string {
  const time = new Date(iso).getTime();
  if (Number.isNaN(time)) return '';
  const minutes = Math.floor((Date.now() - time) / 60000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  return days < 30 ? `${days} 天前` : new Date(time).toLocaleDateString();
}

/**
 * AI 侧栏:基于知识库的流式问答(M4)。
 *
 * 几处刻意的做法:
 * - **不预先建空会话**:点「＋」只是清空面板,等真的问了第一句才建会话 —— 否则翻两次
 *   就攒下一串空的"新对话"。所以 `activeId` 允许为 null。
 * - **流式期间锁住切换**:换会话会把 `bubbles` 整个换掉,流式回调就找不到该写进哪条气泡了
 *   (后端还在照常生成,前端却什么都看不见)。索性流式期间禁掉切换与删除。
 * - **中止等于放弃这一条**:点了停止会 abort 请求,后端的收尾(落库)也就不会执行,
 *   半截答案只留在当前界面里;刷新后只剩提问。这是有意的取舍,见 ChatService 的说明。
 */
export default function AiPanel({ workspaceId, onOpenCitation }: Props) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [bubbles, setBubbles] = useState<Bubble[]>([]);
  const [input, setInput] = useState('');
  const [mode, setMode] = useState<PanelMode>('rag');
  const [streaming, setStreaming] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  /** 用户是不是贴着底部看 —— 决定新内容来了要不要自动跟着滚 */
  const atBottomRef = useRef(true);
  /** 刚在本地开的新会话 id:切换 effect 看到它就跳过拉取,免得把正在流的气泡冲掉 */
  const skipLoad = useRef<string | null>(null);

  // 会话列表(进入工作区时拉一次)。
  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const list = await api.listConversations(workspaceId);
        if (!active) return;
        setConversations(list);
        if (list.length > 0) {
          setActiveId(list[0].id);
        } else {
          setBubbles([]);
        }
      } catch (e) {
        if (active) setError(errorText(e));
      }
    })();
    return () => {
      active = false;
    };
  }, [workspaceId]);

  // 切会话就拉它的历史(含引用)。
  useEffect(() => {
    if (!activeId) {
      setBubbles([]);
      return;
    }
    if (skipLoad.current === activeId) {
      skipLoad.current = null;
      return;
    }
    let active = true;
    void (async () => {
      try {
        const rows = await api.getMessages(activeId);
        if (!active) return;
        setBubbles(rows.map(fromApi));
        setError(null);
      } catch (e) {
        if (active) setError(errorText(e));
      }
    })();
    return () => {
      active = false;
    };
  }, [activeId]);

  // 新内容来了跟着滚(前提是用户本来就在底部,不然读旧消息会被硬拽下来)
  useEffect(() => {
    const box = scrollRef.current;
    if (box && atBottomRef.current) {
      box.scrollTop = box.scrollHeight;
    }
  }, [bubbles]);

  function onScroll() {
    const box = scrollRef.current;
    if (!box) return;
    atBottomRef.current = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  }

  /** 只改"正在流的那条"气泡,不用记 id(meta 回来时 id 会换成后端的) */
  function patchStreaming(patch: (bubble: Bubble) => Bubble) {
    setBubbles((prev) => prev.map((bubble) => (bubble.role === 'assistant' && bubble.streaming ? patch(bubble) : bubble)));
  }

  /**
   * 按运行 id 改那条气泡。
   *
   * <p>确认/取消时不能用 {@link patchStreaming}:暂停的气泡**已经不在流式中**了(它的 streaming
   * 是 false),而"流式中"的标志要等这条续跑流开会才立起来。
   */
  function patchByRun(runId: string, patch: (bubble: Bubble) => Bubble) {
    setBubbles((prev) =>
      prev.map((bubble) => (bubble.role === 'assistant' && bubble.runId === runId ? patch(bubble) : bubble)),
    );
  }

  /** 首轮目标/提问会被后端拿去当标题;列表里立刻显示,不用等下一次拉取 */
  function touchConversation(conversationId: string, title: string | null) {
    setConversations((prev) =>
      prev.map((conversation) =>
        conversation.id === conversationId
          ? { ...conversation, title: conversation.title ?? title, updatedAt: new Date().toISOString() }
          : conversation,
      ),
    );
  }

  /**
   * Agent 流的回调(起跑与续跑共用 —— 后端在第二段会重发 meta,所以这里不需要两套)。
   *
   * <p>`confirm` 是**暂停**不是结束:收到它就把卡片画出来、把 streaming 放下。那一段流
   * 后端已经收尾了,用户在卡片上点一下才会开第二段。
   */
  function agentHandlers() {
    // 工具调用事件里还没有结果:读工具是"正在跑",写工具是"等你点"
    const stepOf = (call: AgentToolCall): AgentToolStep => ({
      id: call.id,
      name: call.name,
      label: call.label,
      args: call.args ?? {},
      status: call.requiresConfirmation ? 'pending' : 'running',
      requiresConfirmation: call.requiresConfirmation,
      summary: call.summary,
      text: null,
      latencyMs: null,
    });

    return {
      onMeta: (meta: AgentMeta) => {
        patchStreaming((bubble) => ({ ...bubble, id: meta.assistantMessageId, runId: meta.runId, runStatus: 'running' }));
        touchConversation(meta.conversationId, meta.conversationTitle);
      },
      onCitation: (citation: ChatCitation) =>
        patchStreaming((bubble) => ({ ...bubble, citations: [...bubble.citations, citation] })),
      onToolCall: (call: AgentToolCall) => {
        setStatus(`${call.label}…`);
        patchStreaming((bubble) => ({ ...bubble, trace: [...(bubble.trace ?? []), stepOf(call)] }));
      },
      onToolResult: (result: AgentToolResult) =>
        patchStreaming((bubble) => ({
          ...bubble,
          trace: (bubble.trace ?? []).map((step) =>
            step.id === result.id
              ? { ...step, status: result.status ?? (result.ok ? 'ok' : 'error'), summary: result.summary, latencyMs: result.latencyMs }
              : step,
          ),
        })),
      onToken: (delta: string) => {
        setStatus('生成中…');
        patchStreaming((bubble) => ({ ...bubble, content: bubble.content + delta }));
      },
      onConfirm: (confirm: AgentConfirm) => {
        const pending = confirm.pending.map(stepOf);
        patchStreaming((bubble) => ({
          ...bubble,
          streaming: false,
          runStatus: 'awaiting_confirmation',
          pending,
        }));
        // 后端那一段流到此结束;不放下"流式中"的话别的操作全被锁住
        setStreaming(false);
        setStatus('');
      },
      onDone: (done: AgentDone) => {
        patchStreaming((bubble) => ({
          ...bubble,
          streaming: false,
          runStatus: 'succeeded',
          pending: [],
          tokenIn: done.tokenIn,
          tokenOut: done.tokenOut,
          steps: done.steps,
        }));
        setStatus('');
      },
      onError: (streamError: { code: string; message: string }) => {
        patchStreaming((bubble) => ({ ...bubble, streaming: false, runStatus: 'failed', error: streamError.message }));
        setStatus('');
      },
    };
  }

  async function send(questionOverride?: string) {
    const question = (questionOverride ?? input).trim();
    if (!question || streaming) return;

    setError(null);
    setStatus('');
    if (questionOverride === undefined) {
      setInput('');
    }

    // 第一句才建会话:标题由后端用这句话自动生成(见 Conversation#titleFromFirstQuestion)。
    // Agent 运行也走同一张会话表,所以两边共用「＋」与历史。
    let conversationId = activeId;
    if (!conversationId) {
      try {
        const created = await api.createConversation(workspaceId);
        conversationId = created.id;
        skipLoad.current = created.id;
        setConversations((prev) => [created, ...prev]);
        setActiveId(created.id);
        setBubbles([]);
      } catch (e) {
        setError(errorText(e));
        return;
      }
    }

    const stamp = Date.now();
    setBubbles((prev) => [
      ...prev,
      { id: `local-user-${stamp}`, role: 'user', content: question, citations: [] },
      { id: `local-assistant-${stamp}`, role: 'assistant', content: '', citations: [], streaming: true },
    ]);
    atBottomRef.current = true;

    const controller = new AbortController();
    abortRef.current = controller;
    setStreaming(true);
    setStatus(mode === 'agent' ? '正在规划…' : '检索知识库…');

    try {
      if (mode === 'agent') {
        await streamAgent(workspaceId, { goal: question, conversationId }, agentHandlers(), controller.signal);
      } else {
        await streamChat(
          conversationId,
          { q: question, mode },
          {
            onMeta: (meta) => {
              patchStreaming((bubble) => ({ ...bubble, id: meta.assistantMessageId }));
              setStatus(
                meta.sources > 0
                  ? `找到 ${meta.sources} 段依据,生成中…`
                  : mode === 'rag'
                    ? '知识库里没有相关片段,直接作答…'
                    : '生成中…',
              );
              touchConversation(meta.conversationId, meta.conversationTitle);
            },
            onCitation: (citation) => patchStreaming((bubble) => ({ ...bubble, citations: [...bubble.citations, citation] })),
            onToken: (delta) => {
              setStatus('生成中…');
              patchStreaming((bubble) => ({ ...bubble, content: bubble.content + delta }));
            },
            onDone: (done) => {
              patchStreaming((bubble) => ({ ...bubble, streaming: false, tokenIn: done.tokenIn, tokenOut: done.tokenOut }));
              // 不清掉的话状态栏会一直停在"生成中…"(实测:done 之后还在)
              setStatus('');
            },
            onError: (streamError) => {
              patchStreaming((bubble) => ({ ...bubble, streaming: false, error: streamError.message }));
              setStatus('');
            },
          },
          controller.signal,
        );
      }
    } catch (e) {
      if (controller.signal.aborted) {
        patchStreaming((bubble) => ({ ...bubble, streaming: false, error: '已停止生成(这一条不会保存)' }));
        setStatus('已停止');
      } else {
        const text = errorText(e);
        patchStreaming((bubble) => ({ ...bubble, streaming: false, error: text }));
        setError(text);
      }
    } finally {
      setStreaming(false);
      abortRef.current = null;
      // 兜底:流断了但 done 没来,别让光标一直闪
      setBubbles((prev) => prev.map((bubble) => (bubble.streaming ? { ...bubble, streaming: false } : bubble)));
      // 列表的排序与标题以服务端为准,顺手对一次(失败就算了,不影响对话)
      void api
        .listConversations(workspaceId)
        .then(setConversations)
        .catch(() => undefined);
    }
  }

  /**
   * 确认卡片上的那一下:接着跑第二段流(同一个 runId、同一条助手消息)。
   *
   * <p>先把卡片收掉、把那几步标成"正在跑":后端在执行写操作,用户需要立刻看到反馈。
   * 真正的结果由随后的 `tool_result` 覆盖(取消时后端给的是 rejected)。
   */
  async function decideRun(runId: string, approve: boolean) {
    if (streaming) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setStreaming(true);
    setStatus(approve ? '正在执行…' : '已取消,继续…');
    setError(null);

    patchByRun(runId, (bubble) => ({
      ...bubble,
      streaming: true,
      runStatus: 'running',
      pending: [],
      trace: (bubble.trace ?? []).map((step) =>
        step.status === 'pending' ? { ...step, status: approve ? 'running' : 'rejected' } : step,
      ),
    }));

    try {
      await confirmAgent(runId, approve, agentHandlers(), controller.signal);
    } catch (e) {
      const text = errorText(e);
      patchByRun(runId, (bubble) => ({ ...bubble, streaming: false, error: text }));
      setError(text);
    } finally {
      setStreaming(false);
      abortRef.current = null;
      setBubbles((prev) => prev.map((bubble) => (bubble.streaming ? { ...bubble, streaming: false } : bubble)));
    }
  }

  function stop() {
    abortRef.current?.abort();
  }

  /** 「＋」:只是回到"还没建会话"的状态,第一句话才真的建 */
  function startNewConversation() {
    if (streaming) return;
    setActiveId(null);
    setBubbles([]);
    setInput('');
    setStatus('');
    setError(null);
    setConfirmDelete(false);
    setListOpen(false);
  }

  function openConversation(id: string) {
    if (streaming) return;
    skipLoad.current = null;
    setActiveId(id);
    setConfirmDelete(false);
    setListOpen(false);
    setInput('');
    setStatus('');
    setError(null);
  }

  async function removeConversation() {
    if (!activeId || streaming) return;
    try {
      await api.deleteConversation(activeId);
      const rest = conversations.filter((conversation) => conversation.id !== activeId);
      setConversations(rest);
      setConfirmDelete(false);
      setActiveId(rest.length > 0 ? rest[0].id : null);
    } catch (e) {
      setError(errorText(e));
    }
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
    // isComposing:中文输入法选词时的回车不能当成"发送"
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void send();
    }
  }

  const canSend = input.trim().length > 0 && !streaming;
  const activeTitle = conversations.find((conversation) => conversation.id === activeId)?.title ?? null;

  /** 空面板上的三行说明:每个模式要说清"我能做什么",不然用户不知道该不该切 */
  const intro =
    mode === 'agent'
      ? {
          title: '让我动手',
          body: '说出你想要的结果:我会自己检索、读笔记、整理,再把结果写回笔记库。',
          hint: '新建 / 修改 / 打标签会先停下来等你点确认;只读的检索与阅读自动执行。',
        }
      : mode === 'rag'
        ? {
            title: '问知识库',
            body: '我会先检索你的笔记,再把答案依据的片段列在下面,点引用直接跳到原文。',
            hint: '切到「聊天」就不查笔记了;切到「工具」就可以让我动手改笔记。',
          }
        : {
            title: '随便聊聊',
            body: '不查知识库的普通对话 —— 问概念、让我起标题、解释一段术语都行。',
            hint: '切回「知识库」:答案会带上引用片段。',
          };

  return (
    <aside className="glass flex w-80 shrink-0 flex-col border-y-0 border-r-0 xl:w-96">
      <div className="flex items-center gap-2 border-b border-white/10 px-3 py-2.5">
        <span className="text-[10px] font-semibold uppercase tracking-[0.18em] text-faint">AI</span>

        {/* 三种模式:知识库(带引用的检索问答)/ 聊天 / 工具(Agent:会读写笔记) */}
        <span className="glass-soft flex items-center gap-0.5 rounded-full p-0.5">
          {MODES.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => setMode(item.id)}
              disabled={streaming}
              title={item.title}
              aria-pressed={mode === item.id}
              className={`rounded-full px-1.5 py-0.5 text-[10px] transition-colors disabled:opacity-50 ${
                mode === item.id ? 'bg-accent/20 text-accent' : 'text-faint hover:text-ink'
              }`}
            >
              {item.label}
            </button>
          ))}
        </span>

        <span className="ml-auto flex items-center gap-1">
          <button
            type="button"
            onClick={() => setListOpen((open) => !open)}
            aria-expanded={listOpen}
            className="glass-soft rounded-full px-2 py-0.5 text-[10px] text-muted transition-colors hover:text-ink"
          >
            会话{conversations.length > 0 ? ` ${conversations.length}` : ''} {listOpen ? '▴' : '▾'}
          </button>
          <button
            type="button"
            onClick={startNewConversation}
            disabled={streaming}
            title="新对话"
            className="glass-soft rounded-full px-2 py-0.5 text-[10px] text-muted transition-colors hover:text-ink disabled:opacity-40"
          >
            ＋
          </button>
        </span>
      </div>

      {listOpen && (
        <div className="border-b border-white/10 p-1.5">
          {conversations.length === 0 ? (
            <p className="px-2 py-1.5 text-[11px] text-faint">还没有会话,下面问一句就建好了。</p>
          ) : (
            <div className="max-h-40 overflow-y-auto">
              {conversations.map((conversation) => (
                <button
                  key={conversation.id}
                  type="button"
                  onClick={() => openConversation(conversation.id)}
                  className={`flex w-full items-baseline gap-1.5 rounded-lg px-2 py-1.5 text-left text-[11px] transition-colors ${
                    conversation.id === activeId ? 'glass-active text-ink' : 'text-muted hover:bg-white/5'
                  }`}
                >
                  <span className="truncate">{conversation.title ?? '新对话'}</span>
                  <span className="ml-auto shrink-0 text-[10px] text-faint">{relativeTime(conversation.updatedAt)}</span>
                </button>
              ))}
            </div>
          )}

          {activeId && !streaming && (
            <div className="mt-1 flex items-center gap-2 border-t border-white/10 px-1.5 pt-1.5">
              {confirmDelete ? (
                <>
                  <span className="text-[10px] text-red-300/90">删掉这个会话?</span>
                  <button
                    type="button"
                    onClick={() => void removeConversation()}
                    className="glass-soft ml-auto rounded-full px-2 py-0.5 text-[10px] text-red-300/90"
                  >
                    确认删除
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmDelete(false)}
                    className="rounded-full px-2 py-0.5 text-[10px] text-faint hover:text-ink"
                  >
                    取消
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  onClick={() => setConfirmDelete(true)}
                  className="rounded-full px-1 py-0.5 text-[10px] text-faint transition-colors hover:text-red-300/90"
                >
                  删除当前会话
                </button>
              )}
            </div>
          )}
        </div>
      )}

      <div ref={scrollRef} onScroll={onScroll} className="flex-1 space-y-3 overflow-y-auto p-3">
        {bubbles.length === 0 && (
          <div className="space-y-3">
            <div className="glass relative overflow-hidden rounded-2xl p-4">
              <div className="grad-accent pointer-events-none absolute -right-8 -top-10 h-24 w-24 rounded-full opacity-25 blur-2xl" />
              <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-faint">
                {activeTitle ?? '新对话'}
              </p>
              <p className="mt-1.5 text-xs leading-relaxed text-muted">{intro.body}</p>
              <p className="mt-1.5 text-[10px] text-faint">{intro.hint}</p>
            </div>

            <div className="space-y-1.5">
              {SUGGESTIONS[mode].map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => void send(suggestion)}
                  className="glass-soft block w-full rounded-xl px-3 py-2 text-left text-[11px] text-muted transition-colors hover:text-ink"
                >
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        )}

        {bubbles.map((bubble) =>
          bubble.role === 'user' ? (
            <div key={bubble.id} className="flex justify-end">
              <div className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-accent/20 px-3 py-2 text-[13px] leading-relaxed text-ink">
                {bubble.content}
              </div>
            </div>
          ) : (
            <div key={bubble.id} className="space-y-1.5">
              {(bubble.trace?.length ?? 0) > 0 && (
                <div className="glass-soft rounded-2xl px-2 py-1.5">
                  <AgentTrace steps={bubble.trace ?? []} />
                </div>
              )}

              <div className="glass rounded-2xl rounded-bl-sm px-3 py-2">
                {bubble.content ? (
                  <ChatMarkdown
                    content={bubble.streaming ? `${bubble.content} ▍` : bubble.content}
                    citations={bubble.citations}
                    onOpenCitation={onOpenCitation}
                  />
                ) : (
                  <p className="flex items-center gap-2 text-[11px] text-muted">
                    {bubble.streaming && <span className="dot-pulse h-1.5 w-1.5 rounded-full bg-accent" />}
                    {bubble.streaming
                      ? '思考中…'
                      : bubble.runStatus === 'awaiting_confirmation'
                        ? '等你确认后继续'
                        : '没有回答'}
                  </p>
                )}
                {bubble.error && <p className="mt-1.5 text-[11px] text-red-300/90">{bubble.error}</p>}
              </div>

              {/* 写操作等确认:卡片本身就是那道确认,不再叠二次弹窗 */}
              {(bubble.pending?.length ?? 0) > 0 && bubble.runId && (
                <AgentConfirmCard
                  pending={bubble.pending ?? []}
                  busy={streaming}
                  onDecide={(approve) => void decideRun(bubble.runId ?? '', approve)}
                />
              )}

              {bubble.citations.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  {bubble.citations.map((citation) => (
                    <button
                      key={`${bubble.id}-${citation.index}`}
                      type="button"
                      onClick={() => onOpenCitation(citation)}
                      title={`跳到《${citationLabel(citation)}》`}
                      className="glass-soft flex max-w-full items-center gap-1 rounded-full px-2 py-0.5 text-[10px] text-muted transition-colors hover:text-ink"
                    >
                      <span className="shrink-0 text-accent">[{citation.index}]</span>
                      <span className="truncate">{citationLabel(citation)}</span>
                    </button>
                  ))}
                </div>
              )}

              {((bubble.tokenIn ?? 0) > 0 || (bubble.steps ?? 0) > 0) && (
                <p className="text-[10px] text-faint">
                  {bubble.steps ? `${bubble.steps} 步 · ` : ''}
                  {bubble.tokenIn ?? 0} in / {bubble.tokenOut ?? 0} out
                </p>
              )}
            </div>
          ),
        )}
      </div>

      <div className="border-t border-white/10 p-3">
        {error && <p className="mb-1.5 text-[11px] text-red-300/90">{error}</p>}

        <div className="glass-soft flex items-end gap-2 rounded-2xl p-2">
          <textarea
            value={input}
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={onKeyDown}
            rows={1}
            placeholder={
              mode === 'agent'
                ? '想让我做什么?(Enter 发送)'
                : mode === 'rag'
                  ? '问知识库…(Enter 发送)'
                  : '随便聊聊…(Enter 发送)'
            }
            className="max-h-32 min-h-6 flex-1 resize-none bg-transparent text-xs leading-relaxed text-ink outline-none placeholder:text-faint"
          />
          {streaming ? (
            <button
              type="button"
              onClick={stop}
              className="glass-soft shrink-0 rounded-xl px-2.5 py-1 text-[11px] font-semibold text-muted transition-colors hover:text-ink"
            >
              停止
            </button>
          ) : (
            <button
              type="button"
              onClick={() => void send()}
              disabled={!canSend}
              className="btn-accent sheen shrink-0 rounded-xl px-2.5 py-1 text-[11px] font-semibold disabled:opacity-40"
            >
              发送
            </button>
          )}
        </div>

        <p className="mt-1 flex items-center gap-2 text-[10px] text-faint">
          <span className="truncate">
            {status ||
              (mode === 'agent' ? '写操作会先让你确认' : mode === 'rag' ? 'Shift+Enter 换行' : '纯聊天模式')}
          </span>
          <span className="ml-auto shrink-0">M5 · 工具调用</span>
        </p>
      </div>
    </aside>
  );
}
