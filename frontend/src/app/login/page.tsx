'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import ThemeMenu from '@/components/ThemeMenu';
import { api, ApiError } from '@/lib/api';
import { isLoggedIn, setToken } from '@/lib/auth';

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('demo@ai-notes.local');
  const [password, setPassword] = useState('demo1234');
  const [displayName, setDisplayName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 已经登录过就别停在登录页
  useEffect(() => {
    if (isLoggedIn()) {
      router.replace('/');
    }
  }, [router]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result =
        mode === 'login'
          ? await api.login(email.trim(), password)
          : await api.register(email.trim(), password, displayName.trim() || undefined);
      setToken(result.token);
      router.replace('/');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  const inputClass =
    'w-full rounded-xl border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors placeholder:text-faint focus:border-accent/60 focus:bg-white/8';

  return (
    <div className="relative flex h-full items-center justify-center overflow-y-auto px-6 py-10">
      {/* 同样需要 z-30:下面那张 .glass-strong 卡片也是层叠上下文且在 DOM 之后,
          窄屏下主题弹层会被卡片盖住。 */}
      <div className="absolute right-5 top-5 z-30">
        <ThemeMenu />
      </div>

      <div className="glass-strong w-full max-w-sm rounded-3xl p-7">
        <div className="flex items-center gap-2.5">
          <span className="grad-accent h-2.5 w-2.5 rounded-full shadow-[0_0_18px_var(--accent)]" />
          <span className="text-sm font-semibold tracking-tight text-ink">ai-notes</span>
        </div>

        <h1 className="text-flow mt-6 text-2xl font-bold tracking-tight">欢迎回来</h1>
        <p className="mt-1.5 text-sm text-muted">
          {mode === 'login' ? '登录以访问你的知识库' : '创建一个账号,数据存在你自己的库里'}
        </p>

        <div className="glass-soft mt-6 flex rounded-xl p-1 text-xs">
          {(['login', 'register'] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => {
                setMode(m);
                setError(null);
              }}
              className={`flex-1 rounded-lg px-3 py-1.5 transition-all ${
                mode === m
                  ? 'glass-active font-medium text-ink'
                  : 'text-muted hover:text-ink'
              }`}
            >
              {m === 'login' ? '登录' : '注册'}
            </button>
          ))}
        </div>

        <form onSubmit={handleSubmit} className="mt-5 space-y-3">
          <input
            type="email"
            required
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="邮箱"
            className={inputClass}
          />
          <input
            type="password"
            required
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            minLength={mode === 'register' ? 8 : undefined}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={mode === 'register' ? '密码(至少 8 位)' : '密码'}
            className={inputClass}
          />
          {mode === 'register' && (
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="昵称(可选)"
              className={inputClass}
            />
          )}

          <button
            type="submit"
            disabled={busy}
            className="btn-accent sheen mt-1 w-full rounded-xl px-4 py-2.5 text-sm font-semibold"
          >
            {busy ? '处理中…' : mode === 'login' ? '登录' : '注册并登录'}
          </button>
        </form>

        {error && (
          <p className="mt-4 rounded-xl border border-red-500/25 bg-red-500/10 px-3.5 py-2.5 text-xs text-red-200">
            {error}
          </p>
        )}

        <p className="mt-6 text-[11px] leading-relaxed text-faint">
          本地开发默认账号:
          <code className="mx-1 rounded bg-white/8 px-1.5 py-0.5 text-muted">
            demo@ai-notes.local / demo1234
          </code>
          (首次启动后端自动创建)
        </p>

        <Link
          href="/"
          className="mt-4 inline-block text-[11px] text-faint transition-colors hover:text-ink"
        >
          ← 返回首页
        </Link>
      </div>
    </div>
  );
}
