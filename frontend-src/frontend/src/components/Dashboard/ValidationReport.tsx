import { useState, useMemo, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { ValidateResponse, ValidationError } from '../../types';
import {
  getErrorSeverity,
  SEVERITY_ORDER,
  ERROR_FIX_HINTS,
  type ErrorSeverity,
} from './types';
import { useDashboard, actions } from './DashboardContext';

// ════════════════════════════════════════════
// Spring config
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
export interface ValidationReportProps {
  validation: ValidateResponse;
}

// ════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════
function extractRuleId(error: ValidationError): string | null {
  const match = error.message.match(/\b(R\d{1,3})\b/);
  return match ? match[1] : null;
}

const severityColors: Record<ErrorSeverity, { bg: string; text: string; border: string; bar: string }> = {
  critical: { bg: 'bg-red-500/10', text: 'text-red-400', border: 'border-red-500/20', bar: 'bg-red-400' },
  warning:  { bg: 'bg-amber-500/10', text: 'text-amber-400', border: 'border-amber-500/20', bar: 'bg-amber-400' },
  info:     { bg: 'bg-blue-500/10', text: 'text-blue-400', border: 'border-blue-500/20', bar: 'bg-blue-400' },
};

const severityLabels: Record<ErrorSeverity, string> = {
  critical: '嚴重',
  warning: '警告',
  info: '資訊',
};

// ════════════════════════════════════════════
// ErrorBarChart (mini inline bar chart)
// ════════════════════════════════════════════
function ErrorBarChart({ errors }: { errors: ValidationError[] }) {
  const counts = useMemo(() => {
    const map = new Map<string, { count: number; severity: ErrorSeverity }>();
    for (const err of errors) {
      const entry = map.get(err.code);
      if (entry) {
        entry.count++;
      } else {
        map.set(err.code, { count: 1, severity: getErrorSeverity(err.code) });
      }
    }
    return Array.from(map.entries()).sort(
      (a, b) => SEVERITY_ORDER[a[1].severity] - SEVERITY_ORDER[b[1].severity],
    );
  }, [errors]);

  const maxCount = Math.max(...counts.map(([, v]) => v.count), 1);

  const [hovered, setHovered] = useState<string | null>(null);

  return (
    <div className="flex items-end gap-[3px] h-5">
      {counts.map(([code, { count, severity }]) => {
        const colors = severityColors[severity];
        const heightPct = (count / maxCount) * 100;
        const isHovered = hovered === code;

        return (
          <div
            key={code}
            className="relative"
            onMouseEnter={() => setHovered(code)}
            onMouseLeave={() => setHovered(null)}
          >
            <motion.div
              initial={{ height: 0 }}
              animate={{ height: `${Math.max(heightPct, 15)}%` }}
              transition={SPRING}
              className={`w-[6px] rounded-full ${colors.bar} ${isHovered ? 'opacity-100' : 'opacity-60'}`}
              style={{ minHeight: 3 }}
            />
            {/* Tooltip */}
            {isHovered && (
              <div className="
                absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5
                px-2 py-1 rounded-md text-[9px] font-mono whitespace-nowrap z-50
                dark:bg-surface-3 bg-white shadow-lg border dark:border-border border-light-border
                dark:text-text-primary text-light-text-primary
              ">
                {code}: {count}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ════════════════════════════════════════════
// ErrorRow
// ════════════════════════════════════════════
function ErrorRow({
  error,
  index,
}: {
  error: ValidationError;
  index: number;
}) {
  const { dispatch, scrollToRef } = useDashboard();
  const severity = getErrorSeverity(error.code);
  const colors = severityColors[severity];
  const linkedRuleId = extractRuleId(error);
  const hint = ERROR_FIX_HINTS[error.code];

  const handleNavigate = useCallback(() => {
    if (!linkedRuleId) return;
    dispatch(
      actions.navigateAndHighlight('rules', [linkedRuleId], 'validation', `rule-${linkedRuleId}`),
    );
    setTimeout(() => scrollToRef(`rule-${linkedRuleId}`), 300);
  }, [linkedRuleId, dispatch, scrollToRef]);

  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...SPRING, delay: index * 0.03 }}
      className="
        rounded-lg
        dark:hover:bg-surface-2/50 hover:bg-light-surface-2/50
        transition-colors duration-150
      "
    >
      {/* Main error line */}
      <div className="flex items-start gap-3 px-3 py-2.5">
        {/* Number */}
        <span className="
          text-[10px] font-mono tabular-nums pt-0.5 shrink-0
          dark:text-text-tertiary text-light-text-tertiary
        ">
          {String(index + 1).padStart(2, '0')}
        </span>

        {/* Severity dot */}
        <span className={`w-1.5 h-1.5 rounded-full mt-1.5 shrink-0 ${colors.bar}`} />

        {/* Error code badge */}
        <code className={`
          shrink-0 px-2 py-0.5 rounded text-[10px] font-mono font-medium
          ${colors.bg} ${colors.text} border ${colors.border}
        `}>
          {error.code}
        </code>

        {/* Error message */}
        <span className="
          flex-1 text-sm leading-relaxed
          dark:text-text-secondary text-light-text-secondary
        ">
          {error.message}
        </span>

        {/* Rule link button */}
        {linkedRuleId && (
          <button
            onClick={(e) => { e.stopPropagation(); handleNavigate(); }}
            className="
              shrink-0 inline-flex items-center gap-1 text-[10px] font-mono
              px-2 py-0.5 rounded cursor-pointer
              dark:bg-surface-3 dark:text-text-tertiary dark:hover:text-accent
              bg-light-surface-3 text-light-text-tertiary hover:text-accent
              transition-colors duration-150
            "
            title={`定位到規則 ${linkedRuleId}`}
          >
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 6l2-2" />
              <path d="M3 7a2 2 0 0 1 0-2.83l.7-.7" />
              <path d="M7 3a2 2 0 0 1 0 2.83l-.7.7" />
            </svg>
            {linkedRuleId}
          </button>
        )}
      </div>

      {/* v3.7.0: Structured witness chips (IEEE 2024 counter-example) */}
      {error.witness && Object.keys(error.witness).length > 0 && (
        <div className="flex items-start gap-2 px-3 pb-2 pl-[52px]">
          <span className="
            text-[10px] shrink-0 mt-0.5 uppercase tracking-wide
            dark:text-text-tertiary text-light-text-tertiary
          ">
            觸發範例
          </span>
          <div className="flex flex-wrap gap-1.5">
            {Object.entries(error.witness).map(([field, value]) => (
              <span
                key={field}
                className="
                  inline-flex items-baseline gap-1 text-[10px] font-mono
                  px-1.5 py-0.5 rounded
                  dark:bg-surface-3 dark:text-text-secondary
                  bg-light-surface-3 text-light-text-secondary
                  border dark:border-border/40 border-light-border/40
                "
                title={`${field} = ${value}`}
              >
                <span className="dark:text-text-tertiary text-light-text-tertiary">{field}</span>
                <span className="opacity-50">=</span>
                <span className="text-accent">{value}</span>
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Fix hint */}
      {hint && (
        <div className="flex items-start gap-2 px-3 pb-2.5 pl-[52px]">
          <span className="
            w-4 h-4 rounded-full shrink-0 mt-0.5
            flex items-center justify-center
            bg-accent/10 text-accent
          ">
            <svg width="8" height="8" viewBox="0 0 8 8" fill="currentColor">
              <circle cx="4" cy="2" r="0.8" />
              <rect x="3.2" y="3.2" width="1.6" height="3.2" rx="0.4" />
            </svg>
          </span>
          <p className="text-[11px] leading-relaxed dark:text-text-tertiary text-light-text-tertiary">
            {hint}
          </p>
        </div>
      )}
    </motion.div>
  );
}

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
export default function ValidationReport({ validation }: ValidationReportProps) {
  const [expanded, setExpanded] = useState(true);

  // Sort errors by severity
  const sortedErrors = useMemo(() => {
    if (!validation.errors) return [];
    return [...validation.errors].sort((a, b) => {
      const sa = getErrorSeverity(a.code);
      const sb = getErrorSeverity(b.code);
      return SEVERITY_ORDER[sa] - SEVERITY_ORDER[sb];
    });
  }, [validation.errors]);

  // Group by severity for category pills
  const severityGroups = useMemo(() => {
    const groups: { severity: ErrorSeverity; label: string; count: number }[] = [];
    const counts = new Map<ErrorSeverity, number>();
    for (const err of sortedErrors) {
      const s = getErrorSeverity(err.code);
      counts.set(s, (counts.get(s) ?? 0) + 1);
    }
    for (const severity of ['critical', 'warning', 'info'] as ErrorSeverity[]) {
      const count = counts.get(severity) ?? 0;
      if (count > 0) {
        groups.push({ severity, label: severityLabels[severity], count });
      }
    }
    return groups;
  }, [sortedErrors]);

  // ── VALID ──
  if (validation.valid) {
    return (
      <div className="
        rounded-xl border overflow-hidden
        dark:border-success/20 border-success/20
      ">
        <div className="
          relative px-5 py-4
          bg-gradient-to-r from-success/10 via-success/5 to-transparent
        ">
          {/* Shimmer */}
          <div className="absolute inset-0 overflow-hidden">
            <div
              className="absolute inset-0 opacity-30"
              style={{
                background: 'linear-gradient(90deg, transparent, rgba(16,185,129,0.08), transparent)',
                animation: 'shimmer 3s ease-in-out infinite',
              }}
            />
          </div>

          <div className="relative flex items-center gap-3">
            <motion.div
              initial={{ scale: 0 }}
              animate={{ scale: 1 }}
              transition={SPRING}
              className="
                w-8 h-8 rounded-full bg-success/15
                flex items-center justify-center text-success
              "
            >
              <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M4 9.5l3.5 3.5L14 5" />
              </svg>
            </motion.div>
            <motion.div
              initial={{ opacity: 0, x: -8 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ ...SPRING, delay: 0.1 }}
            >
              <p className="text-sm font-semibold text-success">
                驗證通過
              </p>
              <p className="text-xs dark:text-text-tertiary text-light-text-tertiary mt-0.5">
                規則格式完整、所有欄位設定正確，且無邏輯矛盾
              </p>
            </motion.div>
          </div>
        </div>
      </div>
    );
  }

  // ── INVALID ──
  const errorCount = sortedErrors.length;

  return (
    <div className="
      rounded-xl border overflow-hidden
      dark:border-danger/20 border-danger/20
    ">
      {/* Error banner */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="
          w-full px-5 py-4 flex items-center justify-between cursor-pointer
          bg-gradient-to-r from-danger/10 via-danger/5 to-transparent
          hover:from-danger/15 hover:via-danger/8 transition-colors
        "
      >
        <div className="flex items-center gap-3">
          <div className="
            w-8 h-8 rounded-full bg-danger/15
            flex items-center justify-center text-danger
          ">
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M5 5l8 8M13 5l-8 8" />
            </svg>
          </div>
          <div className="text-left">
            <p className="text-sm font-semibold text-danger">
              驗證失敗
            </p>
            <p className="text-xs dark:text-text-tertiary text-light-text-tertiary mt-0.5">
              發現 {errorCount} 個錯誤，請修正後重新驗證
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <ErrorBarChart errors={sortedErrors} />
          <span className="
            text-[11px] font-mono font-semibold tabular-nums
            px-2 py-0.5 rounded-full
            bg-danger/15 text-danger
          ">
            {errorCount}
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

      {/* Error list (collapsible) */}
      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={SPRING}
            className="overflow-hidden"
          >
            <div className="
              px-4 py-3 space-y-4
              dark:bg-surface-1 bg-white
            ">
              {/* Severity summary pills */}
              <div className="flex flex-wrap gap-2">
                {severityGroups.map(({ severity, label, count }) => {
                  const colors = severityColors[severity];
                  return (
                    <span
                      key={severity}
                      className={`
                        inline-flex items-center gap-1.5 text-[10px] font-medium px-2 py-0.5 rounded
                        ${colors.bg} ${colors.text}
                      `}
                    >
                      <span className={`w-1.5 h-1.5 rounded-full ${colors.bar}`} />
                      {label}
                      <span className="font-mono">×{count}</span>
                    </span>
                  );
                })}
              </div>

              {/* Error rows */}
              <div className="space-y-1">
                {sortedErrors.map((err, idx) => (
                  <ErrorRow
                    key={`${err.code}-${idx}`}
                    error={err}
                    index={idx}
                  />
                ))}
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
