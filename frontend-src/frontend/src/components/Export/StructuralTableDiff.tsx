import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import type { RuleEnvelope, RuleSetDiffResult, RuleChange } from '../../types';
import { api } from '../../api/rulesApi';

interface Props {
  before: RuleEnvelope;
  after: RuleEnvelope;
}

const TYPE_STYLE: Record<RuleChange['type'], { dot: string; text: string; label: string }> = {
  MODIFIED: { dot: 'bg-amber-500', text: 'text-amber-600 dark:text-amber-400', label: '決策改變' },
  REMOVED: { dot: 'bg-red-500', text: 'text-red-600 dark:text-red-400', label: '刪除' },
  ADDED: { dot: 'bg-emerald-500', text: 'text-emerald-600 dark:text-emerald-400', label: '新增' },
};

/**
 * 結構比對（方向③）：依條件簽章對齊兩個 DecisionTable 的規則列，
 * 回報哪些規則新增/刪除/決策改變（不受 ruleId 改名干擾）。
 */
export default function StructuralTableDiff({ before, after }: Props) {
  const [result, setResult] = useState<RuleSetDiffResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    api.diffRules(before, after)
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
    { label: '新增', value: result.added, cls: 'bg-emerald-500/10 text-emerald-500' },
    { label: '刪除', value: result.removed, cls: 'bg-red-500/10 text-red-500' },
    { label: '決策改變', value: result.modified, cls: 'bg-amber-500/10 text-amber-500' },
    { label: '不變', value: result.unchanged, cls: 'dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary' },
  ];

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border"
    >
      <div className="flex items-center justify-between px-4 py-3 border-b dark:border-border border-light-border">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-md flex items-center justify-center dark:bg-accent/15 dark:text-accent bg-accent/10 text-accent">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M3 6h18M3 12h18M3 18h18" />
            </svg>
          </div>
          <div>
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">規則結構比對</p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              依條件對齊（不受 ruleId 改名干擾）
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

      <div className="px-4 py-3">
        {result.changes.length === 0 ? (
          <p className="text-xs text-center py-3 dark:text-text-tertiary text-light-text-tertiary">
            兩個版本的規則集在條件與決策上完全相同（即使 ruleId 不同）
          </p>
        ) : (
          <ul className="space-y-1.5">
            {result.changes.map((c, i) => {
              const s = TYPE_STYLE[c.type];
              return (
                <li key={i} className="flex items-start gap-2 text-[11px] leading-relaxed">
                  <span className={`shrink-0 mt-1 w-1.5 h-1.5 rounded-full ${s.dot}`} />
                  <span className="dark:text-text-secondary text-light-text-secondary">
                    <span className={`font-semibold ${s.text}`}>[{s.label}]</span>{' '}
                    {c.detail}
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
