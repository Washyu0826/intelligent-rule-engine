import { useRef, useEffect, useState, useCallback, useMemo, lazy, Suspense } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { GenerationResult } from '../../types';

// Context
import {
  DashboardProvider,
  useDashboard,
  actions,
  TAB_LIST,
  type TabId,
} from './DashboardContext';

// Lazy-loaded Dashboard components (Phase 2 optimization: code splitting)
const EvaluationPanel = lazy(() => import('./EvaluationPanel'));
const RuleTable = lazy(() => import('./RuleTable'));
const ValidationReport = lazy(() => import('./ValidationReport'));
const CoverageHeatmap = lazy(() => import('./CoverageHeatmap'));
const GapAnalysis = lazy(() => import('./GapAnalysis'));
const SimplificationHints = lazy(() => import('./SimplificationHints'));
const ScenarioExpansionTable = lazy(() => import('./ScenarioExpansionTable'));
const DeliveryPositionPanel = lazy(() => import('./DeliveryPositionPanel'));

// Lazy-loaded DecisionTree visualization
const DecisionTreeView = lazy(() => import('./DecisionTreeView'));
const RuleLookupPanel = lazy(() => import('./RuleLookupPanel'));
const WhatIfSimulator = lazy(() => import('./WhatIfSimulator'));
const RuleSummary = lazy(() => import('./RuleSummary'));
const RuleHistory = lazy(() => import('./RuleHistory'));
const ImpactAnalysis = lazy(() => import('./ImpactAnalysis'));
const BusinessReport = lazy(() => import('../Export/BusinessReport'));
const ConfidenceBar = lazy(() => import('./ConfidenceBar'));
const RuleNarrative = lazy(() => import('./RuleNarrative'));
const SparsityPanel = lazy(() => import('./SparsityPanel'));
const GroundingPanel = lazy(() => import('./GroundingPanel'));
const QualityToolbar = lazy(() => import('./QualityToolbar'));

// Lazy-loaded Export components
const JsonViewer = lazy(() => import('../Export/JsonViewer'));
const CsvExporter = lazy(() => import('../Export/CsvExporter'));
const AdapterMappingTable = lazy(() => import('../Export/AdapterMappingTable'));
const DiffView = lazy(() => import('../Export/DiffView'));
const EngineExecutionTab = lazy(() => import('../EngineExecutionTab'));

// ════════════════════════════════════════════
// Spring configs
// ════════════════════════════════════════════
const INDICATOR_SPRING = { type: 'spring' as const, stiffness: 400, damping: 32 };
const CONTENT_SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Tab Icons
// ════════════════════════════════════════════
const TabIcons: Record<TabId, React.ReactNode> = {
  overview: (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
      <rect x="1" y="1" width="5" height="5" rx="1" />
      <rect x="8" y="1" width="5" height="5" rx="1" />
      <rect x="1" y="8" width="5" height="5" rx="1" />
      <rect x="8" y="8" width="5" height="5" rx="1" />
    </svg>
  ),
  rules: (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 3h10M2 7h10M2 11h10" />
      <circle cx="5" cy="3" r="0.4" fill="currentColor" />
      <circle cx="5" cy="7" r="0.4" fill="currentColor" />
      <circle cx="5" cy="11" r="0.4" fill="currentColor" />
    </svg>
  ),
  analysis: (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M1 13l3-4 3 2 3-5 3-3" />
      <circle cx="4" cy="9" r="0.5" fill="currentColor" />
      <circle cx="7" cy="11" r="0.5" fill="currentColor" />
      <circle cx="10" cy="6" r="0.5" fill="currentColor" />
    </svg>
  ),
  validation: (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M7 1l5.5 3v4.5c0 2.5-2.2 4.2-5.5 5.5-3.3-1.3-5.5-3-5.5-5.5V4z" />
      <path d="M5 7.5l2 2 3-4" />
    </svg>
  ),
  export: (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M7 2v7M4.5 7L7 9.5 9.5 7" />
      <path d="M2 10v1.5a1 1 0 001 1h8a1 1 0 001-1V10" />
    </svg>
  ),
  execution: (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M7 1.5v3" />
      <path d="M3 6.5h8" />
      <rect x="1.5" y="8.5" width="3" height="3" rx="0.8" />
      <rect x="5.5" y="8.5" width="3" height="3" rx="0.8" />
      <rect x="9.5" y="8.5" width="3" height="3" rx="0.8" />
      <path d="M3 6.5v2M7 6.5v2M11 6.5v2" />
    </svg>
  ),
};

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
interface Props {
  result: GenerationResult;
  onBack: () => void;
}

// ════════════════════════════════════════════
// TabBar with spring indicator
// ════════════════════════════════════════════
function TabBar({
  analyze,
  validate,
  confidence,
}: {
  analyze?: GenerationResult['analyze'];
  validate?: GenerationResult['validate'];
  confidence?: GenerationResult['confidence'];
}) {
  const { state, dispatch } = useDashboard();
  const tabRefs = useRef<Map<TabId, HTMLButtonElement>>(new Map());
  const containerRef = useRef<HTMLDivElement>(null);

  const [indicator, setIndicator] = useState({ left: 0, width: 0 });

  // Measure active tab button position
  const measureTab = useCallback(() => {
    const btn = tabRefs.current.get(state.activeTab);
    const container = containerRef.current;
    if (!btn || !container) return;
    const containerRect = container.getBoundingClientRect();
    const btnRect = btn.getBoundingClientRect();
    setIndicator({
      left: btnRect.left - containerRect.left,
      width: btnRect.width,
    });
  }, [state.activeTab]);

  useEffect(() => {
    measureTab();
    window.addEventListener('resize', measureTab);
    return () => window.removeEventListener('resize', measureTab);
  }, [measureTab]);

  // Badge values
  const badges = useMemo(() => {
    const analysisCount = (analyze?.gaps?.length ?? 0) + (analyze?.overlaps?.length ?? 0);
    const errorCount = validate?.errors?.length ?? 0;
    const warningCount = confidence?.actionableWarnings?.length ?? 0;
    const overviewBadge =
      confidence && confidence.tier !== 'PRODUCTION_READY' && warningCount > 0
        ? String(warningCount)
        : undefined;
    return {
      overview: overviewBadge,
      analysis: analysisCount > 0 ? String(analysisCount) : undefined,
      validation: !validate?.valid && errorCount > 0 ? String(errorCount) : undefined,
    };
  }, [analyze, validate, confidence]);

  return (
    <div
      ref={containerRef}
      className="
        relative flex items-center gap-0.5 p-0.5 rounded-lg
        dark:bg-surface-2 bg-light-surface-2
      "
    >
      {/* Sliding indicator */}
      <motion.div
        className="
          absolute top-0.5 bottom-0.5 rounded-md z-0
          dark:bg-surface-4 bg-white shadow-sm
        "
        animate={{ left: indicator.left, width: indicator.width }}
        transition={INDICATOR_SPRING}
      />

      {TAB_LIST.map((tab) => {
        const isActive = state.activeTab === tab.id;
        const badge = tab.id === 'analysis' ? badges.analysis
          : tab.id === 'validation' ? badges.validation
          : tab.id === 'overview' ? badges.overview
          : undefined;

        return (
          <button
            key={tab.id}
            ref={(el) => {
              if (el) tabRefs.current.set(tab.id, el);
            }}
            onClick={() => dispatch(actions.setTab(tab.id))}
            className={`
              relative z-10 flex items-center gap-1.5 px-3 py-1.5 rounded-md text-[11px] font-medium
              cursor-pointer transition-colors duration-200
              ${isActive
                ? 'dark:text-text-primary text-light-text-primary'
                : 'dark:text-text-tertiary dark:hover:text-text-secondary text-light-text-tertiary hover:text-light-text-secondary'
              }
            `}
          >
            {TabIcons[tab.id]}
            {tab.label}
            {badge && (
              <span className={`
                text-[9px] font-mono font-semibold px-1.5 py-0.5 rounded-full
                ${isActive
                  ? 'bg-accent/15 text-accent'
                  : 'dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary'
                }
              `}>
                {badge}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

// ════════════════════════════════════════════
// Tab Content with slide animation
// ════════════════════════════════════════════
function TabContent({ result }: { result: GenerationResult }) {
  const { state } = useDashboard();
  const { generate, recommend, validate, analyze, durationMs, originalDescription } = result;

  // Compute slide direction from tab index diff
  const tabIndex = TAB_LIST.findIndex((t) => t.id === state.activeTab);
  const prevTabIndex = state.prevTab ? TAB_LIST.findIndex((t) => t.id === state.prevTab) : tabIndex;
  const slideDir = tabIndex >= prevTabIndex ? 1 : -1;

  const tabFallback = (
    <div className="flex items-center justify-center py-20">
      <div className="animate-pulse text-text-secondary">載入中…</div>
    </div>
  );

  return (
    <AnimatePresence mode="wait">
      <motion.div
        key={state.activeTab}
        initial={{ opacity: 0, x: slideDir * 20 }}
        animate={{ opacity: 1, x: 0 }}
        exit={{ opacity: 0, x: -slideDir * 20 }}
        transition={CONTENT_SPRING}
        className="space-y-5"
      >
        {/* ─── OVERVIEW ─── */}
        {state.activeTab === 'overview' && (
          <Suspense fallback={tabFallback}>
            <DeliveryPositionPanel envelope={generate} />

            {/* v3.12: 業務總覽 — 非工程使用者第一眼理解，放在最上方 */}
            <RuleNarrative
              envelope={generate}
              description={originalDescription}
              autoFetch={false}
            />

            {/* v3.10.0: Composite Confidence bar — 擺在頂端，一眼判斷可上線 */}
            {result.confidence && <ConfidenceBar confidence={result.confidence} />}

            {/* v3.8.0: Grounding panel — 符號幻覺偵測結果 */}
            {result.grounding && <GroundingPanel grounding={result.grounding} />}

            {/* v3.6/3.9: 品質工具列 — 跨 provider 評估、產生 rationale */}
            <QualityToolbar result={result} />

            {/* 原始需求描述 */}
            {originalDescription && (
              <div className="rounded-xl border p-4
                dark:bg-surface-1 dark:border-border bg-white border-light-border">
                <p className="text-[10px] uppercase tracking-[0.08em] font-medium mb-2
                  dark:text-text-tertiary text-light-text-tertiary">
                  您的需求描述
                </p>
                <p className="text-sm leading-relaxed dark:text-text-secondary text-light-text-secondary">
                  {originalDescription}
                </p>
              </div>
            )}
            <EvaluationPanel envelope={generate} recommend={recommend} durationMs={durationMs} validate={validate} analyze={analyze} />
            {validate && <ValidationReport validation={validate} />}
            <RuleHistory currentResult={result} onRestore={() => {}} />
          </Suspense>
        )}

        {/* ─── RULES ─── */}
        {state.activeTab === 'rules' && (
          <Suspense fallback={tabFallback}>
            <div className="space-y-4">
              {/* 工具列：查詢 + 模擬並排 */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <RuleLookupPanel envelope={generate} />
                <WhatIfSimulator envelope={generate} />
              </div>

              {/* 規則表 / 決策樹 */}
              {generate.ruleType === 'DecisionTree' ? (
                <DecisionTreeView envelope={generate} analyze={analyze} />
              ) : (
                <RuleTable envelope={generate} analyze={analyze} />
              )}

              {/* 影響分析 + 摘要（DecisionTree 不適用 ImpactAnalysis 直接隱藏，避免提示卡片夾在樹下方） */}
              {generate.ruleType !== 'DecisionTree' && <ImpactAnalysis envelope={generate} />}
              <RuleSummary envelope={generate} />
            </div>
          </Suspense>
        )}

        {/* ─── ANALYSIS ─── */}
        {state.activeTab === 'analysis' && (
          <Suspense fallback={tabFallback}>
            {generate.ruleType === 'DecisionTree' ? (
              <DecisionTreeView envelope={generate} analyze={analyze} />
            ) : (
              <CoverageHeatmap envelope={generate} analyze={analyze} />
            )}
            <ScenarioExpansionTable envelope={generate} />
            {/* v3.13: Sparsity Panel — DecisionTree 專屬，提供 6-pass v2 優化 */}
            {generate.ruleType === 'DecisionTree' && (
              <SparsityPanel envelope={generate} />
            )}
            {/* DecisionTable 才顯示 GapAnalysis / SimplificationHints；DT 缺口/簡化建議透過樹節點標記呈現 */}
            {analyze && generate.ruleType !== 'DecisionTree' && <GapAnalysis analyze={analyze} />}
            {analyze && generate.ruleType !== 'DecisionTree' && <SimplificationHints analyze={analyze} />}
            {!analyze && (
              <div className="
                rounded-xl border p-8 text-center
                dark:bg-surface-1 dark:border-border bg-white border-light-border
              ">
                <div className="
                  w-12 h-12 rounded-xl mx-auto mb-3
                  flex items-center justify-center
                  dark:bg-surface-3 bg-light-surface-3
                  dark:text-text-tertiary text-light-text-tertiary
                ">
                  {TabIcons.analysis}
                </div>
                <p className="text-sm font-semibold dark:text-text-secondary text-light-text-secondary">
                  分析資料不可用
                </p>
                <p className="text-xs dark:text-text-tertiary text-light-text-tertiary mt-1">
                  /tools/analyze 端點尚未回傳分析結果
                </p>
              </div>
            )}
          </Suspense>
        )}

        {/* ─── VALIDATION ─── */}
        {state.activeTab === 'validation' && (
          <Suspense fallback={tabFallback}>
            {validate ? (
              <ValidationReport validation={validate} />
            ) : (
              <div className="
                rounded-xl border p-8 text-center
                dark:bg-surface-1 dark:border-border bg-white border-light-border
              ">
                <p className="text-sm dark:text-text-tertiary text-light-text-tertiary">
                  尚無驗證結果
                </p>
              </div>
            )}
          </Suspense>
        )}

        {/* ─── EXPORT ─── */}
        {state.activeTab === 'export' && (
          <Suspense fallback={tabFallback}>
            <DeliveryPositionPanel envelope={generate} />
            <BusinessReport result={result} />
            {/* 列印時只用 BusinessReport 為正式報告，下面輔助元件 print:hidden */}
            <div className="print:hidden space-y-5">
              <AdapterMappingTable envelope={generate} />
              <JsonViewer envelope={generate} />
              <CsvExporter envelope={generate} />
              <DiffView current={generate} />
            </div>
          </Suspense>
        )}

        {/* ─── ENGINE EXECUTION ─── */}
        {state.activeTab === 'execution' && (
          <Suspense fallback={tabFallback}>
            <EngineExecutionTab envelope={generate} />
          </Suspense>
        )}
      </motion.div>
    </AnimatePresence>
  );
}

// ════════════════════════════════════════════
// DashboardInner (consumes context)
// ════════════════════════════════════════════
function DashboardInner({ result, onBack }: Props) {
  const { generate, validate, analyze, confidence, durationMs } = result;
  const isValid = validate?.valid ?? true;
  const evaluation = generate?.evaluation;
  const rule = generate?.rule as Record<string, unknown> | undefined;
  const isDecisionTree = generate?.ruleType === 'DecisionTree';
  const rulesCount = isDecisionTree
    ? (evaluation?.totalScenarios ?? 0)
    : ((rule?.rules as unknown[])?.length ?? 0);
  const rulesLabel = isDecisionTree ? '決策路徑' : '規則數';
  const inputsCount = (rule?.inputs as unknown[])?.length ?? 0;
  const outputsCount = (rule?.outputs as unknown[])?.length ?? 0;
  const errorCount = validate?.errors?.length ?? 0;
  const generatedAt = new Date().toLocaleString('zh-TW', { hour12: false });

  return (
    <div className="w-full max-w-6xl mx-auto pb-12 print:max-w-none print:pb-0">

      {/* ── 頂部摘要統計列（專業級）— 列印時隱藏，避免與 BusinessReport 報告重疊 ── */}
      <div className="mt-4 mb-3 rounded-xl border overflow-hidden print:hidden
        dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div className="flex items-center justify-between px-4 py-2 border-b
          dark:border-border/50 border-light-border/50
          dark:bg-surface-2/50 bg-light-surface-2/50">
          <div className="flex items-center gap-3">
            <span className="text-xs font-bold dark:text-text-primary text-light-text-primary">
              {generate?.ruleType === 'DecisionTree' ? '決策樹' : '決策表'}品質與交付前檢視
            </span>
            <span
              className="hidden sm:inline-flex text-[10px] font-medium px-2 py-0.5 rounded-full
                bg-amber-500/10 text-amber-500 border border-amber-500/15"
              title="本系統輸出 engine-neutral 中介模型；正式部署集團規則引擎前需經 adapter 轉換"
            >
              中介模型 · 待 adapter 轉換
            </span>
            <span className={`inline-flex items-center gap-1 text-[10px] font-semibold px-2 py-0.5 rounded-full
              ${isValid
                ? 'bg-success/10 text-success'
                : 'bg-danger/10 text-danger'}`}>
              <span className={`w-1.5 h-1.5 rounded-full ${isValid ? 'bg-success' : 'bg-danger'}`} />
              {isValid ? '驗證通過' : `${errorCount} 個問題`}
            </span>
          </div>
          <div className="flex items-center gap-3 text-[10px] dark:text-text-tertiary text-light-text-tertiary">
            <span>{generatedAt}</span>
            {durationMs != null && <span>耗時 {(durationMs / 1000).toFixed(1)}s</span>}
            <button
              onClick={() => window.print()}
              className="px-2 py-0.5 rounded text-[10px] font-medium cursor-pointer
                dark:bg-surface-3 dark:hover:bg-surface-4 bg-light-surface-3 hover:bg-light-surface-4
                transition-colors print:hidden"
            >
              列印報告
            </button>
          </div>
        </div>
        <div className="grid grid-cols-5 divide-x dark:divide-border/50 divide-light-border/50">
          {[
            { label: rulesLabel, value: String(rulesCount), accent: false },
            { label: '覆蓋率', value: `${((evaluation?.coverageRate ?? 0) * 100).toFixed(1)}%`, accent: (evaluation?.coverageRate ?? 0) >= 0.95 },
            { label: '輸入欄位', value: String(inputsCount), accent: false },
            { label: '輸出欄位', value: String(outputsCount), accent: false },
            { label: '衝突', value: evaluation?.conflictDetection === 'NO_CONFLICT' ? '無' : '有', accent: evaluation?.conflictDetection === 'NO_CONFLICT' },
          ].map((item) => (
            <div key={item.label} className="px-4 py-3 text-center">
              <p className="text-[9px] uppercase tracking-wider font-medium mb-1
                dark:text-text-tertiary text-light-text-tertiary">{item.label}</p>
              <p className={`text-base font-bold tabular-nums
                ${item.accent ? 'text-success' : 'dark:text-text-primary text-light-text-primary'}`}>
                {item.value}
              </p>
            </div>
          ))}
        </div>
      </div>

      {/* ── Top Navigation Bar ── */}
      <div className="
        flex items-center justify-between pb-3
        sticky top-14 z-40 backdrop-blur-xl print:hidden
        dark:bg-surface-0/80 bg-white/80
      ">
        <button
          onClick={onBack}
          className="flex items-center gap-1.5 text-xs font-medium cursor-pointer
            dark:text-text-tertiary dark:hover:text-text-primary
            text-light-text-tertiary hover:text-light-text-primary
            transition-colors group"
        >
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none"
            className="transition-transform group-hover:-translate-x-0.5">
            <path d="M9 3L5 7l4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          返回輸入
        </button>

        <TabBar analyze={analyze} validate={validate} confidence={confidence} />

        <span className={`inline-flex items-center gap-1.5 text-[11px] font-medium px-2.5 py-1 rounded-md
          ${isValid
            ? 'bg-success/10 text-success border border-success/15'
            : 'bg-danger/10 text-danger border border-danger/15'}`}>
          <span className={`w-1.5 h-1.5 rounded-full ${isValid ? 'bg-success' : 'bg-danger'}`} />
          {isValid ? '驗證通過' : '驗證失敗'}
        </span>
      </div>

      {/* ── Tab Content ── */}
      <TabContent result={result} />
    </div>
  );
}

// ════════════════════════════════════════════
// Exported Component (wraps with Provider)
// ════════════════════════════════════════════
export default function ResultDashboard({ result, onBack }: Props) {
  return (
    <DashboardProvider>
      <DashboardInner result={result} onBack={onBack} />
    </DashboardProvider>
  );
}
