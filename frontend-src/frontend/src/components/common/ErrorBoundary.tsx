import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  /** 區塊名稱，顯示於 fallback（例如「結果分析」）。 */
  label?: string;
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * React Error Boundary —— 攔截子樹 render 階段的未捕捉例外，避免單一元件崩潰造成整頁白畫面。
 *
 * 包覆懶載入的重型區塊（Dashboard / Export / 預覽）。提供「重試」（重置邊界並重新 render）
 * 與「重新整理」兩個復原途徑。Error boundary 必須是 class 元件（React 限制）。
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 僅記到 console（無外部遙測）；保留 component stack 供除錯
    console.error(`[ErrorBoundary${this.props.label ? ` · ${this.props.label}` : ''}]`, error, info.componentStack);
  }

  private reset = () => this.setState({ error: null });

  render() {
    if (!this.state.error) return this.props.children;

    return (
      <div className="animate-fade-in-up rounded-xl border overflow-hidden my-6
        dark:border-danger/30 dark:bg-danger/5 border-danger/20 bg-danger/5"
        role="alert"
      >
        <div className="flex items-center gap-2 px-4 py-3 border-b dark:border-danger/20 border-danger/10">
          <div className="w-5 h-5 rounded-full bg-danger/20 flex items-center justify-center">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <path d="M6 3v4M6 8.5v.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" className="text-danger" />
            </svg>
          </div>
          <span className="text-sm font-medium dark:text-danger text-danger">
            {this.props.label ? `${this.props.label}發生錯誤` : '畫面發生錯誤'}
          </span>
        </div>

        <div className="px-4 py-3 space-y-2">
          <p className="text-sm dark:text-text-secondary text-light-text-secondary leading-relaxed">
            此區塊在顯示時發生未預期的錯誤，已被攔截以保護其他功能正常運作。其他分頁仍可使用。
          </p>
          <p className="text-xs font-mono dark:text-text-tertiary text-light-text-tertiary break-all">
            {this.state.error.message || String(this.state.error)}
          </p>
        </div>

        <div className="px-4 py-3 border-t dark:border-danger/20 border-danger/10 flex gap-2">
          <button
            onClick={this.reset}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium
              bg-danger/10 text-danger hover:bg-danger/20 transition-colors cursor-pointer"
          >
            重試
          </button>
          <button
            onClick={() => window.location.reload()}
            className="px-4 py-2 rounded-lg text-sm font-medium
              dark:bg-surface-3 dark:text-text-secondary bg-light-surface-3 text-light-text-secondary
              hover:opacity-80 transition-opacity cursor-pointer"
          >
            重新整理頁面
          </button>
        </div>
      </div>
    );
  }
}
