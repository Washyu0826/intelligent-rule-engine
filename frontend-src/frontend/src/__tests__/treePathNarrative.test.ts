import { describe, expect, it } from 'vitest';
import type { TreeNode } from '../components/Dashboard/types';
import {
  collectLeafPath,
  formatPathStep,
} from '../components/Dashboard/treePathNarrative';

describe('tree path narrative', () => {
  it('marks a legacy falseBranch as negated', () => {
    const tree: TreeNode = {
      nodeId: 'N1',
      condition: { field: 'smoker', operator: 'equals', value: true },
      trueBranch: { nodeId: 'N2', results: [{ field: 'decision', value: 'surcharge' }] },
      falseBranch: { nodeId: 'N3', results: [{ field: 'decision', value: 'standard' }] },
    };

    const path = collectLeafPath(tree, 'N3');
    expect(path).toHaveLength(1);
    expect(path?.[0].negated).toBe(true);
    expect(formatPathStep(path![0])).toContain('非（');
  });

  it('uses the N-ary fallback label instead of the parent condition', () => {
    const tree: TreeNode = {
      nodeId: 'N1',
      condition: { field: 'age', operator: 'greaterThan', value: 60 },
      branches: [
        {
          label: '高齡',
          condition: { field: 'age', operator: 'greaterThan', value: 60 },
          child: { nodeId: 'N2', results: [{ field: 'decision', value: 'review' }] },
        },
        {
          label: '其他',
          child: { nodeId: 'N3', results: [{ field: 'decision', value: 'accept' }] },
        },
      ],
    };

    const path = collectLeafPath(tree, 'N3');
    expect(path).toEqual([{ label: '其他' }]);
    expect(formatPathStep(path![0])).toBe('其他');
  });
});
