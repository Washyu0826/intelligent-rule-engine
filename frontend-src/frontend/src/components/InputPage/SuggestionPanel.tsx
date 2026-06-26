import type { SuggestResponse } from '../../types';

interface Props {
  suggestion: SuggestResponse | null;
  loading: boolean;
  onInsertDimension?: (text: string) => void;
}

const DOMAIN_LABELS: Record<string, string> = {
  'insurance-underwriting': '保險核保',
  'insurance-pricing': '保險費率',
  'insurance-frontend-validation': '核保前端檢核',
  'credit-scoring': '信用評分',
  'unknown': '未識別領域',
};

function QualityBar({ score }: { score: number }) {
  const pct = Math.round(score * 100);
  const color = pct >= 80 ? 'bg-emerald-500' : pct >= 50 ? 'bg-amber-500' : 'bg-red-500';
  const textColor = pct >= 80 ? 'text-emerald-400' : pct >= 50 ? 'text-amber-400' : 'text-red-400';
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-2 rounded-full dark:bg-surface-3 bg-light-surface-3 overflow-hidden">
        <div className={`h-full rounded-full transition-all duration-500 ${color}`} style={{ width: `${pct}%` }} />
      </div>
      <span className={`text-xs font-bold tabular-nums ${textColor}`}>{pct}%</span>
    </div>
  );
}

export default function SuggestionPanel({ suggestion, loading, onInsertDimension }: Props) {
  if (loading) {
    return (
      <div className="mt-3 p-3 rounded-xl border animate-pulse
        dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div className="h-3 w-24 rounded dark:bg-surface-3 bg-light-surface-3 mb-2" />
        <div className="h-2 w-full rounded dark:bg-surface-3 bg-light-surface-3 mb-2" />
        <div className="flex gap-2">
          <div className="h-6 w-16 rounded-lg dark:bg-surface-3 bg-light-surface-3" />
          <div className="h-6 w-16 rounded-lg dark:bg-surface-3 bg-light-surface-3" />
          <div className="h-6 w-16 rounded-lg dark:bg-surface-3 bg-light-surface-3" />
        </div>
      </div>
    );
  }

  if (!suggestion) return null;

  const { detectedInputs, detectedOutputs, missingDimensions, qualityScore, suggestions, estimatedRuleCount, detectedDomain } = suggestion;

  return (
    <div className="mt-3 p-4 rounded-xl border space-y-3
      dark:bg-surface-1 dark:border-border bg-white border-light-border">

      {/* Header row: domain + quality + rule count */}
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="px-2 py-0.5 rounded-md text-[11px] font-semibold
            bg-accent/15 text-accent">
            {DOMAIN_LABELS[detectedDomain] || detectedDomain}
          </span>
          <span className="px-2 py-0.5 rounded-md text-[11px] font-medium
            dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary">
            ~{estimatedRuleCount} 條規則
          </span>
        </div>
        <div className="w-32">
          <QualityBar score={qualityScore} />
        </div>
      </div>

      {/* Detected inputs */}
      {detectedInputs.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-[0.08em] mb-1.5
            dark:text-text-tertiary text-light-text-tertiary font-medium">
            已偵測輸入 ({detectedInputs.length})
          </p>
          <div className="flex flex-wrap gap-1.5">
            {detectedInputs.map((d) => (
              <span key={d.englishName} className="px-2 py-0.5 rounded-md text-[11px] font-medium
                bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                title={`${d.englishName} (${d.typeRef}) ${d.values.length > 0 ? `: ${d.values.join(', ')}` : ''}`}>
                {d.chineseName}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Detected outputs */}
      {detectedOutputs.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-[0.08em] mb-1.5
            dark:text-text-tertiary text-light-text-tertiary font-medium">
            已偵測輸出 ({detectedOutputs.length})
          </p>
          <div className="flex flex-wrap gap-1.5">
            {detectedOutputs.map((d) => (
              <span key={d.englishName} className="px-2 py-0.5 rounded-md text-[11px] font-medium
                bg-blue-500/15 text-blue-600 dark:text-blue-400">
                {d.chineseName}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Missing dimensions */}
      {missingDimensions.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-[0.08em] mb-1.5
            dark:text-text-tertiary text-light-text-tertiary font-medium">
            建議補充 ({missingDimensions.length})
          </p>
          <div className="flex flex-wrap gap-1.5">
            {missingDimensions.map((d) => (
              <button key={d.englishName}
                onClick={() => onInsertDimension?.(`、${d.name}（值1／值2）`)}
                className="px-2 py-0.5 rounded-md text-[11px] font-medium cursor-pointer
                  bg-amber-500/15 text-amber-600 dark:text-amber-400
                  hover:bg-amber-500/25 transition-colors"
                title={d.reason}>
                + {d.name}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Suggestions */}
      {suggestions.length > 0 && (
        <div className="pt-1">
          {suggestions.map((s, i) => (
            <p key={i} className="text-[11px] leading-relaxed
              dark:text-text-secondary text-light-text-secondary">
              {s}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
