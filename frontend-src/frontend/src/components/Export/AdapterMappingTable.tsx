import { useState, useMemo } from 'react';
import type { RuleEnvelope, RuleInput, RuleCondition, Operator } from '../../types';

/**
 * v3.12 — Adapter 對照表
 *
 * 給集團技術接入方看的「契約頁」：把 RuleEnvelope 拆成
 *   - 欄位 → 集團代碼（fieldCode / externalCodes）
 *   - 使用到的 operator → 集團規則引擎是否原生支援 / 需 adapter 編排
 *   - valueRef 條件清單（跨欄位 / 相對日期，必須由 adapter 在執行期解析）
 *
 * 重點不是花俏視覺，是讓 reviewer 一眼看到「engine-neutral 不是宣稱，
 * 我有列出 adapter 真正要處理的欄位與動作」。
 */

interface Props {
  envelope: RuleEnvelope;
}

interface OperatorRow {
  operator: Operator | string;
  count: number;
  status: 'native' | 'adapter';
  note: string;
}

interface ValueRefRow {
  ruleId: string;
  field: string;
  operator: string;
  valueRef: string;
  resolution: string;
}

const OPERATOR_NATIVE_SUPPORT: Record<string, { status: 'native' | 'adapter'; note: string }> = {
  equals:              { status: 'native',  note: '集團引擎原生 = 比對' },
  notEquals:           { status: 'native',  note: '集團引擎原生 != 比對' },
  greaterThan:         { status: 'native',  note: '集團引擎原生 > 比對' },
  greaterThanOrEqual:  { status: 'native',  note: '集團引擎原生 >= 比對' },
  lessThan:            { status: 'native',  note: '集團引擎原生 < 比對' },
  lessThanOrEqual:     { status: 'native',  note: '集團引擎原生 <= 比對' },
  between:             { status: 'adapter', note: '需 adapter 拆成 >= min AND <= max（多數引擎不支援 between 字面 token）' },
  in:                  { status: 'adapter', note: '需 adapter 轉為 enumeration / OR 串接' },
  notIn:               { status: 'adapter', note: '需 adapter 轉為 NOT IN / NAND 串接' },
  isNull:              { status: 'native',  note: 'IS NULL（部分引擎需轉成 default 值比對）' },
  isNotNull:           { status: 'native',  note: 'IS NOT NULL' },
  anything:            { status: 'adapter', note: '不轉 — 視為 wildcard，adapter 可省略此 condition' },
};

function collectConditions(envelope: RuleEnvelope): Array<{ ruleId: string; condition: RuleCondition }> {
  const result: Array<{ ruleId: string; condition: RuleCondition }> = [];
  const rule = envelope.rule as Record<string, unknown> | undefined;
  if (!rule) return result;

  const tableRules = rule.rules as Array<Record<string, unknown>> | undefined;
  if (Array.isArray(tableRules)) {
    for (const r of tableRules) {
      const ruleId = String(r.ruleId ?? '');
      const conds = r.conditions as RuleCondition[] | undefined;
      if (Array.isArray(conds)) {
        for (const c of conds) result.push({ ruleId, condition: c });
      }
    }
  }

  const walkTree = (node: Record<string, unknown> | undefined, parentId: string): void => {
    if (!node) return;
    const nodeId = String(node.nodeId ?? parentId);
    const cond = node.condition as RuleCondition | undefined;
    if (cond) result.push({ ruleId: nodeId, condition: cond });
    const branches = node.branches as Array<Record<string, unknown>> | undefined;
    if (Array.isArray(branches)) {
      for (const b of branches) {
        const bCond = b.condition as RuleCondition | undefined;
        if (bCond) result.push({ ruleId: `${nodeId}/${b.label ?? '?'}`, condition: bCond });
        walkTree(b.child as Record<string, unknown> | undefined, nodeId);
      }
    }
    walkTree(node.trueBranch as Record<string, unknown> | undefined, nodeId);
    walkTree(node.falseBranch as Record<string, unknown> | undefined, nodeId);
  };
  walkTree(rule.root as Record<string, unknown> | undefined, 'N00');

  return result;
}

function describeValueRef(ref: string): string {
  if (ref === '$today') return 'adapter 執行期讀系統日期';
  if (/^\$today\s*[+-]\s*\d+\s*[dmy]$/i.test(ref))
    return `adapter 執行期計算 ${ref}（系統日期偏移）`;
  return `adapter 執行期讀同筆 input.${ref}`;
}

export default function AdapterMappingTable({ envelope }: Props) {
  const [open, setOpen] = useState(false);

  const inputs = useMemo<RuleInput[]>(() => {
    const rule = envelope.rule as Record<string, unknown> | undefined;
    return (rule?.inputs as RuleInput[]) ?? [];
  }, [envelope]);

  const allConditions = useMemo(() => collectConditions(envelope), [envelope]);

  const operatorRows = useMemo<OperatorRow[]>(() => {
    const counts = new Map<string, number>();
    for (const { condition } of allConditions) {
      counts.set(condition.operator, (counts.get(condition.operator) ?? 0) + 1);
    }
    return Array.from(counts.entries())
      .map(([op, count]) => {
        const meta = OPERATOR_NATIVE_SUPPORT[op] ?? {
          status: 'adapter' as const,
          note: `非標準 operator，需 adapter 自行對應`,
        };
        return { operator: op, count, ...meta };
      })
      .sort((a, b) => b.count - a.count);
  }, [allConditions]);

  const valueRefRows = useMemo<ValueRefRow[]>(() => {
    return allConditions
      .filter(({ condition }) => condition.valueRef)
      .map(({ ruleId, condition }) => ({
        ruleId,
        field: condition.field,
        operator: condition.operator,
        valueRef: condition.valueRef!,
        resolution: describeValueRef(condition.valueRef!),
      }));
  }, [allConditions]);

  const fieldGaps = useMemo(() => {
    return inputs
      .map((field) => {
        const issues: string[] = [];
        if (!field.fieldCode) issues.push('缺 fieldCode');
        if (field.typeRef === 'ENUM' && (!field.externalCodes || Object.keys(field.externalCodes).length === 0)) {
          issues.push('缺 externalCodes');
        }
        if (field.typeRef === 'DECIMAL' && field.scale == null) issues.push('缺 scale');
        return { field, issues };
      });
  }, [inputs]);

  const gapCount = fieldGaps.reduce((sum, e) => sum + e.issues.length, 0);
  const adapterOpCount = operatorRows.filter((r) => r.status === 'adapter').length;

  return (
    <div className="
      rounded-xl border overflow-hidden
      dark:bg-surface-1 dark:border-border bg-white border-light-border
    ">
      <button
        onClick={() => setOpen((v) => !v)}
        className="
          w-full flex items-center justify-between px-4 py-3 text-left
          hover:dark:bg-surface-2 hover:bg-light-surface-2 transition-colors
        "
      >
        <div className="flex items-center gap-2.5">
          <span className="
            w-7 h-7 rounded-md flex items-center justify-center
            dark:bg-surface-3 bg-light-surface-3
            dark:text-text-tertiary text-light-text-tertiary
          ">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
              <path d="M2 4l5-3 5 3" />
              <path d="M2 4v6l5 3 5-3V4" />
              <path d="M7 1v12" />
            </svg>
          </span>
          <div>
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
              Adapter 對照表
            </p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              {inputs.length} 個欄位 · {operatorRows.length} 個 operator
              {valueRefRows.length > 0 && ` · ${valueRefRows.length} 個 valueRef`}
              {gapCount > 0 && (
                <span className="text-amber-500"> · {gapCount} 處待補</span>
              )}
              {adapterOpCount > 0 && (
                <span className="text-amber-500"> · {adapterOpCount} 個需編排 operator</span>
              )}
            </p>
          </div>
        </div>
        <svg
          width="14" height="14" viewBox="0 0 14 14" fill="none"
          stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"
          className="dark:text-text-tertiary text-light-text-tertiary"
          style={{ transform: open ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.15s ease' }}
        >
          <path d="M3 5l4 4 4-4" />
        </svg>
      </button>

      {open && (
        <div className="border-t dark:border-border/50 border-light-border/50">
          {/* 欄位對照 */}
          <section className="px-4 py-3 border-b dark:border-border/30 border-light-border/30">
            <p className="text-[11px] font-semibold mb-2 dark:text-text-primary text-light-text-primary">
              欄位 → 集團代碼
            </p>
            <div className="overflow-x-auto">
              <table className="w-full text-[10px]">
                <thead>
                  <tr className="dark:text-text-tertiary text-light-text-tertiary">
                    <th className="text-left font-semibold px-2 py-1">業務欄位</th>
                    <th className="text-left font-semibold px-2 py-1">typeRef</th>
                    <th className="text-left font-semibold px-2 py-1">fieldCode</th>
                    <th className="text-left font-semibold px-2 py-1">單位/精度</th>
                    <th className="text-left font-semibold px-2 py-1">外部代碼對照</th>
                    <th className="text-left font-semibold px-2 py-1">缺料</th>
                  </tr>
                </thead>
                <tbody>
                  {fieldGaps.map(({ field, issues }) => (
                    <tr key={field.name} className="border-t dark:border-border/30 border-light-border/30">
                      <td className="font-mono px-2 py-1 dark:text-text-primary text-light-text-primary">
                        {field.name}
                      </td>
                      <td className="font-mono px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                        {field.typeRef}
                      </td>
                      <td className="font-mono px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                        {field.fieldCode ?? <span className="text-amber-500">&lt;TODO&gt;</span>}
                      </td>
                      <td className="font-mono px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                        {field.unit ?? '-'}{field.scale != null ? ` (${field.scale})` : ''}
                      </td>
                      <td className="font-mono px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                        {field.externalCodes && Object.keys(field.externalCodes).length > 0
                          ? Object.entries(field.externalCodes)
                              .map(([k, v]) => `${k}→${v}`).join(', ')
                          : '-'}
                      </td>
                      <td className="px-2 py-1">
                        {issues.length === 0
                          ? <span className="text-emerald-500">完整</span>
                          : <span className="text-amber-500">{issues.join('、')}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          {/* Operator 支援 */}
          <section className="px-4 py-3 border-b dark:border-border/30 border-light-border/30">
            <p className="text-[11px] font-semibold mb-2 dark:text-text-primary text-light-text-primary">
              Operator → 集團引擎支援
            </p>
            <div className="overflow-x-auto">
              <table className="w-full text-[10px]">
                <thead>
                  <tr className="dark:text-text-tertiary text-light-text-tertiary">
                    <th className="text-left font-semibold px-2 py-1">operator</th>
                    <th className="text-left font-semibold px-2 py-1">使用次數</th>
                    <th className="text-left font-semibold px-2 py-1">支援</th>
                    <th className="text-left font-semibold px-2 py-1">備註</th>
                  </tr>
                </thead>
                <tbody>
                  {operatorRows.map((row) => (
                    <tr key={String(row.operator)} className="border-t dark:border-border/30 border-light-border/30">
                      <td className="font-mono px-2 py-1 dark:text-text-primary text-light-text-primary">
                        {row.operator}
                      </td>
                      <td className="font-mono px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                        {row.count}
                      </td>
                      <td className="px-2 py-1">
                        {row.status === 'native'
                          ? <span className="px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-500 border border-emerald-500/20">原生</span>
                          : <span className="px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-500 border border-amber-500/20">需編排</span>}
                      </td>
                      <td className="px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                        {row.note}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          {/* valueRef */}
          {valueRefRows.length > 0 && (
            <section className="px-4 py-3">
              <p className="text-[11px] font-semibold mb-2 dark:text-text-primary text-light-text-primary">
                valueRef 條件（跨欄位 / 相對日期，須 adapter 編排）
              </p>
              <div className="overflow-x-auto">
                <table className="w-full text-[10px]">
                  <thead>
                    <tr className="dark:text-text-tertiary text-light-text-tertiary">
                      <th className="text-left font-semibold px-2 py-1">規則 / 節點</th>
                      <th className="text-left font-semibold px-2 py-1">欄位</th>
                      <th className="text-left font-semibold px-2 py-1">operator</th>
                      <th className="text-left font-semibold px-2 py-1">valueRef</th>
                      <th className="text-left font-semibold px-2 py-1">執行期解析方式</th>
                    </tr>
                  </thead>
                  <tbody>
                    {valueRefRows.map((row, idx) => (
                      <tr key={`${row.ruleId}-${row.field}-${idx}`} className="border-t dark:border-border/30 border-light-border/30">
                        <td className="font-mono px-2 py-1 dark:text-text-primary text-light-text-primary">
                          {row.ruleId}
                        </td>
                        <td className="font-mono px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                          {row.field}
                        </td>
                        <td className="font-mono px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                          {row.operator}
                        </td>
                        <td className="font-mono px-2 py-1 dark:text-text-primary text-light-text-primary">
                          {row.valueRef}
                        </td>
                        <td className="px-2 py-1 dark:text-text-secondary text-light-text-secondary">
                          {row.resolution}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </div>
      )}
    </div>
  );
}
