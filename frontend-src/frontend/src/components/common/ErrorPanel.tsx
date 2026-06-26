import type { ApiError } from '../../types';

interface Props {
  error: ApiError;
  onRetry?: () => void;
  onDismiss?: () => void;
}

export default function ErrorPanel({ error, onRetry, onDismiss }: Props) {
  return (
    <div className="animate-fade-in-up rounded-xl border overflow-hidden
      dark:border-danger/30 dark:bg-danger/5
      border-danger/20 bg-danger/5
    ">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b
        dark:border-danger/20 border-danger/10
      ">
        <div className="flex items-center gap-2">
          <div className="w-5 h-5 rounded-full bg-danger/20 flex items-center justify-center">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <path d="M6 3v4M6 8.5v.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"
                className="text-danger" />
            </svg>
          </div>
          <span className="text-sm font-medium dark:text-danger text-danger">
            生成失敗
          </span>
        </div>
        {onDismiss && (
          <button
            onClick={onDismiss}
            className="text-xs dark:text-text-tertiary text-light-text-tertiary
              hover:text-danger transition-colors cursor-pointer"
          >
            ✕
          </button>
        )}
      </div>

      {/* Body */}
      <div className="px-4 py-3 space-y-2">
        <p className="text-sm dark:text-text-secondary text-light-text-secondary font-mono leading-relaxed">
          {error.message}
        </p>
        {error.suggestion && (
          <p className="text-sm dark:text-text-tertiary text-light-text-tertiary">
            💡 {error.suggestion}
          </p>
        )}
      </div>

      {/* Actions */}
      {error.retryable && onRetry && (
        <div className="px-4 py-3 border-t dark:border-danger/20 border-danger/10">
          <button
            onClick={onRetry}
            className="
              flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium
              bg-danger/10 text-danger hover:bg-danger/20
              transition-colors duration-200 cursor-pointer
            "
          >
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path
                d="M11.83 7A4.83 4.83 0 1 1 7 2.17M7 2.17L9.33 4.5M7 2.17V5.83"
                stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"
              />
            </svg>
            重試
          </button>
        </div>
      )}
    </div>
  );
}
