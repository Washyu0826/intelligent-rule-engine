import { useState, useMemo, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { RuleEnvelope, MatchedRule } from '../../types';
import { api } from '../../api/rulesApi';

interface Props {
  envelope: RuleEnvelope;
}

interface FieldDef {
  name: string;
  typeRef: string;
  allowedValues?: string[];
}

const ENGLISH_TO_CHINESE: Record<string, string> = {
  age: '年齡', ageGroup: '年齡區間', ageInRange: '年齡範圍',
  gender: '性別', smoking: '吸菸狀態', smoker: '是否吸菸',
  bmi: 'BMI', bmiCategory: 'BMI 區間',
  hypertension: '高血壓', hasHypertension: '是否有高血壓',
  diabetes: '糖尿病', hasDiabetes: '是否有糖尿病',
  heartDisease: '心臟病', hasHeartDisease: '是否有心臟病',
  occupationRisk: '職業風險', occupationClass: '職業類別',
  highRiskActivity: '高風險活動', claimCount: '理賠次數',
  disabilityLevel: '殘廢等級', creditScore: '信用評分',
  underwritingDecision: '核保決議', premiumFactor: '保費係數',
  rateLevel: '費率等級', remark: '備註', decision: '決議',
};

interface SimResult {
  label: string;
  values: Record<string, unknown>;
  matched: boolean;
  matchedRules: MatchedRule[];
}

export default function WhatIfSimulator({ envelope }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [baseValues, setBaseValues] = useState<Record<string, unknown>>({});
  const [targetField, setTargetField] = useState<string>('');
  const [results, setResults] = useState<SimResult[]>([]);
  const [loading, setLoading] = useState(false);

  const inputs = useMemo<FieldDef[]>(() => {
    const rule = envelope?.rule as Record<string, unknown> | undefined;
    if (!rule) return [];
    return (rule.inputs as FieldDef[] | undefined) || [];
  }, [envelope]);

  const cn = (name: string) => ENGLISH_TO_CHINESE[name] || name;

  // 從第一條規則 / 決策樹第一條路徑取基準值
  const fillBase = useCallback(() => {
    const rule = envelope?.rule as Record<string, unknown> | undefined;
    if (!rule) return;
    const base: Record<string, unknown> = {};

    // DecisionTable 路徑
    const rules = rule.rules as Array<{ conditions: Array<{ field: string; value: unknown }> }> | undefined;
    if (rules && rules.length > 0) {
      for (const cond of rules[0].conditions) {
        if (cond.value != null) {
          base[cond.field] = Array.isArray(cond.value) ? cond.value[0] : cond.value;
        }
      }
    } else {
      // DecisionTree 路徑：走第一條到 leaf 的路徑取每層條件值
      type DTBranch = { label?: string; condition?: { field: string; value: unknown }; child?: DTNode };
      type DTNode = {
        condition?: { field: string; value: unknown };
        branches?: DTBranch[];
        trueBranch?: DTNode; falseBranch?: DTNode;
        results?: unknown[];
      };
      const root = rule.root as DTNode | undefined;
      const walk = (node: DTNode | undefined): void => {
        if (!node) return;
        if (node.condition?.field && node.condition.value !== undefined && node.condition.value !== null) {
          const v = Array.isArray(node.condition.value) ? node.condition.value[0] : node.condition.value;
          if (!(node.condition.field in base)) base[node.condition.field] = v;
        }
        const firstBranch = node.branches?.[0];
        if (firstBranch?.child) {
          if (firstBranch.condition?.field && firstBranch.condition.value !== undefined) {
            const v = Array.isArray(firstBranch.condition.value) ? firstBranch.condition.value[0] : firstBranch.condition.value;
            if (!(firstBranch.condition.field in base)) base[firstBranch.condition.field] = v;
          }
          walk(firstBranch.child);
          return;
        }
        if (node.trueBranch) { walk(node.trueBranch); return; }
        if (node.falseBranch) { walk(node.falseBranch); return; }
      };
      walk(root);
    }

    setBaseValues(base);
    if (inputs.length > 0 && !targetField) setTargetField(inputs[0].name);
  }, [envelope, inputs, targetField]);

  // 執行 What-If：固定其他值，遍歷目標欄位的所有可能值
  const runSimulation = useCallback(async () => {
    if (!targetField) return;
    const field = inputs.find(f => f.name === targetField);
    if (!field) return;

    setLoading(true);
    const variations: Array<{ label: string; values: Record<string, unknown> }> = [];

    if (field.typeRef === 'BOOLEAN') {
      variations.push({ label: 'true', values: { ...baseValues, [targetField]: true } });
      variations.push({ label: 'false', values: { ...baseValues, [targetField]: false } });
    } else if (field.typeRef === 'ENUM' && field.allowedValues) {
      for (const val of field.allowedValues) {
        variations.push({ label: val, values: { ...baseValues, [targetField]: val } });
      }
    } else {
      // 對於 INTEGER/DECIMAL，使用已知規則中的不同值
      const rule = envelope?.rule as Record<string, unknown>;
      const rules = rule?.rules as Array<{ conditions: Array<{ field: string; value: unknown }> }>;
      const uniqueVals = new Set<string>();
      if (rules) {
        for (const r of rules) {
          for (const c of r.conditions) {
            if (c.field === targetField && c.value != null) {
              const v = Array.isArray(c.value) ? c.value[0] : c.value;
              uniqueVals.add(String(v));
            }
          }
        }
      }
      for (const v of uniqueVals) {
        const numVal = Number(v);
        variations.push({
          label: v,
          values: { ...baseValues, [targetField]: isNaN(numVal) ? v : numVal },
        });
      }
    }

    // 並行查詢所有變體
    const simResults: SimResult[] = [];
    for (const v of variations) {
      try {
        const res = await api.lookup(envelope, v.values);
        simResults.push({
          label: v.label,
          values: v.values,
          matched: res.matched,
          matchedRules: res.matchedRules,
        });
      } catch {
        simResults.push({ label: v.label, values: v.values, matched: false, matchedRules: [] });
      }
    }

    setResults(simResults);
    setLoading(false);
  }, [targetField, baseValues, inputs, envelope]);

  // 取得所有 output 欄位名
  const outputNames = useMemo(() => {
    const rule = envelope?.rule as Record<string, unknown> | undefined;
    if (!rule) return [];
    const outputs = rule.outputs as FieldDef[] | undefined;
    return outputs?.map(o => o.name) || [];
  }, [envelope]);

  return (
    <div className="rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border overflow-hidden">
      <button
        onClick={() => { setExpanded(!expanded); if (!expanded) fillBase(); }}
        className="w-full flex items-center justify-between px-4 py-3 text-left cursor-pointer
          hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
      >
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold dark:text-text-primary text-light-text-primary">
            What-If 模擬
          </span>
          <span className="px-1.5 py-0.5 rounded text-[10px] font-medium bg-purple-500/15 text-purple-600 dark:text-purple-400">
            情境分析
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
            <div className="px-4 pb-4 space-y-4">
              <p className="text-[11px] dark:text-text-tertiary text-light-text-tertiary">
                選擇一個條件欄位，系統會固定其他條件、遍歷該欄位的所有取值，讓您看到結果如何變化。
              </p>

              {/* 目標欄位選擇 */}
              <div className="flex gap-3 items-end">
                <div className="flex-1">
                  <label className="block text-[10px] font-medium mb-1 dark:text-text-tertiary text-light-text-tertiary">
                    模擬欄位（改變這個欄位的值）
                  </label>
                  <select
                    value={targetField}
                    onChange={(e) => setTargetField(e.target.value)}
                    className="w-full px-3 py-1.5 rounded-lg text-xs border outline-none
                      dark:bg-surface-2 dark:text-text-primary dark:border-border
                      bg-light-surface-2 text-light-text-primary border-light-border cursor-pointer"
                  >
                    <option value="">-- 請選擇 --</option>
                    {inputs.map(f => (
                      <option key={f.name} value={f.name}>{cn(f.name)}</option>
                    ))}
                  </select>
                </div>
                <button
                  onClick={runSimulation}
                  disabled={loading || !targetField}
                  className="px-4 py-1.5 rounded-lg text-xs font-semibold text-white
                    bg-purple-600 hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed
                    transition-colors cursor-pointer shrink-0"
                >
                  {loading ? '模擬中...' : '開始模擬'}
                </button>
              </div>

              {/* 基準值顯示 */}
              {Object.keys(baseValues).length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary mr-1">固定條件：</span>
                  {Object.entries(baseValues).filter(([k]) => k !== targetField).map(([k, v]) => (
                    <span key={k} className="px-2 py-0.5 rounded text-[10px]
                      dark:bg-surface-2 dark:text-text-secondary bg-light-surface-2 text-light-text-secondary">
                      {cn(k)}={String(v)}
                    </span>
                  ))}
                </div>
              )}

              {/* 模擬結果比較表 */}
              {results.length > 0 && (
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b dark:border-border border-light-border">
                        <th className="text-left py-2 px-2 font-semibold dark:text-text-secondary text-light-text-secondary">
                          {cn(targetField)}
                        </th>
                        {outputNames.map(name => (
                          <th key={name} className="text-left py-2 px-2 font-semibold dark:text-text-secondary text-light-text-secondary">
                            {cn(name)}
                          </th>
                        ))}
                        <th className="text-left py-2 px-2 font-semibold dark:text-text-secondary text-light-text-secondary">
                          命中規則
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {results.map((r, i) => (
                        <tr key={i} className={`border-b last:border-0
                          ${r.matched
                            ? 'dark:border-border border-light-border'
                            : 'dark:border-border border-light-border opacity-50'}`}
                        >
                          <td className="py-2 px-2 font-bold dark:text-accent text-accent">
                            {r.label}
                          </td>
                          {outputNames.map(name => {
                            const val = r.matchedRules[0]?.results?.[name];
                            return (
                              <td key={name} className="py-2 px-2 dark:text-text-primary text-light-text-primary">
                                {val != null ? String(val) : '—'}
                              </td>
                            );
                          })}
                          <td className="py-2 px-2">
                            {r.matched ? (
                              <span className="px-1.5 py-0.5 rounded text-[10px] font-medium
                                bg-emerald-500/15 text-emerald-600 dark:text-emerald-400">
                                {r.matchedRules[0]?.ruleId}
                              </span>
                            ) : (
                              <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">無匹配</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
