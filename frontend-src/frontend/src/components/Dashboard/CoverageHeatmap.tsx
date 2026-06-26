import { useState, useMemo, useRef, useEffect } from 'react';
import { motion, useSpring, useTransform } from 'framer-motion';
import type { RuleEnvelope, AnalyzeResponse } from '../../types';
import {
  parseDecisionTable,
  type DecisionTableRule,
  type RuleInput,
  type RuleCondition,
  type RuleEntry,
} from './types';
import { useDashboard, actions } from './DashboardContext';

// ════════════════════════════════════════════
// Spring config
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Mini Coverage Ring (for header)
// ════════════════════════════════════════════
function MiniCoverageRing({ covered, total }: { covered: number; total: number }) {
  const rate = total > 0 ? covered / total : 0;
  const pct = Math.min(rate * 100, 100);
  const radius = 14;
  const circumference = 2 * Math.PI * radius;

  const springVal = useSpring(circumference, { stiffness: 300, damping: 30 });
  const dashOffset = useTransform(springVal, (v) => v);

  useEffect(() => {
    springVal.set(circumference * (1 - rate));
  }, [rate, circumference, springVal]);

  const color = pct >= 90 ? '#10b981' : pct >= 60 ? '#f59e0b' : '#ef4444';

  return (
    <div className="relative w-9 h-9 shrink-0 flex items-center justify-center">
      <svg width="36" height="36" viewBox="0 0 36 36" className="-rotate-90">
        <circle cx="18" cy="18" r={radius} fill="none" strokeWidth="3"
          className="dark:stroke-surface-3 stroke-light-surface-3" />
        <motion.circle cx="18" cy="18" r={radius} fill="none" strokeWidth="3"
          stroke={color} strokeLinecap="round"
          strokeDasharray={circumference}
          style={{ strokeDashoffset: dashOffset }} />
      </svg>
      <span className="absolute text-[8px] font-mono font-bold" style={{ color }}>
        {Math.round(pct)}
      </span>
    </div>
  );
}

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
export interface CoverageHeatmapProps {
  envelope: RuleEnvelope;
  analyze?: AnalyzeResponse;
}

// ════════════════════════════════════════════
// Cell status
// ════════════════════════════════════════════
type CellStatus = 'covered' | 'gap' | 'overlap' | 'empty';

interface HeatmapCell {
  xLabel: string;
  yLabel: string;
  status: CellStatus;
  ruleIds: string[];
  tooltip: string;
}

// ════════════════════════════════════════════
// Color mapping
// ════════════════════════════════════════════
const statusColors: Record<CellStatus, { fill: string; fillDark: string; stroke: string }> = {
  covered: { fill: '#10b981', fillDark: 'rgba(16,185,129,0.35)', stroke: 'rgba(16,185,129,0.5)' },
  gap:     { fill: '#ef4444', fillDark: 'rgba(239,68,68,0.30)',   stroke: 'rgba(239,68,68,0.5)' },
  overlap: { fill: '#f59e0b', fillDark: 'rgba(245,158,11,0.35)', stroke: 'rgba(245,158,11,0.5)' },
  empty:   { fill: '#71717a', fillDark: 'rgba(113,113,122,0.08)', stroke: 'rgba(113,113,122,0.15)' },
};

const statusLabels: Record<CellStatus, string> = {
  covered: '已覆蓋（有規則處理）',
  gap:     '缺口（無規則處理）',
  overlap: '衝突（多條規則重疊）',
  empty:   '未定義',
};

// ════════════════════════════════════════════
// Extract dimension values from rules + gaps + overlaps
// ════════════════════════════════════════════
function extractDimensionValues(
  input: RuleInput,
  rules: RuleEntry[],
  gaps?: AnalyzeResponse['gaps'],
  overlaps?: AnalyzeResponse['overlaps']
): string[] {
  const values = new Set<string>();

  // From allowedValues
  if (input.allowedValues) {
    input.allowedValues.forEach(v => values.add(v));
  }

  // From rule conditions
  for (const rule of rules) {
    const cond = rule.conditions.find(c => c.field === input.name);
    if (!cond) continue;

    if (cond.operator === 'equals' && cond.value !== undefined) {
      values.add(String(cond.value));
    } else if (cond.operator === 'in' && Array.isArray(cond.value)) {
      cond.value.forEach((v: unknown) => values.add(String(v)));
    } else if (cond.operator === 'between' && Array.isArray(cond.value)) {
      values.add(`[${cond.value[0]},${cond.value[1]}]`);
    } else if (cond.operator === 'anything') {
      values.add('*');
    } else if (cond.value !== undefined) {
      values.add(`${cond.operator} ${cond.value}`);
    }
  }

  // From gaps
  for (const gap of gaps ?? []) {
    const gapVal = gap.conditions[input.name];
    if (gapVal) values.add(gapVal);
  }

  // From overlaps
  for (const overlap of overlaps ?? []) {
    const overlapVal = overlap.intersection[input.name];
    if (overlapVal) values.add(overlapVal);
  }

  return Array.from(values).sort();
}

// ════════════════════════════════════════════
// Check if a rule's condition matches a dimension value
// ════════════════════════════════════════════
function conditionMatchesValue(cond: RuleCondition | undefined, value: string): boolean {
  if (!cond) return false;
  if (cond.operator === 'anything') return true;

  if (cond.operator === 'equals') {
    return String(cond.value) === value;
  }

  if (cond.operator === 'in' && Array.isArray(cond.value)) {
    return cond.value.map(String).includes(value);
  }

  if (cond.operator === 'between' && Array.isArray(cond.value)) {
    const rangeStr = `[${cond.value[0]},${cond.value[1]}]`;
    if (value === rangeStr) return true;
    // Try numeric comparison
    const num = parseFloat(value);
    if (!isNaN(num)) {
      return num >= cond.value[0] && num <= cond.value[1];
    }
    return false;
  }

  return String(cond.value) === value;
}

// ════════════════════════════════════════════
// Build heatmap grid data
// ════════════════════════════════════════════
function buildHeatmapData(
  table: DecisionTableRule,
  xInput: RuleInput,
  yInput: RuleInput | null,
  analyze?: AnalyzeResponse
): HeatmapCell[][] {
  const xValues = extractDimensionValues(xInput, table.rules, analyze?.gaps, analyze?.overlaps);
  const yValues = yInput
    ? extractDimensionValues(yInput, table.rules, analyze?.gaps, analyze?.overlaps)
    : ['—'];

  if (xValues.length === 0) xValues.push('(空)');
  if (yValues.length === 0) yValues.push('(空)');

  // Build overlap lookup: "xVal|yVal" → boolean
  const overlapLookup = new Set<string>();
  if (analyze?.overlaps) {
    for (const ov of analyze.overlaps) {
      const xVal = ov.intersection[xInput.name];
      const yVal = yInput ? ov.intersection[yInput.name] : '—';
      if (xVal && yVal) overlapLookup.add(`${xVal}|${yVal}`);
    }
  }

  // Build gap lookup
  const gapLookup = new Set<string>();
  if (analyze?.gaps) {
    for (const gap of analyze.gaps) {
      const xVal = gap.conditions[xInput.name];
      const yVal = yInput ? gap.conditions[yInput.name] : '—';
      if (xVal && yVal) gapLookup.add(`${xVal}|${yVal}`);
    }
  }

  return yValues.map(yVal =>
    xValues.map(xVal => {
      // Find matching rules
      const matchingRules = table.rules.filter(rule => {
        const xCond = rule.conditions.find(c => c.field === xInput.name);
        if (!conditionMatchesValue(xCond, xVal)) return false;
        if (yInput) {
          const yCond = rule.conditions.find(c => c.field === yInput.name);
          if (!conditionMatchesValue(yCond, yVal)) return false;
        }
        return true;
      });

      const key = `${xVal}|${yVal}`;
      const ruleIds = matchingRules.map(r => r.ruleId);

      let status: CellStatus;
      if (overlapLookup.has(key)) {
        status = 'overlap';
      } else if (gapLookup.has(key)) {
        status = 'gap';
      } else if (matchingRules.length > 1) {
        status = 'overlap';
      } else if (matchingRules.length === 1) {
        status = 'covered';
      } else {
        // Check if any "anything" rule covers this
        const anythingRules = table.rules.filter(rule => {
          const xCond = rule.conditions.find(c => c.field === xInput.name);
          if (xCond?.operator !== 'anything' && !conditionMatchesValue(xCond, xVal)) return false;
          if (yInput) {
            const yCond = rule.conditions.find(c => c.field === yInput.name);
            if (yCond?.operator !== 'anything' && !conditionMatchesValue(yCond, yVal)) return false;
          }
          return true;
        });
        if (anythingRules.length > 0) {
          status = 'covered';
          ruleIds.push(...anythingRules.map(r => r.ruleId));
        } else {
          status = 'gap';
        }
      }

      const tooltip = ruleIds.length > 0
        ? `${xVal} × ${yVal}: ${statusLabels[status]} (${ruleIds.join(', ')})`
        : `${xVal} × ${yVal}: ${statusLabels[status]}`;

      return { xLabel: xVal, yLabel: yVal, status, ruleIds, tooltip };
    })
  );
}

// ════════════════════════════════════════════
// Tooltip component
// ════════════════════════════════════════════
function Tooltip({
  cell,
  x,
  y,
}: {
  cell: HeatmapCell;
  x: number;
  y: number;
}) {
  const colors = statusColors[cell.status];
  return (
    <div
      className="
        fixed z-50 pointer-events-none
        px-3 py-2 rounded-lg shadow-xl
        dark:bg-surface-3 bg-white
        border dark:border-border border-light-border
      "
      style={{ left: x + 12, top: y - 8 }}
    >
      <div className="flex items-center gap-2 mb-1">
        <span
          className="w-2.5 h-2.5 rounded-sm"
          style={{ backgroundColor: colors.fill }}
        />
        <span className="text-[11px] font-semibold dark:text-text-primary text-light-text-primary">
          {statusLabels[cell.status]}
        </span>
      </div>
      <p className="text-[10px] font-mono dark:text-text-tertiary text-light-text-tertiary">
        {cell.xLabel} × {cell.yLabel}
      </p>
      {cell.ruleIds.length > 0 && (
        <p className="text-[10px] font-mono mt-0.5 dark:text-text-secondary text-light-text-secondary">
          {cell.ruleIds.join(', ')}
        </p>
      )}
    </div>
  );
}

// ════════════════════════════════════════════
// Bar Chart (1D fallback)
// ════════════════════════════════════════════
function BarChart1D({
  table,
  input,
  analyze,
}: {
  table: DecisionTableRule;
  input: RuleInput;
  analyze?: AnalyzeResponse;
}) {
  const values = extractDimensionValues(input, table.rules, analyze?.gaps, analyze?.overlaps);
  const [hoveredIdx, setHoveredIdx] = useState<number | null>(null);

  const bars = values.map(val => {
    const matchingRules = table.rules.filter(rule => {
      const cond = rule.conditions.find(c => c.field === input.name);
      return conditionMatchesValue(cond, val) || cond?.operator === 'anything';
    });

    const isOverlap = analyze?.overlaps?.some(ov => ov.intersection[input.name] === val) ?? false;
    const isGap = analyze?.gaps?.some(g => g.conditions[input.name] === val) ?? false;

    let status: CellStatus;
    if (isOverlap || matchingRules.length > 1) status = 'overlap';
    else if (isGap || matchingRules.length === 0) status = 'gap';
    else status = 'covered';

    return { label: val, status, ruleIds: matchingRules.map(r => r.ruleId), count: matchingRules.length };
  });

  const maxCount = Math.max(...bars.map(b => b.count), 1);
  const barWidth = Math.max(24, Math.min(60, 600 / bars.length));

  return (
    <div className="overflow-x-auto">
      <svg
        viewBox={`0 0 ${bars.length * (barWidth + 8) + 40} 200`}
        className="w-full"
        style={{ maxHeight: 200 }}
      >
        {bars.map((bar, i) => {
          const x = 30 + i * (barWidth + 8);
          const barHeight = Math.max(4, (bar.count / maxCount) * 140);
          const y = 160 - barHeight;
          const colors = statusColors[bar.status];
          const isHovered = hoveredIdx === i;

          return (
            <g
              key={bar.label}
              onMouseEnter={() => setHoveredIdx(i)}
              onMouseLeave={() => setHoveredIdx(null)}
              className="cursor-pointer"
            >
              {/* Bar */}
              <rect
                x={x}
                y={y}
                width={barWidth}
                height={barHeight}
                rx={4}
                fill={colors.fillDark}
                stroke={colors.stroke}
                strokeWidth={isHovered ? 2 : 1}
                opacity={isHovered ? 1 : 0.8}
                style={{ transition: 'all 0.2s ease' }}
              />
              {/* Value on hover */}
              {isHovered && (
                <text
                  x={x + barWidth / 2}
                  y={y - 6}
                  textAnchor="middle"
                  className="text-[10px] font-mono"
                  fill="currentColor"
                >
                  {bar.count} rule{bar.count !== 1 ? 's' : ''}
                </text>
              )}
              {/* X-axis label */}
              <text
                x={x + barWidth / 2}
                y={178}
                textAnchor="middle"
                className="text-[9px] font-mono"
                fill="currentColor"
                opacity={0.5}
              >
                {bar.label.length > 8 ? bar.label.slice(0, 7) + '…' : bar.label}
              </text>
            </g>
          );
        })}
        {/* Y-axis label */}
        <text x={4} y={10} className="text-[9px]" fill="currentColor" opacity={0.4}>
          Rules
        </text>
      </svg>
    </div>
  );
}

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
export default function CoverageHeatmap({ envelope, analyze }: CoverageHeatmapProps) {
  const { state, dispatch } = useDashboard();
  const table = useMemo(() => parseDecisionTable(envelope.rule), [envelope.rule]);
  const containerRef = useRef<HTMLDivElement>(null);

  // Dimension selection (for >2 inputs)
  const [xDimIdx, setXDimIdx] = useState(0);
  const [yDimIdx, setYDimIdx] = useState(1);

  // Tooltip
  const [tooltip, setTooltip] = useState<{ cell: HeatmapCell; x: number; y: number } | null>(null);

  // Hover tracking for cell highlight
  const [hoveredCellKey, setHoveredCellKey] = useState<string | null>(null);

  const inputs = table?.inputs ?? [];
  const is1D = inputs.length === 1;
  const safeXDimIdx = Math.min(xDimIdx, Math.max(inputs.length - 1, 0));
  const fallbackYDimIdx = inputs.length > 1 ? 1 : 0;
  const candidateYDimIdx = Math.min(yDimIdx, Math.max(inputs.length - 1, 0));
  const safeYDimIdx = !is1D && candidateYDimIdx === safeXDimIdx
    ? inputs.findIndex((_, i) => i !== safeXDimIdx)
    : candidateYDimIdx;
  const xInput = inputs[safeXDimIdx] ?? inputs[0] ?? null;
  const yInput = is1D
    ? null
    : inputs[safeYDimIdx >= 0 ? safeYDimIdx : fallbackYDimIdx] ?? inputs[1] ?? null;

  // Build grid data
  const grid = useMemo(
    () => (table && xInput ? buildHeatmapData(table, xInput, yInput, analyze) : []),
    [table, xInput, yInput, analyze]
  );

  if (!table || inputs.length === 0 || !xInput) {
    const isDecisionTree = envelope.ruleType === 'DecisionTree';
    return (
      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={SPRING}
        className="
          rounded-xl border p-8 text-center
          dark:bg-surface-1 dark:border-border bg-white border-light-border
        "
      >
        <p className="text-sm dark:text-text-tertiary text-light-text-tertiary">
          {isDecisionTree
            ? '決策樹型態不適用熱圖檢視，請至「規則表」分頁查看互動式決策樹'
            : '無輸入欄位，無法生成覆蓋率熱圖'}
        </p>
      </motion.div>
    );
  }

  // Dimensions for SVG
  const xLabels = grid[0]?.map(c => c.xLabel) ?? [];
  const yLabels = grid.map(row => row[0]?.yLabel ?? '');

  const cellSize = Math.max(28, Math.min(56, 600 / Math.max(xLabels.length, 1)));
  const labelPad = 80;
  const topPad = 40;
  const svgWidth = labelPad + xLabels.length * (cellSize + 3) + 20;
  const svgHeight = topPad + yLabels.length * (cellSize + 3) + 30;

  // Summary stats
  const allCells = grid.flat();
  const coveredCount = allCells.filter(c => c.status === 'covered').length;
  const gapCount = allCells.filter(c => c.status === 'gap').length;
  const overlapCount = allCells.filter(c => c.status === 'overlap').length;
  const totalCells = allCells.length;

  // External highlight from GapAnalysis
  const externalHighlight = state.highlightedHeatmapCell;

  // Cell click handler
  const handleCellClick = (cell: HeatmapCell) => {
    if (cell.status === 'overlap' && cell.ruleIds.length > 0) {
      dispatch(actions.navigateAndHighlight('validation', cell.ruleIds, 'heatmap', null));
    } else if (cell.status === 'covered' && cell.ruleIds.length > 0) {
      dispatch(actions.highlightRules(cell.ruleIds, 'heatmap'));
    }
  };

  return (
    <motion.div
      ref={containerRef}
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={SPRING}
      className="
        rounded-xl border overflow-hidden
        dark:bg-surface-1 dark:border-border bg-white border-light-border
      "
    >
      {/* ── Header ── */}
      <div className="
        flex items-center justify-between px-4 py-3
        border-b dark:border-border border-light-border
      ">
        <div className="flex items-center gap-2.5">
          <div className="
            w-7 h-7 rounded-md flex items-center justify-center
            bg-gradient-to-br from-emerald-500/20 to-amber-500/20
          ">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round">
              <rect x="1" y="1" width="3.5" height="3.5" rx="0.5" className="text-success" />
              <rect x="5.25" y="1" width="3.5" height="3.5" rx="0.5" className="text-success" />
              <rect x="9.5" y="1" width="3.5" height="3.5" rx="0.5" className="text-danger" />
              <rect x="1" y="5.25" width="3.5" height="3.5" rx="0.5" className="text-warning" />
              <rect x="5.25" y="5.25" width="3.5" height="3.5" rx="0.5" className="text-success" />
              <rect x="9.5" y="5.25" width="3.5" height="3.5" rx="0.5" className="text-success" />
              <rect x="1" y="9.5" width="3.5" height="3.5" rx="0.5" className="text-success" />
              <rect x="5.25" y="9.5" width="3.5" height="3.5" rx="0.5" className="text-danger" />
              <rect x="9.5" y="9.5" width="3.5" height="3.5" rx="0.5" className="text-success" />
            </svg>
          </div>
          <div>
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
              覆蓋率熱力圖
            </p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              {is1D ? `1 個維度` : `橫軸: ${xInput.name} · 縱軸: ${yInput?.name ?? '—'}`}
              {' — '}每個格子代表一種條件組合，顏色顯示該組合是否有對應規則
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          {/* Dimension selector (for >2 inputs) */}
          {inputs.length > 2 && (
            <div className="flex items-center gap-2">
              <label className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">X:</label>
              <select
                value={safeXDimIdx}
                onChange={e => {
                  const newIdx = Number(e.target.value);
                  setXDimIdx(newIdx);
                  if (newIdx === safeYDimIdx) {
                    setYDimIdx(inputs.findIndex((_, i) => i !== newIdx));
                  }
                }}
                className="
                  text-[10px] font-mono px-1.5 py-0.5 rounded
                  dark:bg-surface-3 dark:text-text-secondary dark:border-border
                  bg-light-surface-3 text-light-text-secondary border-light-border
                  border outline-none
                "
              >
                {inputs.map((inp, i) => (
                  <option key={i} value={i}>{inp.name}</option>
                ))}
              </select>

              <label className="text-[10px] dark:text-text-tertiary text-light-text-tertiary ml-2">Y:</label>
              <select
                value={safeYDimIdx >= 0 ? safeYDimIdx : fallbackYDimIdx}
                onChange={e => {
                  const newIdx = Number(e.target.value);
                  setYDimIdx(newIdx);
                  if (newIdx === safeXDimIdx) {
                    setXDimIdx(inputs.findIndex((_, i) => i !== newIdx));
                  }
                }}
                className="
                  text-[10px] font-mono px-1.5 py-0.5 rounded
                  dark:bg-surface-3 dark:text-text-secondary dark:border-border
                  bg-light-surface-3 text-light-text-secondary border-light-border
                  border outline-none
                "
              >
                {inputs.map((inp, i) => (
                  <option key={i} value={i}>{inp.name}</option>
                ))}
              </select>
            </div>
          )}

          {/* Mini coverage ring */}
          <MiniCoverageRing covered={coveredCount} total={totalCells} />
        </div>
      </div>

      {/* ── Chart Area ── */}
      <div className="p-4">
        {is1D ? (
          <BarChart1D table={table} input={xInput} analyze={analyze} />
        ) : (
          <div className="overflow-x-auto relative">
            <svg
              viewBox={`0 0 ${svgWidth} ${svgHeight}`}
              className="w-full dark:text-text-primary text-light-text-primary"
              style={{ maxHeight: 500, minHeight: 200 }}
            >
              {/* X-axis labels */}
              {xLabels.map((label, i) => (
                <text
                  key={`x-${i}`}
                  x={labelPad + i * (cellSize + 3) + cellSize / 2}
                  y={topPad - 8}
                  textAnchor="middle"
                  className="text-[9px] font-mono"
                  fill="currentColor"
                  opacity={0.45}
                >
                  {label.length > 10 ? label.slice(0, 9) + '…' : label}
                </text>
              ))}

              {/* Y-axis labels */}
              {yLabels.map((label, j) => (
                <text
                  key={`y-${j}`}
                  x={labelPad - 8}
                  y={topPad + j * (cellSize + 3) + cellSize / 2 + 3}
                  textAnchor="end"
                  className="text-[9px] font-mono"
                  fill="currentColor"
                  opacity={0.45}
                >
                  {label.length > 10 ? label.slice(0, 9) + '…' : label}
                </text>
              ))}

              {/* Grid cells */}
              {grid.map((row, rowIdx) =>
                row.map((cell, colIdx) => {
                  const cx = labelPad + colIdx * (cellSize + 3);
                  const cy = topPad + rowIdx * (cellSize + 3);
                  const colors = statusColors[cell.status];
                  const cellKey = `${cell.xLabel}|${cell.yLabel}`;
                  const isHovered = hoveredCellKey === cellKey;
                  const isExternallyHighlighted =
                    externalHighlight !== null &&
                    externalHighlight.x === cell.xLabel &&
                    externalHighlight.y === cell.yLabel;

                  return (
                    <g
                      key={`${rowIdx}-${colIdx}`}
                      onMouseEnter={(e) => {
                        setHoveredCellKey(cellKey);
                        setTooltip({ cell, x: e.clientX, y: e.clientY });
                      }}
                      onMouseMove={(e) => {
                        setTooltip({ cell, x: e.clientX, y: e.clientY });
                      }}
                      onMouseLeave={() => {
                        setHoveredCellKey(null);
                        setTooltip(null);
                      }}
                      onClick={() => handleCellClick(cell)}
                      className="cursor-pointer"
                    >
                      <rect
                        x={cx}
                        y={cy}
                        width={cellSize}
                        height={cellSize}
                        rx={4}
                        fill={colors.fillDark}
                        stroke={colors.stroke}
                        strokeWidth={isHovered ? 2 : 1}
                        opacity={hoveredCellKey !== null && !isHovered ? 0.6 : 1}
                        style={{ transition: 'transform 0.2s ease, opacity 0.2s ease, stroke-width 0.2s ease' }}
                      />
                      {/* Rule count inside cell */}
                      {cell.ruleIds.length > 0 && cellSize >= 32 && (
                        <text
                          x={cx + cellSize / 2}
                          y={cy + cellSize / 2 + 3}
                          textAnchor="middle"
                          className="text-[9px] font-mono font-semibold"
                          fill={colors.fill}
                          opacity={0.8}
                        >
                          {cell.ruleIds.length}
                        </text>
                      )}

                      {/* External highlight ring (from GapAnalysis) */}
                      {isExternallyHighlighted && (
                        <rect
                          x={cx - 2}
                          y={cy - 2}
                          width={cellSize + 4}
                          height={cellSize + 4}
                          rx={6}
                          fill="none"
                          stroke="#00e5bf"
                          strokeWidth={2}
                          strokeDasharray="4 2"
                        >
                          <animate
                            attributeName="stroke-dashoffset"
                            from="0"
                            to="12"
                            dur="1s"
                            repeatCount="indefinite"
                          />
                        </rect>
                      )}
                    </g>
                  );
                })
              )}

              {/* Axis titles */}
              <text
                x={labelPad + (xLabels.length * (cellSize + 3)) / 2}
                y={svgHeight - 4}
                textAnchor="middle"
                className="text-[10px] font-medium"
                fill="currentColor"
                opacity={0.35}
              >
                {xInput.name}
              </text>
              <text
                x={8}
                y={topPad + (yLabels.length * (cellSize + 3)) / 2}
                textAnchor="middle"
                className="text-[10px] font-medium"
                fill="currentColor"
                opacity={0.35}
                transform={`rotate(-90, 8, ${topPad + (yLabels.length * (cellSize + 3)) / 2})`}
              >
                {yInput?.name ?? ''}
              </text>
            </svg>

            {/* Tooltip Portal */}
            {tooltip && (
              <Tooltip cell={tooltip.cell} x={tooltip.x} y={tooltip.y} />
            )}
          </div>
        )}
      </div>

      {/* ── Footer: Legend + Stats ── */}
      <div className="
        flex items-center justify-between px-4 py-2.5
        border-t dark:border-border border-light-border
        dark:bg-surface-2/50 bg-light-surface-2/50
      ">
        {/* Legend */}
        <div className="flex items-center gap-4">
          {(['covered', 'gap', 'overlap'] as CellStatus[]).map(status => (
            <span key={status} className="flex items-center gap-1.5 text-[10px]">
              <span
                className="w-3 h-3 rounded-sm"
                style={{ backgroundColor: statusColors[status].fillDark, border: `1px solid ${statusColors[status].stroke}` }}
              />
              <span className="dark:text-text-tertiary text-light-text-tertiary">
                {statusLabels[status]}
              </span>
            </span>
          ))}
        </div>

        {/* Stats */}
        <div className="flex items-center gap-3 text-[10px] dark:text-text-tertiary text-light-text-tertiary">
          <span className="font-mono">
            <span className="text-success font-semibold">{coveredCount}</span> 已覆蓋
          </span>
          <span>·</span>
          <span className="font-mono">
            <span className="text-danger font-semibold">{gapCount}</span> 缺口
          </span>
          <span>·</span>
          <span className="font-mono">
            <span className="text-warning font-semibold">{overlapCount}</span> 衝突
          </span>
          <span className="opacity-50">/ 共 {totalCells} 種組合</span>
        </div>
      </div>
    </motion.div>
  );
}
