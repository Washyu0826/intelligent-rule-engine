import { describe, it, expect } from 'vitest';
import {
  parseDecisionTree,
  parseDecisionTable,
  isLeafNode,
  isBranchNode,
  countNodes,
  countLeaves,
  getTreeDepth,
  normalizeTreeNode,
  getOperatorDisplay,
  formatConditionValue,
  getErrorSeverity,
  generateExampleInput,
  type RuleEntry,
  type RuleInput,
  type TreeNode,
} from '../components/Dashboard/types';

// ════════════════════════════════════════════
// TreeNode helpers
// ════════════════════════════════════════════

describe('TreeNode helpers', () => {
  const leaf: TreeNode = {
    nodeId: 'N01',
    results: [{ field: 'decision', value: 'approve' }],
  };

  const binaryTree: TreeNode = {
    nodeId: 'N01',
    condition: { field: 'age', operator: 'greaterThan', value: 60 },
    trueBranch: { nodeId: 'N02', results: [{ field: 'decision', value: 'reject' }] },
    falseBranch: { nodeId: 'N03', results: [{ field: 'decision', value: 'approve' }] },
  };

  const naryTree: TreeNode = {
    nodeId: 'N01',
    branches: [
      { label: 'A', condition: { field: 'x', operator: 'equals', value: 'A' }, child: { nodeId: 'N02', results: [{ field: 'r', value: 1 }] } },
      { label: 'B', condition: { field: 'x', operator: 'equals', value: 'B' }, child: { nodeId: 'N03', results: [{ field: 'r', value: 2 }] } },
      { label: 'C', condition: { field: 'x', operator: 'equals', value: 'C' }, child: { nodeId: 'N04', results: [{ field: 'r', value: 3 }] } },
    ],
  };

  it('isLeafNode', () => {
    expect(isLeafNode(leaf)).toBe(true);
    expect(isLeafNode(binaryTree)).toBe(false);
    expect(isLeafNode(naryTree)).toBe(false);
  });

  it('isBranchNode', () => {
    expect(isBranchNode(leaf)).toBe(false);
    expect(isBranchNode(binaryTree)).toBe(true);
    expect(isBranchNode(naryTree)).toBe(true);
  });

  it('countNodes — binary', () => {
    expect(countNodes(binaryTree)).toBe(3);
  });

  it('countNodes — N-ary', () => {
    expect(countNodes(naryTree)).toBe(4); // root + 3 children
  });

  it('countLeaves — binary', () => {
    expect(countLeaves(binaryTree)).toBe(2);
  });

  it('countLeaves — N-ary', () => {
    expect(countLeaves(naryTree)).toBe(3);
  });

  it('getTreeDepth — binary', () => {
    expect(getTreeDepth(binaryTree)).toBe(2);
  });

  it('getTreeDepth — N-ary', () => {
    expect(getTreeDepth(naryTree)).toBe(2);
  });

  it('getTreeDepth — leaf', () => {
    expect(getTreeDepth(leaf)).toBe(1);
  });

  it('countNodes — undefined', () => {
    expect(countNodes(undefined)).toBe(0);
  });
});

// ════════════════════════════════════════════
// normalizeTreeNode
// ════════════════════════════════════════════

describe('normalizeTreeNode', () => {
  it('converts trueBranch/falseBranch to branches', () => {
    const tree: TreeNode = {
      nodeId: 'N01',
      condition: { field: 'age', operator: 'greaterThan', value: 60 },
      trueBranch: { nodeId: 'N02', results: [{ field: 'd', value: 'yes' }] },
      falseBranch: { nodeId: 'N03', results: [{ field: 'd', value: 'no' }] },
    };

    const normalized = normalizeTreeNode(tree);
    expect(normalized.branches).toBeDefined();
    expect(normalized.branches!.length).toBe(2);
    expect(normalized.branches![0].label).toBe('TRUE');
    expect(normalized.branches![1].label).toBe('FALSE');
    expect(normalized.trueBranch).toBeUndefined();
    expect(normalized.falseBranch).toBeUndefined();
  });

  it('leaves N-ary branches unchanged', () => {
    const tree: TreeNode = {
      nodeId: 'N01',
      branches: [
        { label: 'A', child: { nodeId: 'N02', results: [{ field: 'r', value: 1 }] } },
        { label: 'B', child: { nodeId: 'N03', results: [{ field: 'r', value: 2 }] } },
      ],
    };

    const normalized = normalizeTreeNode(tree);
    expect(normalized.branches!.length).toBe(2);
    expect(normalized.branches![0].label).toBe('A');
  });

  it('handles leaf node (no conversion needed)', () => {
    const leaf: TreeNode = { nodeId: 'N01', results: [{ field: 'd', value: 'x' }] };
    const normalized = normalizeTreeNode(leaf);
    expect(normalized.branches).toBeUndefined();
    expect(normalized.results!.length).toBe(1);
  });
});

// ════════════════════════════════════════════
// parseDecisionTree / parseDecisionTable
// ════════════════════════════════════════════

describe('parseDecisionTree', () => {
  it('parses valid tree', () => {
    const raw = { inputs: [{ name: 'a', typeRef: 'INTEGER' }], outputs: [], root: { nodeId: 'N01', results: [] } };
    expect(parseDecisionTree(raw)).not.toBeNull();
  });

  it('returns null for missing root', () => {
    expect(parseDecisionTree({ inputs: [] } as unknown as Record<string, unknown>)).toBeNull();
  });

  it('returns null for null', () => {
    expect(parseDecisionTree(null as unknown as Record<string, unknown>)).toBeNull();
  });
});

describe('parseDecisionTable', () => {
  it('parses valid table', () => {
    const raw = { hitPolicy: 'FIRST', inputs: [], outputs: [], rules: [] };
    expect(parseDecisionTable(raw)).not.toBeNull();
  });

  it('returns null for missing hitPolicy', () => {
    expect(parseDecisionTable({ inputs: [], rules: [] } as unknown as Record<string, unknown>)).toBeNull();
  });
});

// ════════════════════════════════════════════
// Operator display
// ════════════════════════════════════════════

describe('getOperatorDisplay', () => {
  it('returns correct label for equals', () => {
    expect(getOperatorDisplay('equals').label).toBe('=');
  });

  it('returns correct label for between', () => {
    expect(getOperatorDisplay('between').label).toBe('BETWEEN');
  });

  it('returns fallback for unknown operator', () => {
    expect(getOperatorDisplay('unknown').label).toBe('unknown');
  });
});

describe('formatConditionValue', () => {
  it('formats between', () => {
    expect(formatConditionValue({ field: 'x', operator: 'between', value: [1, 10] })).toBe('[1, 10]');
  });

  it('formats in', () => {
    expect(formatConditionValue({ field: 'x', operator: 'in', value: ['a', 'b'] })).toBe('{a, b}');
  });

  it('returns empty for anything', () => {
    expect(formatConditionValue({ field: 'x', operator: 'anything' })).toBe('');
  });
});

describe('generateExampleInput', () => {
  const rule = (condition: RuleEntry['conditions'][number]): RuleEntry => ({
    ruleId: 'R1',
    priority: 1,
    conditions: [condition],
    results: [],
  });
  const input = (typeRef: RuleInput['typeRef'], allowedValues?: string[]): RuleInput => ({
    name: 'field',
    typeRef,
    allowedValues,
  });

  it('generates a valid DATE between example', () => {
    const result = generateExampleInput(
      rule({ field: 'field', operator: 'between', value: ['2026-01-01', '2026-12-31'] }),
      [input('DATE')],
    );
    expect(result.field).toBe('2026-01-01');
  });

  it('generates a value that satisfies notEquals', () => {
    const result = generateExampleInput(
      rule({ field: 'field', operator: 'notEquals', value: false }),
      [input('BOOLEAN')],
    );
    expect(result.field).toBe(true);
  });

  it('generates an allowed value outside notIn', () => {
    const result = generateExampleInput(
      rule({ field: 'field', operator: 'notIn', value: ['A', 'B'] }),
      [input('ENUM', ['A', 'B', 'C'])],
    );
    expect(result.field).toBe('C');
  });
});

// ════════════════════════════════════════════
// Error severity
// ════════════════════════════════════════════

describe('getErrorSeverity', () => {
  it('MISSING_FIELD is critical', () => {
    expect(getErrorSeverity('MISSING_FIELD')).toBe('critical');
  });

  it('INCONSISTENT_TABLE is warning', () => {
    expect(getErrorSeverity('INCONSISTENT_TABLE')).toBe('warning');
  });

  it('TYPE_MISMATCH is info', () => {
    expect(getErrorSeverity('TYPE_MISMATCH')).toBe('info');
  });
});
