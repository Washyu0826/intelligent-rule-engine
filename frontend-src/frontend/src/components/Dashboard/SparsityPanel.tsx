import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type {
  RuleEnvelope,
  OptimizeConfigV2,
  OptimizeResponseV2,
  TreeQualityMetricsDto,
} from '../../types';
import { api } from '../../api/rulesApi';

const SPRING = { type: 'spring' as const, stiffness: 300, damping: 28 };

export interface SparsityPanelProps {
  envelope: RuleEnvelope;
  /** 套用優化後通知父層（可選） */
  onApplyOptimized?: (optimized: RuleEnvelope) => void;
}

/**
 * v3.13 — DecisionTree v2 sparsity-aware 優化面板。
 *
 * 對 DecisionTree envelope 提供 6-pass pruning 並可視化前後對比。
 * 對非工程使用者（精算師）強調「樹變小多少」，而非技術細節。
 */
export default function SparsityPanel({ envelope, onApplyOptimized }: SparsityPanelProps) {
  const [result, setResult] = useState<OptimizeResponseV2 | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [config, setConfig] = useState<OptimizeConfigV2>({
    leafWeight: 0.01,
    depthWeight: 0.005,
    coverageFloor: 0.95,
  });

  // DecisionTable 不適用 v2，給訊息
  if (envelope.ruleType !== 'DecisionTree') {
    return (
      <div className="rounded-xl border p-4 text-xs
        dark:bg-surface-1 dark:border-border dark:text-text-tertiary
        bg-white border-light-border text-light-text-tertiary">
        v2 sparsity 優化僅適用 DecisionTree 型態。目前是 {envelope.ruleType}。
      </div>
    );
  }

  const runOptimize = async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await api.optimizeV2(envelope as unknown as Record<string, unknown>, config);
      setResult(r);
    } catch (e) {
      setError(typeof e === 'object' && e && 'message' in e
        ? String((e as { message: unknown }).message) : String(e));
    } finally {
      setLoading(false);
    }
  };

  const apply = () => {
    if (result?.optimized && onApplyOptimized) {
      onApplyOptimized(result.optimized);
    }
  };

  const scoreImproved = result
    ? result.sparsityScoreAfter < result.sparsityScoreBefore - 1e-9
    : false;
  const leafReduced = result
    ? result.metricsAfter.leafCount < result.metricsBefore.leafCount
    : false;

  return (
    <div className="
      rounded-xl border overflow-hidden
      dark:bg-surface-1 dark:border-border
      bg-white border-light-border
    ">
      {/* Header */}
      <div className="flex items-center justify-between px-5 py-3 border-b
        dark:border-border border-light-border">
        <div className="flex items-center gap-2.5">
          <div className="
            w-7 h-7 rounded-lg flex items-center justify-center
            dark:bg-violet-900/30 bg-violet-50
            dark:text-violet-300 text-violet-600
          ">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none"
              stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M8 2v3m0 6v3m-6-6h3m6 0h3" />
              <circle cx="8" cy="8" r="2" />
            </svg>
          </div>
          <div>
            <h3 className="text-sm font-semibold
              dark:text-text-primary text-light-text-primary">
              規則樹精簡建議
            </h3>
            <p className="text-[11px] dark:text-text-tertiary text-light-text-tertiary">
              在不影響覆蓋率的前提下，自動把決策樹改得更短、更易讀
            </p>
          </div>
        </div>
        <button
          onClick={runOptimize}
          disabled={loading}
          className="
            text-[11px] px-3 py-1.5 rounded-md font-medium transition-all
            dark:bg-violet-700/40 dark:hover:bg-violet-700/60 dark:text-violet-100
            bg-violet-100 hover:bg-violet-200 text-violet-700
            disabled:opacity-50 disabled:cursor-not-allowed
          "
        >
          {loading ? '優化中…' : result ? '重新優化' : '開始優化'}
        </button>
      </div>

      {/* 參數可調區（精算師 / 雲端工程師可調） */}
      <details className="px-5 py-3 border-b dark:border-border border-light-border">
        <summary className="text-[11px] cursor-pointer
          dark:text-text-tertiary text-light-text-tertiary
          hover:dark:text-text-secondary hover:text-light-text-secondary">
          進階參數
        </summary>
        <div className="grid grid-cols-3 gap-3 mt-3">
          <ParamInput
            label="偏好少分支" value={config.leafWeight ?? 0.01}
            onChange={(v) => setConfig((c) => ({ ...c, leafWeight: v }))}
            step={0.005} min={0} max={1}
            hint="數值越大，越偏好分支較少的樹" />
          <ParamInput
            label="偏好淺結構" value={config.depthWeight ?? 0.005}
            onChange={(v) => setConfig((c) => ({ ...c, depthWeight: v }))}
            step={0.005} min={0} max={1}
            hint="數值越大，越偏好層數較淺的樹" />
          <ParamInput
            label="最低覆蓋率" value={config.coverageFloor ?? 0.95}
            onChange={(v) => setConfig((c) => ({ ...c, coverageFloor: v }))}
            step={0.05} min={0} max={1}
            hint="優化後的樹覆蓋率不低於此值" />
        </div>
      </details>

      {/* Body */}
      <div className="p-5">
        <AnimatePresence mode="wait">
          {error && (
            <motion.div key="err"
              initial={{ opacity: 0 }} animate={{ opacity: 1 }}
              className="text-xs dark:text-danger text-red-600">
              優化失敗：{error}
            </motion.div>
          )}

          {!result && !loading && !error && (
            <p key="hint" className="text-xs dark:text-text-tertiary text-light-text-tertiary">
              點「開始優化」分析此決策樹是否能進一步精簡。
            </p>
          )}

          {loading && (
            <motion.div key="loading"
              initial={{ opacity: 0 }} animate={{ opacity: 1 }}
              className="space-y-3">
              <div className="h-4 rounded animate-pulse w-1/2
                dark:bg-surface-3 bg-light-surface-3" />
              <div className="h-4 rounded animate-pulse w-3/4
                dark:bg-surface-3 bg-light-surface-3" />
              <div className="h-4 rounded animate-pulse w-2/3
                dark:bg-surface-3 bg-light-surface-3" />
            </motion.div>
          )}

          {result && !loading && (
            <motion.div key="result"
              initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}
              transition={SPRING} className="space-y-4">

              {/* Headline */}
              <div className={`
                rounded-lg px-4 py-3 text-sm
                ${scoreImproved
                  ? 'dark:bg-success/10 bg-emerald-50 dark:text-success text-emerald-700'
                  : 'dark:bg-surface-2 bg-light-surface-2 dark:text-text-secondary text-light-text-secondary'}
              `}>
                {scoreImproved ? (
                  <>已優化：分數從 <b>{result.sparsityScoreBefore.toFixed(3)}</b> 降至 <b>{result.sparsityScoreAfter.toFixed(3)}</b>
                  {leafReduced && <>，葉節點 {result.metricsBefore.leafCount} → {result.metricsAfter.leafCount}</>}</>
                ) : (
                  <>樹結構已接近最優，本輪未發現更稀疏化空間。</>
                )}
              </div>

              {/* Metrics 對比 */}
              <div className="grid grid-cols-2 gap-3">
                <MetricsCard title="優化前" m={result.metricsBefore} />
                <MetricsCard title="優化後" m={result.metricsAfter} highlight={scoreImproved} />
              </div>

              {/* Pass 貢獻 */}
              {Object.keys(result.passContributions ?? {}).length > 0 && (
                <Section title="各階段貢獻">
                  <table className="text-xs w-full">
                    <tbody>
                      {Object.entries(result.passContributions).map(([k, v]) => (
                        <tr key={k} className="border-b dark:border-border/50 border-light-border/50 last:border-0">
                          <td className="py-1.5 dark:text-text-tertiary text-light-text-tertiary">
                            {PASS_LABEL[k] ?? k}
                          </td>
                          <td className="py-1.5 text-right font-mono
                            dark:text-text-primary text-light-text-primary">
                            減少 {v} 個葉節點
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </Section>
              )}

              {/* 動作清單 */}
              {result.appliedOptimizations.length > 0 && (
                <Section title="實際動作">
                  <ul className="space-y-1">
                    {result.appliedOptimizations.map((s, i) => (
                      <li key={i} className="flex gap-2 text-xs
                        dark:text-text-secondary text-light-text-secondary">
                        <span className="text-violet-500 shrink-0">›</span>
                        <span>{s}</span>
                      </li>
                    ))}
                  </ul>
                </Section>
              )}

              {/* 套用按鈕 */}
              {scoreImproved && onApplyOptimized && (
                <button
                  onClick={apply}
                  className="
                    w-full text-xs px-3 py-2 rounded-md font-medium transition-all
                    dark:bg-violet-700 dark:hover:bg-violet-600 dark:text-white
                    bg-violet-600 hover:bg-violet-700 text-white
                  ">
                  套用優化版本
                </button>
              )}

              <p className="text-[10px] text-center
                dark:text-text-tertiary text-light-text-tertiary">
                耗時 {result.durationMs} ms · 保證：覆蓋率不會降低、規則數量不會增加
              </p>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════
// MetricsCard
// ════════════════════════════════════════════
function MetricsCard({
  title,
  m,
  highlight,
}: {
  title: string;
  m: TreeQualityMetricsDto;
  highlight?: boolean;
}) {
  return (
    <div className={`
      rounded-lg border p-3
      ${highlight
        ? 'dark:border-success/40 dark:bg-success/5 border-emerald-300 bg-emerald-50/50'
        : 'dark:border-border dark:bg-surface-2 border-light-border bg-light-surface-2'}
    `}>
      <p className="text-[10px] uppercase tracking-[0.08em] mb-2 font-semibold
        dark:text-text-tertiary text-light-text-tertiary">
        {title}
      </p>
      <div className="space-y-1.5 text-xs">
        <Row label="葉節點" value={m.leafCount} />
        <Row label="最大深度" value={m.maxDepth} />
        <Row label="平均路徑" value={m.avgPathLength.toFixed(2)} />
        <Row label="平衡指數" value={`${(m.balanceIndex * 100).toFixed(0)}%`} />
        <Row label="重複率" value={`${(m.duplicationRatio * 100).toFixed(0)}%`} />
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="flex items-center justify-between">
      <span className="dark:text-text-tertiary text-light-text-tertiary">{label}</span>
      <span className="font-mono font-medium
        dark:text-text-primary text-light-text-primary">{value}</span>
    </div>
  );
}

// ════════════════════════════════════════════
// Section
// ════════════════════════════════════════════
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h4 className="text-[10px] uppercase tracking-[0.08em] font-semibold mb-2
        dark:text-text-primary text-light-text-primary">
        {title}
      </h4>
      {children}
    </div>
  );
}

// ════════════════════════════════════════════
// ParamInput
// ════════════════════════════════════════════
function ParamInput({
  label, value, onChange, step, min, max, hint,
}: {
  label: string; value: number; onChange: (v: number) => void;
  step: number; min: number; max: number; hint?: string;
}) {
  return (
    <label className="flex flex-col gap-1 text-[11px]
      dark:text-text-tertiary text-light-text-tertiary">
      <span className="font-medium">{label}</span>
      <input
        type="number"
        value={value} step={step} min={min} max={max}
        onChange={(e) => onChange(parseFloat(e.target.value) || 0)}
        className="
          px-2 py-1 rounded border text-xs font-mono
          dark:bg-surface-2 dark:border-border dark:text-text-primary
          bg-white border-light-border text-light-text-primary
          focus:outline-none focus:ring-2 focus:ring-violet-500/40
        "
      />
      {hint && (
        <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
          {hint}
        </span>
      )}
    </label>
  );
}

const PASS_LABEL: Record<string, string> = {
  'pass1-3': '合併重複結果、移除無法到達的分支',
  pass4: '合併結構相同的子樹',
  pass5: '化簡冗餘條件',
  pass6: '重新組合更佳的分支順序',
};
