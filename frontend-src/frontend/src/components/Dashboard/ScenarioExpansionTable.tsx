import { useMemo } from 'react';
import type { RuleEnvelope, ScenarioDomain, ScenarioExpandResponse, ScenarioRow } from '../../types';

interface Props {
  envelope: RuleEnvelope;
}

interface FieldDef {
  name: string;
  typeRef?: string;
  allowedValues?: string[];
}

interface RuleCondition {
  field: string;
  operator: string;
  value?: unknown;
}

interface RuleResult {
  field: string;
  value: unknown;
}

interface RuleEntry {
  ruleId: string;
  priority?: number;
  conditions?: RuleCondition[];
  results?: RuleResult[];
}

const MAX_ROWS = 128;

function asFields(value: unknown): FieldDef[] {
  return Array.isArray(value) ? value as FieldDef[] : [];
}

function asRules(value: unknown): RuleEntry[] {
  return Array.isArray(value) ? value as RuleEntry[] : [];
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) return value.map(formatValue).join(', ');
  if (value == null) return '';
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
}

function defaultValue(field: FieldDef): unknown {
  if (field.allowedValues?.length) return field.allowedValues[0];
  switch (field.typeRef) {
    case 'INTEGER': return 0;
    case 'DECIMAL': return 0;
    case 'BOOLEAN': return true;
    case 'DATE': return '2026-01-01';
    default: return 'sample';
  }
}

function buildDomains(inputs: FieldDef[], rules: RuleEntry[]): { domains: ScenarioDomain[]; warnings: string[] } {
  const warnings: string[] = [];
  const domains = inputs.map((input) => {
    let values: unknown[] = [];
    let finite = true;
    let source = 'allowedValues';

    if (input.allowedValues?.length) {
      values = input.allowedValues;
    } else if (input.typeRef === 'BOOLEAN') {
      values = [true, false];
      source = 'typeRef';
    } else {
      finite = false;
      source = 'conditionSamples';
      const samples = new Set<unknown>();
      for (const rule of rules) {
        for (const condition of rule.conditions ?? []) {
          if (condition.field !== input.name) continue;
          if (condition.operator === 'equals') samples.add(condition.value);
          if (condition.operator === 'in' && Array.isArray(condition.value)) {
            condition.value.forEach((v) => samples.add(v));
          }
          if (condition.operator === 'between' && Array.isArray(condition.value)) {
            samples.add(condition.value[0]);
            samples.add(condition.value[1]);
          }
        }
      }
      values = Array.from(samples);
      if (values.length === 0) values = [defaultValue(input)];
      warnings.push(`${input.name} 未提供有限 allowedValues，目前以規則樣本值做分析。`);
    }

    return {
      field: input.name,
      typeRef: input.typeRef ?? 'STRING',
      values,
      finite,
      source,
    };
  });

  return { domains, warnings };
}

function cartesian(domains: ScenarioDomain[], maxRows: number): Record<string, unknown>[] {
  let rows: Record<string, unknown>[] = [{}];
  for (const domain of domains) {
    const next: Record<string, unknown>[] = [];
    const values = domain.values.length ? domain.values : [''];
    for (const row of rows) {
      for (const value of values) {
        next.push({ ...row, [domain.field]: value });
        if (next.length >= maxRows) break;
      }
      if (next.length >= maxRows) break;
    }
    rows = next;
    if (rows.length >= maxRows) break;
  }
  return rows;
}

function equalsValue(expected: unknown, actual: unknown): boolean {
  if (expected === actual) return true;
  return String(expected) === String(actual);
}

function numeric(value: unknown): number {
  return typeof value === 'number' ? value : Number(value);
}

function matches(condition: RuleCondition, inputValues: Record<string, unknown>): boolean {
  const actual = inputValues[condition.field];
  const expected = condition.value;

  switch (condition.operator) {
    case 'anything': return true;
    case 'equals': return equalsValue(expected, actual);
    case 'notEquals': return !equalsValue(expected, actual);
    case 'in': return Array.isArray(expected) && expected.some((v) => equalsValue(v, actual));
    case 'notIn': return Array.isArray(expected) && !expected.some((v) => equalsValue(v, actual));
    case 'greaterThan': return numeric(actual) > numeric(expected);
    case 'greaterThanOrEqual': return numeric(actual) >= numeric(expected);
    case 'lessThan': return numeric(actual) < numeric(expected);
    case 'lessThanOrEqual': return numeric(actual) <= numeric(expected);
    case 'between':
      return Array.isArray(expected)
        && expected.length >= 2
        && numeric(actual) >= numeric(expected[0])
        && numeric(actual) <= numeric(expected[1]);
    case 'isNull': return actual == null;
    case 'isNotNull': return actual != null;
    default: return false;
  }
}

function appendResult(results: Record<string, unknown>, field: string, value: unknown) {
  if (!(field in results)) {
    results[field] = value;
    return;
  }
  const existing = results[field];
  results[field] = Array.isArray(existing) ? [...existing, value] : [existing, value];
}

function expand(envelope: RuleEnvelope): ScenarioExpandResponse {
  const rule = envelope.rule as Record<string, unknown> | undefined;
  const inputs = asFields(rule?.inputs);
  const rules = asRules(rule?.rules);
  const hitPolicy = String(rule?.hitPolicy ?? 'FIRST').toUpperCase();
  const { domains, warnings } = buildDomains(inputs, rules);
  const totalPossible = domains.reduce((total, domain) => total * Math.max(domain.values.length, 1), 1);
  const rows = cartesian(domains, MAX_ROWS);

  const scenarios: ScenarioRow[] = rows.map((inputValues, index) => {
    const matchedRules: RuleEntry[] = [];
    const evaluationPath: string[] = [];

    for (const candidate of rules) {
      const isMatch = (candidate.conditions ?? []).every((condition) => matches(condition, inputValues));
      evaluationPath.push(`${candidate.ruleId} ${isMatch ? 'MATCHED' : 'SKIPPED'}`);
      if (isMatch) {
        matchedRules.push(candidate);
        if (hitPolicy === 'FIRST') break;
      }
    }

    const results: Record<string, unknown> = {};
    for (const matched of matchedRules) {
      for (const result of matched.results ?? []) {
        appendResult(results, result.field, result.value);
      }
    }

    return {
      scenarioId: `S${String(index + 1).padStart(3, '0')}`,
      inputValues,
      matched: matchedRules.length > 0,
      matchedRuleIds: matchedRules.map((ruleRow) => ruleRow.ruleId),
      results,
      outcomeLabel: matchedRules.length > 0 ? 'MATCHED' : 'PASS',
      evaluationPath,
    };
  });

  return {
    totalPossible,
    returned: scenarios.length,
    truncated: totalPossible > scenarios.length,
    noMatchLabel: 'PASS',
    domains,
    scenarios,
    warnings,
  };
}

export default function ScenarioExpansionTable({ envelope }: Props) {
  const data = useMemo(() => expand(envelope), [envelope]);
  const rule = envelope.rule as Record<string, unknown> | undefined;
  const inputs = useMemo(() => asFields(rule?.inputs), [rule]);
  const outputs = useMemo(() => asFields(rule?.outputs), [rule]);

  return (
    <div className="
      rounded-xl border overflow-hidden animate-fade-in-up
      dark:bg-surface-1 dark:border-border bg-white border-light-border
      print:break-inside-avoid print:rounded-none print:border print:border-gray-300
    ">
      <div className="
        flex items-center justify-between px-4 py-3 border-b
        dark:border-border border-light-border
      ">
        <div>
          <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
            情境展開分析
          </p>
          <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
            顯示 {data.returned}/{data.totalPossible} 種測試情境；正式規則仍以原始 rules 為準
          </p>
        </div>
        {data.truncated && (
          <span className="
            text-[10px] font-semibold px-2 py-1 rounded-md
            bg-amber-500/10 text-amber-500 border border-amber-500/15
          ">
            已截斷
          </span>
        )}
      </div>

      <div className="
        px-4 py-2 flex flex-wrap gap-2 border-b
        dark:border-border/50 border-light-border/50
        dark:bg-surface-2/40 bg-light-surface-2/40
      ">
        {data.domains.map((domain) => (
          <span
            key={domain.field}
            className={`
              inline-flex items-center gap-1.5 px-2 py-1 rounded-md text-[10px]
              border ${domain.finite
                ? 'bg-emerald-500/8 text-emerald-500 border-emerald-500/15'
                : 'bg-amber-500/8 text-amber-500 border-amber-500/15'
              }
            `}
          >
            <span className="font-semibold">{domain.field}</span>
            <span className="font-mono opacity-75">{domain.values.map(formatValue).join('|')}</span>
          </span>
        ))}
      </div>

      <div className="overflow-auto max-h-[460px]">
        <table className="w-full text-left border-collapse text-[11px]">
          <thead className="sticky top-0 z-10">
            <tr className="dark:bg-surface-2 bg-light-surface-2">
              <th className="px-3 py-2 font-semibold dark:text-text-secondary text-light-text-secondary">ID</th>
              {inputs.map((input) => (
                <th key={input.name} className="px-3 py-2 font-semibold dark:text-text-secondary text-light-text-secondary">
                  {input.name}
                </th>
              ))}
              <th className="px-3 py-2 font-semibold dark:text-text-secondary text-light-text-secondary">命中規則</th>
              <th className="px-3 py-2 font-semibold dark:text-text-secondary text-light-text-secondary">結果</th>
              {outputs.map((output) => (
                <th key={output.name} className="px-3 py-2 font-semibold dark:text-text-secondary text-light-text-secondary">
                  {output.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data.scenarios.map((row, index) => (
              <tr
                key={row.scenarioId}
                className={`
                  border-b dark:border-border/40 border-light-border/40
                  ${index % 2 === 0 ? 'dark:bg-surface-1 bg-white' : 'dark:bg-surface-1/50 bg-light-surface-1/50'}
                `}
              >
                <td className="px-3 py-2 font-mono font-semibold dark:text-text-tertiary text-light-text-tertiary">
                  {row.scenarioId}
                </td>
                {inputs.map((input) => (
                  <td key={`${row.scenarioId}-${input.name}`} className="px-3 py-2 font-mono dark:text-text-secondary text-light-text-secondary">
                    {formatValue(row.inputValues[input.name])}
                  </td>
                ))}
                <td className="px-3 py-2">
                  {row.matchedRuleIds.length > 0 ? (
                    <div className="flex flex-wrap items-center gap-1">
                      {row.matchedRuleIds.map((ruleId) => (
                        <span
                          key={`${row.scenarioId}-rule-${ruleId}`}
                          className="
                            inline-flex items-center px-1.5 py-0.5 rounded
                            text-[10px] font-mono font-semibold
                            bg-accent/10 text-accent border border-accent/20
                          "
                        >
                          {ruleId}
                        </span>
                      ))}
                      {row.matchedRuleIds.length > 1 && (
                        <span
                          title="此情境同時觸發多條規則 (MULTI hit policy)"
                          className="
                            inline-flex items-center px-1.5 py-0.5 rounded
                            text-[9px] font-semibold tracking-wide uppercase
                            bg-rose-500/10 text-rose-500 border border-rose-500/20
                          "
                        >
                          多重命中 ×{row.matchedRuleIds.length}
                        </span>
                      )}
                    </div>
                  ) : (
                    <span className="font-mono dark:text-text-tertiary text-light-text-tertiary">-</span>
                  )}
                </td>
                <td className="px-3 py-2">
                  <span className={`
                    inline-flex px-2 py-0.5 rounded text-[10px] font-semibold
                    ${row.matched
                      ? 'bg-rose-500/10 text-rose-500'
                      : 'bg-emerald-500/10 text-emerald-500'
                    }
                  `}>
                    {row.outcomeLabel}
                  </span>
                </td>
                {outputs.map((output) => {
                  const value = row.results?.[output.name];
                  if (Array.isArray(value)) {
                    return (
                      <td
                        key={`${row.scenarioId}-${output.name}`}
                        className="px-3 py-2 font-mono dark:text-text-primary text-light-text-primary"
                      >
                        <div className="flex flex-wrap gap-1">
                          {value.map((entry, idx) => (
                            <span
                              key={`${row.scenarioId}-${output.name}-${idx}`}
                              className="
                                inline-flex px-1.5 py-0.5 rounded text-[10px]
                                bg-rose-500/10 text-rose-500 border border-rose-500/20
                              "
                            >
                              {row.matchedRuleIds[idx] ? `${row.matchedRuleIds[idx]}: ` : ''}
                              {formatValue(entry)}
                            </span>
                          ))}
                        </div>
                      </td>
                    );
                  }
                  return (
                    <td
                      key={`${row.scenarioId}-${output.name}`}
                      className="px-3 py-2 font-mono dark:text-text-primary text-light-text-primary"
                    >
                      {formatValue(value)}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data.warnings.length > 0 && (
        <div className="
          px-4 py-2 border-t text-[10px]
          dark:border-border/50 border-light-border/50
          dark:text-text-tertiary text-light-text-tertiary
        ">
          {data.warnings.join(' ')}
        </div>
      )}
    </div>
  );
}
