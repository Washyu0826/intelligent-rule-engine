import { useState } from 'react';
import type { RuleEnvelope } from '../../types';
import { parseDecisionTable, getOperatorDisplay, formatConditionValue, type RuleCondition } from '../Dashboard/types';
import { card, textPrimary, textSecondary, textTertiary } from './ui';

function condValue(c: RuleCondition): string {
  return c.valueRef ? `「${c.valueRef}」` : formatConditionValue(c);
}

function valueText(v: unknown): string {
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v ?? '');
}

export default function RuleView({ envelope }: { envelope: RuleEnvelope }) {
  const [mode, setMode] = useState<'sentences' | 'table'>('sentences');
  const table = envelope.ruleType === 'DecisionTable' ? parseDecisionTable(envelope.rule) : null;

  if (!table) {
    return (
      <div className={`${card} p-4`}>
        <p className={`text-xs mb-2 ${textTertiary}`}>
          {envelope.ruleType} 目前以原始內容顯示（白話句子與表格切換僅支援決策表）
        </p>
        <pre className={`text-[11px] overflow-auto max-h-80 ${textSecondary}`}>
          {JSON.stringify(envelope.rule, null, 2)}
        </pre>
      </div>
    );
  }

  const rules = [...table.rules].sort((a, b) => a.priority - b.priority);
  const resultFields = Array.from(new Set(rules.flatMap((r) => r.results.map((x) => x.field))));
  const condFields = Array.from(new Set(rules.flatMap((r) => r.conditions.map((x) => x.field))));

  return (
    <div className={`${card} p-4 space-y-3`}>
      <div className="flex items-center justify-between">
        <span className={`text-sm font-semibold ${textPrimary}`}>規則內容（{rules.length} 條）</span>
        <div className="flex rounded-lg border dark:border-border border-light-border overflow-hidden text-xs">
          {(['sentences', 'table'] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMode(m)}
              className={`px-3 py-1 cursor-pointer ${
                mode === m ? 'bg-[var(--color-group-green-600)] text-white' : textSecondary
              }`}
            >
              {m === 'sentences' ? '白話句子' : '表格'}
            </button>
          ))}
        </div>
      </div>

      {mode === 'sentences' ? (
        <ol className="space-y-1.5">
          {rules.map((r) => {
            const conds = r.conditions.filter((c) => c.operator !== 'anything');
            return (
              <li key={r.ruleId} className={`text-sm leading-relaxed ${textPrimary}`}>
                <span className={`text-[10px] mr-2 font-mono ${textTertiary}`}>{r.ruleId}</span>
                {conds.length === 0 ? '不論條件' : '當 '}
                {conds.map((c, i) => (
                  <span key={i}>
                    {i > 0 && ' 且 '}
                    <b>{c.field}</b> {getOperatorDisplay(c.operator).labelCN} {condValue(c)}
                  </span>
                ))}
                ，則 {r.results.map((x) => `${x.field} 為 ${valueText(x.value)}`).join('、')}
              </li>
            );
          })}
        </ol>
      ) : (
        <div className="overflow-auto">
          <table className={`text-xs w-full ${textPrimary}`}>
            <thead>
              <tr className={`text-left ${textTertiary}`}>
                <th className="py-1 pr-3">規則</th>
                {condFields.map((f) => <th key={f} className="py-1 pr-3">{f}</th>)}
                {resultFields.map((f) => <th key={f} className="py-1 pr-3">→ {f}</th>)}
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.ruleId} className="border-t dark:border-border/40 border-light-border">
                  <td className="py-1 pr-3 font-mono">{r.ruleId}</td>
                  {condFields.map((f) => {
                    const c = r.conditions.find((x) => x.field === f);
                    return (
                      <td key={f} className="py-1 pr-3">
                        {c && c.operator !== 'anything'
                          ? `${getOperatorDisplay(c.operator).labelCN} ${condValue(c)}`
                          : '—'}
                      </td>
                    );
                  })}
                  {resultFields.map((f) => (
                    <td key={f} className="py-1 pr-3">
                      {valueText(r.results.find((x) => x.field === f)?.value) || '—'}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
