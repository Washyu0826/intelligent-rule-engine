import { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { GenerationResult } from '../../types';

const STORAGE_KEY = 'rules-mcp-history';
const MAX_HISTORY = 20;

interface HistoryEntry {
  id: string;
  timestamp: string;
  description: string;
  ruleType: string;
  rulesCount: number;
  coverageRate: number;
  isValid: boolean;
  durationMs?: number;
  /** Serialized GenerationResult */
  data: string;
}

interface Props {
  currentResult: GenerationResult;
  onRestore: (result: GenerationResult) => void;
}

function generateId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

function loadHistory(): HistoryEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch { return []; }
}

function saveHistory(entries: HistoryEntry[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(entries.slice(0, MAX_HISTORY)));
}

export function addToHistory(result: GenerationResult) {
  const entries = loadHistory();
  const rule = result.generate?.rule as Record<string, unknown> | undefined;
  const ruleType = result.generate?.ruleType || 'DecisionTable';
  const arrayCount = (rule?.rules as unknown[])?.length ?? 0;
  const totalScenarios = result.generate?.evaluation?.totalScenarios ?? 0;
  const entry: HistoryEntry = {
    id: generateId(),
    timestamp: new Date().toLocaleString('zh-TW', { hour12: false }),
    description: result.originalDescription || '(無描述)',
    ruleType,
    rulesCount: ruleType === 'DecisionTree' ? totalScenarios : arrayCount,
    coverageRate: result.generate?.evaluation?.coverageRate ?? 0,
    isValid: result.validate?.valid ?? true,
    durationMs: result.durationMs,
    data: JSON.stringify(result),
  };
  entries.unshift(entry);
  saveHistory(entries);
}

export default function RuleHistory({ currentResult, onRestore }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [compareId, setCompareId] = useState<string | null>(null);
  const lastHistoryKeyRef = useRef<string | null>(null);

  useEffect(() => {
    setEntries(loadHistory());
  }, []);

  // 新結果進來時自動存歷史
  useEffect(() => {
    if (!currentResult?.generate) return;

    const historyKey = JSON.stringify({
      description: currentResult.originalDescription,
      durationMs: currentResult.durationMs,
      generate: currentResult.generate,
      validate: currentResult.validate,
    });

    if (lastHistoryKeyRef.current === historyKey) return;
    lastHistoryKeyRef.current = historyKey;

    addToHistory(currentResult);
    setEntries(loadHistory());
  }, [currentResult]);

  const handleRestore = (entry: HistoryEntry) => {
    try {
      const parsed = JSON.parse(entry.data) as GenerationResult;
      onRestore(parsed);
    } catch { /* ignore */ }
  };

  const handleClear = () => {
    localStorage.removeItem(STORAGE_KEY);
    setEntries([]);
  };

  return (
    <div className="rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 text-left cursor-pointer
          hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
      >
        <div className="flex items-center gap-2">
          <span className="text-sm font-bold dark:text-text-primary text-light-text-primary">
            生成歷史
          </span>
          <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
            {entries.length} 筆
          </span>
        </div>
        <svg className={`w-4 h-4 transition-transform duration-200 dark:text-text-tertiary text-light-text-tertiary
          ${expanded ? 'rotate-180' : ''}`}
          fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
        </svg>
      </button>

      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div className="px-4 pb-4 space-y-2">
              {entries.length === 0 ? (
                <p className="text-xs dark:text-text-tertiary text-light-text-tertiary py-4 text-center">
                  尚無歷史記錄
                </p>
              ) : (
                <>
                  {entries.map((entry, idx) => (
                    <div key={entry.id}
                      className={`p-3 rounded-lg border transition-colors
                        ${idx === 0
                          ? 'dark:border-accent/30 border-accent/20 dark:bg-accent/5 bg-accent/5'
                          : 'dark:border-border/50 border-light-border/50 dark:bg-surface-2/50 bg-light-surface-2/50'}
                        ${compareId === entry.id ? 'ring-2 ring-accent/30' : ''}`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-1">
                            <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary tabular-nums">
                              {entry.timestamp}
                            </span>
                            <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded
                              ${entry.isValid
                                ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                                : 'bg-red-500/10 text-red-600 dark:text-red-400'}`}>
                              {entry.isValid ? '通過' : '有問題'}
                            </span>
                            <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
                              {entry.ruleType === 'DecisionTree' ? '決策樹' : '決策表'} · {entry.rulesCount} {entry.ruleType === 'DecisionTree' ? '條路徑' : '條'} · {(entry.coverageRate * 100).toFixed(0)}%
                            </span>
                          </div>
                          <p className="text-xs dark:text-text-secondary text-light-text-secondary truncate">
                            {entry.description}
                          </p>
                        </div>
                        <div className="flex gap-1 shrink-0">
                          <button
                            onClick={() => setCompareId(compareId === entry.id ? null : entry.id)}
                            className="px-2 py-1 rounded text-[10px] font-medium cursor-pointer
                              dark:text-text-tertiary dark:hover:text-text-secondary
                              text-light-text-tertiary hover:text-light-text-secondary transition-colors"
                            title="比較"
                          >
                            比較
                          </button>
                          {idx > 0 && (
                            <button
                              onClick={() => handleRestore(entry)}
                              className="px-2 py-1 rounded text-[10px] font-medium cursor-pointer
                                dark:text-accent/70 dark:hover:text-accent
                                text-accent/70 hover:text-accent transition-colors"
                              title="還原此版本"
                            >
                              還原
                            </button>
                          )}
                        </div>
                      </div>

                      {/* 比較面板 */}
                      {compareId === entry.id && idx > 0 && (
                        <div className="mt-2 pt-2 border-t dark:border-border/30 border-light-border/30">
                          <p className="text-[10px] font-medium dark:text-text-tertiary text-light-text-tertiary mb-1">
                            與目前結果比較
                          </p>
                          <div className="grid grid-cols-3 gap-2 text-[10px]">
                            {(() => {
                              const currentRuleType = currentResult.generate?.ruleType;
                              const currentRule = currentResult.generate?.rule as Record<string, unknown> | undefined;
                              const currentEval = currentResult.generate?.evaluation;
                              // DT 用 totalScenarios 當「規則數」；DTable 用 rules.length
                              const currentRuleCount = currentRuleType === 'DecisionTree'
                                ? (currentEval?.totalScenarios ?? 0)
                                : ((currentRule?.rules as unknown[])?.length ?? 0);
                              const ruleCountLabel = entry.ruleType === 'DecisionTree' ? '決策路徑' : '規則數';
                              const items = [
                                { label: ruleCountLabel, currVal: currentRuleCount, histVal: entry.rulesCount, isPct: false },
                                { label: '覆蓋率', currVal: currentResult.generate?.evaluation?.coverageRate ?? 0, histVal: entry.coverageRate, isPct: true },
                              ];
                              return items.map(item => {
                                const diff = item.currVal - item.histVal;
                                return (
                                  <div key={item.label} className="text-center">
                                    <p className="dark:text-text-tertiary text-light-text-tertiary">{item.label}</p>
                                    <p className="font-bold dark:text-text-primary text-light-text-primary">
                                      {item.isPct ? `${(item.histVal * 100).toFixed(0)}%` : item.histVal}
                                      {diff !== 0 && (
                                        <span className={diff > 0 ? 'text-emerald-500 ml-1' : 'text-red-500 ml-1'}>
                                          {diff > 0 ? '+' : ''}{item.isPct ? `${(diff * 100).toFixed(0)}%` : diff}
                                        </span>
                                      )}
                                    </p>
                                  </div>
                                );
                              });
                            })()}
                          </div>
                        </div>
                      )}
                    </div>
                  ))}
                  <button onClick={handleClear}
                    className="w-full py-1.5 text-[10px] font-medium cursor-pointer
                      dark:text-text-tertiary dark:hover:text-red-400
                      text-light-text-tertiary hover:text-red-500 transition-colors">
                    清除所有歷史
                  </button>
                </>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
