import type { RecommendResponse } from '../../types';

const TYPE_LABEL: Record<string, string> = {
  DecisionTable: '決策表',
  DecisionTree: '決策樹',
  ScoreCard: '評分卡',
};

interface Props {
  recommend: RecommendResponse;
  onPick: (ruleType: string) => void;
  onCancel: () => void;
}

export default function ClarifyDialog({ recommend, onPick, onCancel }: Props) {
  const options = recommend.clarifyOptions ?? [];
  const suggested = recommend.recommendedRuleType;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="clarify-title"
      className="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/40"
    >
      <div className="w-full max-w-lg rounded-xl border p-6 space-y-4 shadow-xl
        dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div>
          <p className="text-[10px] uppercase tracking-[0.08em] font-medium dark:text-text-tertiary text-light-text-tertiary">
            生成前先確認一件事
          </p>
          <h2 id="clarify-title" className="mt-1 text-base font-bold dark:text-text-primary text-light-text-primary">
            {recommend.clarifyingQuestion ?? '這些條件是同時比對，還是有先後順序？'}
          </h2>
          <p className="mt-1 text-xs dark:text-text-secondary text-light-text-secondary">
            從描述看不出來，所以先問你。選一個，系統就依此生成。
          </p>
        </div>

        <div className="grid gap-2">
          {options.map((o) => (
            <button
              key={o.ruleType}
              type="button"
              onClick={() => onPick(o.ruleType)}
              className="text-left px-4 py-3 rounded-lg border cursor-pointer transition-colors
                dark:border-border dark:hover:bg-surface-2 border-light-border hover:bg-light-surface-2"
            >
              <span className="text-sm font-semibold dark:text-text-primary text-light-text-primary">{o.label}</span>
              <span className="ml-2 text-[11px] dark:text-text-tertiary text-light-text-tertiary">
                → {TYPE_LABEL[o.ruleType] ?? o.ruleType}
                {o.ruleType === suggested ? '（系統預設）' : ''}
              </span>
            </button>
          ))}
        </div>

        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="px-3 py-1.5 rounded-md text-xs cursor-pointer dark:text-text-secondary text-light-text-secondary"
          >
            取消
          </button>
          <button
            type="button"
            onClick={() => onPick(suggested)}
            className="px-3 py-1.5 rounded-md text-xs font-semibold cursor-pointer text-white bg-[var(--color-group-green-600)]"
          >
            就用{TYPE_LABEL[suggested] ?? suggested}
          </button>
        </div>
      </div>
    </div>
  );
}
