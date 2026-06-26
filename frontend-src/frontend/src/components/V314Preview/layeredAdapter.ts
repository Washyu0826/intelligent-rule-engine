/**
 * v3.14 整合 adapter — 把 v3.13 RuleEnvelope（LLM 生產出的真實資料）轉成 5 層 view model。
 *
 * 設計依據：docs/v3.14-schema-evolution.md §3 5 層分層架構。
 *
 * 對應策略（v3.13 schema 沒有顯式 L1/L2/L5 欄位，故由既有欄位 derive）：
 *   L1 BA 摘要層      ← envelope.reason / RuleNarrative (v3.12)
 *   L2 業務語意層      ← 第一條規則的 condition 翻成中文 + tags
 *   L3 規則結構層      ← envelope.rule（既有）
 *   L4 執行表達層      ← 第一條規則的 conditions（既有）
 *   L5 整合對映層      ← FieldDef.fieldCode (v3.12) 等
 *
 * 目前 MVP：只顯示第一條規則。未來可加 rule selector。
 */

import type { LayeredRule } from './sampleData';
import type { RuleEnvelope } from '../../types';

// 內部 typed 視圖：v3.13 RuleEnvelope.rule 是 Record<string, unknown>，這裡做窄化
interface RuleShape {
  hitPolicy?: string;
  inputs?: Array<{ name: string; typeRef: string; allowedValues?: string[]; fieldCode?: string; unit?: string }>;
  outputs?: Array<{ name: string; typeRef: string }>;
  rules?: Array<{
    ruleId?: string;
    priority?: number;
    conditions?: Array<{ field: string; operator: string; value?: unknown; valueRef?: string }>;
    results?: Array<{ field: string; value?: unknown }>;
  }>;
}

const OPERATOR_ZH: Record<string, string> = {
  equals: '為',
  notEquals: '不為',
  greaterThan: '大於',
  greaterThanOrEqual: '大於或等於',
  lessThan: '小於',
  lessThanOrEqual: '小於或等於',
  between: '介於',
  in: '屬於',
  notIn: '不屬於',
  isNull: '為空',
  isNotNull: '不為空',
  anything: '任意',
};

function operatorToZh(operator: string): string {
  return OPERATOR_ZH[operator] ?? operator;
}

function valueToZh(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) {
    return value.length === 2 && typeof value[0] === 'number'
      ? `${value[0]} 至 ${value[1]}`
      : value.map((v) => valueToZh(v)).join(' / ');
  }
  return String(value);
}

function deriveBusinessOwner(envelope: RuleEnvelope): string {
  return envelope.audit?.businessOwner ?? envelope.audit?.createdBy ?? 'unknown';
}

function deriveTechOwner(envelope: RuleEnvelope): string {
  return envelope.audit?.modifiedBy ?? 'platform-team';
}

function deriveReviewStatus(envelope: RuleEnvelope): LayeredRule['audit']['reviewStatus'] {
  // v3.13 沒有 reviewStatus 概念；用 audit.operation 推測
  const op = envelope.audit?.operation;
  if (op === 'OPTIMIZE' || op === 'UPDATE') return 'in_review';
  return 'draft';
}

export interface AdapterOptions {
  /** 第幾條規則作為 5 層 view（預設 0 = 第一條）。 */
  ruleIndex?: number;
  /** 從原始描述中抽取的業務概念 tag（如「核保」、「ID 一致性」）。 */
  tagsFromDescription?: string[];
}

/**
 * 將真實 RuleEnvelope 轉成 LayeredRule view model。
 * 若 envelope 結構不完整（例如 rules[] 為空），會回傳一個 minimal 但合法的 view。
 */
export function adaptToLayeredRule(
  envelope: RuleEnvelope,
  options: AdapterOptions = {},
): LayeredRule {
  const ruleIndex = options.ruleIndex ?? 0;
  const rule = envelope.rule as RuleShape;
  const rules = rule?.rules ?? [];
  const targetRule = rules[ruleIndex];

  const inputs = (rule?.inputs ?? []).map((i) => ({
    name: i.name,
    typeRef: i.typeRef,
    allowedValues: i.allowedValues,
  }));

  const output = rule?.outputs?.[0] ?? { name: 'result', typeRef: 'STRING' };

  // L1 — 取 envelope.reason 作為 BA 摘要
  const summary = envelope.reason ?? `規則 ${targetRule?.ruleId ?? ruleIndex + 1}`;

  // L2 — 翻譯第一條規則的 conditions 為中文
  const conditionsZh = (targetRule?.conditions ?? [])
    .filter((c) => c.operator !== 'anything')
    .map((c) => ({
      fieldTerm: c.field,
      operatorTerm: operatorToZh(c.operator),
      valueTerm: c.valueRef ?? valueToZh(c.value),
    }));

  // L2 result — 取第一個 result 的 value
  const firstResult = targetRule?.results?.[0];
  const resultTerm = firstResult
    ? `${firstResult.field}: ${valueToZh(firstResult.value)}`
    : '（無結果輸出）';

  // L3 — 用 rule 既有結構
  const ruleStructure: LayeredRule['ruleStructure'] = {
    ruleType: (envelope.ruleType === 'DecisionTable' ? 'DecisionTable' : 'DecisionTable'),
    hitPolicy: (rule?.hitPolicy as 'ALL_FAIL') ?? 'ALL_FAIL', // v3.13 沒有 ALL_FAIL，但 view 顯示 v3.14 目標
    inputs,
    output: { name: output.name, typeRef: output.typeRef },
    severity: 'ERROR' as const, // v3.13 schema 無 severity
    errorMessage: typeof firstResult?.value === 'string'
      ? firstResult.value
      : `${firstResult?.field ?? 'output'}: ${valueToZh(firstResult?.value)}`,
  };

  // L4 — 執行表達層
  const executionLayer = (targetRule?.conditions ?? [])
    .filter((c) => c.operator !== 'anything')
    .map((c) => ({
      field: c.field,
      operator: c.operator,
      value: c.valueRef ?? (c.value as string | boolean) ?? '',
    }));

  // L5 — FieldDef.fieldCode 對映
  const fieldMappings: Record<
    string,
    { externalSystem: string; externalField: string; transform?: string }
  > = {};
  (rule?.inputs ?? []).forEach((i) => {
    if (i.fieldCode) {
      fieldMappings[i.name] = {
        externalSystem: 'POLICY_CORE',
        externalField: i.fieldCode,
        transform: i.unit ? `unit=${i.unit}` : undefined,
      };
    } else {
      // 沒 fieldCode：給 placeholder，提示「待補對應」
      fieldMappings[i.name] = {
        externalSystem: '（待對應）',
        externalField: i.name.toUpperCase(),
      };
    }
  });

  return {
    ruleId: targetRule?.ruleId ?? `R${String(ruleIndex + 1).padStart(2, '0')}`,
    schemaVersion: envelope.schemaVersion ?? '1.0.0',
    promptVersion: envelope.promptVersion ?? 'p3.13.0',
    narrative: {
      summary,
      tags: options.tagsFromDescription ?? [envelope.ruleType ?? 'rule'],
    },
    businessSemantic: {
      scope: null,
      conditions: conditionsZh,
      resultTerm,
    },
    ruleStructure,
    executionLayer,
    integration: {
      fieldMappings,
    },
    audit: {
      businessOwner: deriveBusinessOwner(envelope),
      techOwner: deriveTechOwner(envelope),
      regulatoryRef: envelope.audit?.businessDomain ?? '（待補法規依據）',
      effectiveDate: envelope.audit?.effectiveDate ?? envelope.audit?.createdAt?.split('T')[0] ?? new Date().toISOString().split('T')[0],
      version: envelope.versionId ?? '1.0',
      reviewStatus: deriveReviewStatus(envelope),
      reviewers: [],
    },
  };
}
