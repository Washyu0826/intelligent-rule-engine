import { useMemo, memo } from 'react';
import { motion } from 'framer-motion';

/**
 * QualityRadarChart — 5 維度品質雷達圖（純 SVG）
 *
 * 維度：完整性、覆蓋率、無衝突、規則數適當性、深度適當性
 */

interface RadarDimension {
  label: string;
  value: number; // 0-1
  color: string;
}

interface QualityRadarChartProps {
  completeness: number;   // 0 or 1
  coverageRate: number;   // 0-1
  noConflict: number;     // 0 or 1
  ruleCountScore: number; // 0-1
  depthScore: number;     // 0-1
  size?: number;
}

const QualityRadarChart = memo(function QualityRadarChart({
  completeness,
  coverageRate,
  noConflict,
  ruleCountScore,
  depthScore,
  size = 200,
}: QualityRadarChartProps) {
  const dimensions: RadarDimension[] = useMemo(() => [
    { label: '完整性', value: completeness, color: '#10b981' },
    { label: '覆蓋率', value: coverageRate, color: '#3b82f6' },
    { label: '無衝突', value: noConflict, color: '#f59e0b' },
    { label: '規則數', value: ruleCountScore, color: '#8b5cf6' },
    { label: '深度', value: depthScore, color: '#06b6d4' },
  ], [completeness, coverageRate, noConflict, ruleCountScore, depthScore]);

  const cx = size / 2;
  const cy = size / 2;
  const radius = size * 0.35;
  const n = dimensions.length;
  const angleStep = (2 * Math.PI) / n;

  // Grid rings
  const rings = [0.25, 0.5, 0.75, 1.0];

  // Axis endpoints
  const axes = dimensions.map((_, i) => {
    const angle = -Math.PI / 2 + i * angleStep;
    return {
      x: cx + radius * Math.cos(angle),
      y: cy + radius * Math.sin(angle),
    };
  });

  // Data polygon points
  const dataPoints = dimensions.map((d, i) => {
    const angle = -Math.PI / 2 + i * angleStep;
    const r = radius * d.value;
    return { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) };
  });
  // Label positions (slightly outside)
  const labelPositions = dimensions.map((_, i) => {
    const angle = -Math.PI / 2 + i * angleStep;
    const lr = radius + 24;
    return { x: cx + lr * Math.cos(angle), y: cy + lr * Math.sin(angle) };
  });

  // Overall score
  const overallScore = Math.round(dimensions.reduce((sum, d) => sum + d.value, 0) / n * 100);

  return (
    <div className="flex flex-col items-center">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        {/* Grid rings */}
        {rings.map((r) => (
          <polygon
            key={r}
            points={Array.from({ length: n }, (_, i) => {
              const angle = -Math.PI / 2 + i * angleStep;
              return `${cx + radius * r * Math.cos(angle)},${cy + radius * r * Math.sin(angle)}`;
            }).join(' ')}
            fill="none"
            stroke="currentColor"
            strokeWidth="0.5"
            className="dark:text-border/30 text-light-border/50"
          />
        ))}

        {/* Axes */}
        {axes.map((a, i) => (
          <line
            key={i}
            x1={cx} y1={cy}
            x2={a.x} y2={a.y}
            stroke="currentColor"
            strokeWidth="0.5"
            className="dark:text-border/30 text-light-border/50"
          />
        ))}

        {/* Data polygon (animated) */}
        <motion.polygon
          initial={{ points: Array(n).fill(`${cx},${cy}`).join(' ') }}
          animate={{ points: dataPoints.map(p => `${p.x},${p.y}`).join(' ') }}
          transition={{ duration: 0.8, ease: 'easeOut' }}
          fill="currentColor"
          fillOpacity={0.15}
          stroke="currentColor"
          strokeWidth="2"
          className="dark:text-accent text-accent"
        />

        {/* Data points */}
        {dataPoints.map((p, i) => (
          <motion.circle
            key={i}
            initial={{ cx, cy: cy, r: 0 }}
            animate={{ cx: p.x, cy: p.y, r: 3.5 }}
            transition={{ duration: 0.8, delay: i * 0.1, ease: 'easeOut' }}
            fill={dimensions[i].color}
            stroke="white"
            strokeWidth="1.5"
          />
        ))}

        {/* Labels */}
        {labelPositions.map((pos, i) => (
          <text
            key={i}
            x={pos.x}
            y={pos.y}
            textAnchor="middle"
            dominantBaseline="middle"
            className="text-[10px] font-medium dark:fill-text-secondary fill-light-text-secondary"
          >
            {dimensions[i].label}
          </text>
        ))}

        {/* Center score */}
        <text
          x={cx} y={cy - 6}
          textAnchor="middle"
          className="text-xl font-bold dark:fill-text-primary fill-light-text-primary"
        >
          {overallScore}
        </text>
        <text
          x={cx} y={cy + 10}
          textAnchor="middle"
          className="text-[9px] dark:fill-text-tertiary fill-light-text-tertiary"
        >
          品質分數
        </text>
      </svg>

      {/* Dimension bars */}
      <div className="w-full mt-2 space-y-1">
        {dimensions.map((d) => (
          <div key={d.label} className="flex items-center gap-2 text-[10px]">
            <span className="w-10 text-right dark:text-text-tertiary text-light-text-tertiary">{d.label}</span>
            <div className="flex-1 h-1.5 rounded-full dark:bg-surface-3 bg-gray-200 overflow-hidden">
              <motion.div
                initial={{ width: 0 }}
                animate={{ width: `${d.value * 100}%` }}
                transition={{ duration: 0.6, delay: 0.3 }}
                className="h-full rounded-full"
                style={{ backgroundColor: d.color }}
              />
            </div>
            <span className="w-8 tabular-nums dark:text-text-secondary text-light-text-secondary">
              {Math.round(d.value * 100)}%
            </span>
          </div>
        ))}
      </div>
    </div>
  );
});

export default QualityRadarChart;
