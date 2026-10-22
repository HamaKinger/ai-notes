'use client';

/**
 * 前端 Token 存储。
 *
 * M1 先用 localStorage 保存 JWT;后续要换成 httpOnly Cookie 只需改这里。
 */
const TOKEN_KEY = 'ai-notes.token';

export const AUTH_CHANGED_EVENT = 'ai-notes:auth-changed';

export function getToken(): string | null {
  if (typeof window === 'undefined') {
    return null;
  }
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  window.localStorage.setItem(TOKEN_KEY, token);
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

export function clearToken(): void {
  window.localStorage.removeItem(TOKEN_KEY);
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

export function isLoggedIn(): boolean {
  return getToken() !== null;
}
