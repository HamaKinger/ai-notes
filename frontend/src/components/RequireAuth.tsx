'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { AUTH_CHANGED_EVENT, isLoggedIn } from '@/lib/auth';

/**
 * 登录守卫:把"没登录就看不了笔记"这件事在**前端**兜住。
 *
 * 为什么光改前端? —— 后端默认开着 `ai-notes.security.dev-permit-all=true`(本地开发免登录),
 * 未带 Token 的请求会被放行并回落到演示账号,所以"退出登录后接口照样返回数据"。
 * 服务端要真正拦住需要把该开关置为 false(见 README 的说明),那属于部署口径的变化。
 * 无论后端怎么配,这一层都能保证:**界面上**退出后立即回到登录页。
 *
 * 判定依据是 localStorage 里有没有 Token,而不是调 `/auth/me` ——
 * 因为免登录模式下 `/auth/me` 对匿名请求也会返回演示账号,分辨不出来。
 * 另外监听 AUTH_CHANGED_EVENT:令牌过期时 `api.ts` 收到 401 会清 Token 并广播,
 * 于是用户会被自动请回登录页,而不是停在一个到处报错的界面。
 */
export default function RequireAuth({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [allowed, setAllowed] = useState(false);

  useEffect(() => {
    const sync = () => {
      const ok = isLoggedIn();
      setAllowed(ok);
      if (!ok) {
        router.replace('/login');
      }
    };
    sync();
    window.addEventListener(AUTH_CHANGED_EVENT, sync);
    return () => window.removeEventListener(AUTH_CHANGED_EVENT, sync);
  }, [router]);

  // 首帧固定渲染校验态:localStorage 只有客户端才有,若用懒初始化会与服务端输出不一致,
  // 触发 hydration 报错。这里宁可先显示一帧加载态。
  if (!allowed) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="glass flex items-center gap-3 rounded-2xl px-5 py-3 text-sm text-muted">
          <span className="dot-pulse h-2 w-2 rounded-full bg-accent" />
          正在校验登录状态…
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
