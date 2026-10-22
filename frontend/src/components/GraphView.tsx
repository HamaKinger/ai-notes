'use client';

import dynamic from 'next/dynamic';
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ForceGraphMethods } from 'react-force-graph-2d';
import { useTheme } from '@/components/ThemeProvider';
import { api, ApiError } from '@/lib/api';
import type { GraphData } from '@/lib/types';

/**
 * 3D/2D 关系图:用 react-force-graph-2d(canvas 力导向图)渲染。
 * 必须 dynamic + ssr:false —— 它直接操作 canvas / window。
 */
const ForceGraph2D = dynamic(() => import('react-force-graph-2d'), { ssr: false });

/** 连线的目标长度(库默认 30):适当拉长,让相邻标题不挤在一起,又不至于散得太开 */
const LINK_DISTANCE = 60;
/** 节点之间的斥力(库默认 -30):不宜过大,否则布局散开后需要手动缩放才看得全 */
const CHARGE_STRENGTH = -140;

interface Props {
  workspaceId: string;
  onOpenNote: (noteId: string) => void;
  onClose: () => void;
}

export default function GraphView({ workspaceId, onOpenNote, onClose }: Props) {
  const { theme, accent } = useTheme();
  const [graph, setGraph] = useState<GraphData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [size, setSize] = useState({ width: 900, height: 600 });
  /** 画布颜色取自 CSS 变量,这样换主题时图也跟着变色 */
  const [accentColor, setAccentColor] = useState('#6ad5e8');
  const containerRef = useRef<HTMLDivElement | null>(null);
  const fgRef = useRef<ForceGraphMethods | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const data = await api.graph(workspaceId);
        if (active) {
          setGraph(data);
          setError(null);
        }
      } catch (e) {
        if (active) setError(e instanceof ApiError ? e.message : String(e));
      }
    })();
    return () => {
      active = false;
    };
  }, [workspaceId]);

  // canvas 画不了 CSS 变量,只能把当前值取出来传进去;主题一变就重新取。
  useLayoutEffect(() => {
    const value = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim();
    if (value) setAccentColor(value);
  }, [theme, accent]);

  useEffect(() => {
    const element = containerRef.current;
    if (!element) {
      return;
    }
    const update = () =>
      setSize({ width: element.clientWidth, height: element.clientHeight });
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  /**
   * 力参数要等图挂上之后才能设置:组件是动态加载的,挂载时机不确定。
   * 所以在第一次 engine tick(此时实例一定可用)时设置一次,之后每换一张图重新设置。
   */
  const forcesApplied = useRef(false);
  const applyForces = useCallback(() => {
    const graphInstance = fgRef.current;
    if (!graphInstance || forcesApplied.current) return;
    graphInstance.d3Force('link')?.distance(LINK_DISTANCE);
    graphInstance.d3Force('charge')?.strength(CHARGE_STRENGTH);
    graphInstance.d3ReheatSimulation();
    forcesApplied.current = true;
  }, []);

  useEffect(() => {
    forcesApplied.current = false;
  }, [graph]);

  /** 布局稳定后把整张图缩放进视野,首次打开就能看全,不用手动缩小 */
  const fitView = useCallback(() => {
    fgRef.current?.zoomToFit(600, 40);
  }, []);

  const nodes = (graph?.nodes ?? []).map((node) => ({ id: node.id, name: node.title }));
  const links = (graph?.edges ?? []).map((edge) => ({ source: edge.source, target: edge.target }));

  return (
    <div className="absolute inset-0 z-20 flex flex-col bg-black/45 backdrop-blur-sm">
      <header className="glass flex items-center gap-3 border-x-0 border-t-0 px-4 py-2.5 text-xs">
        <span className="text-[10px] font-semibold uppercase tracking-[0.18em] text-faint">
          知识图谱
        </span>
        <span className="glass-soft rounded-full px-2 py-0.5 text-[10px] text-muted">
          {nodes.length} 节点 · {links.length} 条链接
        </span>
        <span className="hidden text-[10px] text-faint sm:inline">点击节点打开笔记</span>
        <button
          type="button"
          onClick={onClose}
          className="glass-soft ml-auto rounded-full px-3 py-1 text-muted transition-colors hover:text-ink"
        >
          关闭
        </button>
      </header>

      <div ref={containerRef} className="min-h-0 flex-1">
        {error && <p className="p-4 text-xs text-red-300">{error}</p>}
        {!error && nodes.length === 0 && (
          <p className="p-4 text-xs text-faint">
            还没有可展示的关系。在笔记里用 {'[[标题]]'} 互相引用后就会出现在这里。
          </p>
        )}
        {!error && nodes.length > 0 && (
          <ForceGraph2D
            ref={fgRef}
            onEngineTick={applyForces}
            onEngineStop={fitView}
            graphData={{ nodes, links }}
            width={size.width}
            height={size.height}
            backgroundColor="transparent"
            linkColor={() => 'rgba(255,255,255,0.22)'}
            linkWidth={1}
            linkDirectionalArrowLength={3}
            linkDirectionalArrowRelPos={1}
            linkDirectionalParticles={1}
            linkDirectionalParticleWidth={1.4}
            linkDirectionalParticleColor={() => accentColor}
            cooldownTicks={120}
            onNodeClick={(node) => onOpenNote(String(node.id))}
            nodeCanvasObject={(node, ctx, globalScale) => {
              const x = node.x ?? 0;
              const y = node.y ?? 0;
              const label = String(node.name ?? node.id);
              const fontSize = Math.max(9, 12 / globalScale);

              // 节点带一圈同色辉光 —— canvas 里用 shadowBlur 比画渐变便宜得多
              ctx.shadowColor = accentColor;
              ctx.shadowBlur = 14;
              ctx.beginPath();
              ctx.arc(x, y, 3.6, 0, 2 * Math.PI);
              ctx.fillStyle = accentColor;
              ctx.fill();
              ctx.shadowBlur = 0;

              ctx.font = `${fontSize}px ui-sans-serif, system-ui, sans-serif`;
              // 标题底下垫一块半透明的深色底:连线和相邻标题叠在一起时,字仍然看得清
              const textWidth = ctx.measureText(label).width;
              const padX = 3;
              const padY = 2;
              const baseline = y + fontSize / 3;
              ctx.fillStyle = 'rgba(12,14,22,0.55)';
              ctx.fillRect(x + 7 - padX, baseline - fontSize * 0.85 - padY, textWidth + padX * 2, fontSize * 1.1 + padY * 2);
              ctx.fillStyle = 'rgba(255,255,255,0.82)';
              ctx.fillText(label, x + 7, baseline);
            }}
          />
        )}
      </div>
    </div>
  );
}
