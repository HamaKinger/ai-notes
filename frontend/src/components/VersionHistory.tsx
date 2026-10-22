'use client';

import { useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { Note, NoteVersion, VersionDetail } from '@/lib/types';

interface Props {
  noteId: string;
  onRestored: (note: Note) => void;
  onClose: () => void;
}

/**
 * 版本历史:M1 支持查看任意历史版本内容并回滚。
 * 回滚会以「追加新版本」的方式完成,历史只增不减。
 */
export default function VersionHistory({ noteId, onRestored, onClose }: Props) {
  const [versions, setVersions] = useState<NoteVersion[]>([]);
  const [preview, setPreview] = useState<VersionDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const list = await api.listVersions(noteId);
        if (active) {
          setVersions(list);
          setPreview(null);
        }
      } catch (e) {
        if (active) setError(e instanceof ApiError ? e.message : String(e));
      }
    })();
    return () => {
      active = false;
    };
  }, [noteId]);

  // Esc 关闭,和主题选择器的行为保持一致
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  async function handlePreview(version: number) {
    try {
      setPreview(await api.getVersion(noteId, version));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleRestore(version: number) {
    setBusy(true);
    try {
      onRestored(await api.restoreVersion(noteId, version));
      onClose();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      className="absolute inset-0 z-20 flex justify-end bg-black/45 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="glass-pop flex w-[26rem] flex-col rounded-none border-y-0 border-r-0"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-white/10 px-4 py-3">
          <span className="text-[11px] font-semibold uppercase tracking-[0.18em] text-faint">
            版本历史
          </span>
          <button
            type="button"
            onClick={onClose}
            className="rounded-full px-2 py-1 text-xs text-muted transition-colors hover:text-ink"
          >
            关闭
          </button>
        </div>

        {error && (
          <p className="border-b border-red-500/25 bg-red-500/10 px-4 py-2 text-xs text-red-200">
            {error}
          </p>
        )}

        <div className="flex min-h-0 flex-1">
          <ul className="w-32 shrink-0 space-y-1 overflow-y-auto border-r border-white/10 p-2">
            {versions.length === 0 && (
              <li className="px-2 py-3 text-xs text-faint">暂无版本</li>
            )}
            {versions.map((v) => (
              <li key={v.id}>
                <button
                  type="button"
                  onClick={() => void handlePreview(v.version)}
                  className={`w-full rounded-xl px-2 py-1.5 text-left text-xs transition-all ${
                    preview?.version === v.version ? 'glass-active text-ink' : 'glass-soft text-muted'
                  }`}
                >
                  v{v.version}
                  <span className="mt-0.5 block text-[10px] text-faint">
                    {new Date(v.createdAt).toLocaleString()}
                  </span>
                </button>
              </li>
            ))}
          </ul>

          <div className="flex min-w-0 flex-1 flex-col">
            <div className="min-h-0 flex-1 overflow-y-auto p-3.5">
              {preview ? (
                <pre className="font-mono whitespace-pre-wrap break-words text-xs leading-relaxed text-ink/85">
                  {preview.contentMd}
                </pre>
              ) : (
                <p className="text-xs text-faint">选择左侧版本查看内容</p>
              )}
            </div>
            {preview && (
              <div className="border-t border-white/10 p-3.5">
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleRestore(preview.version)}
                  className="btn-accent sheen w-full rounded-xl px-3 py-2 text-xs font-semibold"
                >
                  {busy ? '回滚中…' : `回滚到 v${preview.version}`}
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
