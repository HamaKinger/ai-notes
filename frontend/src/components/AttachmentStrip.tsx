'use client';

import { useEffect, useRef, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { Attachment } from '@/lib/types';

interface Props {
  noteId: string;
  /** 上传成功后把 Markdown 片段插到正文里 */
  onInsert: (markdown: string) => void;
}

function formatSize(bytes: number | null): string {
  if (bytes === null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** 附件条:M1 用后端本地目录存储,支持上传 / 下载 / 插入正文引用。 */
export default function AttachmentStrip({ noteId, onInsert }: Props) {
  const [items, setItems] = useState<Attachment[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const list = await api.listAttachments(noteId);
        if (active) setItems(list);
      } catch (e) {
        if (active) setError(e instanceof ApiError ? e.message : String(e));
      }
    })();
    return () => {
      active = false;
    };
  }, [noteId]);

  async function handleUpload(file: File) {
    setBusy(true);
    setError(null);
    try {
      const created = await api.uploadAttachment(noteId, file);
      setItems((prev) => [created, ...prev]);
      onInsert(`\n[${created.filename}](${api.attachmentUrl(created.id)})\n`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  }

  async function handleDownload(attachment: Attachment) {
    try {
      const blob = await api.downloadAttachment(attachment.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = attachment.filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  return (
    <div className="border-t border-white/10 px-4 py-2.5 text-xs">
      <div className="flex items-center gap-2">
        <span className="text-[10px] font-semibold uppercase tracking-[0.18em] text-faint">
          附件
        </span>
        <button
          type="button"
          disabled={busy}
          onClick={() => fileInput.current?.click()}
          className="glass-soft sheen rounded-full px-2.5 py-0.5 text-muted transition-colors hover:text-ink disabled:opacity-50"
        >
          {busy ? '上传中…' : '上传'}
        </button>
        <input
          ref={fileInput}
          type="file"
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void handleUpload(file);
          }}
        />
        {error && <span className="text-red-300">{error}</span>}
      </div>

      {items.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-1.5">
          {items.map((a) => (
            <li key={a.id}>
              <button
                type="button"
                onClick={() => void handleDownload(a)}
                title={`下载 ${a.filename}`}
                className="glass-soft rounded-full px-2.5 py-1 text-muted transition-colors hover:text-ink"
              >
                {a.filename}
                <span className="ml-1.5 text-faint">{formatSize(a.sizeBytes)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
