'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import AuthChip from '@/components/AuthChip';
import RequireAuth from '@/components/RequireAuth';
import ThemeMenu from '@/components/ThemeMenu';
import { api, ApiError } from '@/lib/api';
import type { Workspace } from '@/lib/types';

export default function HomePage() {
  return (
    <RequireAuth>
      <HomeContent />
    </RequireAuth>
  );
}

function HomeContent() {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const unauthorized = error === '401';

  async function load() {
    try {
      setWorkspaces(await api.listWorkspaces());
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? '401' : e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      await api.createWorkspace(name.trim());
      setName('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto flex min-h-full max-w-3xl flex-col px-6 py-10">
        <header className="flex items-center gap-3">
          <span className="grad-accent h-2.5 w-2.5 rounded-full shadow-[0_0_18px_var(--accent)]" />
          <span className="text-sm font-semibold tracking-tight text-ink">ai-notes</span>
          <span className="hidden text-[11px] text-faint sm:inline">
            以本地知识库为长期记忆的 AI Agent
          </span>
          <span className="ml-auto flex items-center gap-2">
            <ThemeMenu />
            <AuthChip />
          </span>
        </header>

        <section className="mt-14">
          <h1 className="text-flow text-4xl font-bold tracking-tight sm:text-5xl">
            你的第二大脑
          </h1>
          <p className="mt-3 max-w-lg text-sm leading-relaxed text-muted">
            全文检索、语义召回、双链与知识图谱都长在本地。先选一个工作区开始,
            所有数据留在你自己的 PostgreSQL 与 Elasticsearch 里。
          </p>
        </section>

        <form onSubmit={handleCreate} className="glass mt-8 flex gap-2 rounded-2xl p-2">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="新建工作区名称"
            className="min-w-0 flex-1 rounded-xl bg-transparent px-3 py-2 text-sm text-ink outline-none placeholder:text-faint"
          />
          <button type="submit" className="btn-accent sheen rounded-xl px-4 py-2 text-sm font-semibold">
            新建
          </button>
        </form>

        {error && (
          <p className="mt-4 rounded-2xl border border-red-500/25 bg-red-500/10 px-4 py-3 text-sm text-red-200">
            {unauthorized ? (
              <>
                需要登录后才能访问工作区。
                <Link href="/login" className="ml-1 underline">
                  去登录
                </Link>
              </>
            ) : (
              <>
                无法连接后端:{error}
                <br />
                <span className="text-red-200/70">
                  请确认后端已启动(默认 http://localhost:8080),以及 NEXT_PUBLIC_API_BASE 配置正确。
                </span>
              </>
            )}
          </p>
        )}

        <section className="mt-10 pb-6">
          <h2 className="mb-3 text-[11px] font-semibold uppercase tracking-[0.18em] text-faint">
            工作区
          </h2>

          {loading ? (
            <p className="text-sm text-muted">加载中…</p>
          ) : workspaces.length === 0 ? (
            <p className="text-sm text-muted">还没有工作区,先创建一个。</p>
          ) : (
            <ul className="grid gap-3 sm:grid-cols-2">
              {workspaces.map((w) => (
                <li key={w.id}>
                  <Link
                    href={`/w/${w.id}`}
                    className="glass glass-hover sheen group flex items-center gap-3 rounded-2xl px-4 py-3.5"
                  >
                    <span className="grad-accent grid h-9 w-9 shrink-0 place-items-center rounded-xl text-sm font-bold text-accent-contrast">
                      {w.name.charAt(0).toUpperCase()}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium text-ink">{w.name}</span>
                      <span className="mt-0.5 block text-[11px] text-faint">{w.storageMode}</span>
                    </span>
                    <span className="text-faint transition-transform group-hover:translate-x-0.5 group-hover:text-accent">
                      →
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
