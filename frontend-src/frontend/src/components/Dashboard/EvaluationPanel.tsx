import { useMemo, useState, useEffect, useCallback, lazy, Suspense } from 'react';
import { motion, useSpring, useTransform } from 'framer-motion';
import type { RuleEnvelope, Evaluation, RecommendResponse, ValidateResponse, AnalyzeResponse } from '../../types';
import { parseDecisionTable } from './types';
import HelpTooltip from '../common/HelpTooltip';

const QualityRadarChart = lazy(() => import('./QualityRadarChart'));

// ════════════════════════════════════════════
// Spring config
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
interface EvaluationPanelProps {
  envelope: RuleEnvelope;
  recommend?: RecommendResponse;
  durationMs?: number;
  validate?: ValidateResponse;
  analyze?: AnalyzeResponse;
}

// ════════════════════════════════════════════
// AnimatedNumber
// ════════════════════════════════════════════
function AnimatedNumber({
  value,
  decimals = 0,
  suffix = '',
}: {
  value: number;
  decimals?: number;
  suffix?: string;
}) {
  const spring = useSpring(0, { stiffness: 300, damping: 30 });
  const display = useTransform(spring, (v) => `${v.toFixed(decimals)}${suffix}`);

  useEffect(() => {
    spring.set(value);
  }, [value, spring]);

  return <motion.span>{display}</motion.span>;
}

// ════════════════════════════════════════════
// MetricCard
// ════════════════════════════════════════════
type CardStatus = 'success' | 'danger' | 'warning' | 'neutral';

const statusDotColor: Record<CardStatus, string> = {
  success: 'bg-success',
  danger: 'bg-danger',
  warning: 'bg-warning',
  neutral: 'dark:bg-text-tertiary bg-light-text-tertiary',
};

const statusTextColor: Record<CardStatus, string> = {
  success: 'text-success',
  danger: 'text-danger',
  warning: 'text-warning',
  neutral: 'dark:text-text-primary text-light-text-primary',
};

function MetricCard({
  label,
  value,
  status = 'neutral',
  delay = 0,
  helpText,
}: {
  label: string;
  value: string;
  status?: CardStatus;
  delay?: number;
  helpText?: string;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...SPRING, delay }}
      className="
        group relative rounded-xl border p-4
        dark:bg-surface-1 dark:border-border
        bg-white border-light-border
        hover:dark:border-border-hover hover:border-light-border-hover
        transition-colors duration-200
      "
    >
      <span className={`absolute top-3 right-3 w-1.5 h-1.5 rounded-full ${statusDotColor[status]}`} />
      <p className="
        text-[10px] uppercase tracking-[0.08em] font-medium mb-2
        dark:text-text-tertiary text-light-text-tertiary
      ">
        {helpText ? <HelpTooltip text={helpText}>{label}</HelpTooltip> : label}
      </p>
      <p className={`text-lg font-semibold tracking-tight ${statusTextColor[status]}`}>
        {value}
      </p>
    </motion.div>
  );
}

// ════════════════════════════════════════════
// CoverageRing
// ════════════════════════════════════════════
function CoverageRing({ rate }: { rate: number }) {
  const pct = Math.min(rate * 100, 100);
  const radius = 38;
  const circumference = 2 * Math.PI * radius;

  const springValue = useSpring(circumference, { stiffness: 300, damping: 30 });
  const strokeDashoffset = useTransform(springValue, (v) => v);

  useEffect(() => {
    springValue.set(circumference * (1 - rate));
  }, [rate, circumference, springValue]);

  const color =
    pct >= 90 ? { stroke: '#10b981', text: 'text-emerald-400', glow: 'rgba(16,185,129,0.15)' }
    : pct >= 60 ? { stroke: '#f59e0b', text: 'text-amber-400', glow: 'rgba(245,158,11,0.15)' }
    : { stroke: '#ef4444', text: 'text-red-400', glow: 'rgba(239,68,68,0.15)' };

  return (
    <div className="relative flex items-center justify-center w-24 h-24 shrink-0">
      <svg width="96" height="96" viewBox="0 0 96 96" className="-rotate-90">
        {/* Background ring */}
        <circle
          cx="48" cy="48" r={radius}
          fill="none"
          strokeWidth="6"
          className="dark:stroke-surface-3 stroke-light-surface-3"
        />
        {/* Foreground ring */}
        <motion.circle
          cx="48" cy="48" r={radius}
          fill="none"
          strokeWidth="6"
          stroke={color.stroke}
          strokeLinecap="round"
          strokeDasharray={circumference}
          style={{ strokeDashoffset }}
          filter={`drop-shadow(0 0 6px ${color.glow})`}
        />
      </svg>
      {/* Center number */}
      <div className={`absolute inset-0 flex flex-col items-center justify-center ${color.text}`}>
        <span className="text-lg font-bold font-mono tabular-nums">
          <AnimatedNumber value={pct} decimals={1} suffix="%" />
        </span>
        <span className="text-[8px] uppercase tracking-widest dark:text-text-tertiary text-light-text-tertiary">
          覆蓋率
        </span>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════
// StatPill
// ════════════════════════════════════════════
function StatPill({
  label,
  value,
  icon,
  delay = 0,
}: {
  label: string;
  value: number;
  icon: React.ReactNode;
  delay?: number;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...SPRING, delay }}
      className="
        flex items-center gap-2.5 px-3 py-2 rounded-lg
        dark:bg-surface-2 bg-light-surface-2
      "
    >
      <span className="
        w-7 h-7 rounded-md flex items-center justify-center shrink-0
        dark:bg-surface-3 bg-light-surface-3
        dark:text-text-tertiary text-light-text-tertiary
      ">
        {icon}
      </span>
      <div className="min-w-0">
        <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary truncate">
          {label}
        </p>
        <p className="text-sm font-semibold tabular-nums dark:text-text-primary text-light-text-primary">
          <AnimatedNumber value={value} />
        </p>
      </div>
    </motion.div>
  );
}

// ════════════════════════════════════════════
// ShareButton
// ════════════════════════════════════════════
function ShareButton() {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = window.location.href;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    setCopied(true);
  }, []);

  useEffect(() => {
    if (copied) {
      const t = setTimeout(() => setCopied(false), 2000);
      return () => clearTimeout(t);
    }
  }, [copied]);

  return (
    <motion.button
      whileHover={{ scale: 1.05 }}
      whileTap={{ scale: 0.95 }}
      onClick={handleCopy}
      className="
        inline-flex items-center gap-1 text-[10px] font-medium
        px-2 py-1 rounded-md cursor-pointer
        dark:bg-surface-3 dark:text-text-secondary dark:hover:text-text-primary
        bg-light-surface-3 text-light-text-secondary hover:text-light-text-primary
        border dark:border-border border-light-border
        transition-colors
      "
    >
      {copied ? (
        <>
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M2 5.5l2 2 4-4" />
          </svg>
          已複製
        </>
      ) : (
        <>
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
            <path d="M7.5 3.5h-4a1 1 0 00-1 1v4a1 1 0 001 1h4a1 1 0 001-1v-4a1 1 0 00-1-1z" />
            <path d="M3.5 3.5v-1a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1h-1" />
          </svg>
          分享
        </>
      )}
    </motion.button>
  );
}

// ════════════════════════════════════════════
// SVG Icons
// ════════════════════════════════════════════
const Icons = {
  conditions: (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="M1.5 2.5h11M3.5 5.5h7M5.5 8.5h3M6.5 11.5h1" />
    </svg>
  ),
  results: (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="7" cy="7" r="5" />
      <circle cx="7" cy="7" r="2.5" />
      <circle cx="7" cy="7" r="0.5" fill="currentColor" />
    </svg>
  ),
  scenarios: (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
      <rect x="1.5" y="1.5" width="4.5" height="4.5" rx="1" />
      <rect x="8" y="1.5" width="4.5" height="4.5" rx="1" />
      <rect x="1.5" y="8" width="4.5" height="4.5" rx="1" />
      <rect x="8" y="8" width="4.5" height="4.5" rx="1" />
    </svg>
  ),
};

// ════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════
function getCompletenessStatus(val: string): CardStatus {
  if (val === 'COMPLETE') return 'success';
  if (val === 'PARTIAL') return 'warning';
  return 'danger';
}

function getConflictStatus(val: string): CardStatus {
  return val === 'NO_CONFLICT' ? 'success' : 'danger';
}

function getCompletenessLabel(val: string): string {
  switch (val) {
    case 'COMPLETE': return '完整';
    case 'PARTIAL': return '部分覆蓋';
    case 'INCOMPLETE': return '不完整';
    default: return val;
  }
}

function getConflictLabel(val: string): string {
  return val === 'NO_CONFLICT' ? '無衝突' : '有衝突';
}

function getStrategyLabel(val: string): string {
  switch (val) {
    case 'FIRST': return '首次命中';
    case 'MULTI': return '多重命中';
    case 'COLLECT': return '全部收集';
    case 'PRIORITY': return '優先序判定';
    default: return val;
  }
}

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const seconds = ms / 1000;
  return seconds < 60 ? `${seconds.toFixed(1)}s` : `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`;
}

// ════════════════════════════════════════════
// Quality Summary — 一眼看出品質好壞
// ════════════════════════════════════════════
type QualityLevel = 'excellent' | 'good' | 'fair' | 'poor';

interface QualityItem {
  label: string;
  status: QualityLevel;
  detail: string;
}

const qualityConfig: Record<QualityLevel, { icon: string; color: string; bg: string }> = {
  excellent: { icon: '\u2713', color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
  good: { icon: '\u2713', color: 'text-blue-400', bg: 'bg-blue-500/10' },
  fair: { icon: '!', color: 'text-amber-400', bg: 'bg-amber-500/10' },
  poor: { icon: '\u2717', color: 'text-red-400', bg: 'bg-red-500/10' },
};

function QualitySummary({
  envelope,
  validate,
  analyze,
  ruleCount,
}: {
  envelope: RuleEnvelope;
  validate?: ValidateResponse;
  analyze?: AnalyzeResponse;
  ruleCount: number;
}) {
  const items: QualityItem[] = [];
  const evaluation = envelope.evaluation;
  const coverageRate = evaluation?.coverageRate ?? 0;
  const gapCount = analyze?.gaps?.length ?? 0;
  const overlapCount = analyze?.overlaps?.length ?? 0;
  const errorCount = validate?.errors?.length ?? 0;

  // 1. Validation
  if (validate) {
    if (validate.valid) {
      items.push({ label: '結構驗證', status: 'excellent', detail: '通過，無錯誤' });
    } else {
      items.push({
        label: '結構驗證',
        status: errorCount > 3 ? 'poor' : 'fair',
        detail: `${errorCount} 個錯誤`,
      });
    }
  }

  // 2. Coverage
  if (coverageRate >= 0.95) {
    items.push({ label: '覆蓋率', status: 'excellent', detail: `${(coverageRate * 100).toFixed(1)}%` });
  } else if (coverageRate >= 0.7) {
    items.push({ label: '覆蓋率', status: 'good', detail: `${(coverageRate * 100).toFixed(1)}%` });
  } else if (coverageRate >= 0.4) {
    items.push({ label: '覆蓋率', status: 'fair', detail: `${(coverageRate * 100).toFixed(1)}%，有 ${gapCount} 個缺口` });
  } else {
    items.push({ label: '覆蓋率', status: 'poor', detail: `${(coverageRate * 100).toFixed(1)}%，有 ${gapCount} 個缺口` });
  }

  // 3. Conflict
  if (overlapCount === 0) {
    items.push({ label: '規則衝突', status: 'excellent', detail: '無重疊' });
  } else {
    items.push({
      label: '規則衝突',
      status: overlapCount > 5 ? 'poor' : 'fair',
      detail: `${overlapCount} 組重疊`,
    });
  }

  // 4. Rule count（DecisionTree 沒有 rules array，改用 totalScenarios）
  const totalScenarios = evaluation?.totalScenarios ?? 0;
  const isDecisionTree = envelope.ruleType === 'DecisionTree';
  if (isDecisionTree && totalScenarios > 0) {
    items.push({ label: '規則完整度', status: 'excellent', detail: `${totalScenarios} 條決策路徑` });
  } else if (ruleCount >= totalScenarios && totalScenarios > 0) {
    items.push({ label: '規則完整度', status: 'excellent', detail: `${ruleCount}/${totalScenarios} 條` });
  } else if (ruleCount > 0 && totalScenarios > 0) {
    const ratio = ruleCount / totalScenarios;
    items.push({
      label: '規則完整度',
      status: ratio > 0.8 ? 'good' : ratio > 0.5 ? 'fair' : 'poor',
      detail: `${ruleCount}/${totalScenarios} 條 (${(ratio * 100).toFixed(0)}%)`,
    });
  } else if (ruleCount > 0) {
    items.push({ label: '規則數量', status: 'good', detail: `${ruleCount} 條` });
  }

  // Overall score
  const scores: Record<QualityLevel, number> = { excellent: 3, good: 2, fair: 1, poor: 0 };
  const avgScore = items.length > 0
    ? items.reduce((sum, i) => sum + scores[i.status], 0) / items.length
    : 0;
  const overall: QualityLevel =
    avgScore >= 2.5 ? 'excellent' : avgScore >= 1.5 ? 'good' : avgScore >= 0.8 ? 'fair' : 'poor';
  const overallLabel: Record<QualityLevel, string> = {
    excellent: '品質優良',
    good: '品質良好',
    fair: '需要關注',
    poor: '品質不佳',
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...SPRING, delay: 0.22 }}
      className="rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border p-4"
    >
      {/* Header */}
      <div className="flex items-center justify-between mb-3">
        <p className="text-[10px] uppercase tracking-[0.08em] font-medium dark:text-text-tertiary text-light-text-tertiary">
          品質摘要
        </p>
        <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${qualityConfig[overall].bg} ${qualityConfig[overall].color}`}>
          {overallLabel[overall]}
        </span>
      </div>

      {/* Radar Chart + Items Grid */}
      <div className="flex gap-4 items-start">
        {/* Radar Chart */}
        <div className="hidden md:block flex-shrink-0">
          <Suspense fallback={<div className="w-[180px] h-[180px]" />}>
            <QualityRadarChart
              completeness={items.find(i => i.label.includes('結構'))?.status === 'excellent' ? 1 : items.find(i => i.label.includes('結構'))?.status === 'good' ? 0.8 : 0.3}
              coverageRate={items.find(i => i.label.includes('覆蓋'))?.status === 'excellent' ? 1 : items.find(i => i.label.includes('覆蓋'))?.status === 'good' ? 0.8 : 0.5}
              noConflict={items.find(i => i.label.includes('衝突'))?.status === 'excellent' ? 1 : items.find(i => i.label.includes('衝突'))?.status === 'good' ? 0.7 : 0.3}
              ruleCountScore={items.find(i => i.label.includes('完整'))?.status === 'excellent' ? 1 : items.find(i => i.label.includes('完整'))?.status === 'good' ? 0.8 : 0.5}
              depthScore={0.8}
              size={180}
            />
          </Suspense>
        </div>

        {/* Items */}
        <div className="flex-1 grid grid-cols-2 md:grid-cols-2 gap-2">
          {items.map((item, idx) => {
            const cfg = qualityConfig[item.status];
            return (
              <motion.div
                key={item.label}
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={{ ...SPRING, delay: 0.25 + idx * 0.05 }}
                className={`flex items-start gap-2 rounded-lg p-2.5 ${cfg.bg}`}
              >
                <span className={`text-sm font-bold mt-0.5 ${cfg.color}`}>{cfg.icon}</span>
                <div className="min-w-0">
                  <p className="text-[11px] font-medium dark:text-text-secondary text-light-text-secondary truncate">
                    {item.label}
                  </p>
                  <p className={`text-[10px] ${cfg.color} truncate`}>{item.detail}</p>
                </div>
              </motion.div>
            );
          })}
        </div>
      </div>
    </motion.div>
  );
}

export default function EvaluationPanel({ envelope, recommend, durationMs, validate, analyze }: EvaluationPanelProps) {
  const evaluation: Evaluation | undefined = envelope.evaluation;
  const table = useMemo(() => parseDecisionTable(envelope.rule), [envelope.rule]);

  const ruleAny = envelope.rule as Record<string, unknown> | undefined;
  const fallbackInputs = (ruleAny?.inputs as unknown[] | undefined) ?? [];
  const fallbackOutputs = (ruleAny?.outputs as unknown[] | undefined) ?? [];
  const inputCount = table?.inputs?.length ?? fallbackInputs.length;
  const outputCount = table?.outputs?.length ?? fallbackOutputs.length;
  const ruleCount = table?.rules?.length ?? 0;
  const totalScenarios = evaluation?.totalScenarios ?? ruleCount;
  const coverageRate = evaluation?.coverageRate ?? 0;

  const reason = recommend?.reason || envelope.reason;
  const confidence = recommend?.confidence;

  return (
    <div className="space-y-4">
      {/* ── 5 Metric Cards ── */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
        <MetricCard
          label="規則型態"
          value={envelope.ruleType === 'DecisionTable' ? '決策表' : envelope.ruleType === 'DecisionTree' ? '決策樹' : envelope.ruleType}
          status="neutral"
          delay={0}
          helpText="決策表：所有條件平行比對，適合規則數量明確的場景。決策樹：條件依序判斷，適合有先後邏輯的場景。"
        />
        <MetricCard
          label="完整性"
          value={getCompletenessLabel(evaluation?.completeness ?? '—')}
          status={evaluation ? getCompletenessStatus(evaluation.completeness) : 'neutral'}
          delay={0.05}
          helpText="是否所有條件組合都已建立對應規則，無遺漏。「完整」代表所有情境都有規則覆蓋。"
        />
        <MetricCard
          label="衝突檢測"
          value={getConflictLabel(evaluation?.conflictDetection ?? '—')}
          status={evaluation ? getConflictStatus(evaluation.conflictDetection) : 'neutral'}
          delay={0.1}
          helpText="檢查是否有兩條以上的規則在相同條件下產生不同結果。有衝突代表某些情境可能得到矛盾的決策。"
        />
        <MetricCard
          label="執行策略"
          value={getStrategyLabel(evaluation?.recommendedStrategy ?? '—')}
          status="neutral"
          delay={0.15}
          helpText="首次命中：找到第一條符合的規則就停止。多重命中：所有符合的規則都會執行。"
        />
        <MetricCard
          label="生成耗時"
          value={durationMs != null ? formatDuration(durationMs) : '—'}
          status={durationMs != null ? (durationMs < 10000 ? 'success' : durationMs < 30000 ? 'warning' : 'danger') : 'neutral'}
          delay={0.2}
        />
      </div>

      {/* ── Stats Row: 3 StatPills + CoverageRing ── */}
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ ...SPRING, delay: 0.18 }}
        className="
          flex items-center gap-3
          rounded-xl border p-4
          dark:bg-surface-1 dark:border-border
          bg-white border-light-border
        "
      >
        <div className="flex-1 grid grid-cols-1 sm:grid-cols-3 gap-3">
          <StatPill
            label="條件欄位數"
            value={inputCount}
            icon={Icons.conditions}
            delay={0.2}
          />
          <StatPill
            label="結果欄位數"
            value={outputCount}
            icon={Icons.results}
            delay={0.25}
          />
          <StatPill
            label="決策場景數"
            value={totalScenarios}
            icon={Icons.scenarios}
            delay={0.3}
          />
        </div>

        <CoverageRing rate={coverageRate} />
      </motion.div>

      {/* ── Quality Summary ── */}
      <QualitySummary
        envelope={envelope}
        validate={validate}
        analyze={analyze}
        ruleCount={ruleCount}
      />

      {/* ── Reason Display ── */}
      {reason && (
        <motion.div
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ ...SPRING, delay: 0.35 }}
          className="
            relative rounded-xl border-l-[3px] border-l-blue-500
            border border-l-0
            dark:bg-surface-1 dark:border-border
            bg-white border-light-border
            p-5
          "
        >
          {/* Blue glow */}
          <div className="absolute left-0 top-0 bottom-0 w-8 bg-gradient-to-r from-blue-500/5 to-transparent rounded-l-xl pointer-events-none" />

          <div className="relative space-y-2">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <p className="text-[10px] uppercase tracking-[0.08em] font-medium dark:text-text-tertiary text-light-text-tertiary">
                  AI 推薦理由
                </p>
                {confidence !== undefined && (
                  <span className="
                    text-[10px] font-mono px-1.5 py-0.5 rounded
                    bg-blue-500/10 text-blue-400
                  ">
                    信心度 <AnimatedNumber value={confidence * 100} decimals={0} suffix="%" />
                  </span>
                )}
              </div>
              <ShareButton />
            </div>

            <p className="text-sm leading-relaxed dark:text-text-secondary text-light-text-secondary">
              {reason}
            </p>

            {evaluation?.recommendedStrategy && (
              <p className="text-xs dark:text-text-tertiary text-light-text-tertiary mt-1">
                建議策略：{getStrategyLabel(evaluation.recommendedStrategy)}
              </p>
            )}

            {recommend?.alternatives && recommend.alternatives.length > 0 && (
              <motion.div
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                transition={{ ...SPRING, delay: 0.45 }}
                className="mt-3 pt-3 border-t dark:border-border border-light-border"
              >
                <p className="text-[10px] uppercase tracking-[0.08em] font-medium dark:text-text-tertiary text-light-text-tertiary mb-2">
                  替代方案
                </p>
                <div className="flex flex-wrap gap-2">
                  {recommend.alternatives.map((alt, i) => (
                    <span
                      key={i}
                      className="
                        inline-flex items-center gap-1.5 text-[11px] px-2 py-1 rounded-md
                        dark:bg-surface-2 dark:text-text-secondary
                        bg-light-surface-2 text-light-text-secondary
                      "
                      title={alt.reason}
                    >
                      <span className="font-medium">{alt.ruleType}</span>
                      <span className="dark:text-text-tertiary text-light-text-tertiary">
                        <AnimatedNumber value={alt.score * 100} decimals={0} suffix="%" />
                      </span>
                    </span>
                  ))}
                </div>
              </motion.div>
            )}
          </div>
        </motion.div>
      )}
    </div>
  );
}
