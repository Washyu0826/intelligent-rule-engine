import { fieldLabel, humanizeValue } from '../../constants/fieldLabels';
import { getOperatorDisplay, type TreeNode } from './types';

export interface LeafPathStep {
  field?: string;
  operator?: string;
  value?: unknown;
  label?: string;
  negated?: boolean;
}

export function collectLeafPath(node: TreeNode | undefined, leafId: string): LeafPathStep[] | null {
  if (!node) return null;
  if (node.nodeId === leafId) return [];

  const children: Array<{ step: LeafPathStep; child?: TreeNode }> =
    node.branches && node.branches.length > 0
      ? node.branches.map((branch) => ({
          step: branch.condition
            ? {
                field: branch.condition.field,
                operator: branch.condition.operator,
                value: branch.condition.value,
                label: branch.label,
              }
            : { label: branch.label },
          child: branch.child,
        }))
      : [
          ...(node.trueBranch
            ? [{
                step: {
                  field: node.condition?.field,
                  operator: node.condition?.operator,
                  value: node.condition?.value,
                  label: 'TRUE',
                },
                child: node.trueBranch,
              }]
            : []),
          ...(node.falseBranch
            ? [{
                step: {
                  field: node.condition?.field,
                  operator: node.condition?.operator,
                  value: node.condition?.value,
                  label: 'FALSE',
                  negated: true,
                },
                child: node.falseBranch,
              }]
            : []),
        ];

  for (const { step, child } of children) {
    const subPath = collectLeafPath(child, leafId);
    if (subPath !== null) return [step, ...subPath];
  }
  return null;
}

export function formatPathStep(step: LeafPathStep): string {
  if (!step.field || !step.operator) return step.label ?? '';

  const value = step.value;
  const valueText = value == null
    ? ''
    : Array.isArray(value)
      ? value.join(' ~ ')
      : humanizeValue(String(value));
  const base = `${fieldLabel(step.field)} ${getOperatorDisplay(step.operator).labelCN}${valueText ? ` ${valueText}` : ''}`.trim();
  return step.negated ? `非（${base}）` : base;
}
