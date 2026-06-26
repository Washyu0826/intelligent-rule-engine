import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import type { RuleEnvelope, TableDiffResult, RegionDiff } from '../../types';
import { api } from '../../api/rulesApi';

interface Props {
  before: RuleEnvelope;
  after: RuleEnvelope;
}

const TYPE_STYLE: Record<RegionDiff['type'], { dot: string; text: string; label: string }> = {
  LOST_COVERAGE: { dot: 'bg-red-500', text: 'text-red-600 dark:text-red-400', label: '回歸·漏覆蓋' },
  CHANGED_DECISION: { dot: 'bg-amber-500', text: 'text-amber-600 dark:text-amber-400', label: '決策改變' },
  NEW_COVERAGE: { dot: 'bg-emerald-500', text: 'text-emerald-600 dark:text-emerald-400', label: '新增覆蓋' },
};

/**
 * 行為比對（方向③ L2）：用後端超矩形集合差比對兩個 DecisionTable，
 * 找出回歸（舊版涵蓋、新版漏掉）、決策改變、新增覆蓋的具體輸入組合。
 */
export default function BehavioralTableDiff({ before, after }: Props) {
  const [result, setResult] = useState<TableDiffResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    api.diffTable(before, after)
      .then((r) => { if (!cancelled) setResult(r); })
      .catch(() => { if (!cancelled) setError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [before, after]);

  if (loading) {
    return (
      <div className="rounded-xl border p-4 animate-pulse dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div className="h-3 w-44 rounded dark:bg-surface-3 bg-light-surface-3" />
      </div>
    );
  }
  if (error || !result) return null;

  if (result.comparable === false) {
    return (
      <div className="rounded-xl border px-4 py-3 dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <p className="text-xs dark:text-amber-400 text-amber-600">{result.summary}</p>
      </div>
    );
  }

  const counts = [
    { label: '回歸', value: result.lostCount, cls: 'bg-red-500/10 text-red-500' },
    { label: '決策改變', value: result.changedCount, cls: 'bg-amber-500/10 text-amber-500' },
    { label: '新增覆蓋', value: result.newCount, cls: 'bg-emerald-500/10 text-emerald-500' },
  ];
  const clean = result.lostCount + result.changedCount + result.newCount === 0;

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border"
    >
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b dark:border-border border-light-border">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-md flex items-center justify-center dark:bg-accent/15 dark:text-accent bg-accent/10 text-accent">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M3 3v18h18M7 14l4-4 4 4 5-6" />
            </svg>
          </div>
          <div>
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">行為比對（與舊版）</p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              {result.summary}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1.5">
          {counts.map((c) => (
            <span key={c.label} className={`text-[10px] font-mono px-1.5 py-0.5 rounded ${c.cls}`}>
              {c.label} {c.value}
            </span>
          ))}
        </div>
      </div>

      {/* Body */}
      <div className="px-4 py-3">
        {clean ? (
          <p className="text-xs text-center py-3 dark:text-text-tertiary text-light-text-tertiary">
            兩個版本在所有輸入組合上的決策行為一致（無回歸、無決策改變）
          </p>
        ) : (
          <ul className="space-y-1.5">
            {result.regions.map((r, i) => {
              const s = TYPE_STYLE[r.type];
              const cond = Object.entries(r.point).map(([k, v]) => `${k}=${v}`).join('、');
              return (
                <li key={i} className="flex items-start gap-2 text-[11px] leading-relaxed">
                  <span className={`shrink-0 mt-1 w-1.5 h-1.5 rounded-full ${s.dot}`} />
                  <span className="dark:text-text-secondary text-light-text-secondary">
                    <span className={`font-semibold ${s.text}`}>[{s.label}]</span>{' '}
                    <span className="font-mono">{cond}</span> — {r.message}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </motion.div>
  );
}
