import { useState, useMemo, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { RuleEnvelope, LookupResponse } from '../../types';
import { api } from '../../api/rulesApi';

interface Props {
  envelope: RuleEnvelope;
  onMatchedRules?: (ruleIds: string[]) => void;
}

interface FieldDef {
  name: string;
  typeRef: string;
  allowedValues?: string[];
}

export default function RuleLookupPanel({ envelope, onMatchedRules }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [inputValues, setInputValues] = useState<Record<string, unknown>>({});
  const [result, setResult] = useState<LookupResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 英文→中文名稱映射
  const ENGLISH_TO_CHINESE: Record<string, string> = {
    age: '年齡', ageGroup: '年齡區間', ageInRange: '年齡是否在範圍內',
    gender: '性別', smoking: '吸菸狀態', smoker: '是否吸菸',
    bmi: 'BMI', bmiCategory: 'BMI 區間', bmiRange: 'BMI 區間',
    hypertension: '高血壓', hasHypertension: '是否有高血壓',
    diabetes: '糖尿病', hasDiabetes: '是否有糖尿病',
    heartDisease: '心臟病', hasHeartDisease: '是否有心臟病',
    occupationRisk: '職業風險等級', occupationClass: '職業類別',
    highRiskActivity: '高風險活動', claimCount: '理賠次數',
    disabilityLevel: '殘廢等級', creditScore: '信用評分',
    debtRatio: '負債比', annualIncome: '年收入',
    underwritingDecision: '核保決議', premiumFactor: '保費係數',
    rateLevel: '費率等級', surchargePercent: '加費百分比',
    maxCoverageAmount: '最高承保金額', exclusionClause: '除外條款',
    remark: '備註', decision: '決議', approval: '核准結果',
  };

  const TYPE_LABELS: Record<string, string> = {
    INTEGER: '整數', DECIMAL: '小數', BOOLEAN: '是/否',
    STRING: '文字', ENUM: '選項', DATE: '日期',
  };

  const inputs = useMemo<FieldDef[]>(() => {
    const rule = envelope?.rule as Record<string, unknown> | undefined;
    if (!rule) return [];
    const rawInputs = rule.inputs as FieldDef[] | undefined;
    return rawInputs || [];
  }, [envelope]);

  const getChineseName = (name: string) => ENGLISH_TO_CHINESE[name] || name;
  const getTypeLabel = (typeRef: string) => TYPE_LABELS[typeRef] || typeRef;

  // 從第一條規則 / 決策樹第一條路徑提取範例值
  const handleFillExample = useCallback(() => {
    const rule = envelope?.rule as Record<string, unknown> | undefined;
    if (!rule) return;
    const example: Record<string, unknown> = {};

    const rules = rule.rules as Array<{ conditions: Array<{ field: string; value: unknown }> }> | undefined;
    if (rules && rules.length > 0) {
      // DecisionTable
      const firstRule = rules[0];
      for (const cond of firstRule.conditions) {
        if (cond.value != null && cond.field) {
          if (Array.isArray(cond.value)) {
            example[cond.field] = cond.value[0];
          } else {
            example[cond.field] = cond.value;
          }
        }
      }
    } else {
      // DecisionTree：走第一條 leaf path 取每層條件值
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
          if (!(node.condition.field in example)) example[node.condition.field] = v;
        }
        const firstBranch = node.branches?.[0];
        if (firstBranch?.child) {
          if (firstBranch.condition?.field && firstBranch.condition.value !== undefined) {
            const v = Array.isArray(firstBranch.condition.value) ? firstBranch.condition.value[0] : firstBranch.condition.value;
            if (!(firstBranch.condition.field in example)) example[firstBranch.condition.field] = v;
          }
          walk(firstBranch.child);
          return;
        }
        if (node.trueBranch) { walk(node.trueBranch); return; }
        if (node.falseBranch) { walk(node.falseBranch); return; }
      };
      walk(root);
    }

    setInputValues(example);
  }, [envelope]);

  const handleChange = useCallback((field: string, value: unknown) => {
    setInputValues(prev => ({ ...prev, [field]: value }));
  }, []);

  const handleSubmit = useCallback(async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const res = await api.lookup(envelope, inputValues);
      setResult(res);
      if (res.matched && onMatchedRules) {
        onMatchedRules(res.matchedRules.map(r => r.ruleId));
      } else if (onMatchedRules) {
        onMatchedRules([]);
      }
    } catch (e: unknown) {
      const err = e as { message?: string };
      setError(err.message || '查詢失敗');
    } finally {
      setLoading(false);
    }
  }, [envelope, inputValues, onMatchedRules]);

  const handleReset = useCallback(() => {
    setInputValues({});
    setResult(null);
    setError(null);
    onMatchedRules?.([]);
  }, [onMatchedRules]);

  return (
    <div className="rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border overflow-hidden">
      {/* Header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 text-left cursor-pointer
          hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
      >
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold dark:text-text-primary text-light-text-primary">
            規則查詢
          </span>
          <span className="px-1.5 py-0.5 rounded text-[10px] font-medium
            bg-accent/15 text-accent">
            手動測試
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
              {/* Form fields */}
              {inputs.length === 0 ? (
                <p className="text-xs dark:text-text-tertiary text-light-text-tertiary">
                  無法讀取規則的輸入欄位定義
                </p>
              ) : (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {inputs.map((field) => (
                    <FieldInput
                      key={field.name}
                      field={field}
                      value={inputValues[field.name]}
                      onChange={(v) => handleChange(field.name, v)}
                      chineseName={getChineseName(field.name)}
                      typeLabel={getTypeLabel(field.typeRef)}
                    />
                  ))}
                </div>
              )}

              {/* Actions */}
              <div className="flex gap-2">
                <button
                  onClick={handleSubmit}
                  disabled={loading || inputs.length === 0}
                  className="px-4 py-1.5 rounded-lg text-xs font-semibold text-white
                    bg-accent hover:bg-accent/90 disabled:opacity-50 disabled:cursor-not-allowed
                    transition-colors cursor-pointer"
                >
                  {loading ? '查詢中...' : '查詢規則'}
                </button>
                <button
                  onClick={handleFillExample}
                  className="px-4 py-1.5 rounded-lg text-xs font-medium cursor-pointer
                    bg-blue-500/10 text-blue-600 dark:text-blue-400 hover:bg-blue-500/20
                    transition-colors"
                >
                  用範例填充
                </button>
                <button
                  onClick={handleReset}
                  className="px-4 py-1.5 rounded-lg text-xs font-medium cursor-pointer
                    dark:bg-surface-2 dark:text-text-secondary dark:hover:bg-surface-3
                    bg-light-surface-2 text-light-text-secondary hover:bg-light-surface-3
                    transition-colors"
                >
                  重置
                </button>
              </div>

              {/* Error */}
              {error && (
                <div className="p-3 rounded-lg bg-red-500/10 text-red-500 text-xs">
                  {error}
                </div>
              )}

              {/* Results */}
              {result && (
                <div className="space-y-3">
                  {result.matched ? (
                    <>
                      <div className="flex items-center gap-2">
                        <span className="w-2 h-2 rounded-full bg-emerald-500" />
                        <span className="text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                          命中 {result.matchedRules.length} 條規則
                        </span>
                      </div>
                      {result.matchedRules.map((rule) => (
                        <div key={rule.ruleId} className="p-3 rounded-lg border
                          dark:bg-emerald-500/5 dark:border-emerald-500/20
                          bg-emerald-50 border-emerald-200">
                          <div className="flex items-center gap-2 mb-2">
                            <span className="text-xs font-bold dark:text-emerald-400 text-emerald-700">
                              {rule.ruleId}
                            </span>
                            {rule.priority != null && (
                              <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
                                優先序 {rule.priority}
                              </span>
                            )}
                          </div>
                          <div className="grid grid-cols-1 gap-1">
                            {Object.entries(rule.results).map(([key, val]) => (
                              <div key={key} className="flex items-center gap-2 text-[11px]">
                                <span className="dark:text-text-tertiary text-light-text-tertiary font-medium">
                                  {key}:
                                </span>
                                <span className="dark:text-text-primary text-light-text-primary font-semibold">
                                  {String(val)}
                                </span>
                              </div>
                            ))}
                          </div>
                        </div>
                      ))}
                    </>
                  ) : (
                    <div className="p-3 rounded-lg border text-center
                      dark:bg-surface-2 dark:border-border bg-light-surface-2 border-light-border">
                      <p className="text-xs dark:text-text-secondary text-light-text-secondary">
                        未找到匹配的規則
                      </p>
                      {result.unmatchedInputs.length > 0 && (
                        <p className="text-[10px] mt-1 dark:text-text-tertiary text-light-text-tertiary">
                          未匹配欄位: {result.unmatchedInputs.join(', ')}
                        </p>
                      )}
                    </div>
                  )}

                  {/* Evaluation path */}
                  {result.evaluationPath.length > 0 && (
                    <details className="text-[10px]">
                      <summary className="cursor-pointer dark:text-text-tertiary text-light-text-tertiary font-medium">
                        評估路徑 ({result.evaluationPath.length} steps)
                      </summary>
                      <div className="mt-1 max-h-32 overflow-y-auto space-y-0.5 pl-2">
                        {result.evaluationPath.map((step, i) => (
                          <div key={i} className={`${step.includes('MATCHED') ? 'dark:text-emerald-400 text-emerald-600 font-semibold' : 'dark:text-text-tertiary text-light-text-tertiary'}`}>
                            {step}
                          </div>
                        ))}
                      </div>
                    </details>
                  )}
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function FieldInput({ field, value, onChange, chineseName, typeLabel }: { field: FieldDef; value: unknown; onChange: (v: unknown) => void; chineseName?: string; typeLabel?: string }) {
  const baseInput = `w-full px-3 py-1.5 rounded-lg text-xs border outline-none transition-colors
    dark:bg-surface-2 dark:text-text-primary dark:border-border dark:focus:border-accent/50
    bg-light-surface-2 text-light-text-primary border-light-border focus:border-accent/50`;

  return (
    <div>
      <label className="block text-[10px] font-medium mb-1
        dark:text-text-tertiary text-light-text-tertiary">
        {chineseName || field.name}
        <span className="ml-1 opacity-50">({typeLabel || field.typeRef})</span>
      </label>

      {field.typeRef === 'BOOLEAN' ? (
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={!!value}
            onChange={(e) => onChange(e.target.checked)}
            className="w-4 h-4 rounded accent-accent"
          />
          <span className="text-xs dark:text-text-secondary text-light-text-secondary">
            {value ? 'true' : 'false'}
          </span>
        </label>
      ) : field.typeRef === 'ENUM' && field.allowedValues ? (
        <select
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
          className={baseInput + ' cursor-pointer'}
        >
          <option value="">-- 請選擇 --</option>
          {field.allowedValues.map((v) => (
            <option key={v} value={v}>{v}</option>
          ))}
        </select>
      ) : field.typeRef === 'INTEGER' || field.typeRef === 'DECIMAL' ? (
        <input
          type="number"
          value={value != null ? String(value) : ''}
          onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}
          step={field.typeRef === 'DECIMAL' ? '0.01' : '1'}
          className={baseInput}
          placeholder={`輸入${field.typeRef === 'INTEGER' ? '整數' : '數值'}`}
        />
      ) : field.typeRef === 'DATE' ? (
        <input
          type="date"
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
          className={baseInput}
        />
      ) : (
        <input
          type="text"
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
          className={baseInput}
          placeholder={`輸入 ${field.name}`}
        />
      )}
    </div>
  );
}
