import { useState, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { AnalyzeResponse, GapInfo } from '../../types';
import { useDashboard, actions } from './DashboardContext';
import { fieldLabel, humanizeValue } from '../../constants/fieldLabels';

// ════════════════════════════════════════════
// Spring config
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Severity (relative to the largest gap in this analysis)
// ════════════════════════════════════════════
type Severity = 'high' | 'mid' | 'low';

const SEVERITY_STYLE: Record<Severity, { badge: string; pill: string; label: string }> = {
  high: { badge: 'bg-danger/15 text-danger', pill: 'bg-danger/10 text-danger', label: '高' },
  mid: { badge: 'bg-amber-500/15 text-amber-500', pill: 'bg-amber-500/10 text-amber-500', label: '中' },
  low: {
    badge: 'dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary',
    pill: 'dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary',
    label: '低',
  },
};

function severityOf(ratio: number | undefined, maxVol: number): Severity {
  if (!ratio || maxVol <= 0) return 'low';
  const r = ratio / maxVol;
  return r >= 0.66 ? 'high' : r >= 0.33 ? 'mid' : 'low';
}

function formatRatio(ratio: number | undefined): string | null {
  if (ratio == null) return null;
  const pct = ratio * 100;
  if (pct <= 0) return null;
  if (pct < 0.1) return '<0.1%';
  return `${pct.toFixed(pct < 1 ? 2 : 1)}%`;
}

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
export interface GapAnalysisProps {
  analyze: AnalyzeResponse;
}

// ════════════════════════════════════════════
// Icons
// ════════════════════════════════════════════
const GapIcon = () => (
  <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <rect x="1" y="1" width="12" height="12" rx="2" strokeDasharray="3 2" />
    <path d="M5 7h4" />
  </svg>
);

// ════════════════════════════════════════════
// Gap Card
// ════════════════════════════════════════════
function GapCard({
  gap,
  index,
  maxVol,
  onClickGap,
}: {
  gap: GapInfo;
  index: number;
  maxVol: number;
  onClickGap: (gap: GapInfo) => void;
}) {
  const dimensions = Object.entries(gap.conditions ?? {});
  const [isHovered, setIsHovered] = useState(false);

  const severity = severityOf(gap.volumeRatio, maxVol);
  const style = SEVERITY_STYLE[severity];
  const ratioText = formatRatio(gap.volumeRatio);

  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...SPRING, delay: index * 0.03 }}
      onClick={() => onClickGap(gap)}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      className="
        flex items-start gap-3 px-3 py-3 rounded-lg cursor-pointer
        dark:bg-surface-2/50 bg-light-surface-2/50
        dark:hover:bg-surface-2 hover:bg-light-surface-2
        transition-colors duration-150
      "
    >
      {/* Severity-coloured rank */}
      <span className={`
        w-5 h-5 rounded-md flex items-center justify-center shrink-0 mt-0.5
        text-[10px] font-mono font-semibold ${style.badge}
      `}>
        {index + 1}
      </span>

      <div className="flex-1 min-w-0 space-y-1.5">
        {/* Severity + impact */}
        <div className="flex items-center gap-1.5">
          <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${style.pill}`}>
            嚴重度 {style.label}
          </span>
          {ratioText && (
            <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              影響範圍 {ratioText}
            </span>
          )}
        </div>

        {/* Condition dimensions (中文欄位名) */}
        <div className="flex flex-wrap gap-1.5">
          {dimensions.map(([field, value]) => (
            <span
              key={field}
              title={`${field} = ${value}`}
              className="
                inline-flex items-center gap-1 text-[11px]
                px-2 py-0.5 rounded
                bg-red-500/8 text-red-400
                border border-red-500/12
              "
            >
              <span className="font-semibold">{fieldLabel(field)}</span>
              <span className="opacity-50">=</span>
              <span className="font-mono">{humanizeValue(value)}</span>
            </span>
          ))}
        </div>

        {/* Description */}
        {gap.message && (
          <p className="text-[11px] leading-relaxed dark:text-text-tertiary text-light-text-tertiary">
            {gap.message}
          </p>
        )}

        {/* Click hint */}
        <p className={`
          text-[10px] transition-colors duration-150
          ${isHovered ? 'text-accent' : 'dark:text-text-tertiary/40 text-light-text-tertiary/40'}
        `}>
          點擊查看熱力圖
        </p>
      </div>
    </motion.div>
  );
}

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
export default function GapAnalysis({ analyze }: GapAnalysisProps) {
  const { dispatch } = useDashboard();
  const [expanded, setExpanded] = useState(true);
  const [showAll, setShowAll] = useState(false);

  const gaps = analyze.gaps ?? [];
  const maxVol = gaps.reduce((m, g) => Math.max(m, g.volumeRatio ?? 0), 0);
  const sortedGaps = [...gaps].sort((a, b) => (b.volumeRatio ?? 0) - (a.volumeRatio ?? 0));

  const handleClickGap = useCallback((gap: GapInfo) => {
    const dims = Object.entries(gap.conditions ?? {});
    const x = dims[0]?.[1] ?? '';
    const y = dims[1]?.[1] ?? '';
    dispatch(actions.navigateAndHighlightCell('analysis', { x, y }));
  }, [dispatch]);

  // ── No gaps: success state ──
  if (gaps.length === 0) {
    return (
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={SPRING}
        className="
          rounded-xl border overflow-hidden
          dark:bg-surface-1 dark:border-success/20 bg-white border-success/20
        "
      >
        <div className="px-5 py-4 flex items-center gap-3 bg-gradient-to-r from-success/5 to-transparent">
          <motion.div
            initial={{ scale: 0 }}
            animate={{ scale: 1 }}
            transition={SPRING}
            className="w-7 h-7 rounded-md bg-success/10 flex items-center justify-center text-success"
          >
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 7.5l2.5 2.5L10 5" />
            </svg>
          </motion.div>
          <div>
            <p className="text-xs font-semibold text-success">無覆蓋缺口</p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5">
              所有條件組合均已被規則覆蓋
            </p>
          </div>
        </div>
      </motion.div>
    );
  }

  const displayGaps = showAll ? sortedGaps : sortedGaps.slice(0, 8);
  const hasMore = gaps.length > 8;

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={SPRING}
      className="
        rounded-xl border overflow-hidden
        dark:bg-surface-1 dark:border-border bg-white border-light-border
      "
    >
      {/* Header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="
          w-full px-4 py-3 flex items-center justify-between cursor-pointer
          hover:dark:bg-surface-2/30 hover:bg-light-surface-2/30 transition-colors
        "
      >
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-md bg-danger/10 flex items-center justify-center text-danger">
            <GapIcon />
          </div>
          <div className="text-left">
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
              覆蓋缺口分析
            </p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5">
              發現 {gaps.length} 個未覆蓋的條件組合
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <span className="
            text-[11px] font-mono font-semibold tabular-nums
            px-2 py-0.5 rounded-full
            bg-danger/15 text-danger
          ">
            {gaps.length}
          </span>
          <motion.svg
            width="12" height="12" viewBox="0 0 12 12" fill="none"
            stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"
            animate={{ rotate: expanded ? 180 : 0 }}
            transition={SPRING}
          >
            <path d="M3 4.5l3 3 3-3" />
          </motion.svg>
        </div>
      </button>

      {/* Gap list */}
      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={SPRING}
            className="overflow-hidden"
          >
            <div className="px-4 pb-3 space-y-1.5">
              {displayGaps.map((gap, i) => (
                <GapCard
                  key={i}
                  gap={gap}
                  index={i}
                  maxVol={maxVol}
                  onClickGap={handleClickGap}
                />
              ))}

              {/* Show more / less */}
              {hasMore && (
                <button
                  onClick={() => setShowAll(!showAll)}
                  className="
                    w-full text-center py-2 text-[10px] font-medium cursor-pointer
                    dark:text-text-tertiary dark:hover:text-accent
                    text-light-text-tertiary hover:text-accent
                    transition-colors
                  "
                >
                  {showAll ? '收起' : `顯示全部 ${gaps.length} 個缺口`}
                </button>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
