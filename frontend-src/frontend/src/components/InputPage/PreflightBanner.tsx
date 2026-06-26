import type { PreflightReport, LintFinding } from '../../types';

interface Props {
  report: PreflightReport | null;
  loading: boolean;
}

const SEVERITY_STYLE: Record<LintFinding['severity'], { dot: string; text: string; label: string }> = {
  ERROR: { dot: 'bg-red-500', text: 'text-red-600 dark:text-red-400', label: '錯誤' },
  WARNING: { dot: 'bg-amber-500', text: 'text-amber-600 dark:text-amber-400', label: '警告' },
  INFO: { dot: 'bg-blue-500', text: 'text-blue-600 dark:text-blue-400', label: '提示' },
};

// 紅黃綠燈：有 ERROR → 紅、僅 WARNING/INFO → 黃、全乾淨 → 綠
function lightOf(report: PreflightReport): { color: string; ring: string; text: string; title: string } {
  if (report.hasError) {
    return { color: 'bg-red-500', ring: 'ring-red-500/20', text: 'text-red-600 dark:text-red-400', title: '建議先修正再生成' };
  }
  if (report.findings.length > 0) {
    return { color: 'bg-amber-500', ring: 'ring-amber-500/20', text: 'text-amber-600 dark:text-amber-400', title: '可以生成，但有可留意之處' };
  }
  return { color: 'bg-emerald-500', ring: 'ring-emerald-500/20', text: 'text-emerald-600 dark:text-emerald-400', title: '輸入看起來沒問題' };
}

export default function PreflightBanner({ report, loading }: Props) {
  if (loading) {
    return (
      <div className="mt-3 p-3 rounded-xl border animate-pulse
        dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div className="flex items-center gap-2">
          <div className="w-3 h-3 rounded-full dark:bg-surface-3 bg-light-surface-3" />
          <div className="h-3 w-32 rounded dark:bg-surface-3 bg-light-surface-3" />
        </div>
      </div>
    );
  }

  if (!report) return null;

  const light = lightOf(report);

  return (
    <div className={`mt-3 p-4 rounded-xl border space-y-2.5 ring-1 ${light.ring}
      dark:bg-surface-1 dark:border-border bg-white border-light-border`}>

      {/* 紅黃綠燈 + 完整度 */}
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className={`w-3 h-3 rounded-full ${light.color}`} />
          <span className={`text-xs font-semibold ${light.text}`}>{light.title}</span>
        </div>
        <span className="text-[11px] font-medium tabular-nums dark:text-text-tertiary text-light-text-tertiary">
          完整度 {Math.round(report.qualityScore * 100)}%
        </span>
      </div>

      {/* findings 清單 */}
      {report.findings.length > 0 && (
        <ul className="space-y-1">
          {report.findings.map((f, i) => {
            const s = SEVERITY_STYLE[f.severity];
            return (
              <li key={i} className="flex items-start gap-2 text-[11px] leading-relaxed">
                <span className={`shrink-0 mt-1 w-1.5 h-1.5 rounded-full ${s.dot}`} />
                <span className="dark:text-text-secondary text-light-text-secondary">
                  <span className={`font-semibold ${s.text}`}>[{s.label}]</span>{' '}
                  {f.message}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
