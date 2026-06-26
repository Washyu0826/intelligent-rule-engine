import { useState, useMemo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { RuleEnvelope } from '../../types';

interface Props {
  envelope: RuleEnvelope;
}

interface RuleRow {
  ruleId: string;
  conditions: Array<{ field: string; operator: string; value: unknown }>;
  results: Array<{ field: string; value: unknown }>;
}

interface FieldDef {
  name: string;
  typeRef: string;
  allowedValues?: string[];
}

const FIELD_CN: Record<string, string> = {
  age: '年齡', ageGroup: '年齡區間', gender: '性別',
  hasHypertension: '高血壓', hasDiabetes: '糖尿病', hasHeartDisease: '心臟病',
  smoking: '吸菸', smoker: '是否吸菸', bmiCategory: 'BMI區間',
  occupationRisk: '職業風險', occupationClass: '職業類別',
  underwritingDecision: '核保決議', premiumFactor: '保費係數',
  rateLevel: '費率等級', decision: '決議', remark: '備註',
};

function cn(f: string) { return FIELD_CN[f] || f; }

export default function ImpactAnalysis({ envelope }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [selectedRuleId, setSelectedRuleId] = useState<string>('');
  const [modifiedField, setModifiedField] = useState<string>('');
  const [modifiedValue, setModifiedValue] = useState<string>('');

  const rule = envelope?.rule as Record<string, unknown> | undefined;
  const rules = useMemo(() => (rule?.rules as RuleRow[] | undefined) || [], [rule]);
  const outputs = useMemo(() => (rule?.outputs as FieldDef[] | undefined) || [], [rule]);

  // 找出選中的規則
  const selectedRule = useMemo(
    () => rules.find(r => r.ruleId === selectedRuleId),
    [rules, selectedRuleId]
  );

  // 計算影響分析
  const analysis = useMemo(() => {
    if (!selectedRule || !modifiedField || !modifiedValue) return null;

    // 找出原始 output 值
    const origResult = selectedRule.results.find(r => r.field === modifiedField);
    const origValue = origResult?.value;
    if (String(origValue) === modifiedValue) return null; // 沒變

    // 找出有相同 output 值的所有規則（受影響的群組）
    const affectedRules = rules.filter(r => {
      const result = r.results.find(res => res.field === modifiedField);
      return result && String(result.value) === String(origValue);
    });

    // 計算受影響比例
    const totalRules = rules.length;
    const affectedCount = affectedRules.length;
    const affectedPct = totalRules > 0 ? (affectedCount / totalRules * 100) : 0;

    // 統計原始和新值的分佈
    const outputDistribution: Record<string, number> = {};
    for (const r of rules) {
      const result = r.results.find(res => res.field === modifiedField);
      const val = String(result?.value ?? 'N/A');
      outputDistribution[val] = (outputDistribution[val] || 0) + 1;
    }

    return {
      originalValue: String(origValue),
      newValue: modifiedValue,
      affectedRules: affectedRules.map(r => r.ruleId),
      affectedCount,
      affectedPct,
      totalRules,
      outputDistribution,
    };
  }, [selectedRule, modifiedField, modifiedValue, rules]);

  // DecisionTree 不適用此 panel — 早返回必須在所有 hooks 宣告之後（避免 hook 順序違反 React 規則）
  if (envelope?.ruleType === 'DecisionTree') {
    return (
      <div className="rounded-xl border p-4 text-xs
        dark:bg-surface-1 dark:border-border dark:text-text-tertiary
        bg-white border-light-border text-light-text-tertiary">
        規則影響分析僅適用於決策表（DecisionTable）。決策樹的影響分析請改看「分析」頁的「決策樹」標記與簡化建議。
      </div>
    );
  }

  return (
    <div className="rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 text-left cursor-pointer
          hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
      >
        <div className="flex items-center gap-2">
          <span className="text-sm font-bold dark:text-text-primary text-light-text-primary">
            規則影響分析
          </span>
          <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
            修改一條規則，看影響範圍
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
            <div className="px-4 pb-4 space-y-3">
              {/* 步驟 1: 選規則 */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div>
                  <label className="block text-[10px] font-medium mb-1 dark:text-text-tertiary text-light-text-tertiary">
                    選擇規則
                  </label>
                  <select value={selectedRuleId} onChange={e => setSelectedRuleId(e.target.value)}
                    className="w-full px-3 py-1.5 rounded-lg text-xs border outline-none cursor-pointer
                      dark:bg-surface-2 dark:text-text-primary dark:border-border
                      bg-light-surface-2 text-light-text-primary border-light-border">
                    <option value="">-- 選擇 --</option>
                    {rules.map(r => (
                      <option key={r.ruleId} value={r.ruleId}>{r.ruleId}</option>
                    ))}
                  </select>
                </div>

                {/* 步驟 2: 選要改的欄位 */}
                <div>
                  <label className="block text-[10px] font-medium mb-1 dark:text-text-tertiary text-light-text-tertiary">
                    修改欄位
                  </label>
                  <select value={modifiedField} onChange={e => { setModifiedField(e.target.value); setModifiedValue(''); }}
                    className="w-full px-3 py-1.5 rounded-lg text-xs border outline-none cursor-pointer
                      dark:bg-surface-2 dark:text-text-primary dark:border-border
                      bg-light-surface-2 text-light-text-primary border-light-border">
                    <option value="">-- 選擇 --</option>
                    {outputs.map(o => (
                      <option key={o.name} value={o.name}>{cn(o.name)}</option>
                    ))}
                  </select>
                </div>

                {/* 步驟 3: 新值 */}
                <div>
                  <label className="block text-[10px] font-medium mb-1 dark:text-text-tertiary text-light-text-tertiary">
                    改為
                  </label>
                  {(() => {
                    const outputDef = outputs.find(o => o.name === modifiedField);
                    if (outputDef?.allowedValues) {
                      return (
                        <select value={modifiedValue} onChange={e => setModifiedValue(e.target.value)}
                          className="w-full px-3 py-1.5 rounded-lg text-xs border outline-none cursor-pointer
                            dark:bg-surface-2 dark:text-text-primary dark:border-border
                            bg-light-surface-2 text-light-text-primary border-light-border">
                          <option value="">-- 新值 --</option>
                          {outputDef.allowedValues.map(v => (
                            <option key={v} value={v}>{v}</option>
                          ))}
                        </select>
                      );
                    }
                    return (
                      <input type="text" value={modifiedValue} onChange={e => setModifiedValue(e.target.value)}
                        placeholder="輸入新值"
                        className="w-full px-3 py-1.5 rounded-lg text-xs border outline-none
                          dark:bg-surface-2 dark:text-text-primary dark:border-border
                          bg-light-surface-2 text-light-text-primary border-light-border" />
                    );
                  })()}
                </div>
              </div>

              {/* 選中規則的目前值 */}
              {selectedRule && (
                <div className="p-3 rounded-lg dark:bg-surface-2/50 bg-light-surface-2/50">
                  <p className="text-[10px] font-medium dark:text-text-tertiary text-light-text-tertiary mb-1.5">
                    {selectedRule.ruleId} 目前的結果
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {selectedRule.results.map(r => (
                      <span key={r.field} className={`px-2 py-0.5 rounded text-[11px]
                        ${r.field === modifiedField
                          ? 'font-bold dark:bg-accent/10 dark:text-accent bg-accent/10 text-accent'
                          : 'dark:bg-surface-3 dark:text-text-secondary bg-light-surface-3 text-light-text-secondary'}`}>
                        {cn(r.field)}: {String(r.value)}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* 影響分析結果 */}
              {analysis && (
                <div className="p-3 rounded-lg border dark:border-amber-500/20 border-amber-200
                  dark:bg-amber-500/5 bg-amber-50">
                  <p className="text-xs font-bold dark:text-amber-400 text-amber-700 mb-2">
                    影響分析結果
                  </p>

                  <div className="grid grid-cols-3 gap-3 mb-3">
                    <div className="text-center">
                      <p className="text-2xl font-bold dark:text-text-primary text-light-text-primary">
                        {analysis.affectedCount}
                      </p>
                      <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">受影響規則</p>
                    </div>
                    <div className="text-center">
                      <p className="text-2xl font-bold dark:text-text-primary text-light-text-primary">
                        {analysis.affectedPct.toFixed(0)}%
                      </p>
                      <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">影響比例</p>
                    </div>
                    <div className="text-center">
                      <p className="text-2xl font-bold dark:text-text-primary text-light-text-primary">
                        {analysis.totalRules}
                      </p>
                      <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">規則總數</p>
                    </div>
                  </div>

                  <p className="text-xs dark:text-text-secondary text-light-text-secondary mb-2">
                    將 <span className="font-bold">{cn(modifiedField)}</span> 從
                    「<span className="font-bold dark:text-red-400 text-red-600">{analysis.originalValue}</span>」改為
                    「<span className="font-bold dark:text-emerald-400 text-emerald-600">{analysis.newValue}</span>」，
                    會影響以下 {analysis.affectedCount} 條規則：
                  </p>

                  <div className="flex flex-wrap gap-1">
                    {analysis.affectedRules.map(id => (
                      <span key={id} className="px-1.5 py-0.5 rounded text-[10px] font-bold
                        dark:bg-surface-3 dark:text-text-secondary bg-light-surface-3 text-light-text-secondary">
                        {id}
                      </span>
                    ))}
                  </div>

                  {/* 欄位值分佈 */}
                  <div className="mt-3 pt-2 border-t dark:border-amber-500/10 border-amber-200/50">
                    <p className="text-[10px] font-medium dark:text-text-tertiary text-light-text-tertiary mb-1.5">
                      {cn(modifiedField)} 目前值分佈
                    </p>
                    <div className="space-y-1">
                      {Object.entries(analysis.outputDistribution).map(([val, count]) => (
                        <div key={val} className="flex items-center gap-2">
                          <span className="text-[11px] w-20 truncate dark:text-text-secondary text-light-text-secondary">
                            {val}
                          </span>
                          <div className="flex-1 h-3 rounded-full dark:bg-surface-3 bg-light-surface-3 overflow-hidden">
                            <div className="h-full rounded-full bg-accent/60"
                              style={{ width: `${(count / rules.length) * 100}%` }} />
                          </div>
                          <span className="text-[10px] tabular-nums w-10 text-right dark:text-text-tertiary text-light-text-tertiary">
                            {count} 條
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
