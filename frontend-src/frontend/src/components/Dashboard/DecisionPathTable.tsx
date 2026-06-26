import { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { RuleEnvelope, TreePathsResult, TreePathStep, DecisionPath } from '../../types';
import { api } from '../../api/rulesApi';
import { fieldLabel, humanizeValue } from '../../constants/fieldLabels';
import { getOperatorDisplay } from './types';

interface Props {
  envelope: RuleEnvelope;
}

function renderValue(s: TreePathStep): string {
  const v = s.value;
  if (v == null) return '';
  if (Array.isArray(v)) {
    return s.operator === 'between' ? v.join(' ~ ') : v.map(String).join(', ');
  }
  return humanizeValue(String(v));
}

function renderStep(s: TreePathStep): string {
  if (!s.field || !s.operator) return s.label ?? '';
  const op = getOperatorDisplay(s.operator).labelCN;
  const val = renderValue(s);
  const base = `${fieldLabel(s.field)} ${op}${val ? ' ' + val : ''}`.trim();
  return s.negated ? `非（${base}）` : base;
}

function PathRow({ path, index }: { path: DecisionPath; index: number }) {
  const cond = path.steps.map(renderStep).filter(Boolean).join(' 且 ');
  const outcome = Object.entries(path.outcome)
    .map(([k, v]) => `${fieldLabel(k)}=${humanizeValue(String(v))}`)
    .join('、');

  return (
    <div className="flex items-start gap-3 px-3 py-2 rounded-lg dark:bg-surface-2/40 bg-light-surface-2/60">
      <span className="shrink-0 mt-0.5 w-6 h-6 rounded-full flex items-center justify-center text-[10px] font-bold
        dark:bg-surface-3 dark:text-text-secondary bg-light-surface-3 text-light-text-secondary tabular-nums">
        {index + 1}
      </span>
      <p className="text-xs leading-relaxed dark:text-text-primary text-light-text-primary">
        {cond ? (
          <>當 <span className="font-medium">{cond}</span>，則{' '}
            <span className="font-semibold text-accent">{outcome || '（無結果）'}</span></>
        ) : (
          <>無條件 → <span className="font-semibold text-accent">{outcome || '（無結果）'}</span></>
        )}
      </p>
    </div>
  );
}

/**
 * 決策路徑表（方向②）：把決策樹每條 root→leaf 路徑列成一句白話規則，
 * 讓非技術 BA 不必讀懂樹結構也能逐條檢視判斷。資料來自後端 /tools/tree-paths。
 */
export default function DecisionPathTable({ envelope }: Props) {
  const [result, setResult] = useState<TreePathsResult | null>(null);
  const [open, setOpen] = useState(true);

  useEffect(() => {
    let cancelled = false;
    if (envelope.ruleType !== 'DecisionTree') { setResult(null); return; }
    api.treePaths(envelope)
      .then((r) => { if (!cancelled) setResult(r); })
      .catch(() => { if (!cancelled) setResult(null); });
    return () => { cancelled = true; };
  }, [envelope]);

  if (!result || result.totalPaths === 0) return null;

  return (
    <div className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-2.5 text-left cursor-pointer
          border-b dark:border-border/50 border-light-border/50 hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
      >
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-lg flex items-center justify-center dark:bg-accent/15 dark:text-accent bg-accent/10 text-accent">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </div>
          <h3 className="text-sm font-semibold dark:text-text-primary text-light-text-primary">決策路徑表</h3>
          <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">{result.totalPaths} 條路徑</span>
        </div>
        <svg className={`w-4 h-4 transition-transform dark:text-text-tertiary text-light-text-tertiary ${open ? 'rotate-180' : ''}`}
          fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
        </svg>
      </button>

      <div className="px-4 py-2 border-b dark:border-border/30 border-light-border/30 dark:bg-surface-2/30 bg-light-surface-2/30">
        <p className="text-[11px] dark:text-text-secondary text-light-text-secondary">
          每一列是從樹根到一個葉節點的完整判斷路徑，已轉成白話規則，方便逐條核對。
        </p>
      </div>

      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div className="px-4 py-3 space-y-1.5">
              {result.paths.map((p, i) => (
                <PathRow key={i} path={p} index={i} />
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
