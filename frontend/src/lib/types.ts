export interface Workspace {
  id: string;
  name: string;
  storageMode: string;
  createdAt: string;
}

export interface Note {
  id: string;
  workspaceId: string;
  folderId: string | null;
  title: string;
  slug: string;
  contentMd: string;
  summary: string | null;
  indexStatus: string;
  createdAt: string;
  updatedAt: string;
}

export interface NoteVersion {
  id: string;
  version: number;
  createdAt: string;
}

export interface VersionDetail {
  id: string;
  version: number;
  contentMd: string;
  createdAt: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface AuthUser {
  id: string;
  email: string;
  displayName: string | null;
}

export interface AuthResponse {
  token: string;
  user: AuthUser;
}

/** 一条全文检索命中(Elasticsearch chunk) */
export interface SearchHit {
  chunkId: string;
  noteId: string;
  noteTitle: string | null;
  headingPath: string | null;
  snippet: string | null;
  score: number;
}

/** 预览里 `[[双链]]` 的解析结果:`noteId` 为 null 表示还没有这篇笔记(画成"未创建") */
export interface WikiResolution {
  title: string;
  noteId: string | null;
  noteTitle: string | null;
  snippet: string | null;
}

export interface Attachment {
  id: string;
  noteId: string | null;
  filename: string;
  mimeType: string | null;
  sizeBytes: number | null;
  createdAt: string;
}
/** 一条双链(出链或反链) */
export interface RelatedLink {
  /** 目标/来源笔记 id;出链未解析成功时为 null */
  noteId: string | null;
  title: string;
  context: string | null;
}

export interface NoteRelations {
  tags: string[];
  outgoing: RelatedLink[];
  backlinks: RelatedLink[];
}

export interface TagCount {
  name: string;
  count: number;
}

export interface GraphNode {
  id: string;
  title: string;
}

export interface GraphEdge {
  source: string;
  target: string;
}

export interface GraphData {
  workspaceId: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

// ---- AI 对话 ------------------------------------------------------

/**
 * 对话模式。
 *
 * `rag`(默认):先从工作区里检索一批笔记片段喂给模型,答案带引用;
 * `chat`:不碰知识库的普通聊天(侧栏开关关掉时用)。
 */
export type ChatMode = 'rag' | 'chat';

/** 一个会话(侧栏会话列表里的那一行) */
export interface Conversation {
  id: string;
  workspaceId: string;
  /** 首轮提问会自动成为标题,所以可能为 null(还没说过话,或者用户清空了标题) */
  title: string | null;
  createdAt: string;
  /** 最近一次说话的时间,列表按它倒序 */
  updatedAt: string;
}

/** 回答里的一处引用,`index` 与正文里的 `[n]` 一一对应 */
export interface ChatCitation {
  index: number;
  /** 目标笔记;笔记被删了这里可能指向一篇已删除的笔记(引用是快照,不跟着变) */
  noteId: string | null;
  noteTitle: string | null;
  /** 章节路径(点击跳转时用它对齐标题) */
  headingPath: string | null;
  /** 引用片段原文(跳转时在章节内找这段文字) */
  snippet: string | null;
  score: number;
}

/** 一条历史消息(GET /conversations/{id}/messages) */
export interface ChatMessageItem {
  id: string;
  role: 'user' | 'assistant' | string;
  content: string | null;
  model: string | null;
  tokenIn: number | null;
  tokenOut: number | null;
  createdAt: string;
  citations: ChatCitation[];
  /** 只有 Agent 跑过的助手消息才有:工具轨迹 + 有没有待确认的写操作 */
  run?: AgentRunView | null;
}

// ---- AI 文本工具(选中文本操作 / 摘要 / 标签) ----------------------

/**
 * 选中文本上的操作,与后端 `TextAction` 的 wire 值一一对应。
 *
 * `explain` 不写回笔记:解释是给人看的,替换回去等于把笔记改成问答记录。
 * 这条规则由后端在 `meta.writesBack` 里下发,前端不自己维护一份。
 */
export type TextAction = 'polish' | 'translate' | 'continue' | 'summarize' | 'explain';

/** `/ai/inline` 的 `meta` 事件:这次操作的身份 */
export interface InlineMeta {
  action: TextAction | string;
  /** 中文标签(润色 / 翻译 / …),以后端为准 */
  label: string;
  /** 结果能不能写回笔记(解释为 false) */
  writesBack: boolean;
  model: string;
}

/** `/ai/inline` 的 `done` 事件:流结束时的用量 */
export interface InlineDone {
  tokenIn: number | null;
  tokenOut: number | null;
  chars: number;
}

// ---- Agent 运行(M5 Step 1:工具调用 + 写操作确认) --------------------

/** 轨迹里的一步;`status` 与后端 `ToolInvocation` 的取值一一对应 */
export interface AgentToolStep {
  id: string;
  name: string;
  /** 中文名(新建笔记 / 检索…),以后端下发为准 */
  label: string;
  args: Record<string, unknown>;
  /** `pending`(等确认)/ `ok` / `error` / `rejected` */
  status: string;
  requiresConfirmation: boolean;
  summary: string | null;
  /** 工具返回给模型的原文(已截断);回放时比 summary 有用 */
  text: string | null;
  latencyMs: number | null;
}

/**
 * 一次运行的状态 + 完整轨迹。
 *
 * 会话历史里的助手消息带上它(`ChatMessageView.run`),刷新之后才画得出"跑到哪一步了"
 * 和那张**待确认的卡片** —— 确认卡片的按钮不是装饰,刷新丢了它这次运行就点不动了。
 */
export interface AgentRunView {
  id: string;
  goal: string | null;
  /** `running` / `awaiting_confirmation` / `succeeded` / `failed` */
  status: string;
  steps: number;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
  tools: AgentToolStep[];
}
