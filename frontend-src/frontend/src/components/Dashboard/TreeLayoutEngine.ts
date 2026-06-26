/**
 * TreeLayoutEngine — 樹狀圖自動佈局引擎
 *
 * 簡化版 Reingold-Tilford 演算法：
 * 1. 後序遍歷計算每個子樹的寬度
 * 2. 前序遍歷分配 x/y 座標（子節點水平排列，父節點居中）
 * 3. 支援垂直（top-down）和水平（left-right）兩種模式
 */

import type { TreeNode } from './types';

export interface LayoutNode {
  nodeId: string;
  x: number;
  y: number;
  width: number;
  height: number;
  isLeaf: boolean;
  node: TreeNode;
  children: LayoutNode[];
  branchLabel?: string;
  branchIndex?: number;
}

export interface LayoutEdge {
  from: { x: number; y: number };
  to: { x: number; y: number };
  label: string;
  branchIndex: number;
}

export interface TreeLayout {
  nodes: LayoutNode[];
  edges: LayoutEdge[];
  totalWidth: number;
  totalHeight: number;
}

export type LayoutDirection = 'vertical' | 'horizontal';

// Layout configuration
const NODE_WIDTH = 200;
const NODE_HEIGHT_BRANCH = 64;
const NODE_HEIGHT_LEAF = 80;
const H_GAP = 24;   // horizontal gap between siblings
const V_GAP = 60;    // vertical gap between levels

/** Get children from a TreeNode (N-ary branches or legacy) */
function getNodeChildren(node: TreeNode): { child: TreeNode; label: string }[] {
  if (node.branches && node.branches.length > 0) {
    return node.branches
      .filter(b => b.child)
      .map(b => ({ child: b.child, label: b.label || '' }));
  }
  const children: { child: TreeNode; label: string }[] = [];
  if (node.trueBranch) children.push({ child: node.trueBranch, label: 'TRUE' });
  if (node.falseBranch) children.push({ child: node.falseBranch, label: 'FALSE' });
  return children;
}

function isLeaf(node: TreeNode): boolean {
  const children = getNodeChildren(node);
  return children.length === 0 && !!node.results && node.results.length > 0;
}

function nodeHeight(node: TreeNode): number {
  return isLeaf(node) ? NODE_HEIGHT_LEAF : NODE_HEIGHT_BRANCH;
}

/**
 * Phase 1: Compute subtree widths (bottom-up)
 * Returns the total width needed for this subtree
 */
function computeSubtreeWidth(node: TreeNode): number {
  const children = getNodeChildren(node);
  if (children.length === 0) return NODE_WIDTH;

  const childWidths = children.map(c => computeSubtreeWidth(c.child));
  const totalChildWidth = childWidths.reduce((sum, w) => sum + w, 0)
    + (children.length - 1) * H_GAP;

  return Math.max(NODE_WIDTH, totalChildWidth);
}

/**
 * Phase 2: Assign positions (top-down)
 */
function assignPositions(
  node: TreeNode,
  x: number,
  y: number,
  layoutNodes: LayoutNode[],
  layoutEdges: LayoutEdge[],
  branchLabel?: string,
  branchIndex?: number
): LayoutNode {
  const nodeId = node.nodeId || `auto-${layoutNodes.length}`;
  const leaf = isLeaf(node);
  const h = nodeHeight(node);

  const layoutNode: LayoutNode = {
    nodeId,
    x,
    y,
    width: NODE_WIDTH,
    height: h,
    isLeaf: leaf,
    node,
    children: [],
    branchLabel,
    branchIndex,
  };
  layoutNodes.push(layoutNode);

  const children = getNodeChildren(node);
  if (children.length > 0) {
    // Compute child subtree widths
    const childWidths = children.map(c => computeSubtreeWidth(c.child));
    const totalChildWidth = childWidths.reduce((sum, w) => sum + w, 0)
      + (children.length - 1) * H_GAP;

    // Start x for first child (center children under parent)
    let childX = x + NODE_WIDTH / 2 - totalChildWidth / 2;
    const childY = y + h + V_GAP;

    children.forEach((child, idx) => {
      const childCenterX = childX + childWidths[idx] / 2 - NODE_WIDTH / 2;

      const childLayout = assignPositions(
        child.child,
        childCenterX,
        childY,
        layoutNodes,
        layoutEdges,
        child.label,
        idx
      );
      layoutNode.children.push(childLayout);

      // Edge from parent bottom-center to child top-center
      layoutEdges.push({
        from: { x: x + NODE_WIDTH / 2, y: y + h },
        to: { x: childCenterX + NODE_WIDTH / 2, y: childY },
        label: child.label,
        branchIndex: idx,
      });

      childX += childWidths[idx] + H_GAP;
    });
  }

  return layoutNode;
}

/**
 * Compute full tree layout
 */
export function computeTreeLayout(
  root: TreeNode,
  direction: LayoutDirection = 'vertical'
): TreeLayout {
  const layoutNodes: LayoutNode[] = [];
  const layoutEdges: LayoutEdge[] = [];

  const totalWidth = computeSubtreeWidth(root);
  const startX = totalWidth / 2 - NODE_WIDTH / 2;

  assignPositions(root, startX, 0, layoutNodes, layoutEdges);

  // Calculate total dimensions
  let maxX = 0;
  let maxY = 0;
  for (const n of layoutNodes) {
    maxX = Math.max(maxX, n.x + n.width);
    maxY = Math.max(maxY, n.y + n.height);
  }

  // For horizontal mode, swap x/y
  if (direction === 'horizontal') {
    for (const n of layoutNodes) {
      const tmp = n.x;
      n.x = n.y;
      n.y = tmp;
      const tmpW = n.width;
      n.width = n.height;
      n.height = tmpW;
    }
    for (const e of layoutEdges) {
      [e.from.x, e.from.y] = [e.from.y, e.from.x];
      [e.to.x, e.to.y] = [e.to.y, e.to.x];
    }
    [maxX, maxY] = [maxY, maxX];
  }

  return {
    nodes: layoutNodes,
    edges: layoutEdges,
    totalWidth: maxX + 40, // padding
    totalHeight: maxY + 40,
  };
}

/**
 * Generate SVG path for a curved edge (bezier curve)
 */
export function edgeToPath(edge: LayoutEdge, direction: LayoutDirection = 'vertical'): string {
  const { from, to } = edge;

  if (direction === 'vertical') {
    const midY = (from.y + to.y) / 2;
    return `M ${from.x} ${from.y} C ${from.x} ${midY}, ${to.x} ${midY}, ${to.x} ${to.y}`;
  } else {
    const midX = (from.x + to.x) / 2;
    return `M ${from.x} ${from.y} C ${midX} ${from.y}, ${midX} ${to.y}, ${to.x} ${to.y}`;
  }
}

// Branch color palette (same as DecisionTreeView)
export const BRANCH_COLORS = [
  '#10b981', // emerald
  '#f43f5e', // rose
  '#3b82f6', // blue
  '#f59e0b', // amber
  '#8b5cf6', // violet
  '#06b6d4', // cyan
  '#ec4899', // pink
  '#84cc16', // lime
];

export function getBranchColor(index: number): string {
  return BRANCH_COLORS[index % BRANCH_COLORS.length];
}
