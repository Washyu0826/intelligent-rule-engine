import { useState, useEffect, useCallback, lazy, Suspense } from 'react';
import { useTheme } from './hooks/useTheme';
import { useRuleGeneration } from './hooks/useRuleGeneration';
import { api } from './api/rulesApi';
import ThemeToggle from './components/common/ThemeToggle';
import ApiKeyControl from './components/common/ApiKeyControl';
import LoginControl from './components/common/LoginControl';
import WelcomeGuide from './components/common/WelcomeGuide';
import ErrorBoundary from './components/common/ErrorBoundary';
import InputPage from './components/InputPage/InputPage';
import ResultDashboard from './components/Dashboard/ResultDashboard';
import WorkbenchTab from './components/Workbench/WorkbenchTab';
import type { PageView, InputMode } from './types';

const V314Preview = lazy(() => import('./components/V314Preview/V314Preview'));

const VERSION = 'v3.15.1';

function readPreviewMode(): boolean {
  if (typeof window === 'undefined') return false;
  return new URLSearchParams(window.location.search).get('preview') === 'v3.14';
}

export default function App() {
  const { mode, cycle } = useTheme();
  const { loading, steps, result, error, generate, reset } = useRuleGeneration();
  const [serverUp, setServerUp] = useState<boolean | null>(null);
  const [previewMode, setPreviewMode] = useState<boolean>(readPreviewMode);
  const [view, setView] = useState<'generate' | 'workbench'>('generate');
  const page: PageView = result ? 'dashboard' : 'input';

  // 監聽 popstate（用戶按瀏覽器上一頁/下一頁）以同步 preview mode
  useEffect(() => {
    const onPop = () => setPreviewMode(readPreviewMode());
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const exitPreview = useCallback(() => {
    const url = new URL(window.location.href);
    url.searchParams.delete('preview');
    window.history.pushState({}, '', url.toString());
    setPreviewMode(false);
  }, []);

  // Health check on mount and every 30s
  useEffect(() => {
    const check = () => api.healthCheck().then(setServerUp);
    check();
    const id = setInterval(check, 30_000);
    return () => clearInterval(id);
  }, []);

  const handleGenerate = useCallback(
    async (description: string, inputMode: InputMode, jsonInput?: string, provider?: string, ruleType?: string) => {
      try {
        await generate(description, inputMode, jsonInput, provider, ruleType);
      } catch {
        // error is managed by the hook
      }
    },
    [generate]
  );

  const handleBack = useCallback(() => {
    reset();
  }, [reset]);

  return (
    <div className="min-h-screen flex flex-col" role="application" aria-label="Rules MCP Server">
      <WelcomeGuide />
      {/* ── Header — 官方金控集團 logo（白底 / 深綠底色帶） ── */}
      <header role="banner" className="
        sticky top-0 z-50 backdrop-blur-xl
        dark:bg-surface-0/95 bg-white/95
        border-b-[3px] border-[var(--color-group-green-600)]
        shadow-sm
      ">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-4">
          {/* Left: 官方 logo + 產品 subtitle */}
          <div className="flex items-center gap-4 min-w-0">
            <a href="/" className="shrink-0 block" aria-label="金控集團首頁">
              {/* Dark mode 下用淺色卡片墊底，避免 logo 黑字與深背景衝突 */}
              <div className="dark:bg-white dark:rounded-lg dark:px-2 dark:py-1 dark:shadow-sm transition-all">
                <img
                  src="/logo.svg"
                  alt="金控集團 Group Financial Holdings"
                  className="h-8 sm:h-9 w-auto select-none block"
                  draggable={false}
                />
              </div>
            </a>

            {/* 縱向細綠分隔線 + 產品名 */}
            <div className="hidden sm:flex items-center gap-3 min-w-0">
              <span className="h-7 w-px dark:bg-border bg-light-border" />
              <div className="flex flex-col min-w-0">
                <span className="text-[13px] font-semibold tracking-tight dark:text-text-primary text-light-text-primary leading-tight truncate">
                  智能規則引擎
                </span>
                <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary leading-tight truncate">
                  業務規則自動生成平台
                </span>
              </div>
            </div>
          </div>

          {/* Right: 狀態 / 版本 / 主題切換 */}
          <div className="flex items-center gap-2.5 shrink-0">
            <div className="
              flex items-center gap-1.5 px-2.5 py-1 rounded-full
              dark:bg-surface-2 bg-light-surface-2
              border dark:border-border/60 border-light-border
            ">
              <span className={`w-1.5 h-1.5 rounded-full
                ${serverUp === null ? 'bg-warning animate-pulse'
                  : serverUp ? 'bg-success' : 'bg-danger animate-pulse-glow'}`} />
              <span className="text-[10px] font-medium dark:text-text-secondary text-light-text-secondary">
                {serverUp === null ? '連線中' : serverUp ? '服務正常' : '服務離線'}
              </span>
            </div>
            <span className="text-[9px] font-mono tabular-nums dark:text-text-tertiary/60 text-light-text-tertiary/60 hidden sm:inline">
              {VERSION}
            </span>
            <LoginControl />
            <ApiKeyControl />
            <ThemeToggle mode={mode} onCycle={cycle} />
          </div>
        </div>
      </header>

      {/* ── 分頁導覽 ── */}
      {!previewMode && (
        <nav aria-label="主要功能" className="border-b dark:border-border border-light-border dark:bg-surface-0 bg-white">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 flex gap-1">
            {([['generate', '規則生成'], ['workbench', '審核工作台']] as const).map(([key, label]) => (
              <button
                key={key}
                type="button"
                onClick={() => setView(key)}
                aria-current={view === key ? 'page' : undefined}
                className={`px-4 py-2.5 text-sm font-medium cursor-pointer border-b-2 -mb-px transition-colors ${
                  view === key
                    ? 'border-[var(--color-group-green-600)] dark:text-text-primary text-light-text-primary'
                    : 'border-transparent dark:text-text-tertiary text-light-text-tertiary hover:opacity-80'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        </nav>
      )}

      {/* ── Main ── */}
      <main role="main" aria-label={previewMode ? 'v3.14 預覽' : page === 'input' ? '規則輸入' : '結果分析'} className="flex-1 px-4 sm:px-6">
        {previewMode ? (
          <ErrorBoundary label="v3.14 預覽">
            <Suspense fallback={<div className="py-20 text-center text-sm dark:text-text-tertiary text-light-text-tertiary">載入 v3.14 預覽中…</div>}>
              <V314Preview onExit={exitPreview} />
            </Suspense>
          </ErrorBoundary>
        ) : view === 'workbench' ? (
          <ErrorBoundary label="審核工作台">
            <WorkbenchTab />
          </ErrorBoundary>
        ) : page === 'input' ? (
          <ErrorBoundary label="規則輸入">
            <InputPage
              loading={loading}
              steps={steps}
              error={error}
              onGenerate={handleGenerate}
              onDismissError={() => reset()}
            />
          </ErrorBoundary>
        ) : result ? (
          <ErrorBoundary label="結果分析">
            <ResultDashboard result={result} onBack={handleBack} />
          </ErrorBoundary>
        ) : null}
      </main>

      {/* ── Footer — 金控集團風格 ── */}
      <footer className="mt-12 border-t-2 border-[var(--color-group-green-600)] print:hidden">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 py-6">
          {/* 上半：品牌 logo + tech stack */}
          <div className="flex flex-col sm:flex-row items-center justify-between gap-3">
            <div className="dark:bg-white/95 dark:rounded-md dark:px-1.5 dark:py-0.5">
              <img
                src="/logo.svg"
                alt="金控集團 Group Financial Holdings"
                className="h-5 w-auto opacity-80 dark:opacity-100 select-none block"
                draggable={false}
              />
            </div>

            <div className="flex items-center gap-2 text-[9px] dark:text-text-tertiary/70 text-light-text-tertiary/70">
              <span>Powered by</span>
              <span className="font-semibold">RuleEnvelope 中介模型</span>
            </div>
          </div>

          {/* 下半：版權 */}
          <div className="mt-3 pt-3 border-t dark:border-border/20 border-light-border/30 flex flex-col sm:flex-row items-center justify-between gap-1.5">
            <p className="text-[9px] dark:text-text-tertiary/60 text-light-text-tertiary/60">
              © 2026 Group Financial Holdings Co., Ltd. 保留一切權利
            </p>
            <p className="text-[9px] dark:text-text-tertiary/60 text-light-text-tertiary/60">
              業務規則治理前處理服務
            </p>
          </div>
        </div>
      </footer>
    </div>
  );
}
