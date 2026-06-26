import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { GroundingReport } from '../../types';

const SPRING = { type: 'spring' as const, stiffness: 300, damping: 28 };

const LEVEL_META: Record<GroundingReport['suspicionLevel'], {
  label: string;
  color: string;
  bg: string;
  border: string;
  ring: string;
}> = {
  LOW: {
    label: '通過',
    color: 'text-success',
    bg: 'bg-success/10',
    border: 'border-success/30',
    ring: 'stroke-success',
  },
  MEDIUM: {
    label: '部分疑慮',
    color: 'text-warning',
    bg: 'bg-warning/10',
    border: 'border-warning/30',
    ring: 'stroke-warning',
  },
  HIGH: {
    label: '高度可疑',
    color: 'text-danger',
    bg: 'bg-danger/10',
    border: 'border-danger/30',
    ring: 'stroke-danger',
  },
};

export interface GroundingPanelProps {
  grounding: GroundingReport;
}

export default function GroundingPanel({ grounding }: GroundingPanelProps) {
  const [expanded, setExpanded] = useState(
    grounding.suspicionLevel !== 'LOW',  // 有嫌疑時預設展開
  );
  const meta = LEVEL_META[grounding.suspicionLevel];
  const pct = Math.round(grounding.groundingRatio * 100);
  const ungroundedCount =
    (grounding.ungroundedFields?.length ?? 0) +
    (grounding.ungroundedEnumValues?.length ?? 0);

  // Ring 圓環參數
  const circumference = 2 * Math.PI * 20;
  const dashOffset = circumference * (1 - grounding.groundingRatio);

  return (
    <div className={`rounded-xl border ${meta.border} overflow-hidden`}>
      <button
        onClick={() => setExpanded((v) => !v)}
        className={`
          w-full flex items-center gap-4 px-5 py-3.5 cursor-pointer
          ${meta.bg} hover:brightness-110 transition-all
        `}
      >
        {/* Ring 圓環 */}
        <div className="relative w-12 h-12 shrink-0">
          <svg viewBox="0 0 48 48" className="w-full h-full -rotate-90">
            <circle
              cx="24" cy="24" r="20"
              fill="none"
              strokeWidth="3"
              className="dark:stroke-surface-3 stroke-light-surface-3"
            />
            <motion.circle
              cx="24" cy="24" r="20"
              fill="none"
              strokeWidth="3"
              strokeLinecap="round"
              className={meta.ring}
              initial={{ strokeDasharray: circumference, strokeDashoffset: circumference }}
              animate={{ strokeDashoffset: dashOffset }}
              transition={{ ...SPRING, delay: 0.1 }}
            />
          </svg>
          <div className="absolute inset-0 flex items-center justify-center">
            <span className={`text-[11px] font-bold tabular-nums ${meta.color}`}>
              {pct}%
            </span>
          </div>
        </div>

        {/* 標題 */}
        <div className="flex flex-col items-start gap-0.5">
          <span className="text-sm font-semibold dark:text-text-primary text-light-text-primary">
            幻覺偵測 · <span className={meta.color}>{meta.label}</span>
          </span>
          <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
            {grounding.groundedChecks}/{grounding.totalChecks} 項欄位能回溯至原始描述
            {grounding.durationMs > 0 && ` · ${grounding.durationMs}ms`}
          </span>
        </div>

        {/* Ungrounded count badge */}
        <div className="flex-1 flex items-center justify-end gap-2">
          {ungroundedCount > 0 && (
            <span className={`
              inline-flex items-center gap-1 px-2 py-0.5 rounded-full
              text-[10px] font-medium
              ${meta.bg} ${meta.color} border ${meta.border}
            `}>
              {ungroundedCount} 項可疑
            </span>
          )}
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
        {expanded && (ungroundedCount > 0) && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={SPRING}
            className="overflow-hidden"
          >
            <div className="px-5 py-4 space-y-3 dark:bg-surface-1 bg-white border-t dark:border-border/30 border-light-border/30">
              {grounding.ungroundedFields && grounding.ungroundedFields.length > 0 && (
                <div>
                  <h4 className="text-[10px] font-semibold uppercase tracking-wide mb-1.5 dark:text-text-tertiary text-light-text-tertiary">
                    無法對應的欄位名
                  </h4>
                  <div className="flex flex-wrap gap-1.5">
                    {grounding.ungroundedFields.map((f) => (
                      <span
                        key={`${f.source}-${f.name}`}
                        className="
                          inline-flex items-baseline gap-1 text-[11px] font-mono
                          px-2 py-0.5 rounded
                          dark:bg-surface-3 dark:text-text-secondary
                          bg-light-surface-3 text-light-text-secondary
                          border dark:border-border/40 border-light-border/40
                        "
                        title={`${f.source} 欄位，描述中找不到對應關鍵字`}
                      >
                        <span className="text-accent">{f.name}</span>
                        <span className="text-[9px] opacity-60">({f.source})</span>
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {grounding.ungroundedEnumValues && grounding.ungroundedEnumValues.length > 0 && (
                <div>
                  <h4 className="text-[10px] font-semibold uppercase tracking-wide mb-1.5 dark:text-text-tertiary text-light-text-tertiary">
                    無法對應的 ENUM 值
                  </h4>
                  <div className="flex flex-wrap gap-1.5">
                    {grounding.ungroundedEnumValues.map((v, i) => (
                      <span
                        key={`${v.field}-${v.value}-${i}`}
                        className="
                          inline-flex items-baseline gap-1 text-[11px] font-mono
                          px-2 py-0.5 rounded
                          dark:bg-surface-3 dark:text-text-secondary
                          bg-light-surface-3 text-light-text-secondary
                          border dark:border-border/40 border-light-border/40
                        "
                      >
                        <span className="dark:text-text-tertiary text-light-text-tertiary">{v.field}</span>
                        <span className="opacity-50">=</span>
                        <span className="text-accent">{v.value}</span>
                      </span>
                    ))}
                  </div>
                </div>
              )}

              <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary leading-relaxed">
                💡 若這些欄位是您刻意補充的（例如從指定欄位清單加入），可忽略此提示。
                否則請確認是否為 AI 模型憑空產生的內容。
              </p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
