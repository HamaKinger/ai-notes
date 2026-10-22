'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { AUTH_CHANGED_EVENT, clearToken, isLoggedIn } from '@/lib/auth';
import type { AuthUser } from '@/lib/types';

/** 顶部的登录状态 / 退出入口。 */
export default function AuthChip() {
  const router = useRouter();
  const [user, setUser] = useState<AuthUser | null>(null);
  const [signedIn, setSignedIn] = useState(false);

  const load = useCallback(async () => {
    if (!isLoggedIn()) {
      setUser(null);
      setSignedIn(false);
      return;
    }
    try {
      setUser(await api.me());
      setSignedIn(true);
    } catch {
      setUser(null);
      setSignedIn(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const onChange = () => void load();
    window.addEventListener(AUTH_CHANGED_EVENT, onChange);
    return () => window.removeEventListener(AUTH_CHANGED_EVENT, onChange);
  }, [load]);

  /**
   * 退出登录:清 Token(会广播事件,守卫据此把人请走)并主动跳转,
   * 不等守卫兜底 —— 主动离场更快,也不会出现"退出后还停在原页面"的错觉。
   */
  function handleLogout() {
    clearToken();
    setUser(null);
    setSignedIn(false);
    router.replace('/login');
  }

  if (!signedIn) {
    return (
      <Link
        href="/login"
        className="glass-soft sheen rounded-full px-3 py-1 text-[11px] text-muted hover:text-ink"
      >
        登录
      </Link>
    );
  }

  const name = user?.displayName || user?.email || '已登录';

  return (
    <span className="flex items-center gap-2">
      <span className="glass-soft flex items-center gap-1.5 rounded-full py-0.5 pl-0.5 pr-2.5">
        <span className="grad-accent grid h-5 w-5 place-items-center rounded-full text-[10px] font-bold text-accent-contrast">
          {name.charAt(0).toUpperCase()}
        </span>
        <span className="max-w-[12rem] truncate text-[11px] text-muted">{name}</span>
      </span>
      <button
        type="button"
        onClick={handleLogout}
        className="rounded-full px-2 py-1 text-[11px] text-faint transition-colors hover:text-ink"
      >
        退出
      </button>
    </span>
  );
}
