import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { ConfidenceReport } from '../../types';

const SPRING = { type: 'spring' as const, stiffness: 300, damping: 28 };

const TIER_META: Record<ConfidenceReport['tier'], {
  label: string;
  color: string;
  bg: string;
  border: string;
  bar: string;
  description: string;
}> = {
  PRODUCTION_READY: {
    label: '可上線',
    color: 'text-success',
    bg: 'bg-success/10',
    border: 'border-success/30',
    bar: 'bg-success',
    description: '品質達標，可直接部署至 production',
  },
  REVIEW_NEEDED: {
    label: '需審閱',
    color: 'text-warning',
    bg: 'bg-warning/10',
    border: 'border-warning/30',
    bar: 'bg-warning',
    description: '建議 BA 人工確認後再上線',
  },
  NOT_RECOMMENDED: {
    label: '不建議使用',
    color: 'text-danger',
    bg: 'bg-danger/10',
    border: 'border-danger/30',
    bar: 'bg-danger',
    description: '存在關鍵問題，建議重新生成或大幅修改',
  },
};

const FACTOR_LABEL: Record<string, string> = {
  validation: '結構驗證',
  grounding: '無 AI 幻覺',
  coverage: '覆蓋率',
  noConflict: '無衝突',
  ruleCount: '規則數合理',
  sparsity: '結構精簡度',
  consistency: '邏輯一致性',
  completeness: '完整性',
};

export interface ConfidenceBarProps {
  confidence: ConfidenceReport;
}

export default function ConfidenceBar({ confidence }: ConfidenceBarProps) {
  const [expanded, setExpanded] = useState(false);
  const meta = TIER_META[confidence.tier];
  const warningCount = confidence.actionableWarnings?.length ?? 0;

  return (
    <div className={`rounded-xl border overflow-hidden ${meta.border}`}>
      <button
        onClick={() => setExpanded((v) => !v)}
        className={`
          w-full flex items-center gap-4 px-5 py-4 cursor-pointer
          ${meta.bg} hover:brightness-110 transition-all
        `}
      >
        {/* Score 大字 */}
        <div className="flex items-baseline gap-1">
          <motion.span
            key={confidence.overallScore}
            initial={{ scale: 0.8, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={SPRING}
            className={`text-3xl font-bold tabular-nums ${meta.color}`}
          >
            {confidence.overallScore}
          </motion.span>
          <span className="text-xs dark:text-text-tertiary text-light-text-tertiary">/ 100</span>
        </div>

        {/* Tier 徽章 */}
        <div className="flex flex-col items-start gap-0.5">
          <span className={`text-sm font-semibold ${meta.color}`}>
            {meta.label}
          </span>
          <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
            {meta.description}
          </span>
        </div>

        {/* Bar 圖示 */}
        <div className="flex-1 flex items-center justify-end gap-2">
          {/* 進度條 */}
          <div className="hidden md:block relative w-40 h-1.5 rounded-full dark:bg-surface-3 bg-light-surface-3 overflow-hidden">
            <motion.div
              initial={{ width: 0 }}
              animate={{ width: `${confidence.overallScore}%` }}
              transition={SPRING}
              className={`absolute left-0 top-0 h-full ${meta.bar} rounded-full`}
            />
          </div>

          {/* 警告 badge */}
          {warningCount > 0 && (
            <span className={`
              inline-flex items-center gap-1 px-2 py-0.5 rounded-full
              text-[10px] font-medium
              ${meta.bg} ${meta.color}
            `}>
              <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.3">
                <path d="M5 1.5v3.5M5 7v0.5" strokeLinecap="round" />
                <circle cx="5" cy="5" r="4" />
              </svg>
              {warningCount}
            </span>
          )}

          {/* 展開箭頭 */}
          <motion.svg
            width="12" height="12" viewBox="0 0 12 12" fill="none"
            stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"
            animate={{ rotate: expanded ? 180 : 0 }}
            transition={SPRING}
            className="dark:text-text-tertiary text-light-text-tertiary"
          >
            <path d="M3 4.5l3 3 3-3" />
          </motion.svg>
        </div>
      </button>

      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={SPRING}
            className="overflow-hidden"
          >
            <div className="px-5 py-4 space-y-4 dark:bg-surface-1 bg-white border-t dark:border-border/30 border-light-border/30">
              {/* Factor breakdown */}
              <div>
                <h4 className="text-[10px] font-semibold uppercase tracking-wide mb-2 dark:text-text-tertiary text-light-text-tertiary">
                  五因子分解
                </h4>
                <div className="space-y-1.5">
                  {Object.entries(confidence.breakdown).map(([key, score]) => {
                    const pct = score.weight > 0 ? (score.earned / score.weight) * 100 : 0;
                    return (
                      <div key={key} className="flex items-center gap-2">
                        <span className="text-[11px] w-24 shrink-0 dark:text-text-secondary text-light-text-secondary">
                          {FACTOR_LABEL[key] ?? key}
                        </span>
                        <div className="flex-1 h-1.5 rounded-full dark:bg-surface-3 bg-light-surface-3 overflow-hidden">
                          <motion.div
                            initial={{ width: 0 }}
                            animate={{ width: `${pct}%` }}
                            transition={{ ...SPRING, delay: 0.05 }}
                            className={`h-full rounded-full ${
                              pct >= 80 ? 'bg-success' : pct >= 50 ? 'bg-warning' : 'bg-danger'
                            }`}
                          />
                        </div>
                        <span className="text-[11px] font-mono tabular-nums w-14 text-right dark:text-text-tertiary text-light-text-tertiary">
                          {score.earned}/{score.weight}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </div>

              {/* Actionable warnings */}
              {confidence.actionableWarnings && confidence.actionableWarnings.length > 0 && (
                <div>
                  <h4 className="text-[10px] font-semibold uppercase tracking-wide mb-2 dark:text-text-tertiary text-light-text-tertiary">
                    改善建議
                  </h4>
                  <ul className="space-y-1">
                    {confidence.actionableWarnings.map((w, i) => (
                      <li key={i} className="flex items-start gap-2 text-[12px] leading-relaxed dark:text-text-secondary text-light-text-secondary">
                        <span className="shrink-0 mt-1 w-1 h-1 rounded-full bg-accent" />
                        <span>{w}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
