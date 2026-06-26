import { useState, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { AnalyzeResponse, SimplificationHint } from '../../types';
import { useDashboard, actions } from './DashboardContext';

// ════════════════════════════════════════════
// Spring config
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
export interface SimplificationHintsProps {
  analyze: AnalyzeResponse;
}

// ════════════════════════════════════════════
// Icons
// ════════════════════════════════════════════
const MergeIcon = () => (
  <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <path d="M2 3h4M2 7h4M2 11h4" />
    <path d="M8 3v8" />
    <path d="M6 5l2-2 2 2" />
    <path d="M6 9l2 2 2-2" />
  </svg>
);

// ════════════════════════════════════════════
// Hint Card
// ════════════════════════════════════════════
function HintCard({
  hint,
  index,
  onClickRuleId,
}: {
  hint: SimplificationHint;
  index: number;
  onClickRuleId: (ruleId: string) => void;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...SPRING, delay: index * 0.03 }}
      className="
        flex items-start gap-3 px-3 py-3 rounded-lg
        dark:bg-surface-2/50 bg-light-surface-2/50
        dark:hover:bg-surface-2 hover:bg-light-surface-2
        transition-colors duration-150
      "
    >
      {/* Index */}
      <span className="
        w-5 h-5 rounded-md flex items-center justify-center shrink-0 mt-0.5
        bg-violet-500/10 text-violet-400 text-[10px] font-mono font-semibold
      ">
        {index + 1}
      </span>

      <div className="flex-1 min-w-0 space-y-2">
        {/* Rule IDs that can be merged */}
        <div className="flex flex-wrap items-center gap-1.5">
          {hint.ruleIds.map((id, i) => (
            <span key={id} className="contents">
              {i > 0 && (
                <span className="text-[10px] dark:text-text-tertiary/50 text-light-text-tertiary/50">+</span>
              )}
              <button
                onClick={(e) => { e.stopPropagation(); onClickRuleId(id); }}
                className="
                  inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-semibold
                  bg-violet-500/12 text-violet-400
                  border border-violet-500/15
                  hover:bg-violet-500/25 hover:border-violet-500/30
                  cursor-pointer transition-colors duration-150
                "
              >
                {id}
              </button>
            </span>
          ))}
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" className="text-violet-400/50 mx-1">
            <path d="M4 7h6M8 5l2 2-2 2" />
          </svg>
          <span className="
            inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-semibold
            bg-accent/12 text-accent
            border border-accent/15
          ">
            合併
          </span>
        </div>

        {/* Suggestion text */}
        <p className="text-[11px] leading-relaxed dark:text-text-secondary text-light-text-secondary">
          {hint.suggestion}
        </p>
      </div>
    </motion.div>
  );
}

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
export default function SimplificationHints({ analyze }: SimplificationHintsProps) {
  const { dispatch, scrollToRef } = useDashboard();
  const [expanded, setExpanded] = useState(true);

  const simplifications = analyze.simplifications ?? [];

  const handleClickRuleId = useCallback((ruleId: string) => {
    dispatch(
      actions.navigateAndHighlight('rules', [ruleId], 'simplification', `rule-${ruleId}`),
    );
    setTimeout(() => scrollToRef(`rule-${ruleId}`), 300);
  }, [dispatch, scrollToRef]);

  // ── No simplifications ──
  if (simplifications.length === 0) {
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
        <div className="px-5 py-4 flex items-center gap-3">
          <div className="w-7 h-7 rounded-md bg-surface-2 flex items-center justify-center dark:text-text-tertiary text-light-text-tertiary">
            <MergeIcon />
          </div>
          <div>
            <p className="text-xs font-semibold dark:text-text-secondary text-light-text-secondary">
              無簡化建議
            </p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5">
              目前規則已足夠簡潔，無需合併
            </p>
          </div>
        </div>
      </motion.div>
    );
  }

  // Calculate potential reduction
  const totalMergeable = simplifications.reduce((sum: number, h: SimplificationHint) => sum + h.ruleIds.length, 0);
  const afterMerge = totalMergeable - simplifications.length;

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
          <div className="w-7 h-7 rounded-md bg-violet-500/10 flex items-center justify-center text-violet-400">
            <MergeIcon />
          </div>
          <div className="text-left">
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
              簡化建議
            </p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5">
              {simplifications.length} 組規則可合併，可減少約 {afterMerge} 條規則
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <span className="
            text-[11px] font-mono font-semibold tabular-nums
            px-2 py-0.5 rounded-full
            bg-violet-500/15 text-violet-400
          ">
            {simplifications.length}
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

      {/* Hints list */}
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
              {simplifications.map((hint: SimplificationHint, i: number) => (
                <HintCard
                  key={i}
                  hint={hint}
                  index={i}
                  onClickRuleId={handleClickRuleId}
                />
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
