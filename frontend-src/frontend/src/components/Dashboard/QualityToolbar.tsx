import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { api } from '../../api/rulesApi';
import type { GenerationResult, JudgeResult, RuleEnvelope } from '../../types';

const SPRING = { type: 'spring' as const, stiffness: 300, damping: 28 };

export interface QualityToolbarProps {
  result: GenerationResult;
}

export default function QualityToolbar({ result }: QualityToolbarProps) {
  const [judgeLoading, setJudgeLoading] = useState(false);
  const [explainLoading, setExplainLoading] = useState(false);
  const [judge, setJudge] = useState<JudgeResult | null>(null);
  const [rationaleCount, setRationaleCount] = useState<number | null>(null);
  const [rationaleEnvelope, setRationaleEnvelope] = useState<RuleEnvelope | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const isDecisionTree = result.generate?.ruleType === 'DecisionTree';
  // JSON 直接輸入模式時 originalDescription 是貼上的 JSON 字串而非自然語言描述，跨 provider judge 會失真
  const isJsonInputMode = !result.originalDescription
    || result.originalDescription.trim().startsWith('{');
  const explainDisabled = explainLoading || !result.originalDescription || isDecisionTree || isJsonInputMode;
  const judgeDisabled = judgeLoading || !result.originalDescription || isJsonInputMode;
  const explainTooltip = isDecisionTree
    ? '決策樹的解釋已內建在「業務總覽」與每個節點的條件文字，無須額外產生'
    : isJsonInputMode
      ? '需透過自然語言生成的規則才能產生中文解釋（直接貼 JSON 模式不適用）'
      : '為每條規則產生 1-2 句中文解釋';
  const judgeTooltip = isJsonInputMode
    ? '需透過自然語言生成的規則才能跨 provider 評估（直接貼 JSON 模式不適用）'
    : '用另一個 LLM 評估本次生成的 faithfulness / completeness / hallucination / consistency';

  const runJudge = async () => {
    if (!result.originalDescription) return;
    setJudgeLoading(true);
    setErr(null);
    try {
      const r = await api.evaluate(
        result.originalDescription,
        result.generate,
        undefined,
        false,
      );
      setJudge(r);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setJudgeLoading(false);
    }
  };

  const runExplain = async () => {
    if (!result.originalDescription) return;
    setExplainLoading(true);
    setErr(null);
    try {
      const r = await api.explain(result.originalDescription, result.generate);
      setRationaleCount(r.rationalesApplied);
      setRationaleEnvelope(r.envelope);
      // 把 rationale 寫回目前 envelope 的 rules（mutate — 讓 RuleTable 下次 render 看到）
      type RowLite = { ruleId?: string; rationale?: string };
      const returnedRule = r.envelope.rule as { rules?: RowLite[] } | undefined;
      const currentRule = result.generate.rule as { rules?: RowLite[] } | undefined;
      if (returnedRule?.rules && currentRule?.rules) {
        const map = new Map<string, string | undefined>(
          returnedRule.rules.map((x) => [x.ruleId ?? '', x.rationale]),
        );
        currentRule.rules.forEach((row) => {
          const ra = row.ruleId ? map.get(row.ruleId) : undefined;
          if (ra) row.rationale = ra;
        });
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setExplainLoading(false);
    }
  };

  // Void 一下 rationaleEnvelope 避免 unused warning（state 有留給未來用）
  void rationaleEnvelope;

  return (
    <div className="rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border overflow-hidden">
      <div className="flex items-center gap-3 px-5 py-3 flex-wrap">
        <div className="flex items-baseline gap-1.5">
          <span className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
            深度品質檢查
          </span>
          <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
            可選的延伸驗證（需額外 LLM 呼叫）
          </span>
        </div>

        <div className="flex-1" />

        {/* LLM-as-Judge */}
        <button
          onClick={runJudge}
          disabled={judgeDisabled}
          className="
            inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md
            text-[11px] font-medium
            dark:bg-surface-3 bg-light-surface-3
            dark:text-text-secondary text-light-text-secondary
            hover:brightness-110 disabled:opacity-50 disabled:cursor-not-allowed
            transition-all
          "
          title={judgeTooltip}
        >
          {judgeLoading ? (
            <svg className="animate-spin" width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.5">
              <circle cx="5" cy="5" r="3.5" strokeDasharray="6 3" />
            </svg>
          ) : (
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
              <path d="M5 1v4M5 9v0.5M1 5h4M9 5h0.5" />
              <circle cx="5" cy="5" r="4" />
            </svg>
          )}
          {judgeLoading ? '評估中...' : '跨 provider 評估'}
        </button>

        {/* Explain */}
        <button
          onClick={runExplain}
          disabled={explainDisabled}
          className="
            inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md
            text-[11px] font-medium
            bg-accent/10 text-accent
            hover:bg-accent/15 disabled:opacity-50 disabled:cursor-not-allowed
            transition-all
          "
          title={explainTooltip}
        >
          {explainLoading ? (
            <svg className="animate-spin" width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.5">
              <circle cx="5" cy="5" r="3.5" strokeDasharray="6 3" />
            </svg>
          ) : (
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
              <path d="M2 2h6M2 5h6M2 8h4" />
            </svg>
          )}
          {explainLoading ? '產生中...' : '產生每條規則解釋'}
        </button>
      </div>

      <AnimatePresence>
        {err && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={SPRING}
            className="overflow-hidden"
          >
            <div className="px-5 py-2 text-[11px] text-danger border-t dark:border-border/30 border-light-border/30 bg-danger/5">
              ⚠ {err}
            </div>
          </motion.div>
        )}

        {rationaleCount !== null && (
          <motion.div
            key="rationale-result"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={SPRING}
            className="overflow-hidden"
          >
            <div className="px-5 py-2 text-[11px] text-accent border-t dark:border-border/30 border-light-border/30 bg-accent/5">
              ✓ 已為 {rationaleCount} 條規則產生中文解釋，可在「規則表」tab 展開查看
            </div>
          </motion.div>
        )}

        {judge && (
          <motion.div
            key="judge-result"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={SPRING}
            className="overflow-hidden"
          >
            <div className="px-5 py-3 border-t dark:border-border/30 border-light-border/30">
              <div className="flex items-baseline gap-2 mb-2">
                <span className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
                  LLM Judge 結果
                </span>
                <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
                  judge={judge.judgeProvider}
                  {judge.crossProvider && ' · 跨 provider（已規避 self-enhancement）'}
                  {judge.durationMs && ` · ${judge.durationMs}ms`}
                </span>
              </div>

              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 mb-2">
                <JudgeMetric label="忠實度" value={judge.faithfulness} />
                <JudgeMetric label="完整度" value={judge.completeness} />
                <JudgeMetric label="無幻覺" value={1 - judge.hallucination} />
                <JudgeMetric label="一致性" value={judge.consistency} />
              </div>

              {judge.comment && (
                <p className="text-[11px] leading-relaxed dark:text-text-secondary text-light-text-secondary mb-1">
                  💬 {judge.comment}
                </p>
              )}
              {judge.suggestion && (
                <p className="text-[11px] leading-relaxed dark:text-text-tertiary text-light-text-tertiary">
                  💡 {judge.suggestion}
                </p>
              )}
              {judge.biasWarnings && judge.biasWarnings.length > 0 && (
                <ul className="mt-2 space-y-0.5">
                  {judge.biasWarnings.map((w, i) => (
                    <li key={i} className="text-[10px] text-warning flex items-start gap-1">
                      <span>⚠</span><span>{w}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function JudgeMetric({ label, value }: { label: string; value: number }) {
  const pct = Math.round(value * 100);
  const color = pct >= 80 ? 'text-success' : pct >= 50 ? 'text-warning' : 'text-danger';
  return (
    <div className="flex flex-col">
      <span className="text-[9px] uppercase tracking-wide dark:text-text-tertiary text-light-text-tertiary">
        {label}
      </span>
      <span className={`text-base font-bold tabular-nums ${color}`}>{pct}%</span>
    </div>
  );
}
