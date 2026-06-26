import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MouseEvent,
  type WheelEvent,
} from 'react';
import type { GroupTree, GroupTreeEdge, GroupTreeNode } from '../types';

const NODE_WIDTH = 148;
const NODE_HEIGHT = 48;
const CANVAS_MARGIN = 40;
const MIN_SCALE = 0.35;
const MAX_SCALE = 2.5;

interface PositionedNode extends GroupTreeNode {
  x: number;
  y: number;
}

interface PositionedEdge extends GroupTreeEdge {
  fromNode: PositionedNode;
  toNode: PositionedNode;
}

interface GroupTreeViewProps {
  tree: GroupTree | null;
  loading?: boolean;
  error?: string | null;
  hitPath?: string[];
  hitNodeId?: string;
  hitRuleId?: string;
}

function formatValue(value: unknown): string {
  if (value == null) return '';
  if (Array.isArray(value)) return value.map(formatValue).join(', ');
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

function truncate(text: string, limit = 18): string {
  const chars = Array.from(text);
  return chars.length > limit ? `${chars.slice(0, limit).join('')}...` : text;
}

function nodeLabel(node: GroupTreeNode): string {
  if (node.labelChinese) return node.labelChinese;
  const result = node.outputs?.[0] ?? node.results?.[0];
  if (result) return `${result.field}: ${formatValue(result.value)}`;
  return node.fieldName || node.nodeId;
}

function edgeLabel(edge: GroupTreeEdge): string {
  if (edge.label) return edge.label;
  const value = formatValue(edge.value);
  switch (edge.operator) {
    case 'equals':
      return `= ${value}`.trim();
    case 'notEquals':
      return `!= ${value}`.trim();
    case 'greaterThan':
      return `> ${value}`.trim();
    case 'greaterThanOrEqual':
      return `>= ${value}`.trim();
    case 'lessThan':
      return `< ${value}`.trim();
    case 'lessThanOrEqual':
      return `<= ${value}`.trim();
    case 'between':
      return `[] ${Array.isArray(edge.value) ? edge.value.join('..') : value}`.trim();
    case 'in':
      return `in [${Array.isArray(edge.value) ? edge.value.join(', ') : value}]`;
    case 'notIn':
      return `not in [${Array.isArray(edge.value) ? edge.value.join(', ') : value}]`;
    case 'isNull':
      return 'is null';
    case 'isNotNull':
      return 'is not null';
    case 'anything':
      return '*';
    default:
      return value ? `${edge.operator ?? ''} ${value}`.trim() : edge.operator ?? '';
  }
}

function pathForEdge(edge: PositionedEdge): string {
  const startX = edge.fromNode.x + NODE_WIDTH / 2;
  const startY = edge.fromNode.y + NODE_HEIGHT;
  const endX = edge.toNode.x + NODE_WIDTH / 2;
  const endY = edge.toNode.y;
  const midY = startY + Math.max(24, (endY - startY) / 2);
  return `M ${startX} ${startY} C ${startX} ${midY}, ${endX} ${midY}, ${endX} ${endY}`;
}

export default function GroupTreeView({
  tree,
  loading = false,
  error = null,
  hitPath = [],
  hitNodeId,
  hitRuleId,
}: GroupTreeViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef({ active: false, x: 0, y: 0, panX: 0, panY: 0 });
  const [scale, setScale] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [locked, setLocked] = useState(false);

  const layout = useMemo(() => {
    if (!tree || !Array.isArray(tree.nodes) || tree.nodes.length === 0) return null;

    const rawNodes = tree.nodes.map((node, index) => ({
      ...node,
      rawX: node.position?.x ?? (index % 4) * 190,
      rawY: node.position?.y ?? Math.floor(index / 4) * 110,
    }));
    const minX = Math.min(...rawNodes.map((node) => node.rawX));
    const minY = Math.min(...rawNodes.map((node) => node.rawY));
    const positionedNodes: PositionedNode[] = rawNodes.map(({ rawX, rawY, ...node }) => ({
      ...node,
      x: rawX - minX + CANVAS_MARGIN,
      y: rawY - minY + CANVAS_MARGIN,
    }));
    const nodeMap = new Map(positionedNodes.map((node) => [node.nodeId, node]));
    const positionedEdges: PositionedEdge[] = (tree.edges ?? [])
      .map((edge) => {
        const fromNode = nodeMap.get(edge.from);
        const toNode = nodeMap.get(edge.to);
        return fromNode && toNode ? { ...edge, fromNode, toNode } : null;
      })
      .filter((edge): edge is PositionedEdge => edge != null);

    const maxX = Math.max(...positionedNodes.map((node) => node.x + NODE_WIDTH));
    const maxY = Math.max(...positionedNodes.map((node) => node.y + NODE_HEIGHT + 18));

    return {
      nodes: positionedNodes,
      edges: positionedEdges,
      width: maxX + CANVAS_MARGIN,
      height: maxY + CANVAS_MARGIN,
    };
  }, [tree]);

  const hitNodes = useMemo(() => {
    const ids = new Set(hitPath ?? []);
    const effectiveHitNodeId = hitNodeId ?? hitPath[hitPath.length - 1];
    if (effectiveHitNodeId) ids.add(effectiveHitNodeId);
    return ids;
  }, [hitNodeId, hitPath]);

  const hitEdges = useMemo(() => {
    const pairs = new Set<string>();
    const nodeHits = new Set(hitPath ?? []);
    for (let i = 0; i < hitPath.length - 1; i += 1) {
      pairs.add(`${hitPath[i]}->${hitPath[i + 1]}`);
    }
    for (const edge of layout?.edges ?? []) {
      if (nodeHits.has(edge.to)) {
        pairs.add(`${edge.from}->${edge.to}`);
      }
    }
    return pairs;
  }, [hitPath, layout?.edges]);

  const fitToView = useCallback(() => {
    if (!layout || !containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const nextScale = Math.max(
      MIN_SCALE,
      Math.min(MAX_SCALE, Math.min((rect.width - 32) / layout.width, (rect.height - 32) / layout.height)),
    );
    setScale(nextScale);
    setPan({
      x: Math.max(16, (rect.width - layout.width * nextScale) / 2),
      y: Math.max(16, (rect.height - layout.height * nextScale) / 2),
    });
  }, [layout]);

  useEffect(() => {
    fitToView();
  }, [fitToView]);

  useEffect(() => {
    window.addEventListener('resize', fitToView);
    return () => window.removeEventListener('resize', fitToView);
  }, [fitToView]);

  const handleWheel = useCallback((event: WheelEvent<HTMLDivElement>) => {
    if (locked || !layout) return;
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.08 : 0.08;
    setScale((current) => Math.max(MIN_SCALE, Math.min(MAX_SCALE, current + delta)));
  }, [layout, locked]);

  const handleMouseDown = useCallback((event: MouseEvent<HTMLDivElement>) => {
    if (locked || event.button !== 0) return;
    dragRef.current = { active: true, x: event.clientX, y: event.clientY, panX: pan.x, panY: pan.y };
  }, [locked, pan.x, pan.y]);

  const handleMouseMove = useCallback((event: MouseEvent<HTMLDivElement>) => {
    if (!dragRef.current.active) return;
    setPan({
      x: dragRef.current.panX + event.clientX - dragRef.current.x,
      y: dragRef.current.panY + event.clientY - dragRef.current.y,
    });
  }, []);

  const stopDrag = useCallback(() => {
    dragRef.current.active = false;
  }, []);

  if (loading) {
    return (
      <div className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div className="h-[34rem] flex items-center justify-center text-sm dark:text-text-tertiary text-light-text-tertiary">
          Loading Group tree...
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div className="h-[34rem] flex items-center justify-center px-6 text-sm text-danger text-center">
          {error}
        </div>
      </div>
    );
  }

  if (!layout) {
    return (
      <div className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div className="h-[34rem] flex items-center justify-center text-sm dark:text-text-tertiary text-light-text-tertiary">
          No Group tree data
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border">
      <div className="flex items-center justify-between px-4 py-3 border-b dark:border-border/50 border-light-border/50">
        <div>
          <h3 className="text-sm font-semibold dark:text-text-primary text-light-text-primary">
            Group Tree
          </h3>
          <p className="text-[10px] font-mono dark:text-text-tertiary text-light-text-tertiary">
            {tree?.treeId ?? 'draft'} · {layout.nodes.length} nodes · {layout.edges.length} edges
          </p>
        </div>
        {hitRuleId && (
          <span className="rounded-md px-2 py-1 text-[10px] font-semibold bg-blue-500/10 text-blue-600 dark:text-blue-400">
            hit {hitRuleId}
          </span>
        )}
      </div>

      <div
        ref={containerRef}
        className={`relative h-[34rem] overflow-hidden ${locked ? 'cursor-default' : 'cursor-grab active:cursor-grabbing'}`}
        style={{
          backgroundColor: '#fff',
          backgroundImage: 'radial-gradient(#E8E8E8 1px, transparent 1px)',
          backgroundSize: '16px 16px',
        }}
        onWheel={handleWheel}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={stopDrag}
        onMouseLeave={stopDrag}
      >
        <div
          className="absolute top-0 left-0"
          style={{
            width: layout.width,
            height: layout.height,
            transform: `translate(${pan.x}px, ${pan.y}px) scale(${scale})`,
            transformOrigin: 'top left',
          }}
        >
          <svg width={layout.width} height={layout.height} className="absolute inset-0">
            {layout.edges.map((edge) => {
              const isHit = hitEdges.has(`${edge.from}->${edge.to}`);
              const faded = hitNodes.size > 0 && !isHit;
              const label = truncate(edgeLabel(edge), 16);
              const startX = edge.fromNode.x + NODE_WIDTH / 2;
              const startY = edge.fromNode.y + NODE_HEIGHT;
              const endX = edge.toNode.x + NODE_WIDTH / 2;
              const endY = edge.toNode.y;
              const labelX = (startX + endX) / 2;
              const labelY = (startY + endY) / 2 - 4;

              return (
                <g key={`${edge.from}-${edge.to}-${label}`} opacity={faded ? 0.5 : 1}>
                  <path
                    d={pathForEdge(edge)}
                    fill="none"
                    stroke={isHit ? '#0D47A1' : '#BDBDBD'}
                    strokeWidth={isHit ? 2 : 1}
                  />
                  {label && (
                    <>
                      <text
                        x={labelX}
                        y={labelY}
                        textAnchor="middle"
                        fontSize="12"
                        stroke="#FFFFFF"
                        strokeWidth="4"
                        paintOrder="stroke"
                      >
                        {label}
                      </text>
                      <text
                        x={labelX}
                        y={labelY}
                        textAnchor="middle"
                        fontSize="12"
                        fill={isHit ? '#0D47A1' : '#666666'}
                      >
                        {label}
                      </text>
                    </>
                  )}
                </g>
              );
            })}
          </svg>

          {layout.nodes.map((node) => {
            const isLeaf = node.type === 'LEAF';
            const effectiveHitNodeId = hitNodeId ?? hitPath[hitPath.length - 1];
            const onPath = hitNodes.has(node.nodeId);
            const isHitLeaf = isLeaf && (hitNodeId ? node.nodeId === hitNodeId : hitNodes.has(node.nodeId));
            const faded = hitNodes.size > 0 && !onPath;
            const label = nodeLabel(node);

            return (
              <div
                key={node.nodeId}
                title={label}
                className="absolute flex items-center justify-center text-center text-[13px] font-semibold leading-snug px-2 shadow-sm"
                style={{
                  left: node.x,
                  top: node.y,
                  width: NODE_WIDTH,
                  height: NODE_HEIGHT,
                  borderRadius: 6,
                  opacity: faded ? 0.5 : 1,
                  color: '#333333',
                  background: isLeaf ? '#FFFFFF' : onPath ? '#E3F2FD' : '#F5F5F5',
                  border: isLeaf
                    ? `${isHitLeaf ? 3 : 2}px solid ${isHitLeaf ? '#0D47A1' : '#1976D2'}`
                    : '1px solid #E0E0E0',
                }}
              >
                {truncate(label)}
                {isHitLeaf && (
                  <>
                    <span
                      className="absolute rounded-full"
                      style={{ width: 8, height: 8, right: 6, top: 6, background: '#43A047' }}
                    />
                    {(hitRuleId || node.nodeId) && (
                      <span
                        className="absolute text-[10px] font-bold"
                        style={{ left: 0, right: 0, bottom: -17, color: '#43A047' }}
                      >
                        {node.nodeId === effectiveHitNodeId ? (hitRuleId ?? node.nodeId) : node.nodeId}
                      </span>
                    )}
                  </>
                )}
              </div>
            );
          })}
        </div>

        <div className="absolute left-4 bottom-4 z-10 flex flex-col rounded-lg border bg-white shadow-sm border-light-border overflow-hidden">
          <button
            type="button"
            onClick={(event) => { event.stopPropagation(); setScale((current) => Math.min(MAX_SCALE, current + 0.15)); }}
            className="w-8 h-8 text-sm font-bold text-gray-700 hover:bg-gray-100 cursor-pointer"
            title="Zoom in"
          >
            +
          </button>
          <button
            type="button"
            onClick={(event) => { event.stopPropagation(); setScale((current) => Math.max(MIN_SCALE, current - 0.15)); }}
            className="w-8 h-8 text-sm font-bold text-gray-700 hover:bg-gray-100 cursor-pointer border-t border-light-border"
            title="Zoom out"
          >
            -
          </button>
          <button
            type="button"
            onClick={(event) => { event.stopPropagation(); fitToView(); }}
            className="w-8 h-8 text-[11px] font-bold text-gray-700 hover:bg-gray-100 cursor-pointer border-t border-light-border"
            title="Reset"
          >
            []
          </button>
          <button
            type="button"
            onClick={(event) => { event.stopPropagation(); setLocked((current) => !current); }}
            className={`w-8 h-8 text-[13px] font-bold cursor-pointer border-t border-light-border ${locked ? 'bg-blue-50 text-blue-700' : 'text-gray-700 hover:bg-gray-100'}`}
            title={locked ? 'Unlock pan' : 'Lock pan'}
          >
            {locked ? 'L' : 'U'}
          </button>
        </div>
      </div>

      {/* Legend — node types + non-obvious operator abbreviations */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 px-4 py-2.5 border-t text-[11px]
        dark:border-border/50 border-light-border/50 dark:text-text-tertiary text-light-text-tertiary">
        <span className="inline-flex items-center gap-1.5">
          <span style={{ display: 'inline-block', width: 18, height: 12, borderRadius: 3, background: '#F5F5F5', border: '1px solid #E0E0E0' }} />
          判斷節點
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span style={{ display: 'inline-block', width: 18, height: 12, borderRadius: 3, background: '#FFFFFF', border: '2px solid #1976D2' }} />
          結果節點
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 9999, background: '#43A047' }} />
          命中結果
        </span>
        <span className="opacity-30">|</span>
        <span className="inline-flex flex-wrap items-center gap-x-3 gap-y-1">
          <span><b className="font-mono dark:text-text-secondary text-light-text-secondary">[]</b> 區間</span>
          <span><b className="font-mono dark:text-text-secondary text-light-text-secondary">in</b> 清單</span>
          <span><b className="font-mono dark:text-text-secondary text-light-text-secondary">*</b> 不限</span>
          <span><b className="font-mono dark:text-text-secondary text-light-text-secondary">is null</b> 空值</span>
        </span>
      </div>
    </div>
  );
}
