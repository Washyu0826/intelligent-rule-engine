import { describe, it, expect } from 'vitest';
import { computeTreeLayout, edgeToPath, getBranchColor } from '../components/Dashboard/TreeLayoutEngine';
import type { TreeNode } from '../components/Dashboard/types';

describe('TreeLayoutEngine', () => {
  const leafNode: TreeNode = {
    nodeId: 'N01',
    results: [{ field: 'decision', value: 'approve' }],
  };

  const binaryTree: TreeNode = {
    nodeId: 'N01',
    condition: { field: 'age', operator: 'greaterThan', value: 60 },
    branches: [
      { label: 'TRUE', condition: { field: 'age', operator: 'greaterThan', value: 60 }, child: { nodeId: 'N02', results: [{ field: 'd', value: 'reject' }] } },
      { label: 'FALSE', condition: { field: 'age', operator: 'lessThanOrEqual', value: 60 }, child: { nodeId: 'N03', results: [{ field: 'd', value: 'approve' }] } },
    ],
  };

  const naryTree: TreeNode = {
    nodeId: 'N01',
    branches: [
      { label: 'A', child: { nodeId: 'N02', results: [{ field: 'r', value: 1 }] } },
      { label: 'B', child: { nodeId: 'N03', results: [{ field: 'r', value: 2 }] } },
      { label: 'C', child: { nodeId: 'N04', results: [{ field: 'r', value: 3 }] } },
    ],
  };

  describe('computeTreeLayout — vertical', () => {
    it('single leaf node', () => {
      const layout = computeTreeLayout(leafNode, 'vertical');
      expect(layout.nodes.length).toBe(1);
      expect(layout.edges.length).toBe(0);
      expect(layout.totalWidth).toBeGreaterThan(0);
      expect(layout.totalHeight).toBeGreaterThan(0);
    });

    it('binary tree — 3 nodes, 2 edges', () => {
      const layout = computeTreeLayout(binaryTree, 'vertical');
      expect(layout.nodes.length).toBe(3);
      expect(layout.edges.length).toBe(2);
    });

    it('N-ary tree — 4 nodes, 3 edges', () => {
      const layout = computeTreeLayout(naryTree, 'vertical');
      expect(layout.nodes.length).toBe(4);
      expect(layout.edges.length).toBe(3);
    });

    it('root is at top', () => {
      const layout = computeTreeLayout(binaryTree, 'vertical');
      const root = layout.nodes.find(n => n.nodeId === 'N01')!;
      const child = layout.nodes.find(n => n.nodeId === 'N02')!;
      expect(root.y).toBeLessThan(child.y);
    });

    it('children are horizontally spread', () => {
      const layout = computeTreeLayout(naryTree, 'vertical');
      const children = layout.nodes.filter(n => n.nodeId !== 'N01');
      const xs = children.map(c => c.x);
      expect(xs[0]).toBeLessThan(xs[1]);
      expect(xs[1]).toBeLessThan(xs[2]);
    });

    it('edge labels match branch labels', () => {
      const layout = computeTreeLayout(naryTree, 'vertical');
      expect(layout.edges[0].label).toBe('A');
      expect(layout.edges[1].label).toBe('B');
      expect(layout.edges[2].label).toBe('C');
    });
  });

  describe('computeTreeLayout — horizontal', () => {
    it('root is at left', () => {
      const layout = computeTreeLayout(binaryTree, 'horizontal');
      const root = layout.nodes.find(n => n.nodeId === 'N01')!;
      const child = layout.nodes.find(n => n.nodeId === 'N02')!;
      expect(root.x).toBeLessThan(child.x);
    });
  });

  describe('edgeToPath', () => {
    it('returns valid SVG path for vertical', () => {
      const path = edgeToPath({ from: { x: 100, y: 50 }, to: { x: 200, y: 150 }, label: 'A', branchIndex: 0 }, 'vertical');
      expect(path).toContain('M 100 50');
      expect(path).toContain('C');
    });

    it('returns valid SVG path for horizontal', () => {
      const path = edgeToPath({ from: { x: 50, y: 100 }, to: { x: 150, y: 200 }, label: 'B', branchIndex: 1 }, 'horizontal');
      expect(path).toContain('M 50 100');
    });
  });

  describe('getBranchColor', () => {
    it('returns different colors for different indices', () => {
      expect(getBranchColor(0)).not.toBe(getBranchColor(1));
    });

    it('cycles for large indices', () => {
      expect(getBranchColor(0)).toBe(getBranchColor(8)); // 8 colors in palette
    });
  });
});
