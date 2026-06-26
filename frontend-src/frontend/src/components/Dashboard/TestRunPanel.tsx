import { useState, useCallback, memo } from 'react';
import { motion } from 'framer-motion';
import { api } from '../../api/rulesApi';
import type { TestCase, TestRunResponse } from '../../types';

/**
 * TestRunPanel — 批次測試執行面板
 * 呼叫 POST /tools/test-run，顯示通過率、錯誤分布、逐案結果
 */

/** 內建測試案例 — 描述對準離線情境關鍵字（保險/折扣/信貸），無 LLM 金鑰時也能走 OfflineFallback 跑通 */
const BUILT_IN_CASES: TestCase[] = [
  {
    id: 'builtin-01-insurance',
    description: '壽險核保規則：年齡超過 60 歲且有高血壓病史則拒保，BMI 超過 30 需人工審核，其餘承保',
    expectedRuleType: 'DecisionTable',
    expectValid: true,
  },
  {
    id: 'builtin-02-discount',
    description: '購物折扣規則：VIP 會員滿額 3000 元打 85 折，一般會員滿額 5000 元打 9 折，其餘不打折',
    expectedRuleType: 'DecisionTable',
    expectValid: true,
  },
  {
    id: 'builtin-03-credit',
    description: '信用貸款審核：信用評分低於 600 拒絕，600 到 700 之間額度上限 30 萬，高於 700 核准全額',
    expectedRuleType: 'DecisionTable',
    expectValid: true,
  },
];

const TestRunPanel = memo(function TestRunPanel() {
  const [isRunning, setIsRunning] = useState(false);
  const [result, setResult] = useState<TestRunResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const runTests = useCallback(async () => {
    setIsRunning(true);
    setError(null);
    try {
      const data = await api.testRun(BUILT_IN_CASES);
      setResult(data);
    } catch (e) {
      const apiMessage = (e as { message?: string })?.message;
      setError(apiMessage ?? (e instanceof Error ? e.message : 'Unknown error'));
    } finally {
      setIsRunning(false);
    }
  }, []);

  return (
    <div className="rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b dark:border-border/50 border-light-border/50">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-lg flex items-center justify-center dark:bg-violet-500/15 dark:text-violet-400 bg-violet-500/10 text-violet-600">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2" />
              <rect x="9" y="3" width="6" height="4" rx="1" />
              <path d="M9 14l2 2 4-4" />
            </svg>
          </div>
          <h3 className="text-sm font-semibold dark:text-text-primary text-light-text-primary">批次測試</h3>
        </div>
        <button
          onClick={runTests}
          disabled={isRunning}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors
            ${isRunning
              ? 'dark:bg-surface-3 dark:text-text-tertiary bg-gray-200 text-gray-400 cursor-wait'
              : 'dark:bg-accent/15 dark:text-accent dark:hover:bg-accent/25 bg-accent/10 text-accent hover:bg-accent/20'}
          `}
        >
          {isRunning ? '執行中...' : '執行測試'}
        </button>
      </div>

      {/* Error */}
      {error && (
        <div className="px-4 py-2 text-xs text-red-400 dark:bg-red-500/10 bg-red-50">
          {error}
        </div>
      )}

      {/* Results */}
      {result && (
        <div className="p-4 space-y-4">
          {/* Summary */}
          <div className="grid grid-cols-4 gap-3">
            {[
              { label: '總數', value: result.total, color: 'text-blue-400' },
              { label: '通過', value: result.passed, color: 'text-emerald-400' },
              { label: '失敗', value: result.failed, color: 'text-red-400' },
              { label: '通過率', value: `${Math.round(result.passRate * 100)}%`, color: result.passRate >= 0.8 ? 'text-emerald-400' : 'text-amber-400' },
            ].map(item => (
              <div key={item.label} className="text-center rounded-lg p-2 dark:bg-surface-2 bg-gray-50">
                <div className={`text-lg font-bold ${item.color}`}>{item.value}</div>
                <div className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">{item.label}</div>
              </div>
            ))}
          </div>

          {/* Pass rate bar */}
          <div className="h-2 rounded-full dark:bg-surface-3 bg-gray-200 overflow-hidden">
            <motion.div
              initial={{ width: 0 }}
              animate={{ width: `${result.passRate * 100}%` }}
              transition={{ duration: 0.6 }}
              className={`h-full rounded-full ${result.passRate >= 0.8 ? 'bg-emerald-500' : result.passRate >= 0.5 ? 'bg-amber-500' : 'bg-red-500'}`}
            />
          </div>

          {/* Error breakdown */}
          {result.errorBreakdown && Object.keys(result.errorBreakdown).length > 0 && (
            <div>
              <p className="text-[10px] uppercase tracking-wider font-medium dark:text-text-tertiary text-light-text-tertiary mb-2">
                錯誤分布
              </p>
              <div className="space-y-1">
                {Object.entries(result.errorBreakdown).sort((a, b) => b[1] - a[1]).map(([code, count]) => (
                  <div key={code} className="flex items-center gap-2 text-[11px]">
                    <span className="font-mono dark:text-text-secondary text-light-text-secondary w-40 truncate">{code}</span>
                    <div className="flex-1 h-1.5 rounded-full dark:bg-surface-3 bg-gray-200 overflow-hidden">
                      <div className="h-full rounded-full bg-red-500/60"
                        style={{ width: `${(count / result.total) * 100}%` }} />
                    </div>
                    <span className="tabular-nums dark:text-text-tertiary text-light-text-tertiary w-6 text-right">{count}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Individual results */}
          {result.results && result.results.length > 0 && (
            <div>
              <p className="text-[10px] uppercase tracking-wider font-medium dark:text-text-tertiary text-light-text-tertiary mb-2">
                測試案例（{result.results.length}）
              </p>
              <div className="space-y-1 max-h-60 overflow-y-auto">
                {result.results.map((tc) => (
                  <div key={tc.id} className={`flex items-center gap-2 px-2 py-1.5 rounded-lg text-[11px]
                    ${tc.passed
                      ? 'dark:bg-emerald-500/5 bg-emerald-50'
                      : 'dark:bg-red-500/5 bg-red-50'}
                  `}>
                    <span className={`font-bold ${tc.passed ? 'text-emerald-400' : 'text-red-400'}`}>
                      {tc.passed ? '✓' : '✗'}
                    </span>
                    <span className="font-mono dark:text-text-primary text-light-text-primary">{tc.id}</span>
                    {tc.durationMs !== undefined && (
                      <span className="dark:text-text-tertiary text-light-text-tertiary">{tc.durationMs}ms</span>
                    )}
                    {tc.errors && tc.errors.length > 0 && (
                      <span className="text-red-400 truncate">{tc.errors[0].code}</span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Empty state */}
      {!result && !error && !isRunning && (
        <div className="px-4 py-8 text-center">
          <p className="text-xs dark:text-text-tertiary text-light-text-tertiary">
            點擊「執行測試」運行所有內建測試案例
          </p>
        </div>
      )}
    </div>
  );
});

export default TestRunPanel;
