import { useState, useMemo, useCallback, useRef, useEffect, memo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { RuleEnvelope } from '../../types';
import {
  type TreeNode,
  type DecisionTreeRule,
  type Operator,
  parseDecisionTree,
  countNodes,
  countLeaves,
  getTreeDepth,
  getOperatorDisplay,
  formatConditionValue,
} from './types';
import { fieldLabel, humanizeValue } from '../../constants/fieldLabels';
import DecisionPathTable from './DecisionPathTable';
import { collectLeafPath, formatPathStep } from './treePathNarrative';
import {
  computeTreeLayout,
  edgeToPath,
  getBranchColor,
  type LayoutNode,
  type TreeLayout,
  type LayoutDirection,
} from './TreeLayoutEngine';

// ════════════════════════════════════════════
// Constants
// ════════════════════════════════════════════
// 中文欄位/運算子簡寫（沿用全站既有字典；節點細節面板使用）
const cnField = (name: string): string => fieldLabel(name);
const cnOp = (op: string): string => getOperatorDisplay(op as Operator).labelCN;

const NODE_SPRING = { type: 'spring' as const, stiffness: 400, damping: 30 };

// ════════════════════════════════════════════
// Tree helpers (search, parent map, path)
// ════════════════════════════════════════════

function buildParentMap(root: TreeNode): Map<string, string> {
  const map = new Map<string, string>();
  const visit = (node: TreeNode | undefined, parentId: string | null) => {
    if (!node || !node.nodeId) return;
    if (parentId) map.set(node.nodeId, parentId);
    if (node.branches) for (const b of node.branches) visit(b.child, node.nodeId);
    visit(node.trueBranch, node.nodeId);
    visit(node.falseBranch, node.nodeId);
  };
  visit(root, null);
  return map;
}

function getPathToNode(parentMap: Map<string, string>, nodeId: string): Set<string> {
  const path = new Set<string>();
  let current: string | undefined = nodeId;
  while (current) { path.add(current); current = parentMap.get(current); }
  return path;
}

function searchNodes(node: TreeNode | undefined, query: string): Set<string> {
  const matches = new Set<string>();
  if (!node || !query) return matches;
  const q = query.toLowerCase();
  const visit = (n: TreeNode | undefined) => {
    if (!n) return;
    const nid = n.nodeId || '';
    if (nid.toLowerCase().includes(q)) matches.add(nid);
    if (n.condition) {
      if (n.condition.field?.toLowerCase().includes(q)) matches.add(nid);
      if (String(n.condition.value ?? '').toLowerCase().includes(q)) matches.add(nid);
    }
    if (n.branches) for (const b of n.branches) {
      if (b.label?.toLowerCase().includes(q)) matches.add(nid);
      visit(b.child);
    }
    if (n.results) for (const r of n.results) {
      if (r.field?.toLowerCase().includes(q)) matches.add(nid);
      if (String(r.value ?? '').toLowerCase().includes(q)) matches.add(nid);
    }
    visit(n.trueBranch); visit(n.falseBranch);
  };
  visit(node);
  return matches;
}

// ════════════════════════════════════════════
// Hover Tooltip
// ════════════════════════════════════════════
const NodeTooltip = memo(function NodeTooltip({
  layoutNode,
}: {
  layoutNode: LayoutNode;
}) {
  const { node } = layoutNode;

  return (
    <motion.div
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: 4 }}
      transition={{ duration: 0.15 }}
      className="absolute z-50 pointer-events-none"
      style={{
        left: layoutNode.x + layoutNode.width / 2,
        top: layoutNode.y - 8,
        transform: 'translate(-50%, -100%)',
      }}
    >
      <div className="
        rounded-lg border px-3 py-2 shadow-lg max-w-xs
        dark:bg-surface-2 dark:border-border dark:text-text-primary
        bg-white border-light-border text-light-text-primary
        text-xs
      ">
        <div className="font-semibold mb-1">{layoutNode.nodeId}</div>
        {node.condition && (
          <div className="mb-1">
            <span className="opacity-60">條件：</span>
            <span className="font-mono">{node.condition.field} {node.condition.operator} {String(node.condition.value ?? '')}</span>
          </div>
        )}
        {node.results && node.results.length > 0 && (
          <div>
            <span className="opacity-60">結果：</span>
            {node.results.map((r, i) => (
              <div key={i} className="ml-2 font-mono">{r.field} = {String(r.value)}</div>
            ))}
          </div>
        )}
        {layoutNode.branchLabel && (
          <div className="mt-1 opacity-50">分支：{layoutNode.branchLabel}</div>
        )}
      </div>
    </motion.div>
  );
});

// ════════════════════════════════════════════
// Context Menu
// ════════════════════════════════════════════
interface ContextMenuProps {
  x: number;
  y: number;
  nodeId: string;
  isLeaf: boolean;
  onClose: () => void;
  onExpandSubtree: (nodeId: string) => void;
  onCollapseAll: () => void;
  onHighlightPath: (nodeId: string) => void;
  onCopyJson: (nodeId: string) => void;
}

const ContextMenu = memo(function ContextMenu({
  x, y, nodeId, isLeaf, onClose, onExpandSubtree, onCollapseAll, onHighlightPath, onCopyJson,
}: ContextMenuProps) {
  useEffect(() => {
    const handler = () => onClose();
    window.addEventListener('click', handler);
    return () => window.removeEventListener('click', handler);
  }, [onClose]);

  const items = [
    ...(!isLeaf ? [{ label: '展開此子樹', action: () => onExpandSubtree(nodeId) }] : []),
    { label: '高亮路徑', action: () => onHighlightPath(nodeId) },
    { label: '全部摺疊', action: () => onCollapseAll() },
    { label: '複製節點 JSON', action: () => onCopyJson(nodeId) },
  ];

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      className="absolute z-50 rounded-lg border shadow-lg overflow-hidden"
      style={{ left: x, top: y }}
    >
      <div className="dark:bg-surface-2 dark:border-border bg-white border-light-border py-1 min-w-[140px]">
        {items.map((item) => (
          <button
            key={item.label}
            onClick={(e) => { e.stopPropagation(); item.action(); onClose(); }}
            className="
              w-full text-left px-3 py-1.5 text-xs
              dark:text-text-secondary dark:hover:bg-surface-3
              text-light-text-secondary hover:bg-gray-100
              transition-colors
            "
          >
            {item.label}
          </button>
        ))}
      </div>
    </motion.div>
  );
});

// ════════════════════════════════════════════
// SVG Tree Node
// ════════════════════════════════════════════
interface SVGTreeNodeProps {
  layoutNode: LayoutNode;
  isHighlighted: boolean;
  isSearchMatch: boolean;
  isOnPath: boolean;
  markerType?: 'gap' | 'dead' | 'merge';
  isHovered: boolean;
  isFocused: boolean;
  onMouseEnter: () => void;
  onMouseLeave: () => void;
  onClick: () => void;
  onContextMenu: (e: React.MouseEvent) => void;
}

const SVGTreeNode = memo(function SVGTreeNode({
  layoutNode, isHighlighted, isSearchMatch, isOnPath, markerType,
  isHovered, isFocused, onMouseEnter, onMouseLeave, onClick, onContextMenu,
}: SVGTreeNodeProps) {
  const { node, isLeaf: leaf } = layoutNode;
  const opDisplay = node.condition ? getOperatorDisplay(node.condition.operator) : null;

  // Determine border/bg classes
  let borderColor = leaf
    ? 'dark:border-border/60 border-light-border/60'
    : 'dark:border-border border-light-border';
  let bgClass = leaf
    ? 'dark:bg-surface-1 bg-white'
    : 'dark:bg-surface-2 bg-gray-50';

  if (isHighlighted) {
    borderColor = 'dark:border-accent/60 border-accent/50 ring-2 ring-accent/20';
    bgClass = 'dark:bg-accent/10 bg-accent/5';
  } else if (isSearchMatch) {
    borderColor = 'dark:border-yellow-500/60 border-yellow-400/50 ring-1 ring-yellow-400/20';
    bgClass = 'dark:bg-yellow-500/10 bg-yellow-50';
  } else if (isOnPath) {
    borderColor = 'dark:border-blue-500/50 border-blue-400/40 ring-1 ring-blue-400/20';
    bgClass = 'dark:bg-blue-500/10 bg-blue-50';
  } else if (isHovered || isFocused) {
    bgClass = leaf ? 'dark:bg-surface-2/80 bg-gray-50' : 'dark:bg-surface-3/60 bg-gray-100';
  }

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ ...NODE_SPRING, delay: layoutNode.y / 800 }}
      className={`absolute rounded-xl border p-2.5 cursor-pointer transition-shadow duration-150 select-none
        ${bgClass} ${borderColor}
        ${(isHovered || isFocused) ? 'shadow-lg' : 'shadow-sm'}
      `}
      style={{
        left: layoutNode.x,
        top: layoutNode.y,
        width: layoutNode.width,
        minHeight: layoutNode.height,
      }}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      onClick={onClick}
      onContextMenu={onContextMenu}
    >
      {/* Header: icon + nodeId + marker */}
      <div className="flex items-center gap-1.5 mb-1">
        <div className={`w-5 h-5 rounded flex items-center justify-center text-[9px] font-bold
          ${leaf
            ? 'dark:bg-emerald-500/15 dark:text-emerald-400 bg-emerald-500/10 text-emerald-600'
            : 'dark:bg-blue-500/15 dark:text-blue-400 bg-blue-500/10 text-blue-600'}
        `}>
          {leaf ? '✓' : '◉'}
        </div>
        <span className="text-[9px] font-mono dark:text-text-tertiary text-light-text-tertiary truncate">
          {layoutNode.nodeId}
        </span>
        {markerType === 'gap' && <span className="text-[8px] font-bold px-1 rounded bg-red-500/15 text-red-400" title="此路徑缺少規則覆蓋">缺口</span>}
        {markerType === 'dead' && <span className="text-[8px] font-bold px-1 rounded bg-orange-500/15 text-orange-400" title="此分支永遠不會被觸發">無效</span>}
        {markerType === 'merge' && <span className="text-[8px] font-bold px-1 rounded bg-yellow-500/15 text-yellow-400" title="可與相鄰分支合併簡化">可合併</span>}
      </div>

      {/* Condition (branch node) — 條件主角化：放大、放寬截斷，用滿節點寬度 */}
      {node.condition && opDisplay && (
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="text-[12px] font-semibold dark:text-text-primary text-light-text-primary truncate max-w-[120px]" title={node.condition.field}>
            {fieldLabel(node.condition.field)}
          </span>
          <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-bold ${opDisplay.colorClass} ${opDisplay.textClass}`}>
            {opDisplay.labelCN}
          </span>
          {node.condition.value !== undefined && node.condition.value !== null && (
            <span className="text-[11px] dark:text-text-secondary text-light-text-secondary font-mono truncate max-w-[110px]">
              {formatConditionValue({ field: node.condition.field, operator: node.condition.operator as Operator, value: node.condition.value })}
            </span>
          )}
        </div>
      )}

      {/* Results (leaf node) — compact */}
      {leaf && node.results && (
        <div className="flex flex-wrap gap-1 mt-0.5">
          {node.results.slice(0, 3).map((r, i) => (
            <div key={i} className="
              inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[9px]
              dark:bg-amber-500/10 dark:text-amber-300 bg-amber-500/10 text-amber-700
              border dark:border-amber-500/20 border-amber-500/15 truncate max-w-[90px]
            ">
              <span className="opacity-60" title={r.field}>{fieldLabel(r.field)}:</span>
              <span className="font-semibold">{humanizeValue(String(r.value))}</span>
            </div>
          ))}
          {node.results.length > 3 && (
            <span className="text-[9px] dark:text-text-tertiary text-light-text-tertiary">+{node.results.length - 3}</span>
          )}
        </div>
      )}
    </motion.div>
  );
});

// ════════════════════════════════════════════
// Stats Bar
// ════════════════════════════════════════════
const TreeStats = memo(function TreeStats({ tree }: { tree: DecisionTreeRule }) {
  const stats = useMemo(() => ({
    nodes: countNodes(tree.root),
    leaves: countLeaves(tree.root),
    depth: getTreeDepth(tree.root),
    inputs: tree.inputs.length,
    outputs: tree.outputs.length,
  }), [tree]);

  const items = [
    { label: '節點', value: stats.nodes, icon: '◉' },
    { label: '葉節點', value: stats.leaves, icon: '✓' },
    { label: '深度', value: stats.depth, icon: '↕' },
    { label: '輸入', value: stats.inputs, icon: '→' },
    { label: '輸出', value: stats.outputs, icon: '←' },
  ];

  return (
    <div className="flex flex-wrap gap-2">
      {items.map((item) => (
        <div key={item.label} className="
          flex items-center gap-1 px-2 py-1 rounded-lg text-[10px]
          dark:bg-surface-2 dark:text-text-secondary bg-gray-100 text-light-text-secondary
        ">
          <span className="opacity-60">{item.icon}</span>
          <span className="font-medium">{item.label}</span>
          <span className="font-bold dark:text-text-primary text-light-text-primary">{item.value}</span>
        </div>
      ))}
    </div>
  );
});

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════

interface AnalyzeData {
  coverageRate?: number;
  gaps?: Array<{ conditions?: Record<string, string>; message?: string }>;
  overlaps?: Array<{ ruleIds?: string[] }>;
  simplifications?: Array<{ ruleIds?: string[]; suggestion?: string }>;
}

interface DecisionTreeViewProps {
  envelope: RuleEnvelope;
  analyze?: AnalyzeData;
}

// ════════════════════════════════════════════
// NodeDetailCard — 點擊節點後展開的詳情卡片
// ════════════════════════════════════════════
interface PathNarrative { condText: string; resText: string; full: string; }

function NodeDetailCard({ layoutNode, pathNarrative, onClose }: { layoutNode: LayoutNode; pathNarrative?: PathNarrative | null; onClose: () => void }) {
  const { node, isLeaf, nodeId } = layoutNode;

  return (
    <motion.div
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0 }}
      className="mx-4 mb-3 rounded-xl border overflow-hidden
        dark:bg-surface-2 dark:border-border bg-white border-light-border"
    >
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b dark:border-border/50 border-light-border/50">
        <div className="flex items-center gap-2">
          <span className={`w-2.5 h-2.5 rounded-full ${isLeaf ? 'bg-emerald-500' : 'bg-blue-500'}`} />
          <span className="text-xs font-bold dark:text-text-primary text-light-text-primary">
            {nodeId} — {isLeaf ? '決策結果' : '判斷條件'}
          </span>
        </div>
        <button onClick={onClose} className="w-5 h-5 rounded flex items-center justify-center
          dark:text-text-tertiary dark:hover:bg-surface-3 text-light-text-tertiary hover:bg-light-surface-3 cursor-pointer">
          <svg width="10" height="10" viewBox="0 0 10 10" stroke="currentColor" strokeWidth="2">
            <line x1="2" y1="2" x2="8" y2="8" /><line x1="8" y1="2" x2="2" y2="8" />
          </svg>
        </button>
      </div>

      <div className="px-4 py-3 space-y-2">
        {isLeaf ? (
          /* 葉節點：顯示所有結果 */
          <>
            {/* P2：root→leaf 路徑的白話規則 + 複製成需求文字 */}
            {pathNarrative && (
              <div className="px-3 py-2 rounded-lg dark:bg-emerald-500/5 bg-emerald-50 border dark:border-emerald-500/15 border-emerald-200">
                <div className="flex items-center justify-between mb-1">
                  <p className="text-[10px] font-medium dark:text-text-tertiary text-light-text-tertiary">白話規則</p>
                  <button
                    onClick={() => navigator.clipboard.writeText(pathNarrative.full)}
                    className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer
                      dark:bg-surface-3 dark:text-text-secondary dark:hover:bg-surface-1
                      bg-light-surface-3 text-light-text-secondary hover:bg-white"
                    title="複製成需求文字"
                  >複製成需求文字</button>
                </div>
                <p className="text-sm leading-relaxed dark:text-text-primary text-light-text-primary">
                  {pathNarrative.condText ? (
                    <>當 <span className="font-semibold">{pathNarrative.condText}</span>，則 <span className="font-semibold text-emerald-600 dark:text-emerald-400">{pathNarrative.resText}</span></>
                  ) : (
                    <>無條件 → <span className="font-semibold text-emerald-600 dark:text-emerald-400">{pathNarrative.resText}</span></>
                  )}
                </p>
              </div>
            )}
            <p className="text-[10px] font-medium dark:text-text-tertiary text-light-text-tertiary uppercase tracking-wider">
              決策結果
            </p>
            {node.results && node.results.length > 0 ? (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                {node.results.map((r: { field: string; value: unknown }, i: number) => (
                  <div key={i} className="flex items-center gap-2 px-3 py-2 rounded-lg
                    dark:bg-surface-3/50 bg-light-surface-2">
                    <span className="text-[11px] font-medium dark:text-text-tertiary text-light-text-tertiary shrink-0">
                      {cnField(r.field)}
                    </span>
                    <span className="text-xs font-bold dark:text-text-primary text-light-text-primary">
                      {String(r.value)}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-xs dark:text-text-tertiary text-light-text-tertiary">無結果資料</p>
            )}
          </>
        ) : (
          /* 分支節點：顯示條件 + 各分支走向 */
          <>
            {node.condition && (
              <div className="px-3 py-2 rounded-lg dark:bg-blue-500/5 bg-blue-50 border dark:border-blue-500/15 border-blue-200">
                <p className="text-[10px] font-medium dark:text-text-tertiary text-light-text-tertiary mb-1">判斷條件</p>
                <p className="text-sm font-semibold dark:text-text-primary text-light-text-primary">
                  {cnField(node.condition.field)}{' '}
                  {cnOp(node.condition.operator)}{' '}
                  {node.condition.value != null ? String(
                    Array.isArray(node.condition.value) ? node.condition.value.join(' ~ ') : node.condition.value
                  ) : ''}
                </p>
              </div>
            )}
            {node.branches && node.branches.length > 0 && (
              <div>
                <p className="text-[10px] font-medium dark:text-text-tertiary text-light-text-tertiary mb-1.5">分支走向</p>
                <div className="space-y-1.5">
                  {node.branches.map((b: { label?: string; condition?: { field: string; operator: string; value?: unknown }; child?: { nodeId?: string; results?: Array<{ field: string; value?: unknown }> } }, i: number) => (
                    <div key={i} className="flex items-start gap-2 text-xs">
                      <span className="shrink-0 mt-0.5 w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold
                        dark:bg-surface-3 dark:text-text-secondary bg-light-surface-3 text-light-text-secondary">
                        {i + 1}
                      </span>
                      <div>
                        <span className="font-semibold dark:text-text-primary text-light-text-primary">
                          {b.label || (b.condition ? `${cnField(b.condition.field)} ${cnOp(b.condition.operator)} ${b.condition.value ?? ''}` : `分支 ${i + 1}`)}
                        </span>
                        <span className="dark:text-text-tertiary text-light-text-tertiary">
                          {' → '}
                          {b.child?.results
                            ? b.child.results.map((r) => `${cnField(r.field)}: ${r.value ?? 'N/A'}`).join('、')
                            : `進入 ${b.child?.nodeId || '下一層判斷'}`
                          }
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </motion.div>
  );
}

export default function DecisionTreeView({ envelope, analyze }: DecisionTreeViewProps) {
  const [highlightedId, setHighlightedId] = useState<string | null>(null);
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [pathHighlightNodeId, setPathHighlightNodeId] = useState<string | null>(null);
  const [scale, setScale] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [isPanning, setIsPanning] = useState(false);
  const [direction, setDirection] = useState<LayoutDirection>('vertical');
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; nodeId: string; isLeaf: boolean } | null>(null);
  const panStartRef = useRef({ x: 0, y: 0, panX: 0, panY: 0 });
  const containerRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  // Parse tree
  const tree = useMemo(() => {
    if (!envelope.rule) return null;
    return parseDecisionTree(envelope.rule as Record<string, unknown>);
  }, [envelope.rule]);
  const treeRoot = tree?.root;

  // Compute layout
  const layout: TreeLayout | null = useMemo(() => {
    if (!treeRoot) return null;
    return computeTreeLayout(treeRoot, direction);
  }, [treeRoot, direction]);

  // Node map for quick lookup
  const nodeMap = useMemo(() => {
    const map = new Map<string, LayoutNode>();
    if (layout) for (const n of layout.nodes) map.set(n.nodeId, n);
    return map;
  }, [layout]);

  // All node IDs for keyboard nav
  const allNodeIds = useMemo(() => layout?.nodes.map(n => n.nodeId) ?? [], [layout]);

  // Search
  const searchMatches = useMemo(() => {
    if (!treeRoot || !searchQuery.trim()) return new Set<string>();
    return searchNodes(treeRoot, searchQuery.trim());
  }, [treeRoot, searchQuery]);

  // Path highlighting
  const parentMap = useMemo(() => treeRoot ? buildParentMap(treeRoot) : new Map<string, string>(), [treeRoot]);
  const pathHighlight = useMemo(() => {
    if (!pathHighlightNodeId) return new Set<string>();
    return getPathToNode(parentMap, pathHighlightNodeId);
  }, [parentMap, pathHighlightNodeId]);

  // Analysis markers
  const analysisMarkers = useMemo(() => {
    const markers = new Map<string, 'gap' | 'dead' | 'merge'>();
    if (!analyze) return markers;
    for (const gap of analyze.gaps ?? []) {
      const match = (gap.message ?? '').match(/N\d+/);
      if (match) markers.set(match[0], 'gap');
    }
    for (const hint of analyze.simplifications ?? []) {
      const isDead = hint.suggestion?.includes('Dead Code');
      for (const id of hint.ruleIds ?? []) markers.set(id, isDead ? 'dead' : 'merge');
    }
    return markers;
  }, [analyze]);

  // P2：選中葉節點 → root→leaf 白話規則
  const leafPathNarrative = useMemo<PathNarrative | null>(() => {
    if (!treeRoot || !highlightedId) return null;
    const ln = nodeMap.get(highlightedId);
    if (!ln?.isLeaf) return null;
    const steps = collectLeafPath(treeRoot, highlightedId);
    if (!steps) return null;
    const condText = steps.map(formatPathStep).filter(Boolean).join(' 且 ');
    const resText = (ln.node.results ?? []).map(r => `${fieldLabel(r.field)}=${humanizeValue(String(r.value))}`).join('、');
    const full = condText ? `當 ${condText}，則 ${resText}` : `無條件 → ${resText}`;
    return { condText, resText, full };
  }, [treeRoot, highlightedId, nodeMap]);

  // === Handlers ===
  const handleNodeClick = useCallback((nodeId: string) => {
    setHighlightedId(prev => prev === nodeId ? null : nodeId);
    setFocusedId(nodeId);
    // Path highlight for leaf nodes
    const ln = nodeMap.get(nodeId);
    if (ln?.isLeaf) {
      setPathHighlightNodeId(prev => prev === nodeId ? null : nodeId);
    } else {
      setPathHighlightNodeId(null);
    }
  }, [nodeMap]);

  const handleContextMenu = useCallback((e: React.MouseEvent, nodeId: string, isLeaf: boolean) => {
    e.preventDefault();
    const rect = containerRef.current?.getBoundingClientRect();
    setContextMenu({
      x: e.clientX - (rect?.left ?? 0),
      y: e.clientY - (rect?.top ?? 0),
      nodeId,
      isLeaf,
    });
  }, []);

  const handleExpandSubtree = useCallback(() => {
    // In SVG layout, all nodes are always visible — no expand/collapse needed
    // This could be extended for very large trees
  }, []);

  const handleCollapseAll = useCallback(() => {
    setHighlightedId(null);
    setPathHighlightNodeId(null);
    setFocusedId(null);
  }, []);

  const handleHighlightPath = useCallback((nodeId: string) => {
    setPathHighlightNodeId(prev => prev === nodeId ? null : nodeId);
    setHighlightedId(nodeId);
  }, []);

  const handleCopyJson = useCallback((nodeId: string) => {
    const findNode = (n: TreeNode | undefined): TreeNode | null => {
      if (!n) return null;
      if (n.nodeId === nodeId) return n;
      if (n.branches) for (const b of n.branches) { const f = findNode(b.child); if (f) return f; }
      return findNode(n.trueBranch) || findNode(n.falseBranch);
    };
    const node = treeRoot ? findNode(treeRoot) : null;
    if (node) navigator.clipboard.writeText(JSON.stringify(node, null, 2));
  }, [treeRoot]);

  // Zoom/Pan — 用 native non-passive wheel listener 才能 preventDefault（React 預設是 passive）
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      setScale(s => Math.max(0.2, Math.min(3, s + (e.deltaY > 0 ? -0.1 : 0.1))));
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, []);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (e.button !== 0) return;
    setIsPanning(true);
    panStartRef.current = { x: e.clientX, y: e.clientY, panX: pan.x, panY: pan.y };
  }, [pan.x, pan.y]);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (!isPanning) return;
    setPan({
      x: panStartRef.current.panX + (e.clientX - panStartRef.current.x),
      y: panStartRef.current.panY + (e.clientY - panStartRef.current.y),
    });
  }, [isPanning]);

  const handleMouseUp = useCallback(() => setIsPanning(false), []);
  const handleResetView = useCallback(() => { setScale(1); setPan({ x: 0, y: 0 }); }, []);

  // Keyboard navigation
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === '/' || (e.ctrlKey && e.key === 'f')) {
        e.preventDefault();
        searchRef.current?.focus();
        return;
      }
      if (e.key === 'Escape') {
        setSearchQuery('');
        setHighlightedId(null);
        setPathHighlightNodeId(null);
        setContextMenu(null);
        searchRef.current?.blur();
        return;
      }
      if (!focusedId || allNodeIds.length === 0) return;
      const idx = allNodeIds.indexOf(focusedId);
      if (e.key === 'ArrowDown' && idx < allNodeIds.length - 1) {
        setFocusedId(allNodeIds[idx + 1]);
        e.preventDefault();
      } else if (e.key === 'ArrowUp' && idx > 0) {
        setFocusedId(allNodeIds[idx - 1]);
        e.preventDefault();
      } else if (e.key === 'Enter') {
        handleNodeClick(focusedId);
        e.preventDefault();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [focusedId, allNodeIds, handleNodeClick]);

  // ── Not a tree ──
  if (!tree || !tree.root || !layout) {
    if (envelope.ruleType !== 'DecisionTree') return null;
    return (
      <div className="rounded-xl border p-8 text-center dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <p className="text-sm font-semibold dark:text-text-secondary text-light-text-secondary">DecisionTree 資料不可用</p>
        <p className="text-xs dark:text-text-tertiary text-light-text-tertiary mt-1">root 節點為空</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
    <div className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border">
      {/* ─── Header ─── */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b dark:border-border/50 border-light-border/50">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-lg flex items-center justify-center dark:bg-accent/15 dark:text-accent bg-accent/10 text-accent">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 3v6M9 6h6M6 12H3M18 12h3M12 21v-6M9 18h6" />
            </svg>
          </div>
          <h3 className="text-sm font-semibold dark:text-text-primary text-light-text-primary">決策樹</h3>
        </div>

        <div className="flex items-center gap-1">
          {/* Direction toggle */}
          <button onClick={() => setDirection(d => d === 'vertical' ? 'horizontal' : 'vertical')}
            className="px-1.5 py-1 rounded text-[10px] font-medium dark:text-text-tertiary dark:hover:bg-surface-3 text-light-text-tertiary hover:bg-gray-100 transition-colors"
            title={direction === 'vertical' ? '切換水平佈局' : '切換垂直佈局'}
          >{direction === 'vertical' ? '↕ 垂直' : '↔ 水平'}</button>

          <div className="w-px h-4 dark:bg-border/30 bg-light-border/30 mx-0.5" />

          {/* Zoom */}
          <button onClick={() => setScale(s => Math.min(3, s + 0.2))}
            className="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold dark:text-text-tertiary dark:hover:bg-surface-3 text-light-text-tertiary hover:bg-gray-100"
          >+</button>
          <span className="text-[10px] tabular-nums dark:text-text-tertiary text-light-text-tertiary w-8 text-center">
            {Math.round(scale * 100)}%
          </span>
          <button onClick={() => setScale(s => Math.max(0.2, s - 0.2))}
            className="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold dark:text-text-tertiary dark:hover:bg-surface-3 text-light-text-tertiary hover:bg-gray-100"
          >-</button>
          <button onClick={handleResetView}
            className="px-1.5 py-1 rounded text-[10px] font-medium dark:text-text-tertiary dark:hover:bg-surface-3 text-light-text-tertiary hover:bg-gray-100"
          >重置</button>
        </div>
      </div>

      {/* ─── 說明 ─── */}
      <div className="px-4 py-2.5 border-b dark:border-border/30 border-light-border/30
        dark:bg-surface-2/30 bg-light-surface-2/30">
        <p className="text-[11px] leading-relaxed dark:text-text-secondary text-light-text-secondary">
          決策樹從上往下（或左到右）閱讀，每個
          <span className="inline-flex items-center mx-0.5 px-1 py-0.5 rounded text-[10px] font-semibold bg-blue-500/10 text-blue-500">分支節點</span>
          是一個判斷條件，根據條件的結果走向不同路徑。最終到達
          <span className="inline-flex items-center mx-0.5 px-1 py-0.5 rounded text-[10px] font-semibold bg-emerald-500/10 text-emerald-500">葉節點</span>
          就是該情境的決策結果。點擊任一節點可高亮整條決策路徑。
        </p>
      </div>

      {/* ─── Search + Stats ─── */}
      <div className="px-4 py-2 flex items-center gap-3 border-b dark:border-border/30 border-light-border/30">
        <div className="relative flex-1 max-w-xs">
          <svg className="absolute left-2 top-1/2 -translate-y-1/2 w-3 h-3 dark:text-text-tertiary text-light-text-tertiary" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <circle cx="11" cy="11" r="8" /><path d="M21 21l-4.35-4.35" />
          </svg>
          <input
            ref={searchRef}
            type="text"
            placeholder="搜尋 (Ctrl+F)..."
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            className="w-full pl-7 pr-16 py-1 rounded-lg text-[11px]
              dark:bg-surface-2 dark:border-border/40 dark:text-text-primary dark:placeholder-text-tertiary
              bg-gray-50 border border-light-border/40 text-light-text-primary placeholder-light-text-tertiary
              focus:outline-none focus:ring-1 focus:ring-accent/30"
          />
          {searchQuery && (
            <div className="absolute right-1.5 top-1/2 -translate-y-1/2 flex items-center gap-1">
              <span className="text-[9px] dark:text-text-tertiary text-light-text-tertiary">{searchMatches.size} 匹配</span>
              <button onClick={() => setSearchQuery('')}
                className="w-4 h-4 rounded-full flex items-center justify-center dark:hover:bg-surface-3 hover:bg-gray-200 dark:text-text-tertiary text-light-text-tertiary">
                <svg width="7" height="7" viewBox="0 0 10 10" stroke="currentColor" strokeWidth="2.5">
                  <line x1="2" y1="2" x2="8" y2="8" /><line x1="8" y1="2" x2="2" y2="8" />
                </svg>
              </button>
            </div>
          )}
        </div>
        <TreeStats tree={tree} />
      </div>

      {/* ─── SVG Tree Canvas ─── */}
      <div
        ref={containerRef}
        className="relative overflow-hidden cursor-grab active:cursor-grabbing"
        style={{ height: Math.min(600, Math.max(300, layout.totalHeight * scale + 80)) }}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUp}
        onMouseLeave={handleMouseUp}
      >
        <div
          className="absolute"
          style={{
            transform: `translate(${pan.x}px, ${pan.y}px) scale(${scale})`,
            transformOrigin: 'top left',
            transition: isPanning ? 'none' : 'transform 0.15s ease-out',
            width: layout.totalWidth,
            height: layout.totalHeight,
          }}
        >
          {/* SVG edges */}
          <svg
            className="absolute inset-0 pointer-events-none"
            width={layout.totalWidth}
            height={layout.totalHeight}
          >
            {layout.edges.map((edge, i) => {
              const color = getBranchColor(edge.branchIndex);
              const isOnHighlightPath = pathHighlight.size > 0 &&
                layout.nodes.some(n => n.nodeId && pathHighlight.has(n.nodeId) &&
                  Math.abs(n.x + n.width / 2 - edge.to.x) < 2 &&
                  Math.abs(n.y - edge.to.y) < 2);

              return (
                <g key={i}>
                  <motion.path
                    d={edgeToPath(edge, direction)}
                    fill="none"
                    stroke={isOnHighlightPath ? '#3b82f6' : color}
                    strokeWidth={isOnHighlightPath ? 2.5 : 1.5}
                    strokeOpacity={isOnHighlightPath ? 1 : 0.4}
                    initial={{ pathLength: 0 }}
                    animate={{ pathLength: 1 }}
                    transition={{ duration: 0.6, delay: i * 0.03, ease: 'easeOut' }}
                  />
                  {/* Branch label on edge（答案）— 放大 + 白色描邊 halo 提升可讀性 */}
                  {edge.label && (
                    <text
                      x={(edge.from.x + edge.to.x) / 2 + (edge.to.x > edge.from.x ? 8 : -8)}
                      y={(edge.from.y + edge.to.y) / 2}
                      textAnchor="middle"
                      className="text-[11px] font-bold stroke-white dark:stroke-surface-1"
                      fill={color}
                      strokeWidth={3}
                      paintOrder="stroke"
                    >
                      {edge.label}
                    </text>
                  )}
                </g>
              );
            })}
          </svg>

          {/* HTML nodes */}
          {layout.nodes.map((ln) => (
            <SVGTreeNode
              key={ln.nodeId}
              layoutNode={ln}
              isHighlighted={highlightedId === ln.nodeId}
              isSearchMatch={searchMatches.has(ln.nodeId)}
              isOnPath={pathHighlight.has(ln.nodeId)}
              markerType={analysisMarkers.get(ln.nodeId)}
              isHovered={hoveredId === ln.nodeId}
              isFocused={focusedId === ln.nodeId}
              onMouseEnter={() => setHoveredId(ln.nodeId)}
              onMouseLeave={() => setHoveredId(null)}
              onClick={() => handleNodeClick(ln.nodeId)}
              onContextMenu={(e) => handleContextMenu(e, ln.nodeId, ln.isLeaf)}
            />
          ))}

          {/* Hover tooltip */}
          <AnimatePresence>
            {hoveredId && nodeMap.has(hoveredId) && (
              <NodeTooltip
                layoutNode={nodeMap.get(hoveredId)!}
              />
            )}
          </AnimatePresence>
        </div>

        {/* Context menu */}
        <AnimatePresence>
          {contextMenu && (
            <ContextMenu
              x={contextMenu.x}
              y={contextMenu.y}
              nodeId={contextMenu.nodeId}
              isLeaf={contextMenu.isLeaf}
              onClose={() => setContextMenu(null)}
              onExpandSubtree={handleExpandSubtree}
              onCollapseAll={handleCollapseAll}
              onHighlightPath={handleHighlightPath}
              onCopyJson={handleCopyJson}
            />
          )}
        </AnimatePresence>
      </div>

      {/* ─── 節點詳情卡片 ─── */}
      {highlightedId && nodeMap.get(highlightedId) && (
        <NodeDetailCard
          layoutNode={nodeMap.get(highlightedId)!}
          pathNarrative={leafPathNarrative}
          onClose={() => { setHighlightedId(null); setPathHighlightNodeId(null); }}
        />
      )}

      {/* ─── Legend ─── */}
      <div className="flex flex-wrap items-center gap-3 px-4 py-2 border-t dark:border-border/30 border-light-border/30 text-[10px] dark:text-text-tertiary text-light-text-tertiary">
        <div className="flex items-center gap-1"><div className="w-2.5 h-2.5 rounded dark:bg-blue-500/20 bg-blue-500/15" /><span>判斷條件</span></div>
        <div className="flex items-center gap-1"><div className="w-2.5 h-2.5 rounded dark:bg-emerald-500/20 bg-emerald-500/15" /><span>決策結果</span></div>
        <div className="flex items-center gap-1"><div className="w-2.5 h-2.5 rounded bg-blue-500/30" /><span>決策路徑</span></div>
        <span className="opacity-30">|</span>
        <span className="opacity-50">點擊節點高亮路徑 · Ctrl+F 搜尋 · 滾輪縮放</span>
      </div>
    </div>

    {/* 決策路徑表（方向②）：每條 root→leaf 路徑 = 一句白話規則 */}
    <DecisionPathTable envelope={envelope} />
    </div>
  );
}
