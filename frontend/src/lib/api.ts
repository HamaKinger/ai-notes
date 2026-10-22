import { clearToken, getToken } from './auth';
import type {
  Attachment,
  AuthResponse,
  AuthUser,
  ChatCitation,
  ChatMessageItem,
  ChatMode,
  Conversation,
  GraphData,
  InlineDone,
  InlineMeta,
  Note,
  NoteRelations,
  NoteVersion,
  PageResponse,
  SearchHit,
  TagCount,
  TextAction,
  VersionDetail,
  WikiResolution,
  Workspace,
} from './types';

const BASE = process.env.NEXT_PUBLIC_API_BASE ?? 'http://localhost:8080/api/v1';

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const isFormData = typeof FormData !== 'undefined' && init?.body instanceof FormData;

  const headers: Record<string, string> = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...((init?.headers as Record<string, string> | undefined) ?? {}),
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const res = await fetch(`${BASE}${path}`, { ...init, headers, cache: 'no-store' });
  const text = await res.text();

  if (res.status === 401) {
    clearToken();
    throw new ApiError(401, '未登录或登录已过期');
  }
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} ${res.statusText}${text ? ` - ${text}` : ''}`);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  // ---- 鉴权 ----------------------------------------------------
  register: (email: string, password: string, displayName?: string) =>
    request<AuthResponse>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName }),
    }),

  login: (email: string, password: string) =>
    request<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  me: () => request<AuthUser>('/auth/me'),

  // ---- 工作区 --------------------------------------------------
  listWorkspaces: () => request<Workspace[]>('/workspaces'),

  createWorkspace: (name: string) =>
    request<Workspace>('/workspaces', {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),

  // ---- 笔记 ----------------------------------------------------
  listNotes: (workspaceId: string, q?: string, tag?: string) => {
    const params = new URLSearchParams();
    if (q) params.set('q', q);
    if (tag) params.set('tag', tag);
    const qs = params.toString();
    return request<PageResponse<Note>>(`/workspaces/${workspaceId}/notes${qs ? `?${qs}` : ''}`);
  },

  createNote: (workspaceId: string, title: string) =>
    request<Note>(`/workspaces/${workspaceId}/notes`, {
      method: 'POST',
      body: JSON.stringify({ title, contentMd: `# ${title}\n\n` }),
    }),

  getNote: (id: string) => request<Note>(`/notes/${id}`),

  updateNote: (id: string, patch: { title?: string; contentMd?: string; summary?: string }) =>
    request<Note>(`/notes/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    }),

  deleteNote: (id: string) => request<void>(`/notes/${id}`, { method: 'DELETE' }),

  // ---- 版本 ----------------------------------------------------
  listVersions: (noteId: string) => request<NoteVersion[]>(`/notes/${noteId}/versions`),

  getVersion: (noteId: string, version: number) =>
    request<VersionDetail>(`/notes/${noteId}/versions/${version}`),

  restoreVersion: (noteId: string, version: number) =>
    request<Note>(`/notes/${noteId}/versions/${version}/restore`, { method: 'POST' }),

  // ---- 检索 ----------------------------------------------------
  /** 全文检索(Elasticsearch BM25 + IK 分词) */
  search: (workspaceId: string, q: string, tags?: string[], topK?: number) =>
    request<SearchHit[]>(`/workspaces/${workspaceId}/search`, {
      method: 'POST',
      body: JSON.stringify({ q, tags, topK }),
    }),

  suggest: (workspaceId: string, q: string) =>
    request<{ id: string; title: string }[]>(
      `/workspaces/${workspaceId}/search/suggest?q=${encodeURIComponent(q)}`,
    ),

  // ---- 双链与标签 ----------------------------------------------
  /** 某篇笔记的标签、出链与反链 */
  relations: (noteId: string) => request<NoteRelations>(`/notes/${noteId}/relations`),

  listTags: (workspaceId: string) => request<TagCount[]>(`/workspaces/${workspaceId}/tags`),

  /** 批量把 `[[标题]]` 解析成笔记(预览里的双链据此决定可点 / 未创建,并拿悬停预览文本) */
  resolveLinks: (workspaceId: string, titles: string[]) =>
    request<WikiResolution[]>(`/workspaces/${workspaceId}/links/resolve`, {
      method: 'POST',
      body: JSON.stringify({ titles }),
    }),

  graph: (workspaceId: string) => request<GraphData>(`/workspaces/${workspaceId}/graph`),

  // ---- 附件 ----------------------------------------------------
  listAttachments: (noteId: string) => request<Attachment[]>(`/notes/${noteId}/attachments`),

  uploadAttachment: (noteId: string, file: File) => {
    const body = new FormData();
    body.append('file', file);
    return request<Attachment>(`/notes/${noteId}/attachments`, { method: 'POST', body });
  },

  attachmentUrl: (id: string) => `${BASE}/attachments/${id}/content`,

  /** 带鉴权头的下载(严格模式下也能用),返回 Blob 由调用方触发保存 */
  downloadAttachment: async (id: string): Promise<Blob> => {
    const token = getToken();
    const res = await fetch(`${BASE}/attachments/${id}/content`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      cache: 'no-store',
    });
    if (!res.ok) {
      throw new ApiError(res.status, `附件下载失败(${res.status})`);
    }
    return res.blob();
  },

  // ---- AI 对话 --------------------------------------------------
  listConversations: (workspaceId: string) =>
    request<Conversation[]>(`/workspaces/${workspaceId}/conversations`),

  /** 新建会话:标题不用给,第一句提问会自动成为标题 */
  createConversation: (workspaceId: string, title?: string) =>
    request<Conversation>(`/workspaces/${workspaceId}/conversations`, {
      method: 'POST',
      body: JSON.stringify({ title }),
    }),

  /** 会话的完整历史(含每条回答的引用) */
  getMessages: (conversationId: string) =>
    request<ChatMessageItem[]>(`/conversations/${conversationId}/messages`),

  renameConversation: (conversationId: string, title: string) =>
    request<Conversation>(`/conversations/${conversationId}`, {
      method: 'PATCH',
      body: JSON.stringify({ title }),
    }),

  deleteConversation: (conversationId: string) =>
    request<void>(`/conversations/${conversationId}`, { method: 'DELETE' }),

  // ---- AI 文本工具 ----------------------------------------------
  /**
   * 给一篇笔记生成一句话摘要。
   *
   * 传正文而不是 noteId:自动保存有 800ms 防抖,用户选完就点的时候后端手里那份还是旧的;
   * 传正文也顺带让"要不要保存"留在前端(点「保存为摘要」才走 PATCH /notes/{id})。
   */
  summarize: (title: string, content: string, signal?: AbortSignal) =>
    request<{ summary: string }>('/ai/summarize', {
      method: 'POST',
      body: JSON.stringify({ title, content }),
      signal,
    }),

  /** 推荐标签;`existing` 是工作区已有标签 —— 传给模型是为了尽量复用,不是过滤 */
  suggestTags: (title: string, content: string, existing: string[], signal?: AbortSignal) =>
    request<{ tags: string[] }>('/ai/suggest-tags', {
      method: 'POST',
      body: JSON.stringify({ title, content, existing }),
      signal,
    }),
};

// ---- 流式问答(SSE) -----------------------------------------------

/** `meta` 事件:这一轮的身份信息(会话、消息 id、模式、检索到几段) */
export interface ChatMeta {
  conversationId: string;
  conversationTitle: string | null;
  userMessageId: string;
  assistantMessageId: string;
  model: string;
  mode: string;
  sources: number;
}

/** `done` 事件:流结束时的用量 */
export interface ChatDone {
  messageId: string;
  tokenIn: number | null;
  tokenOut: number | null;
  chars: number;
}

/** `error` 事件:模型/落库失败(已经生成的部分仍在 `token` 里发过) */
export interface ChatStreamError {
  code: string;
  message: string;
}

export interface ChatHandlers {
  onMeta?: (meta: ChatMeta) => void;
  onCitation?: (citation: ChatCitation) => void;
  onToken?: (delta: string) => void;
  onDone?: (done: ChatDone) => void;
  onError?: (error: ChatStreamError) => void;
}

/**
 * 打开一个 SSE 流。
 *
 * **为什么不用 `EventSource`**:它只能发 GET、不能带请求头 —— 而这里既要 POST 正文,
 * 又要 `Authorization`。所以用 fetch 读 `response.body`,自己按 SSE 的帧格式切分。
 */
async function openSse(path: string, body: unknown, signal?: AbortSignal) {
  const token = getToken();
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    cache: 'no-store',
    signal,
  });

  if (res.status === 401) {
    clearToken();
    throw new ApiError(401, '未登录或登录已过期');
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new ApiError(res.status, `${res.status} ${res.statusText}${text ? ` - ${text}` : ''}`);
  }
  if (!res.body) {
    throw new ApiError(500, '响应里没有可读的流');
  }
  return res.body.getReader();
}

/**
 * 逐帧读取,每一帧交给 `onFrame`。
 *
 * 帧之间用空行分隔(`event: xxx\ndata: {...}\n\n`);网络会把字节切在任意位置,
 * 所以必须留一段"还没被空行终结"的尾巴,等下一批字节拼上再解析 —— 否则长 JSON 会被截断。
 * JSON 解析失败时**静默丢弃**这一帧:半截帧只可能出现在流被掐断的最后一刻,
 * 那时本来也已经要报错了。
 */
async function readFrames(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onFrame: (event: string, payload: unknown) => void,
): Promise<void> {
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      dispatchFrame(buffer.slice(0, boundary), onFrame);
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
  }
  if (buffer.trim()) {
    dispatchFrame(buffer, onFrame);
  }
}

/** 切出一帧:event 名 + 已解析的 JSON;空帧 / 坏帧返回 null */
function parseFrame(frame: string): { event: string; payload: unknown } | null {
  let event = 'message';
  const data: string[] = [];

  for (const rawLine of frame.split('\n')) {
    const line = rawLine.replace(/\r$/, '');
    if (line.startsWith(':')) continue; // 注释/心跳
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).replace(/^ /, ''));
    }
  }
  if (data.length === 0) return null;

  try {
    return { event, payload: JSON.parse(data.join('\n')) };
  } catch {
    return null;
  }
}

function dispatchFrame(frame: string, onFrame: (event: string, payload: unknown) => void): void {
  const parsed = parseFrame(frame);
  if (parsed) {
    onFrame(parsed.event, parsed.payload);
  }
}

/** 流式问答(事件种类见 {@link ChatHandlers}) */
export async function streamChat(
  conversationId: string,
  body: { q: string; mode: ChatMode },
  handlers: ChatHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const reader = await openSse(`/conversations/${conversationId}/chat`, body, signal);
  await readFrames(reader, (event, payload) => {
    switch (event) {
      case 'meta':
        handlers.onMeta?.(payload as ChatMeta);
        break;
      case 'citation':
        handlers.onCitation?.(payload as ChatCitation);
        break;
      case 'token':
        handlers.onToken?.((payload as { delta?: string }).delta ?? '');
        break;
      case 'done':
        handlers.onDone?.(payload as ChatDone);
        break;
      case 'error':
        handlers.onError?.(payload as ChatStreamError);
        break;
      default:
        break;
    }
  });
}

export interface InlineHandlers {
  onMeta?: (meta: InlineMeta) => void;
  onToken?: (delta: string) => void;
  onDone?: (done: InlineDone) => void;
  onError?: (error: ChatStreamError) => void;
}

// ---- Agent 运行(SSE) -----------------------------------------------

/**
 * `meta` 事件:这一轮的身份。**第二段流(确认之后)会重发一次**,`runId` / `assistantMessageId`
 * 与第一段相同 —— 前端因此不用为"续跑"写第二套处理。
 */
export interface AgentMeta {
  runId: string;
  conversationId: string;
  conversationTitle: string | null;
  userMessageId: string;
  assistantMessageId: string;
  model: string;
  goal: string;
}

/** 一次工具调用。写操作的 `requiresConfirmation` 为 true(确认卡片据此渲染)。 */
export interface AgentToolCall {
  id: string;
  name: string;
  label: string;
  args: Record<string, unknown>;
  requiresConfirmation: boolean;
  summary: string | null;
}

/** 一次工具执行的结果(`ok=false` 代表工具在业务上失败,不是流断了)。 */
export interface AgentToolResult {
  id: string;
  name: string;
  label: string;
  ok: boolean;
  /** `ok` / `error` / `rejected` —— 光看 `ok` 分不出"失败了"和"用户取消了" */
  status: string;
  summary: string | null;
  latencyMs: number | null;
}

/** `confirm` 事件:一轮结束、有写操作在等用户点一下。**流到这里暂停**,不是结束。 */
export interface AgentConfirm {
  runId: string;
  pending: AgentToolCall[];
}

/** `done` 事件:`steps` 是这次运行一共走了几步(一次模型调用算一步)。 */
export interface AgentDone {
  runId: string;
  messageId: string;
  tokenIn: number | null;
  tokenOut: number | null;
  chars: number;
  steps: number;
}

export interface AgentHandlers {
  onMeta?: (meta: AgentMeta) => void;
  onCitation?: (citation: ChatCitation) => void;
  onToolCall?: (call: AgentToolCall) => void;
  onToolResult?: (result: AgentToolResult) => void;
  onToken?: (delta: string) => void;
  onConfirm?: (confirm: AgentConfirm) => void;
  onDone?: (done: AgentDone) => void;
  onError?: (error: ChatStreamError) => void;
}

/**
 * 解析一条 Agent 流(起跑与续跑共用)。
 *
 * 事件比对话多三种:`tool_call` / `tool_result` 是轨迹,`confirm` 是**暂停**信号。
 * `citation` 复用对话的形状 —— 检索工具一拿到片段就发,前端的 [n] 当场可点。
 */
async function readAgentStream(
  path: string,
  body: unknown,
  handlers: AgentHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const reader = await openSse(path, body, signal);
  await readFrames(reader, (event, payload) => {
    switch (event) {
      case 'meta':
        handlers.onMeta?.(payload as AgentMeta);
        break;
      case 'citation':
        handlers.onCitation?.(payload as ChatCitation);
        break;
      case 'tool_call':
        handlers.onToolCall?.(payload as AgentToolCall);
        break;
      case 'tool_result':
        handlers.onToolResult?.(payload as AgentToolResult);
        break;
      case 'token':
        handlers.onToken?.((payload as { delta?: string }).delta ?? '');
        break;
      case 'confirm':
        handlers.onConfirm?.(payload as AgentConfirm);
        break;
      case 'done':
        handlers.onDone?.(payload as AgentDone);
        break;
      case 'error':
        handlers.onError?.(payload as ChatStreamError);
        break;
      default:
        break;
    }
  });
}

/**
 * 起一个 Agent 目标(`POST /workspaces/{ws}/agent/run`)。
 *
 * 不给 `conversationId` 就新建一个会话 —— 与对话共享同一张表,所以侧栏里两边混在一起。
 * 流会在写操作处停下(`onConfirm`),这是**暂停**:拿着 `runId` 调 {@link confirmAgent} 接着跑。
 */
export async function streamAgent(
  workspaceId: string,
  body: { goal: string; conversationId?: string },
  handlers: AgentHandlers,
  signal?: AbortSignal,
): Promise<void> {
  await readAgentStream(`/workspaces/${workspaceId}/agent/run`, body, handlers, signal);
}

/** 用户点了确认 / 取消之后接着跑(`POST /agent/runs/{id}/confirm`),还是一条流。 */
export async function confirmAgent(
  runId: string,
  approve: boolean,
  handlers: AgentHandlers,
  signal?: AbortSignal,
): Promise<void> {
  await readAgentStream(`/agent/runs/${runId}/confirm`, { approve }, handlers, signal);
}

/**
 * 选中文本操作(润色 / 翻译 / 续写 / 总结 / 解释)。
 *
 * 事件与对话同构(`meta` → `token`* → `done` / `error`),只是没有引用。结果**不落库**:
 * 要不要替换选中、要不要接在段落后面,由用户点一下再走普通的保存链路(带版本、索引重建)。
 */
export async function streamInline(
  body: { text: string; action: TextAction; to?: string },
  handlers: InlineHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const reader = await openSse('/ai/inline', body, signal);
  await readFrames(reader, (event, payload) => {
    switch (event) {
      case 'meta':
        handlers.onMeta?.(payload as InlineMeta);
        break;
      case 'token':
        handlers.onToken?.((payload as { delta?: string }).delta ?? '');
        break;
      case 'done':
        handlers.onDone?.(payload as InlineDone);
        break;
      case 'error':
        handlers.onError?.(payload as ChatStreamError);
        break;
      default:
        break;
    }
  });
}
