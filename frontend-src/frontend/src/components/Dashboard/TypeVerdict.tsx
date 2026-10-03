import type { RecommendResponse } from '../../types';

const TYPE_LABEL: Record<string, string> = {
  DecisionTable: '決策表',
  DecisionTree: '決策樹',
  ScoreCard: '評分卡',
};

const METHOD_LABEL: Record<string, string> = {
  structure: '依規格結構判定',
  keyword: '依用詞判定',
  llm: '由模型判定',
  default: '沒有明確訊號，先用預設',
};

const RETYPE_ORDER = ['DecisionTable', 'DecisionTree', 'ScoreCard'];

interface Props {
  recommend?: RecommendResponse;
  ruleType: string;
  onRetype?: (ruleType: string) => void;
}

export default function TypeVerdict({ recommend, ruleType, onRetype }: Props) {
  const label = (t: string) => TYPE_LABEL[t] ?? t;
  const userOverrode = !!recommend && recommend.recommendedRuleType !== ruleType;
  const confidencePct = recommend ? Math.round(recommend.confidence * 100) : null;
  const low = recommend ? recommend.confidence < 0.55 : false;

  return (
    <div className={`rounded-xl border p-4 flex flex-col gap-2
      dark:bg-surface-1 dark:border-border bg-white border-light-border
      ${low ? 'border-l-4 border-l-amber-500' : 'border-l-4 border-l-[var(--color-group-green-600)]'}`}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="text-[10px] uppercase tracking-[0.08em] font-medium dark:text-text-tertiary text-light-text-tertiary">
          規則型態判定
        </span>
        <span className="text-sm font-bold dark:text-text-primary text-light-text-primary">
          {userOverrode ? `你指定為${label(ruleType)}` : label(ruleType)}
        </span>
        {recommend && (
          <span className="text-[11px] tabular-nums dark:text-text-secondary text-light-text-secondary">
            {userOverrode
              ? `系統原判 ${label(recommend.recommendedRuleType)}，信心 ${confidencePct}%`
              : `信心 ${confidencePct}% · ${METHOD_LABEL[recommend.method ?? ''] ?? ''}`}
          </span>
        )}
      </div>
      {recommend?.reason && (
        <p className="text-sm leading-relaxed dark:text-text-secondary text-light-text-secondary">
          {recommend.reason}
        </p>
      )}
      {onRetype && (
        <div className="flex flex-wrap items-center gap-2 pt-1">
          <span className="text-[11px] dark:text-text-tertiary text-light-text-tertiary">判得不對？</span>
          {RETYPE_ORDER.filter((t) => t !== ruleType).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => onRetype(t)}
              className="px-2.5 py-1 rounded-md text-[11px] font-medium cursor-pointer transition-colors
                dark:bg-surface-3 dark:hover:bg-surface-4 dark:text-text-primary
                bg-light-surface-3 hover:bg-light-surface-4 text-light-text-primary"
            >
              改用{label(t)}重新生成
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
