import { btnGhost, card, textPrimary, textSecondary, textTertiary } from './ui';

export interface GapItem {
  message: string;
  conditions?: Record<string, string>;
  volumeRatio?: number;
}

export interface AnalysisLike {
  coverageRate: number;
  gapCount: number;
  overlapCount: number;
  gaps: GapItem[];
  overlaps: string[];
}

interface Props {
  analysis?: AnalysisLike;
  title?: string;
  /** 提供時，每個有條件描述的缺口旁會出現「補成案例」 */
  onFillGap?: (conditions: Record<string, string>) => void;
  busy?: boolean;
}

export default function ImpactPanel({ analysis, title = '缺口與重疊', onFillGap, busy }: Props) {
  if (!analysis) {
    return (
      <div className={`${card} p-4 text-xs ${textTertiary}`}>
        {title}：此規則型態沒有可用的缺口／重疊分析
      </div>
    );
  }
  const clean = analysis.gapCount === 0 && analysis.overlapCount === 0;
  return (
    <div className={`${card} p-4 space-y-2`}>
      <div className="flex items-center justify-between">
        <span className={`text-sm font-semibold ${textPrimary}`}>{title}</span>
        <span className={`text-xs tabular-nums ${textSecondary}`}>
          涵蓋率 {(analysis.coverageRate * 100).toFixed(1)}%
        </span>
      </div>
      <div className="flex gap-3 text-xs">
        <span className={analysis.gapCount ? 'text-red-600 font-semibold' : 'text-emerald-600'}>
          缺口 {analysis.gapCount}
        </span>
        <span className={analysis.overlapCount ? 'text-amber-600 font-semibold' : 'text-emerald-600'}>
          重疊 {analysis.overlapCount}
        </span>
        {clean && <span className="text-emerald-600">沒有發現缺口或重疊</span>}
      </div>
      {analysis.gaps.length > 0 && (
        <ul className={`text-xs list-disc pl-5 space-y-1 ${textSecondary}`}>
          {analysis.gaps.slice(0, 5).map((g, i) => (
            <li key={`g${i}`} className="flex flex-wrap items-center gap-2">
              <span>缺口：{g.message}</span>
              {onFillGap && g.conditions && Object.keys(g.conditions).length > 0 && (
                <button
                  type="button"
                  className={`${btnGhost} !py-0.5 !px-2 !text-[11px]`}
                  disabled={busy}
                  onClick={() => onFillGap(g.conditions!)}
                >
                  補成案例
                </button>
              )}
            </li>
          ))}
          {analysis.gaps.length > 5 && <li>…另有 {analysis.gaps.length - 5} 項</li>}
        </ul>
      )}
      {analysis.overlaps.length > 0 && (
        <ul className={`text-xs list-disc pl-5 space-y-0.5 ${textSecondary}`}>
          {analysis.overlaps.slice(0, 5).map((o, i) => <li key={`o${i}`}>重疊：{o}</li>)}
          {analysis.overlaps.length > 5 && <li>…另有 {analysis.overlaps.length - 5} 項</li>}
        </ul>
      )}
    </div>
  );
}
