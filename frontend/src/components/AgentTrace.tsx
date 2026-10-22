'use client';

import { useState } from 'react';
import type { AgentToolStep } from '@/lib/types';

/**
 * 一次 Agent 运行的工具轨迹,以及"等你点一下"的确认卡片(M5 Step 1)。
 *
 * <p>轨迹和卡片都**能从两个来源画出来**:SSE 事件(正在跑的这次)与后端历史里的 `run`
 * (刷新之后的同一次)。所以这里只认数据结构,不关心它从哪来 —— 否则刷新一下就会"少半截"。
 *
 * 几处刻意的做法:
 * - 轨迹写成一行一句话(「检索「本周工作」」),不展示工具名与参数 JSON:那是给模型看的,
 *   给用户看的是**发生了什么**。想看细节可以展开那一步。
 * - 确认卡片把**要写进去的正文**摊开:用户是照它决定点不点的,藏起来就等于让他盲签。
 * - 确认按钮不做二次确认弹窗:卡片本身就是那道确认。
 */

const RUNNING = 'running';
const PENDING = 'pending';
const REJECTED = 'rejected';

function stepTone(status: string): string {
  if (status === PENDING) return 'text-accent';
  if (status === RUNNING) return 'text-accent';
  if (status === REJECTED) return 'text-faint line-through';
  if (status === 'ok') return 'text-muted';
  return 'text-red-300/90';
}

function stepMark(status: string): string {
  if (status === PENDING) return '待确认';
  if (status === RUNNING) return '›';
  if (status === REJECTED) return '已取消';
  return status === 'ok' ? '✓' : '!';
}

/** 有正文可看的写操作:把正文摊开给用户过目 */
function body(args: Record<string, unknown>): string | null {
  for (const key of ['contentMd', 'appendText']) {
    const value = args?.[key];
    if (typeof value === 'string' && value.trim().length > 0) {
      return value;
    }
  }
  return null;
}

export function AgentTrace({ steps }: { steps: AgentToolStep[] }) {
  const [expanded, setExpanded] = useState<string | null>(null);

  if (steps.length === 0) {
    return null;
  }
  return (
    <ol className="space-y-0.5">
      {steps.map((step) => (
        <li key={step.id}>
          <button
            type="button"
            onClick={() => setExpanded((current) => (current === step.id ? null : step.id))}
            className="flex w-full items-baseline gap-1.5 rounded px-1 py-0.5 text-left text-[10px] transition-colors hover:bg-white/5"
          >
            <span className={`w-8 shrink-0 text-center ${stepTone(step.status)}`}>{stepMark(step.status)}</span>
            <span className={`truncate ${stepTone(step.status)}`}>{step.summary ?? step.label}</span>
            {step.latencyMs != null && step.latencyMs > 200 && (
              <span className="ml-auto shrink-0 text-faint">{step.latencyMs}ms</span>
            )}
          </button>
          {expanded === step.id && (
            <div className="mt-0.5 space-y-1 rounded-lg bg-black/20 p-1.5 text-[10px] text-faint">
              <p className="break-all">{`${step.name}(${JSON.stringify(step.args ?? {})})`}</p>
              {step.text && <p className="max-h-32 overflow-y-auto whitespace-pre-wrap">{step.text}</p>}
            </div>
          )}
        </li>
      ))}
    </ol>
  );
}

export function AgentConfirmCard({
  pending,
  busy,
  onDecide,
}: {
  pending: AgentToolStep[];
  busy: boolean;
  onDecide: (approve: boolean) => void;
}) {
  if (pending.length === 0) {
    return null;
  }
  return (
    <div className="glass-soft space-y-1.5 rounded-2xl border border-accent/30 p-2.5">
      <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-accent">等你确认</p>

      {pending.map((step) => (
        <div key={step.id} className="space-y-1">
          <p className="text-[11px] text-ink">{step.summary ?? step.label}</p>
          {body(step.args) && (
            <details className="text-[10px] text-muted">
              <summary className="cursor-pointer text-faint">看看要写进去的内容</summary>
              <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded-lg bg-black/25 p-1.5 text-[10px] leading-relaxed">
                {body(step.args)}
              </pre>
            </details>
          )}
        </div>
      ))}

      <div className="flex items-center gap-1.5 pt-0.5">
        <button
          type="button"
          onClick={() => onDecide(true)}
          disabled={busy}
          className="btn-accent sheen rounded-xl px-2.5 py-1 text-[11px] font-semibold disabled:opacity-40"
        >
          确认执行
        </button>
        <button
          type="button"
          onClick={() => onDecide(false)}
          disabled={busy}
          className="glass-soft rounded-xl px-2.5 py-1 text-[11px] text-muted transition-colors hover:text-ink disabled:opacity-40"
        >
          取消
        </button>
        <span className="ml-auto text-[10px] text-faint">写操作不会自动执行</span>
      </div>
    </div>
  );
}
