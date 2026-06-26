import type { GenerationStep } from '../../types';

interface Props {
  loading: boolean;
  steps: GenerationStep[];
  disabled?: boolean;
  onClick: () => void;
  ruleType?: string;
  mode?: 'natural' | 'json';
}

const statusColor: Record<GenerationStep['status'], string> = {
  pending: 'dark:bg-surface-4 bg-light-surface-3',
  active: 'bg-accent/50',
  done: 'bg-accent',
  error: 'bg-danger',
};

export default function GenerateButton({ loading, steps, disabled, onClick, ruleType, mode }: Props) {
  const buttonLabel = mode === 'json'
    ? '驗證並分析模型'
    : ruleType === 'DecisionTable'
      ? '整理為決策表 RuleEnvelope'
      : ruleType === 'DecisionTree'
        ? '整理為決策樹 RuleEnvelope'
        : '整理為 RuleEnvelope';
  const activeStep = steps.find((s) => s.status === 'active');
  const doneCount = steps.filter((s) => s.status === 'done').length;
  const activeCount = steps.filter((s) => s.status === 'active').length;
  const progressCount = doneCount + activeCount;

  // v3.11.0: 全部步驟 done 的 transition state（到 dashboard 渲染前的空檔）
  const allDone = loading && steps.length > 0 && doneCount === steps.length;

  return (
    <div className="space-y-3">
      <button
        onClick={onClick}
        disabled={disabled || loading}
        className={`
          relative w-full py-3.5 rounded-xl text-sm font-semibold
          transition-all duration-300 cursor-pointer
          disabled:cursor-not-allowed overflow-hidden
          ${allDone ? 'bg-success text-white' : 'bg-accent text-surface-0'}
          hover:brightness-110 disabled:opacity-60 active:scale-[0.98]
        `}
      >
        {/* Shimmer effect when loading (non-done) */}
        {loading && !allDone && (
          <div className="absolute inset-0 overflow-hidden">
            <div
              className="absolute inset-0 -translate-x-full animate-[shimmer_2s_infinite]"
              style={{
                background:
                  'linear-gradient(90deg, transparent, rgba(255,255,255,0.15), transparent)',
              }}
            />
          </div>
        )}

        <span className="relative z-10 flex items-center justify-center gap-2">
          {allDone ? (
            <>
              {/* 完成過渡：綠色勾勾 + 中文提示 */}
              <svg className="w-4 h-4 animate-fade-in-up" viewBox="0 0 16 16" fill="none"
                   stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M3 8.5l3 3 7-7" />
              </svg>
              <span>完成，正在呈現結果…</span>
            </>
          ) : loading ? (
            <>
              <svg className="animate-spin-slow w-4 h-4" viewBox="0 0 16 16" fill="none">
                <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="2" opacity="0.3" />
                <path d="M14 8a6 6 0 0 0-6-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
              </svg>
              <span>{activeStep?.label || '處理中'}...</span>
            </>
          ) : (
            <>
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path d="M3 8h10M10 5l3 3-3 3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              <span>{buttonLabel}</span>
            </>
          )}
        </span>
      </button>

      {/* Step progress */}
      {loading && (
        <div className="space-y-2 animate-fade-in-up">
          {/* Progress bar */}
          <div className="flex items-center gap-1.5">
            {steps.map((step) => (
              <div
                key={step.id}
                className={`h-1 flex-1 rounded-full transition-all duration-500 ${statusColor[step.status]}`}
              />
            ))}
          </div>

          {/* Step labels */}
          <div className="flex items-center justify-between">
            <p className="text-xs dark:text-text-secondary text-light-text-secondary font-medium">
              {activeStep?.label || '處理完成'}
            </p>
            <p className="text-[10px] tabular-nums dark:text-text-tertiary text-light-text-tertiary">
              步驟 <span className="font-mono">{progressCount}/{steps.length}</span>
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
