import { useMemo, useCallback, useRef, useEffect } from 'react';
import { Reorder, useDragControls, AnimatePresence, motion } from 'framer-motion';
import type { RuleEnvelope, AnalyzeResponse } from '../../types';
import {
  parseDecisionTable,
  getOperatorDisplay,
  getBooleanDisplay,
  formatConditionValue,
  generateExampleInput,
  type RuleCondition,
  type RuleEntry,
  type RuleInput,
  type RuleOutput,
} from './types';
import { useDashboard, actions } from './DashboardContext';

// ════════════════════════════════════════════
// Spring configs
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 500, damping: 35 };
const SPRING_SOFT = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
export interface RuleTableProps {
  envelope: RuleEnvelope;
  analyze?: AnalyzeResponse;
}

// ════════════════════════════════════════════
// Drag Handle (6 dots)
// ════════════════════════════════════════════
function DragHandle({ controls }: { controls: ReturnType<typeof useDragControls> }) {
  return (
    <div
      onPointerDown={(e) => controls.start(e)}
      className="
        flex flex-col items-center justify-center gap-[3px] w-5 h-8
        cursor-grab active:cursor-grabbing
        dark:text-text-tertiary/40 dark:hover:text-text-secondary
        text-light-text-tertiary/40 hover:text-light-text-secondary
        transition-colors touch-none select-none
      "
    >
      <div className="flex gap-[3px]">
        <span className="w-[3px] h-[3px] rounded-full bg-current" />
        <span className="w-[3px] h-[3px] rounded-full bg-current" />
      </div>
      <div className="flex gap-[3px]">
        <span className="w-[3px] h-[3px] rounded-full bg-current" />
        <span className="w-[3px] h-[3px] rounded-full bg-current" />
      </div>
      <div className="flex gap-[3px]">
        <span className="w-[3px] h-[3px] rounded-full bg-current" />
        <span className="w-[3px] h-[3px] rounded-full bg-current" />
      </div>
    </div>
  );
}

// ════════════════════════════════════════════
// Condition Cell Renderer
// ════════════════════════════════════════════
function ConditionCell({ condition, input }: { condition: RuleCondition; input?: RuleInput }) {
  const opDisplay = getOperatorDisplay(condition.operator);
  const valueStr = formatConditionValue(condition);

  if (
    condition.operator === 'equals' &&
    input?.typeRef === 'BOOLEAN' &&
    typeof condition.value === 'boolean'
  ) {
    const boolDisplay = getBooleanDisplay(condition.value);
    return (
      <span className={`
        inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-medium
        ${boolDisplay.colorClass} ${boolDisplay.textClass}
      `}>
        {boolDisplay.label}
      </span>
    );
  }

  if (condition.operator === 'anything') {
    return (
      <span className={`
        inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono
        ${opDisplay.colorClass} ${opDisplay.textClass}
      `}>
        —
      </span>
    );
  }

  return (
    <span className="inline-flex items-center gap-1 flex-wrap">
      <span className={`
        inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-mono font-semibold uppercase shrink-0
        ${opDisplay.colorClass} ${opDisplay.textClass}
      `}>
        {opDisplay.label}
      </span>
      {valueStr && (
        <span className="text-[11px] font-mono dark:text-text-secondary text-light-text-secondary">
          {valueStr}
        </span>
      )}
    </span>
  );
}

// ════════════════════════════════════════════
// Result Cell Renderer
// ════════════════════════════════════════════
function ResultCell({ value }: { value: unknown }) {
  const display = String(value ?? '—');
  return (
    <span className="
      inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-medium
      bg-orange-500/12 text-orange-400 border border-orange-500/15
    ">
      {display}
    </span>
  );
}

// ════════════════════════════════════════════
// Sort Arrow
// ════════════════════════════════════════════
function SortArrow({ active, direction }: { active: boolean; direction: 'asc' | 'desc' }) {
  return (
    <motion.svg
      width="10"
      height="10"
      viewBox="0 0 10 10"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      animate={{ rotate: direction === 'desc' ? 180 : 0 }}
      transition={SPRING}
      className={`shrink-0 ${active ? 'text-accent' : 'dark:text-text-tertiary/30 text-light-text-tertiary/30'}`}
    >
      <path d="M5 2v6M3 4l2-2 2 2" />
    </motion.svg>
  );
}

// ════════════════════════════════════════════
// Column Header (sortable)
// ════════════════════════════════════════════
function SortableColumnHeader({
  name,
  typeRef,
  kind,
  sortField,
  sortDirection,
  onSort,
}: {
  name: string;
  typeRef: string;
  kind: 'input' | 'output';
  sortField: string | null;
  sortDirection: 'asc' | 'desc';
  onSort: (field: string) => void;
}) {
  const kindColor = kind === 'input'
    ? 'dark:text-blue-400/60 text-blue-600/60'
    : 'dark:text-orange-400/60 text-orange-600/60';
  const isActive = sortField === name;

  return (
    <th
      className="
        px-3 py-2.5 text-left cursor-pointer select-none
        dark:bg-surface-2 bg-light-surface-2
        dark:hover:bg-surface-3/50 hover:bg-light-surface-3/50
        transition-colors
      "
      onClick={() => onSort(name)}
    >
      <div className="space-y-0.5">
        <div className="flex items-center gap-1">
          <p className={`
            text-[11px] font-semibold uppercase tracking-wide
            ${isActive ? 'text-accent' : 'dark:text-text-secondary text-light-text-secondary'}
          `}>
            {name}
          </p>
          <SortArrow active={isActive} direction={isActive ? sortDirection : 'asc'} />
        </div>
        <p className={`text-[9px] font-mono ${kindColor}`}>
          {typeRef}
        </p>
      </div>
    </th>
  );
}

// ════════════════════════════════════════════
// HitPolicy Badge
// ════════════════════════════════════════════
function HitPolicyBadge({ policy }: { policy: string }) {
  const policyMap: Record<string, { letter: string; color: string }> = {
    FIRST:    { letter: 'F', color: 'bg-blue-500/15 text-blue-400 border-blue-500/20' },
    MULTI:    { letter: 'M', color: 'bg-violet-500/15 text-violet-400 border-violet-500/20' },
    COLLECT:  { letter: 'C', color: 'bg-teal-500/15 text-teal-400 border-teal-500/20' },
    PRIORITY: { letter: 'P', color: 'bg-amber-500/15 text-amber-400 border-amber-500/20' },
  };
  const info = policyMap[policy] ?? { letter: '?', color: 'bg-zinc-500/15 text-zinc-400 border-zinc-500/20' };

  return (
    <span className={`
      inline-flex items-center justify-center w-6 h-6 rounded-md
      text-[11px] font-mono font-bold border ${info.color}
    `} title={`Hit Policy: ${policy}`}>
      {info.letter}
    </span>
  );
}

// ════════════════════════════════════════════
// Expanded Detail Row
// ════════════════════════════════════════════
function ExpandedDetail({
  rule,
  inputs,
  conflictIds,
  colSpan,
}: {
  rule: RuleEntry;
  inputs: RuleInput[];
  conflictIds: string[];
  colSpan: number;
}) {
  const example = useMemo(
    () => generateExampleInput(rule, inputs),
    [rule, inputs],
  );

  return (
    <motion.tr
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: 'auto' }}
      exit={{ opacity: 0, height: 0 }}
      transition={SPRING_SOFT}
    >
      <td colSpan={colSpan} className="p-0">
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -8 }}
          transition={SPRING_SOFT}
          className="
            px-6 py-4
            dark:bg-surface-2/60 bg-light-surface-2/60
            border-b dark:border-border/50 border-light-border/50
          "
        >
          {/* v3.9.0: Rationale banner (only when explain API has been called) */}
          {rule.rationale && (
            <div className="
              mb-3 flex items-start gap-2 rounded-lg px-3 py-2
              bg-accent/8 border border-accent/20
            ">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"
                   className="shrink-0 mt-0.5 text-accent">
                <circle cx="6" cy="6" r="4.5" />
                <path d="M6 3.5v2.5M6 8v0.5" />
              </svg>
              <div className="flex-1 min-w-0">
                <p className="text-[9px] uppercase tracking-wide font-semibold mb-0.5 text-accent">
                  規則理由
                </p>
                <p className="text-[12px] leading-relaxed dark:text-text-secondary text-light-text-secondary">
                  {rule.rationale}
                </p>
              </div>
            </div>
          )}

          <div className="grid grid-cols-3 gap-4">
            {/* Column 1: Condition explanation */}
            <div className="space-y-2">
              <p className="text-[10px] font-semibold uppercase tracking-wide dark:text-text-tertiary text-light-text-tertiary">
                條件解說
              </p>
              <div className="space-y-1.5">
                {rule.conditions.map((cond) => {
                  const opInfo = getOperatorDisplay(cond.operator);
                  return (
                    <div
                      key={cond.field}
                      className="
                        flex items-start gap-2 text-[11px]
                        dark:text-text-secondary text-light-text-secondary
                      "
                    >
                      <span className="font-mono font-semibold shrink-0 dark:text-text-primary text-light-text-primary">
                        {cond.field}
                      </span>
                      <span className="dark:text-text-tertiary text-light-text-tertiary">
                        {opInfo.labelCN}
                      </span>
                      {cond.value !== undefined && cond.operator !== 'anything' &&
                       cond.operator !== 'isNull' && cond.operator !== 'isNotNull' && (
                        <span className="font-mono dark:text-accent text-accent">
                          {JSON.stringify(cond.value)}
                        </span>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Column 2: Example input */}
            <div className="space-y-2">
              <p className="text-[10px] font-semibold uppercase tracking-wide dark:text-text-tertiary text-light-text-tertiary">
                範例輸入
              </p>
              <pre className="
                text-[10px] font-mono leading-relaxed p-2.5 rounded-lg
                dark:bg-surface-3 dark:text-text-secondary
                bg-light-surface-3 text-light-text-secondary
                overflow-x-auto
              ">
                {JSON.stringify(example, null, 2)}
              </pre>
            </div>

            {/* Column 3: Conflict rules */}
            <div className="space-y-2">
              <p className="text-[10px] font-semibold uppercase tracking-wide dark:text-text-tertiary text-light-text-tertiary">
                衝突規則
              </p>
              {conflictIds.length > 0 ? (
                <div className="flex flex-wrap gap-1.5">
                  {conflictIds.map((id) => (
                    <span
                      key={id}
                      className="
                        inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-semibold
                        bg-orange-500/15 text-orange-400 border border-orange-500/20
                      "
                    >
                      {id}
                    </span>
                  ))}
                </div>
              ) : (
                <p className="
                  text-[11px] flex items-center gap-1.5
                  dark:text-emerald-400 text-emerald-600
                ">
                  <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M2.5 6.5l2 2 5-5" />
                  </svg>
                  無衝突
                </p>
              )}
            </div>
          </div>
        </motion.div>
      </td>
    </motion.tr>
  );
}

// ════════════════════════════════════════════
// Draggable Rule Row
// ════════════════════════════════════════════
function RuleRow({
  rule,
  rowIndex,
  inputs,
  outputs,
  isConflict,
  isHighlighted,
  highlightSource,
  conflictIds,
  isExpanded,
  onToggleExpand,
  onHighlight,
  onClearHighlight,
  registerRef,
  colSpan,
}: {
  rule: RuleEntry;
  rowIndex: number;
  inputs: RuleInput[];
  outputs: RuleOutput[];
  isConflict: boolean;
  isHighlighted: boolean;
  highlightSource: string | null;
  conflictIds: string[];
  isExpanded: boolean;
  onToggleExpand: () => void;
  onHighlight: () => void;
  onClearHighlight: () => void;
  registerRef: (id: string, el: HTMLElement | null) => void;
  colSpan: number;
}) {
  const dragControls = useDragControls();
  const rowRef = useRef<HTMLTableRowElement>(null);

  useEffect(() => {
    registerRef(`rule-${rule.ruleId}`, rowRef.current);
    return () => registerRef(`rule-${rule.ruleId}`, null);
  }, [rule.ruleId, registerRef]);

  const highlightColor = highlightSource === 'conflict'
    ? 'dark:bg-orange-500/8 bg-orange-500/5'
    : 'dark:bg-accent/8 bg-accent/5 ring-1 ring-inset ring-accent/20';

  return (
    <>
      <Reorder.Item
        as="tr"
        ref={rowRef}
        value={rule.ruleId}
        dragListener={false}
        dragControls={dragControls}
        layout
        transition={SPRING}
        whileDrag={{ opacity: 0.5, zIndex: 50 }}
        onMouseEnter={isConflict ? onHighlight : undefined}
        onMouseLeave={isConflict ? onClearHighlight : undefined}
        onClick={onToggleExpand}
        className={`
          group border-b last:border-b-0 cursor-pointer
          transition-colors duration-200 ease-out
          ${isHighlighted
            ? highlightColor
            : rowIndex % 2 === 0
              ? 'dark:bg-surface-1 bg-white'
              : 'dark:bg-surface-1/50 bg-light-surface-1/50'
          }
          ${!isHighlighted ? 'dark:hover:bg-surface-2/60 hover:bg-light-surface-2/60' : ''}
          dark:border-border/50 border-light-border/50
        `}
        style={{ position: 'relative' }}
      >
        {/* Drag handle + Rule ID */}
        <td className="px-2 py-2.5 whitespace-nowrap">
          <div className="flex items-center gap-1">
            <DragHandle controls={dragControls} />
            <div className="flex items-center gap-2">
              {/* Chevron */}
              <motion.svg
                width="12"
                height="12"
                viewBox="0 0 12 12"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
                animate={{ rotate: isExpanded ? 180 : 0 }}
                transition={SPRING}
                className="dark:text-text-tertiary text-light-text-tertiary shrink-0"
              >
                <path d="M3 4.5l3 3 3-3" />
              </motion.svg>

              <span className={`
                inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-semibold
                transition-colors duration-200
                ${isHighlighted
                  ? 'bg-accent/20 text-accent ring-1 ring-accent/30'
                  : isConflict
                    ? 'bg-orange-500/15 text-orange-400 ring-1 ring-orange-500/20'
                    : 'dark:bg-surface-3 dark:text-text-secondary bg-light-surface-3 text-light-text-secondary'
                }
              `}>
                {rule.ruleId}
              </span>
              <span className="text-[9px] font-mono tabular-nums dark:text-text-tertiary/50 text-light-text-tertiary/50">
                P{rule.priority}
              </span>
            </div>
          </div>
        </td>

        {/* Input cells */}
        {inputs.map((input) => {
          const condition = rule.conditions.find(c => c.field === input.name);
          return (
            <td key={`${rule.ruleId}-in-${input.name}`} className="px-3 py-2.5">
              {condition ? (
                <ConditionCell condition={condition} input={input} />
              ) : (
                <span className="text-[11px] dark:text-text-tertiary/40 text-light-text-tertiary/40">—</span>
              )}
            </td>
          );
        })}

        {/* Divider */}
        <td className="w-px px-0">
          <div className="w-px h-full dark:bg-border/30 bg-light-border/30" />
        </td>

        {/* Output cells */}
        {outputs.map((output) => {
          const result = rule.results.find(r => r.field === output.name);
          return (
            <td key={`${rule.ruleId}-out-${output.name}`} className="px-3 py-2.5">
              {result ? (
                <ResultCell value={result.value} />
              ) : (
                <span className="text-[11px] dark:text-text-tertiary/40 text-light-text-tertiary/40">—</span>
              )}
            </td>
          );
        })}
      </Reorder.Item>

      {/* Expanded detail row */}
      <AnimatePresence>
        {isExpanded && (
          <ExpandedDetail
            rule={rule}
            inputs={inputs}
            conflictIds={conflictIds}
            colSpan={colSpan}
          />
        )}
      </AnimatePresence>
    </>
  );
}

// ════════════════════════════════════════════
// Sort helper
// ════════════════════════════════════════════
function getSortValue(rule: RuleEntry, field: string): string | number {
  // Check conditions
  const cond = rule.conditions.find(c => c.field === field);
  if (cond) {
    if (cond.operator === 'anything') return '\uffff';
    if (cond.operator === 'between' && Array.isArray(cond.value)) return Number(cond.value[0]);
    if (typeof cond.value === 'boolean') return cond.value ? 1 : 0;
    if (typeof cond.value === 'number') return cond.value;
    if (typeof cond.value === 'string') return cond.value;
    if (Array.isArray(cond.value) && cond.value.length > 0) return String(cond.value[0]);
    return '\uffff';
  }

  // Check results
  const res = rule.results.find(r => r.field === field);
  if (res) {
    if (typeof res.value === 'number') return res.value;
    if (typeof res.value === 'boolean') return res.value ? 1 : 0;
    if (typeof res.value === 'string') return res.value;
    return '\uffff';
  }

  return '\uffff';
}

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
export default function RuleTable({ envelope, analyze }: RuleTableProps) {
  const { state, dispatch, registerRef } = useDashboard();
  const tableRef = useRef<HTMLDivElement>(null);

  const table = useMemo(
    () => parseDecisionTable(envelope.rule),
    [envelope.rule],
  );

  // Register table ref
  useEffect(() => {
    registerRef('rule-table', tableRef.current);
    return () => registerRef('rule-table', null);
  }, [registerRef]);

  // Build conflict map
  const conflictMap = useMemo(() => {
    const map = new Map<string, Set<string>>();
    if (!analyze?.overlaps) return map;
    for (const overlap of analyze.overlaps) {
      for (const id of overlap.ruleIds) {
        if (!map.has(id)) map.set(id, new Set());
        for (const otherId of overlap.ruleIds) {
          if (otherId !== id) {
            const set = map.get(id);
            if (set) set.add(otherId);
          }
        }
      }
    }
    return map;
  }, [analyze]);

  // Sort and reorder rules
  const sortedRuleIds = useMemo(() => {
    if (!table) return [];
    const ids = table.rules.map(r => r.ruleId);

    // Apply custom order from drag
    if (state.ruleOrder) {
      return state.ruleOrder.filter(id => ids.includes(id));
    }

    // Apply column sort
    if (state.sortState.field) {
      const field = state.sortState.field;
      const dir = state.sortState.direction === 'asc' ? 1 : -1;
      const sorted = [...ids].sort((aId, bId) => {
        const ruleA = table.rules.find(r => r.ruleId === aId);
        const ruleB = table.rules.find(r => r.ruleId === bId);
        if (!ruleA || !ruleB) return 0;
        const va = getSortValue(ruleA, field);
        const vb = getSortValue(ruleB, field);
        if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * dir;
        return String(va).localeCompare(String(vb)) * dir;
      });
      return sorted;
    }

    return ids;
  }, [table, state.ruleOrder, state.sortState]);

  const ruleMap = useMemo(() => {
    if (!table) return new Map<string, RuleEntry>();
    return new Map(table.rules.map(r => [r.ruleId, r]));
  }, [table]);

  // Handlers
  const handleSort = useCallback((field: string) => {
    if (state.sortState.field === field) {
      dispatch(actions.setSort(
        field,
        state.sortState.direction === 'asc' ? 'desc' : 'asc',
      ));
    } else {
      dispatch(actions.setSort(field, 'asc'));
    }
    dispatch(actions.setRuleOrder(null));
  }, [state.sortState, dispatch]);

  const handleReorder = useCallback((newOrder: string[]) => {
    dispatch(actions.setRuleOrder(newOrder));
    dispatch(actions.setSort(null, 'asc'));
  }, [dispatch]);

  const handleResetOrder = useCallback(() => {
    dispatch(actions.setRuleOrder(null));
    dispatch(actions.setSort(null, 'asc'));
  }, [dispatch]);

  const hasCustomOrder = state.ruleOrder !== null || state.sortState.field !== null;

  if (!table) {
    return (
      <div className="
        rounded-xl border p-8 text-center animate-fade-in-up
        dark:bg-surface-1 dark:border-border bg-white border-light-border
      ">
        <p className="text-sm dark:text-text-tertiary text-light-text-tertiary">
          無法解析決策表結構
        </p>
      </div>
    );
  }

  const { inputs, outputs, hitPolicy } = table;
  const colSpan = 1 + inputs.length + 1 + outputs.length;

  return (
    <div
      ref={tableRef}
      className="
        rounded-xl border overflow-hidden animate-fade-in-up
        dark:bg-surface-1 dark:border-border bg-white border-light-border
      "
    >
      {/* ── Table Header Bar ── */}
      <div className="
        flex items-center justify-between px-4 py-3
        border-b dark:border-border border-light-border
        dark:bg-surface-1 bg-white
      ">
        <div className="flex items-center gap-2.5">
          <HitPolicyBadge policy={hitPolicy} />
          <div>
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
              決策表
            </p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              {inputs.length} 個輸入 · {outputs.length} 個輸出 · {sortedRuleIds.length} 條規則
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* Conflict indicator */}
          {conflictMap.size > 0 && (
            <span className="
              inline-flex items-center gap-1.5 text-[10px] font-medium
              px-2 py-1 rounded-md
              bg-orange-500/10 text-orange-400 border border-orange-500/15
            ">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M6 1L11 10H1z" />
                <path d="M6 4.5v2" />
                <circle cx="6" cy="8.5" r="0.3" fill="currentColor" />
              </svg>
              {conflictMap.size} 組衝突
            </span>
          )}

          {/* Reset order button */}
          <AnimatePresence>
            {hasCustomOrder && (
              <motion.button
                initial={{ opacity: 0, scale: 0.8 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.8 }}
                transition={SPRING}
                onClick={handleResetOrder}
                className="
                  inline-flex items-center gap-1 text-[10px] font-medium
                  px-2 py-1 rounded-md cursor-pointer
                  dark:bg-surface-3 dark:text-text-secondary dark:hover:text-text-primary
                  bg-light-surface-3 text-light-text-secondary hover:text-light-text-primary
                  border dark:border-border border-light-border
                  transition-colors
                "
              >
                <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M1 1l8 8M9 1l-8 8" />
                </svg>
                重置排序
              </motion.button>
            )}
          </AnimatePresence>
        </div>
      </div>

      {/* ── Scrollable Table ── */}
      <div className="overflow-x-auto">
        <table className="w-full text-left border-collapse">
          {/* ── Column Headers ── */}
          <thead>
            <tr>
              {/* Rule ID column */}
              <th className="
                px-3 py-2.5 text-left sticky left-0 z-10
                dark:bg-surface-2 bg-light-surface-2
              ">
                <p className="text-[11px] font-semibold uppercase tracking-wide dark:text-text-secondary text-light-text-secondary">
                  Rule
                </p>
              </th>

              {/* Input columns */}
              {inputs.map((input: RuleInput) => (
                <SortableColumnHeader
                  key={`in-${input.name}`}
                  name={input.name}
                  typeRef={input.typeRef}
                  kind="input"
                  sortField={state.sortState.field}
                  sortDirection={state.sortState.direction}
                  onSort={handleSort}
                />
              ))}

              {/* Divider */}
              <th className="w-px px-0 dark:bg-surface-2 bg-light-surface-2">
                <div className="w-px h-8 mx-auto dark:bg-border/50 bg-light-border/50" />
              </th>

              {/* Output columns */}
              {outputs.map((output: RuleOutput) => (
                <SortableColumnHeader
                  key={`out-${output.name}`}
                  name={output.name}
                  typeRef={output.typeRef}
                  kind="output"
                  sortField={state.sortState.field}
                  sortDirection={state.sortState.direction}
                  onSort={handleSort}
                />
              ))}
            </tr>
          </thead>

          {/* ── Table Body ── */}
          <Reorder.Group
            as="tbody"
            axis="y"
            values={sortedRuleIds}
            onReorder={handleReorder}
          >
            {sortedRuleIds.map((ruleId, rowIndex) => {
              const rule = ruleMap.get(ruleId);
              if (!rule) return null;

              const isConflict = conflictMap.has(ruleId);
              const isHighlighted = state.highlightedRuleIds.includes(ruleId);
              const conflictIds = Array.from(conflictMap.get(ruleId) ?? []);

              return (
                <RuleRow
                  key={ruleId}
                  rule={rule}
                  rowIndex={rowIndex}
                  inputs={inputs}
                  outputs={outputs}
                  isConflict={isConflict}
                  isHighlighted={isHighlighted}
                  highlightSource={state.highlightSource}
                  conflictIds={conflictIds}
                  isExpanded={state.expandedRuleId === ruleId}
                  onToggleExpand={() => dispatch(actions.toggleExpandRule(ruleId))}
                  onHighlight={() =>
                    dispatch(actions.highlightRules([ruleId, ...conflictIds], 'conflict'))
                  }
                  onClearHighlight={() => dispatch(actions.clearHighlight())}
                  registerRef={registerRef}
                  colSpan={colSpan}
                />
              );
            })}
          </Reorder.Group>
        </table>
      </div>

      {/* ── Footer ── */}
      <div className="
        flex items-center justify-between px-4 py-2.5
        border-t dark:border-border border-light-border
        dark:bg-surface-2/50 bg-light-surface-2/50
      ">
        <div className="flex items-center gap-4">
          <span className="text-[11px] dark:text-text-tertiary text-light-text-tertiary">
            共 <span className="font-mono font-semibold dark:text-text-secondary text-light-text-secondary">{sortedRuleIds.length}</span> 條規則
          </span>
          <span className="text-[10px] font-mono dark:text-text-tertiary/60 text-light-text-tertiary/60">
            schema {envelope.schemaVersion}
          </span>
        </div>

        <div className="flex items-center gap-3">
          <span className="flex items-center gap-1 text-[10px] dark:text-text-tertiary/60 text-light-text-tertiary/60">
            <span className="w-2.5 h-2.5 rounded-sm bg-blue-500/20" /> 區間
          </span>
          <span className="flex items-center gap-1 text-[10px] dark:text-text-tertiary/60 text-light-text-tertiary/60">
            <span className="w-2.5 h-2.5 rounded-sm bg-emerald-500/20" /> 相等
          </span>
          <span className="flex items-center gap-1 text-[10px] dark:text-text-tertiary/60 text-light-text-tertiary/60">
            <span className="w-2.5 h-2.5 rounded-sm bg-orange-500/20" /> 輸出
          </span>
          <span className="flex items-center gap-1 text-[10px] dark:text-text-tertiary/60 text-light-text-tertiary/60">
            <span className="w-2.5 h-2.5 rounded-sm bg-zinc-500/15" /> 任意
          </span>
        </div>
      </div>
    </div>
  );
}
