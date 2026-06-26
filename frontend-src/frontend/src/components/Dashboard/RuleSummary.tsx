import { useMemo, useState } from 'react';
import type { RuleEnvelope } from '../../types';

interface Props {
  envelope: RuleEnvelope;
}

interface Condition {
  field: string;
  operator: string;
  value: unknown;
}

interface Result {
  field: string;
  value: unknown;
}

interface RuleRow {
  ruleId: string;
  priority?: number;
  conditions: Condition[];
  results: Result[];
}

const FIELD_CN: Record<string, string> = {
  age: '年齡', ageGroup: '年齡區間', ageInRange: '年齡範圍',
  gender: '性別', smoking: '吸菸狀態', smoker: '是否吸菸',
  bmi: 'BMI', bmiCategory: 'BMI 區間',
  hypertension: '高血壓', hasHypertension: '高血壓',
  diabetes: '糖尿病', hasDiabetes: '糖尿病',
  heartDisease: '心臟病', hasHeartDisease: '心臟病',
  occupationRisk: '職業風險', occupationClass: '職業類別',
  highRiskActivity: '高風險活動', claimCount: '理賠次數',
  disabilityLevel: '殘廢等級', creditScore: '信用評分',
  underwritingDecision: '核保決議', premiumFactor: '保費係數',
  rateLevel: '費率等級', surchargePercent: '加費百分比',
  surchargeReason: '加費原因', maxCoverageAmount: '最高承保金額',
  maxCoverage: '最高承保金額', exclusionClause: '除外條款',
  exclusionNote: '除外條款說明', remark: '備註', decision: '決議',
  approval: '核准結果', creditLimit: '授信額度', premiumRate: '保費費率',
};

const OP_CN: Record<string, string> = {
  equals: '為', notEquals: '不為', greaterThan: '大於',
  greaterThanOrEqual: '大於等於', lessThan: '小於',
  lessThanOrEqual: '小於等於', between: '介於',
  in: '屬於', notIn: '不屬於', isNull: '為空',
  isNotNull: '不為空', anything: '任意值',
};

function cn(field: string): string {
  return FIELD_CN[field] || field;
}

function formatCondition(c: Condition): string {
  const field = cn(c.field);
  const op = OP_CN[c.operator] || c.operator;

  if (c.operator === 'anything') return `${field}為任意值`;
  if (c.operator === 'isNull') return `${field}為空`;
  if (c.operator === 'isNotNull') return `${field}不為空`;

  if (c.operator === 'between' && Array.isArray(c.value)) {
    return `${field}介於 ${c.value[0]} ~ ${c.value[1]}`;
  }

  if (c.operator === 'in' && Array.isArray(c.value)) {
    return `${field}屬於 [${c.value.join(', ')}]`;
  }

  if (c.operator === 'equals') {
    if (typeof c.value === 'boolean') {
      return c.value ? `有${field}` : `無${field}`;
    }
    return `${field}${op} ${c.value}`;
  }

  return `${field} ${op} ${c.value}`;
}

function formatResult(r: Result): string {
  return `${cn(r.field)}為 ${r.value}`;
}

function ruleToSentence(rule: RuleRow): string {
  const conditions = rule.conditions.map(formatCondition).join('、');
  const results = rule.results.map(formatResult).join('、');
  return `當 ${conditions} 時 → ${results}`;
}

export default function RuleSummary({ envelope }: Props) {
  const sentences = useMemo(() => {
    const rule = envelope?.rule as Record<string, unknown> | undefined;
    if (!rule) return [];
    const rules = rule.rules as RuleRow[] | undefined;
    if (!rules) return [];
    return rules.map(r => ({
      ruleId: r.ruleId,
      sentence: ruleToSentence(r),
    }));
  }, [envelope]);

  const [expanded, setExpanded] = useState(false);
  const PREVIEW_COUNT = 5;

  if (sentences.length === 0) return null;

  const visible = expanded ? sentences : sentences.slice(0, PREVIEW_COUNT);
  const hasMore = sentences.length > PREVIEW_COUNT;

  return (
    <div className="rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border overflow-hidden">
      <div className="px-4 py-3 border-b dark:border-border border-light-border">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-sm font-bold dark:text-text-primary text-light-text-primary">
              規則摘要
            </span>
            <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              共 {sentences.length} 條
            </span>
          </div>
          <button
            onClick={() => {
              const text = sentences.map(s => `${s.ruleId}: ${s.sentence}`).join('\n');
              navigator.clipboard.writeText(text);
            }}
            className="px-2 py-1 rounded text-[10px] font-medium cursor-pointer
              dark:bg-surface-2 dark:text-text-tertiary dark:hover:text-text-secondary
              bg-light-surface-2 text-light-text-tertiary hover:text-light-text-secondary
              transition-colors"
          >
            複製全部
          </button>
        </div>
      </div>

      <div>
        {visible.map(({ ruleId, sentence }) => (
          <div key={ruleId}
            className="px-4 py-2.5 border-b last:border-0 dark:border-border/50 border-light-border/50"
          >
            <div className="flex gap-3 items-start">
              <span className="shrink-0 w-9 text-[10px] font-bold text-right tabular-nums
                dark:text-text-tertiary text-light-text-tertiary pt-0.5">
                {ruleId}
              </span>
              <p className="text-xs leading-relaxed dark:text-text-secondary text-light-text-secondary">
                {sentence}
              </p>
            </div>
          </div>
        ))}
      </div>

      {hasMore && (
        <button
          onClick={() => setExpanded(!expanded)}
          className="w-full py-2 text-[11px] font-medium cursor-pointer
            dark:text-text-tertiary dark:hover:text-text-secondary
            text-light-text-tertiary hover:text-light-text-secondary
            border-t dark:border-border/50 border-light-border/50 transition-colors"
        >
          {expanded ? '收合' : `展開全部 ${sentences.length} 條規則`}
        </button>
      )}
    </div>
  );
}
