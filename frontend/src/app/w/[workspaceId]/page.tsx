'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import AiPanel from '@/components/AiPanel';
import AttachmentStrip from '@/components/AttachmentStrip';
import AuthChip from '@/components/AuthChip';
import FullTextSearch from '@/components/FullTextSearch';
import GraphView from '@/components/GraphView';
import MarkdownEditor from '@/components/MarkdownEditor';
import NoteList from '@/components/NoteList';
import RelationsPanel from '@/components/RelationsPanel';
import RequireAuth from '@/components/RequireAuth';
import ThemeMenu from '@/components/ThemeMenu';
import VersionHistory from '@/components/VersionHistory';
import { api, ApiError } from '@/lib/api';
import type { LocateTarget } from '@/lib/locate';
import type { Note, SearchHit, TagCount, WikiResolution, ChatCitation } from '@/lib/types';
import type { WikiResolveState } from '@/components/WikiLink';
import { collectWikiTargets } from '@/lib/wikilink';

const UNAUTHORIZED = '__unauthorized__';

export default function WorkspacePage() {
  return (
    <RequireAuth>
      <WorkspaceContent />
    </RequireAuth>
  );
}

function WorkspaceContent() {
  const params = useParams<{ workspaceId: string }>();
  const workspaceId = params.workspaceId;

  const [notes, setNotes] = useState<Note[]>([]);
  const [tags, setTags] = useState<TagCount[]>([]);
  const [activeTag, setActiveTag] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState<Note | null>(null);
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [graphOpen, setGraphOpen] = useState(false);
  /**
   * 全文检索点进来时的定位目标(章节路径 + 命中词)。只有 `noteId` 与当前笔记一致时才会
   * 传给编辑器 —— 笔记是异步加载的,早传会在**上一篇**的内容上跳一次。
   */
  const [locate, setLocate] = useState<LocateTarget | null>(null);
  /**
   * 预览里 `[[双链]]` 的解析表:小写标题 → 解析结果(防抖批量查,见下面的 effect) */
  const [wikiLinks, setWikiLinks] = useState<Record<string, WikiResolution>>({});
  /** 解析表可不可信:只有 `ready` 时才允许把"查不到"当成"未创建"去建笔记 */
  const [wikiResolveState, setWikiResolveState] = useState<WikiResolveState>('pending');
  /**
   * 刚**新建**的那篇笔记 id —— 它是唯一一处"打开哪篇"之外还要告诉编辑器的信息:
   * 新笔记落「分栏」(边写边看),已有笔记落「预览」(先读)。切到别的笔记时清空,
   * 免得回头再看那篇新笔记时又被当成新笔记。
   */
  const [freshNoteId, setFreshNoteId] = useState<string | null>(null);
  /** 自增以触发关系 / 标签重新拉取(后端是提交后异步重建的,所以会稍等一下) */
  const [relationsKey, setRelationsKey] = useState(0);

  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const relationsTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const firstRun = useRef(true);
  /** 定位目标的触发计数:同一篇笔记的不同命中要能各自跳一次(用时间戳会在连点时相同) */
  const locateNonce = useRef(0);

  const toMessage = (e: unknown) =>
    e instanceof ApiError && e.status === 401
      ? UNAUTHORIZED
      : e instanceof Error
        ? e.message
        : String(e);

  /**
   * 拉一次左栏列表:按标题过滤(可选)+ 标签过滤,走 PostgreSQL。
   * 全文检索不在这里 —— 它在顶栏 `FullTextSearch`,由它自己管状态。
   */
  const runQuery = useCallback(
    async (q: string, tag: string | null) => {
      setLoading(true);
      try {
        const page = await api.listNotes(workspaceId, q || undefined, tag || undefined);
        setNotes(page.items);
        setError(null);
      } catch (e) {
        setError(toMessage(e));
      } finally {
        setLoading(false);
      }
    },
    [workspaceId],
  );

  const loadTags = useCallback(async () => {
    try {
      setTags(await api.listTags(workspaceId));
    } catch {
      // 标签面板拿不到不影响主流程
    }
  }, [workspaceId]);

  // 首次加载
  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const page = await api.listNotes(workspaceId);
        if (!active) return;
        setNotes(page.items);
        if (page.items.length > 0) {
          setCurrent(page.items[0]);
        }
        setError(null);
      } catch (e) {
        if (active) setError(toMessage(e));
      }
    })();
    return () => {
      active = false;
    };
  }, [workspaceId]);

  useEffect(() => {
    void loadTags();
  }, [loadTags, relationsKey]);

  useEffect(
    () => () => {
      if (relationsTimer.current) clearTimeout(relationsTimer.current);
    },
    [],
  );

  // 关键词 / 标签变化后防抖查询(跳过首次)
  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false;
      return;
    }
    const timer = setTimeout(() => {
      void runQuery(query, activeTag);
    }, 300);
    return () => clearTimeout(timer);
  }, [query, activeTag, runQuery]);

  // 预览里的 `[[双链]]` 要显示成"可点 / 未创建",得先知道标题对应哪篇笔记 ——
  // 一次问一批(后端 `/links/resolve`),防抖 350ms,理由同左栏检索:边打字边发请求
  // 会把后端刷爆,而这些链接往往是一口气写进去的。
  //
  // **关键**:响应里对每个问过的标题都有一条(未创建的 `noteId` 为 null),所以
  // "查不到" ⟺ "没问过 / 请求失败" —— 建笔记只认"后端明确说没有",不认"我不知道"。
  // 见 `lib/wikilink` 里 WikiResolveState 的说明(踩过:后端没重启时点一下多一篇重复笔记)。
  useEffect(() => {
    const titles = collectWikiTargets(current?.contentMd ?? '');
    if (titles.length === 0) {
      setWikiLinks({});
      setWikiResolveState('ready');
      return;
    }
    setWikiResolveState('pending');
    let active = true;
    const timer = setTimeout(() => {
      void api
        .resolveLinks(workspaceId, titles)
        .then((rows) => {
          if (!active) return;
          const next: Record<string, WikiResolution> = {};
          for (const row of rows) {
            next[row.title.trim().toLowerCase()] = row;
          }
          setWikiLinks(next);
          setWikiResolveState('ready');
        })
        .catch(() => {
          if (!active) return;
          // 请求失败:留着上一份解析表(它至少是"某个更早的内容"的答案),
          // 但状态置 failed —— 这时**任何链接都不许点出新建**,界面上也会写明解析不了
          setWikiResolveState('failed');
        });
    }, 350);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [current?.contentMd, workspaceId]);

  /** 保存后延迟刷新关系面板:后端要先提交事务,再异步重建双链。 */
  function scheduleRelationsRefresh() {
    if (relationsTimer.current) clearTimeout(relationsTimer.current);
    relationsTimer.current = setTimeout(() => setRelationsKey((k) => k + 1), 1200);
  }

  async function openNote(noteId: string) {
    if (current?.id === noteId) {
      setGraphOpen(false);
      return;
    }
    try {
      // 打开的是"已有的笔记"了:默认视图回到「预览」(换笔记时编辑器会重挂载,见下面 key)
      setFreshNoteId(null);
      setCurrent(await api.getNote(noteId));
      setStatus('');
      setHistoryOpen(false);
      setGraphOpen(false);
      setError(null);
    } catch (e) {
      setError(toMessage(e));
    }
  }

  /**
   * 全文检索点进来:打开笔记 **并且** 记住命中位置(章节 + 命中词)。
   * `locate` 里存的是**目标**笔记的 id,只有它和当前笔记对上才会传给编辑器 ——
   * 笔记是异步加载的,早传会在上一篇的内容上白跳一次。
   */
  function openFromSearch(noteId: string, hit?: Pick<SearchHit, 'headingPath' | 'snippet'>) {
    if (hit) {
      locateNonce.current += 1;
      setLocate({
        noteId,
        headingPath: hit.headingPath,
        snippet: hit.snippet,
        nonce: locateNonce.current,
      });
    } else {
      setLocate(null);
    }
    void openNote(noteId);
  }

  /**
   * AI 回答里的引用:和全文检索点进来是同一条路 —— 打开笔记 + 定位到引用所在章节。
   * 引用的章节路径与片段在生成回答时就存成了快照(见 V6__ai_chat.sql),所以这里不用回查。
   */
  function openCitation(citation: ChatCitation) {
    if (!citation.noteId) return;
    openFromSearch(citation.noteId, citation);
  }

  /**
   * 点预览里的 `[[双链]]`。已创建就跳过去 —— `[[目标#小节]]` 会顺手定位到那一节
   * (复用全文检索那套文本锚点)。还没创建就地建一篇,与关系面板的「创建」按钮同一套行为;
   * 悬停卡片会先告诉用户"未创建",所以不算突袭。
   */
  function openWikiLink(target: string, heading: string | null) {
    const hit = wikiLinks[target.trim().toLowerCase()];
    if (!hit) {
      // 解析表里没有这个标题 = "还没问过后端"或"请求失败"(问过的标题一定有一条)。
      // 这时**不能**建笔记:后端没这个接口时"查不到"会被误当成"未创建",
      // 点一下就是凭空多出一篇重复笔记 —— 宁可这次点了没反应(给个提示),
      // 也不要建错。等 350ms 防抖 + 一次请求回来,再点就正常了。
      setStatus(
        wikiResolveState === 'failed'
          ? '这条链接解析不了(后端可能缺 /links/resolve)'
          : '链接还在解析,稍等一下再点',
      );
      return;
    }
    if (!hit.noteId) {
      void handleCreateFromLink(target.trim());
      return;
    }
    if (heading) {
      locateNonce.current += 1;
      setLocate({
        noteId: hit.noteId,
        headingPath: heading,
        snippet: null,
        nonce: locateNonce.current,
      });
    }
    void openNote(hit.noteId);
  }

  function scheduleSave(note: Note) {
    setStatus('保存中…');
    if (saveTimer.current) {
      clearTimeout(saveTimer.current);
    }
    saveTimer.current = setTimeout(() => {
      void (async () => {
        try {
          const saved = await api.updateNote(note.id, {
            title: note.title,
            contentMd: note.contentMd,
          });
          setStatus(`已保存 ${new Date(saved.updatedAt).toLocaleTimeString()}`);
          setCurrent((prev) => (prev && prev.id === saved.id ? { ...prev, ...saved } : prev));
          scheduleRelationsRefresh();
          await runQuery(query, activeTag);
        } catch {
          setStatus('保存失败');
        }
      })();
    }, 800);
  }

  function patchCurrent(field: 'title' | 'contentMd', value: string) {
    if (!current) return;
    const next: Note = { ...current, [field]: value };
    setCurrent(next);
    scheduleSave(next);
  }

  async function reloadList(openFirst = false) {
    const page = await api.listNotes(workspaceId, query || undefined, activeTag || undefined);
    setNotes(page.items);
    if (openFirst && page.items.length > 0) {
      setCurrent(page.items[0]);
    }
  }

  async function handleCreate() {
    try {
      const created = await api.createNote(workspaceId, '未命名笔记');
      // 刚建的笔记默认落「分栏」:边写边看。列表按更新时间倒序,`reloadList(true)` 打开的
      // 就是它 —— 这里先记下 id,免得"按标题过滤"时顺位被别的笔记顶掉而误判。
      setFreshNoteId(created.id);
      await reloadList(true);
      setError(null);
    } catch (e) {
      setError(toMessage(e));
    }
  }

  /** 由「出链 → 未创建」按钮触发:先建笔记,再跳过去。 */
  async function handleCreateFromLink(title: string) {
    try {
      const created = await api.createNote(workspaceId, title);
      await reloadList();
      setFreshNoteId(created.id);
      setCurrent(created);
      setStatus('');
      scheduleRelationsRefresh();
      setError(null);
    } catch (e) {
      setError(toMessage(e));
    }
  }

  /** 删除笔记:后端是软删除 —— 界面与检索里立刻消失,历史版本仍留在 PostgreSQL。 */
  async function handleDelete(note: Note) {
    try {
      await api.deleteNote(note.id);
    } catch (e) {
      setError(toMessage(e));
      throw e; // 让 NoteList 的确认框留在原地
    }

    // 本地先摘掉,不用等重新查询
    setNotes((prev) => prev.filter((n) => n.id !== note.id));
    if (current?.id === note.id) {
      setCurrent(null);
      setStatus('');
      setHistoryOpen(false);
      setGraphOpen(false);
    }
    setError(null);
    // 标签计数会变;别人指向它的链接也要解绑,所以关系面板也要刷(后端提交后异步重建)
    void loadTags();
    scheduleRelationsRefresh();
  }

  function handleTagChange(tag: string | null) {
    setActiveTag(tag);
  }

  return (
    <div className="flex h-full overflow-hidden">
      <div className="flex min-w-0 flex-1 flex-col">
        {/* header 是 .glass,而 backdrop-filter 会创建层叠上下文 —— 里面的主题弹层
            无论 z-index 多高都出不去这个上下文。编辑列排在 DOM 之后,所以必须给
            header 一个正的 z-index(且要盖过 z-20 的图谱 / 版本历史浮层),否则
            弹层会被正文盖住:看得见轮廓,点下去却打在编辑器上。 */}
        <header className="glass relative z-30 flex shrink-0 items-center gap-3 border-x-0 border-t-0 px-4 py-2 text-xs">
          <Link
            href="/"
            className="flex items-center gap-1.5 text-muted transition-colors hover:text-ink"
          >
            <span className="text-faint">←</span>
            工作区
          </Link>
          <span className="h-4 w-px bg-white/10" />
          <span className="glass-soft rounded-full px-2 py-0.5 font-mono text-[10px] text-muted">
            {workspaceId.slice(0, 8)}
          </span>

          <span className="ml-auto flex items-center gap-2">
            {/* 全文检索固定在顶栏:结果以浮层挂在输入框下方,不占左栏(左栏只做标题过滤) */}
            <FullTextSearch
              workspaceId={workspaceId}
              onOpenNote={openFromSearch}
              className="w-56 lg:w-80"
            />
            <button
              type="button"
              onClick={() => setGraphOpen(true)}
              className="glass-soft sheen rounded-full px-3 py-1 text-muted transition-colors hover:text-ink"
            >
              知识图谱
            </button>
            <ThemeMenu />
            <AuthChip />
          </span>
        </header>

        <div className="flex min-h-0 flex-1">
        <NoteList
          notes={notes}
          tags={tags}
          activeTag={activeTag}
          loading={loading}
          currentId={current?.id ?? null}
          query={query}
          onQueryChange={setQuery}
          onTagChange={handleTagChange}
          onOpenNote={(id) => void openNote(id)}
          onCreate={() => void handleCreate()}
          onDelete={handleDelete}
        />

        {current ? (
          <div className="relative flex min-w-0 flex-1 flex-col">
            <MarkdownEditor
              noteId={current.id}
              defaultMode={freshNoteId === current.id ? 'split' : 'preview'}
              title={current.title}
              content={current.contentMd}
              status={status}
              indexStatus={current.indexStatus}
              locate={locate && locate.noteId === current.id ? locate : null}
              wikiLinks={wikiLinks}
              wikiResolveState={wikiResolveState}
              onOpenWikiLink={openWikiLink}
              onTitleChange={(v) => patchCurrent('title', v)}
              onContentChange={(v) => patchCurrent('contentMd', v)}
              onStatus={setStatus}
              existingTags={tags.map((tag) => tag.name)}
              onSummarySaved={(summary) =>
                setCurrent((prev) => (prev ? { ...prev, summary } : prev))
              }
              onOpenHistory={() => setHistoryOpen(true)}
              onLinkSearch={(prefix) => api.suggest(workspaceId, prefix)}
            />
            <RelationsPanel
              noteId={current.id}
              refreshKey={relationsKey}
              onOpenNote={(id) => void openNote(id)}
              onCreateNote={(title) => void handleCreateFromLink(title)}
              onSelectTag={handleTagChange}
            />
            <AttachmentStrip
              noteId={current.id}
              onInsert={(markdown) => patchCurrent('contentMd', `${current.contentMd}${markdown}`)}
            />
            {graphOpen && (
              <GraphView
                workspaceId={workspaceId}
                onOpenNote={(id) => void openNote(id)}
                onClose={() => setGraphOpen(false)}
              />
            )}
            {historyOpen && (
              <VersionHistory
                noteId={current.id}
                onRestored={(note) => {
                  setCurrent(note);
                  scheduleRelationsRefresh();
                  void runQuery(query, activeTag);
                }}
                onClose={() => setHistoryOpen(false)}
              />
            )}
          </div>
        ) : (
          <div className="flex flex-1 flex-col items-center justify-center gap-4 px-6 text-center">
            {error === UNAUTHORIZED ? (
              <>
                <p className="text-sm text-muted">需要登录后才能访问知识库。</p>
                <Link href="/login" className="btn-accent sheen rounded-xl px-4 py-2 text-sm font-semibold">
                  去登录
                </Link>
              </>
            ) : (
              <>
                <div className="glass grid h-14 w-14 place-items-center rounded-2xl text-2xl text-faint">
                  ✦
                </div>
                <p className="text-sm text-muted">{error ?? '选择左侧笔记,或点 + 新建一篇'}</p>
              </>
            )}
          </div>
        )}
        </div>
      </div>

      <AiPanel workspaceId={workspaceId} onOpenCitation={openCitation} />
    </div>
  );
}
