// ============================================================
// Dashboard Internal Types
// Precise typing for the `rule` field inside RuleEnvelope
// (which is Record<string, unknown> in the shared types.ts)
// ============================================================

/** 6 supported typeRef values */
export type TypeRef =
  | 'INTEGER'
  | 'DECIMAL'
  | 'BOOLEAN'
  | 'STRING'
  | 'ENUM'
  | 'DATE';

/** 12 supported operators */
export type Operator =
  | 'equals'
  | 'notEquals'
  | 'greaterThan'
  | 'greaterThanOrEqual'
  | 'lessThan'
  | 'lessThanOrEqual'
  | 'between'
  | 'in'
  | 'notIn'
  | 'isNull'
  | 'isNotNull'
  | 'anything';

/** Hit policies */
export type HitPolicy = 'FIRST' | 'MULTI' | 'COLLECT' | 'PRIORITY';

/** An input column definition */
export interface RuleInput {
  name: string;
  typeRef: TypeRef;
  allowedValues?: string[];
  /** v3.12: 集團規則引擎內部欄位代碼（adapter mapping 用） */
  fieldCode?: string;
  /** v3.12: 單位（TWD / USD / YEAR / DAY 等） */
  unit?: string;
  /** v3.12: DECIMAL 精度位數 */
  scale?: number;
  /** v3.12: 是否允許 null */
  nullable?: boolean;
  /** v3.12: ENUM 值對外部代碼的對照（key 為 allowedValues 之值） */
  externalCodes?: Record<string, string>;
}

/** An output column definition */
export interface RuleOutput {
  name: string;
  typeRef: TypeRef;
  allowedValues?: string[];
  fieldCode?: string;
  unit?: string;
  scale?: number;
  nullable?: boolean;
  externalCodes?: Record<string, string>;
}

/** A single condition within a rule */
export interface RuleCondition {
  field: string;
  operator: Operator;
  value?: unknown; // shape varies by operator
  /**
   * v3.12: 跨欄位 / 相對日期參照（與 value 互斥）
   * - "<fieldName>" 引用同 inputs 內另一欄位
   * - "$today" 當天日期
   * - "$today+1d" / "$today-30d" / "$today+1m" 相對偏移
   */
  valueRef?: string;
}

/** A single result within a rule */
export interface RuleResult {
  field: string;
  value: unknown;
}

/** A single rule row in the decision table */
export interface RuleEntry {
  ruleId: string;
  priority: number;
  conditions: RuleCondition[];
  results: RuleResult[];
  /** v3.9.0: 本條規則的中文業務解釋（選配，透過 POST /tools/explain 產生） */
  rationale?: string;
}

/** The complete rule structure inside RuleEnvelope.rule */
export interface DecisionTableRule {
  hitPolicy: HitPolicy;
  inputs: RuleInput[];
  outputs: RuleOutput[];
  rules: RuleEntry[];
}

// ============================================================
// DecisionTree Types (Phase 3 + N-ary)
// ============================================================

/** N-ary branch entry (label + condition + child) */
export interface Branch {
  label: string;
  condition?: RuleCondition;
  child: TreeNode;
}

/** A tree node — either branch (condition + children) or leaf (results) */
export interface TreeNode {
  nodeId?: string;
  condition?: RuleCondition;
  trueBranch?: TreeNode;   // legacy binary (backward compat)
  falseBranch?: TreeNode;  // legacy binary (backward compat)
  branches?: Branch[];     // N-ary branches (canonical format)
  results?: RuleResult[];
}

/** The complete DecisionTree rule structure */
export interface DecisionTreeRule {
  inputs: RuleInput[];
  outputs: RuleOutput[];
  root: TreeNode;
}

/** Helper: safely cast Record<string, unknown> → DecisionTreeRule */
export function parseDecisionTree(
  raw: Record<string, unknown>
): DecisionTreeRule | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (!r.root || !Array.isArray(r.inputs)) return null;
  return raw as unknown as DecisionTreeRule;
}

/**
 * Normalize a tree node: convert legacy trueBranch/falseBranch to branches format.
 * Modifies the node in place and returns it.
 */
export function normalizeTreeNode(node: TreeNode): TreeNode {
  if (!node) return node;

  // Already has branches → just recurse
  if (node.branches && node.branches.length > 0) {
    for (const branch of node.branches) {
      if (branch.child) normalizeTreeNode(branch.child);
    }
    return node;
  }

  // Convert legacy binary to branches
  if (node.trueBranch || node.falseBranch) {
    const branches: Branch[] = [];
    if (node.trueBranch) {
      branches.push({
        label: 'TRUE',
        condition: node.condition,
        child: node.trueBranch,
      });
    }
    if (node.falseBranch) {
      branches.push({
        label: 'FALSE',
        condition: node.condition
          ? { field: node.condition.field, operator: node.condition.operator, value: node.condition.value }
          : undefined,
        child: node.falseBranch,
      });
    }
    node.branches = branches;
    node.trueBranch = undefined;
    node.falseBranch = undefined;

    // Recurse
    for (const branch of branches) {
      if (branch.child) normalizeTreeNode(branch.child);
    }
  }

  return node;
}

/** Get all child nodes (from branches or legacy fields) */
function getChildren(node: TreeNode): TreeNode[] {
  if (node.branches && node.branches.length > 0) {
    return node.branches.map(b => b.child).filter(Boolean) as TreeNode[];
  }
  const children: TreeNode[] = [];
  if (node.trueBranch) children.push(node.trueBranch);
  if (node.falseBranch) children.push(node.falseBranch);
  return children;
}

/** Check if a tree node is a leaf (has results, no condition, no branches) */
export function isLeafNode(node: TreeNode): boolean {
  return !node.condition
    && (!node.branches || node.branches.length === 0)
    && !node.trueBranch && !node.falseBranch
    && !!node.results && node.results.length > 0;
}

/** Check if a tree node is a branch (has condition or branches) */
export function isBranchNode(node: TreeNode): boolean {
  return !!node.condition
    || (!!node.branches && node.branches.length > 0)
    || !!node.trueBranch || !!node.falseBranch;
}

/** Count total nodes in a tree */
export function countNodes(node: TreeNode | undefined): number {
  if (!node) return 0;
  return 1 + getChildren(node).reduce((sum, child) => sum + countNodes(child), 0);
}

/** Count leaf nodes */
export function countLeaves(node: TreeNode | undefined): number {
  if (!node) return 0;
  if (isLeafNode(node)) return 1;
  return getChildren(node).reduce((sum, child) => sum + countLeaves(child), 0);
}

/** Get max depth of tree */
export function getTreeDepth(node: TreeNode | undefined): number {
  if (!node) return 0;
  const childDepths = getChildren(node).map(child => getTreeDepth(child));
  return 1 + (childDepths.length > 0 ? Math.max(...childDepths) : 0);
}

// ============================================================
// Helper: safely cast Record<string, unknown> → DecisionTableRule
// ============================================================
export function parseDecisionTable(
  raw: Record<string, unknown>
): DecisionTableRule | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (!r.hitPolicy || !Array.isArray(r.inputs) || !Array.isArray(r.rules)) {
    return null;
  }
  return raw as unknown as DecisionTableRule;
}

// ============================================================
// Operator display metadata
// ============================================================
export interface OperatorDisplayInfo {
  label: string;
  labelCN: string;    // Chinese description
  colorClass: string; // Tailwind bg class
  textClass: string;  // Tailwind text class
}

const operatorMap: Record<string, OperatorDisplayInfo> = {
  between:            { label: 'BETWEEN',  labelCN: '介於…之間（含兩端）', colorClass: 'bg-blue-500/15',    textClass: 'text-blue-400' },
  equals:             { label: '=',        labelCN: '等於',               colorClass: 'bg-emerald-500/15', textClass: 'text-emerald-400' },
  notEquals:          { label: '≠',        labelCN: '不等於',             colorClass: 'bg-rose-500/15',    textClass: 'text-rose-400' },
  greaterThan:        { label: '>',        labelCN: '大於',               colorClass: 'bg-amber-500/15',   textClass: 'text-amber-400' },
  greaterThanOrEqual: { label: '≥',        labelCN: '大於或等於',         colorClass: 'bg-amber-500/15',   textClass: 'text-amber-400' },
  lessThan:           { label: '<',        labelCN: '小於',               colorClass: 'bg-amber-500/15',   textClass: 'text-amber-400' },
  lessThanOrEqual:    { label: '≤',        labelCN: '小於或等於',         colorClass: 'bg-amber-500/15',   textClass: 'text-amber-400' },
  in:                 { label: 'IN',       labelCN: '包含於清單中',       colorClass: 'bg-violet-500/15',  textClass: 'text-violet-400' },
  notIn:              { label: 'NOT IN',   labelCN: '不包含於清單中',     colorClass: 'bg-violet-500/15',  textClass: 'text-violet-400' },
  isNull:             { label: 'NULL',     labelCN: '為空值',             colorClass: 'bg-zinc-500/15',    textClass: 'text-zinc-400' },
  isNotNull:          { label: 'NOT NULL', labelCN: '非空值',             colorClass: 'bg-zinc-500/15',    textClass: 'text-zinc-400' },
  anything:           { label: '—',        labelCN: '任意值（不限制）',   colorClass: 'bg-zinc-500/10',    textClass: 'text-zinc-500' },
};

export function getOperatorDisplay(op: string): OperatorDisplayInfo {
  return operatorMap[op] ?? { label: op, labelCN: op, colorClass: 'bg-zinc-500/15', textClass: 'text-zinc-400' };
}

// ============================================================
// Boolean value display
// ============================================================
export function getBooleanDisplay(val: unknown): { label: string; colorClass: string; textClass: string } {
  if (val === true)  return { label: 'true',  colorClass: 'bg-emerald-500/15', textClass: 'text-emerald-400' };
  if (val === false) return { label: 'false', colorClass: 'bg-rose-500/15',    textClass: 'text-rose-400' };
  return { label: String(val), colorClass: 'bg-zinc-500/15', textClass: 'text-zinc-400' };
}

// ============================================================
// Format condition value for display
// ============================================================
export function formatConditionValue(condition: RuleCondition): string {
  const { operator, value } = condition;

  if (operator === 'anything' || operator === 'isNull' || operator === 'isNotNull') {
    return '';
  }

  if (operator === 'between' && Array.isArray(value)) {
    return `[${value[0]}, ${value[1]}]`;
  }

  if ((operator === 'in' || operator === 'notIn') && Array.isArray(value)) {
    return `{${value.join(', ')}}`;
  }

  if (typeof value === 'boolean') {
    return value ? 'true' : 'false';
  }

  return String(value ?? '');
}

// ============================================================
// Error fix hints
// ============================================================
export const ERROR_FIX_HINTS: Record<string, string> = {
  MISSING_FIELD:
    '請確認 RuleEnvelope 包含所有必要欄位：ruleType、evaluation、rule（含 hitPolicy、inputs、outputs、rules）',
  UNKNOWN_OPERATOR:
    '請使用支援的 operator：equals、notEquals、greaterThan、greaterThanOrEqual、lessThan、lessThanOrEqual、between、in、notIn、isNull、isNotNull、anything',
  TYPE_MISMATCH:
    '請確認 value 的型別與欄位的 typeRef 一致。例如 BOOLEAN 只接受 true/false、INTEGER 不能有小數點',
  UNKNOWN_FIELD:
    '請確認 condition/result 中的 field 名稱已在 inputs/outputs 中定義',
  DUPLICATE_ID:
    '請確保每條規則的 ruleId 唯一，不可重複',
  ENUM_VALUE_MISSING:
    '當 typeRef 為 ENUM 時，必須提供 allowedValues 陣列',
  INVALID_ENUM_VALUE:
    '請確認值在 allowedValues 清單內',
  INCONSISTENT_TABLE:
    '在 FIRST hitPolicy 下，存在條件重疊的規則。請調整條件範圍或改用 MULTI 策略',
  INVALID_MULTI:
    'MULTI hitPolicy 至少需要 2 條以上規則才有意義',
  MISSING_BRANCH:
    'DecisionTree 缺少分支覆蓋，請確保所有路徑都有對應分支',
  MISSING_RESULTS:
    '規則的 results 數量必須與 outputs 定義的欄位數量一致',
  REDUNDANT_RULE:
    '兩條規則條件重疊且結果相同（冗餘，非衝突）。可考慮合併以簡化規則表，不影響驗證通過',
};

// ============================================================
// Error severity
// ============================================================
export type ErrorSeverity = 'critical' | 'warning' | 'info';

const CRITICAL_CODES = new Set(['MISSING_FIELD', 'MISSING_RESULTS', 'MISSING_BRANCH']);
const WARNING_CODES = new Set(['INCONSISTENT_TABLE', 'DUPLICATE_ID', 'INVALID_MULTI']);

export function getErrorSeverity(code: string): ErrorSeverity {
  if (CRITICAL_CODES.has(code)) return 'critical';
  if (WARNING_CODES.has(code)) return 'warning';
  return 'info';
}

export const SEVERITY_ORDER: Record<ErrorSeverity, number> = {
  critical: 0,
  warning: 1,
  info: 2,
};

// ============================================================
// Generate example input for a given rule
// ============================================================
function defaultForTypeRef(typeRef: TypeRef, allowedValues?: string[]): unknown {
  switch (typeRef) {
    case 'INTEGER':
      return 0;
    case 'DECIMAL':
      return 0.0;
    case 'BOOLEAN':
      return true;
    case 'STRING':
      return 'example';
    case 'DATE':
      return '2025-01-01';
    case 'ENUM':
      return allowedValues?.[0] ?? 'unknown';
    default:
      return null;
  }
}

function shiftIsoDate(value: unknown, days: number): string | null {
  if (typeof value !== 'string') return null;
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return null;
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function valuesEqual(a: unknown, b: unknown): boolean {
  if (typeof a === 'number' && typeof b === 'number') return a === b;
  return String(a) === String(b);
}

function valueOutside(excludedValue: unknown, input: RuleInput): unknown {
  const excluded = Array.isArray(excludedValue) ? excludedValue : [excludedValue];
  const allowed = input.allowedValues?.find((candidate) =>
    !excluded.some((value) => valuesEqual(candidate, value)));
  if (allowed !== undefined) return allowed;

  if (input.typeRef === 'BOOLEAN') {
    return !excluded[0];
  }
  if (input.typeRef === 'INTEGER' || input.typeRef === 'DECIMAL') {
    let candidate = 0;
    while (excluded.some((value) => Number(value) === candidate)) candidate += 1;
    return candidate;
  }
  if (input.typeRef === 'DATE') {
    let candidate = shiftIsoDate(excluded[0], 1) ?? '2025-01-01';
    while (excluded.some((value) => valuesEqual(candidate, value))) {
      candidate = shiftIsoDate(candidate, 1) ?? candidate;
    }
    return candidate;
  }
  return `not-${String(excluded[0] ?? 'value')}`;
}

export function generateExampleInput(
  rule: RuleEntry,
  inputs: RuleInput[],
): Record<string, unknown> {
  const example: Record<string, unknown> = {};

  for (const input of inputs) {
    const cond = rule.conditions.find(c => c.field === input.name);

    if (!cond) {
      example[input.name] = defaultForTypeRef(input.typeRef, input.allowedValues);
      continue;
    }

    switch (cond.operator) {
      case 'equals':
        example[input.name] = cond.value;
        break;

      case 'between': {
        if (Array.isArray(cond.value) && cond.value.length >= 2) {
          if (input.typeRef === 'DATE') {
            example[input.name] = cond.value[0];
          } else {
            const min = Number(cond.value[0]);
            const max = Number(cond.value[1]);
            const mid = (min + max) / 2;
            example[input.name] = input.typeRef === 'INTEGER' ? Math.floor(mid) : mid;
          }
        } else {
          example[input.name] = cond.value;
        }
        break;
      }

      case 'in':
        example[input.name] = Array.isArray(cond.value) ? cond.value[0] : cond.value;
        break;

      case 'notIn':
        example[input.name] = valueOutside(cond.value, input);
        break;

      case 'greaterThan': {
        const shifted = input.typeRef === 'DATE' ? shiftIsoDate(cond.value, 1) : null;
        const v = Number(cond.value);
        example[input.name] = shifted ?? (isNaN(v) ? cond.value : v + 1);
        break;
      }

      case 'greaterThanOrEqual':
        example[input.name] = cond.value;
        break;

      case 'lessThan': {
        const shifted = input.typeRef === 'DATE' ? shiftIsoDate(cond.value, -1) : null;
        const v = Number(cond.value);
        example[input.name] = shifted ?? (isNaN(v) ? cond.value : v - 1);
        break;
      }

      case 'lessThanOrEqual':
        example[input.name] = cond.value;
        break;

      case 'isNull':
        example[input.name] = null;
        break;

      case 'isNotNull':
        example[input.name] = defaultForTypeRef(input.typeRef, input.allowedValues);
        break;

      case 'anything':
        example[input.name] = defaultForTypeRef(input.typeRef, input.allowedValues);
        break;

      case 'notEquals':
        example[input.name] = valueOutside(cond.value, input);
        break;

      default:
        example[input.name] = cond.value ?? defaultForTypeRef(input.typeRef, input.allowedValues);
        break;
    }
  }

  return example;
}
