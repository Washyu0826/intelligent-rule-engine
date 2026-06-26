import { useMemo } from 'react';
import type { GenerationResult } from '../../types';

interface Props {
  result: GenerationResult;
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
  age: '年齡', ageGroup: '年齡區間', ageInRange: '年齡範圍', gender: '性別',
  smoking: '吸菸', smoker: '是否吸菸', bmiCategory: 'BMI區間',
  hasHypertension: '高血壓', hasDiabetes: '糖尿病', hasHeartDisease: '心臟病',
  occupationRisk: '職業風險', occupationClass: '職業類別',
  highRiskActivity: '高風險活動', claimCount: '理賠次數', disabilityLevel: '殘廢等級',
  underwritingDecision: '核保決議', premiumFactor: '保費係數',
  rateLevel: '費率等級', surchargePercent: '加費百分比', surchargeReason: '加費原因',
  maxCoverage: '最高承保金額', maxCoverageAmount: '最高承保金額',
  exclusionClause: '除外條款', exclusionNote: '除外條款說明',
  remark: '備註', decision: '決議', approval: '核准結果',
};

const OP_CN: Record<string, string> = {
  equals: '等於', between: '介於', in: '屬於', anything: '任意',
  greaterThan: '>', greaterThanOrEqual: '>=', lessThan: '<', lessThanOrEqual: '<=',
};

function cn(f: string) { return FIELD_CN[f] || f; }

function formatCondition(c: { field: string; operator: string; value: unknown }): string {
  if (c.operator === 'anything') return `${cn(c.field)}為任意值`;
  if (c.operator === 'equals') {
    if (typeof c.value === 'boolean') return c.value ? `有${cn(c.field)}` : `無${cn(c.field)}`;
    return `${cn(c.field)}為${c.value}`;
  }
  if (c.operator === 'between' && Array.isArray(c.value)) return `${cn(c.field)}介於${c.value[0]}~${c.value[1]}`;
  if (c.operator === 'in' && Array.isArray(c.value)) return `${cn(c.field)}屬於[${c.value.join(',')}]`;
  return `${cn(c.field)} ${OP_CN[c.operator] || c.operator} ${c.value}`;
}

// DecisionTree → 列印用扁平路徑：從 root 遞迴展開所有 leaf 路徑
interface TreePath {
  pathLabel: string;
  results: Array<{ field: string; value: unknown }>;
}
interface DTNode {
  nodeId?: string;
  condition?: { field: string; operator: string; value: unknown };
  branches?: Array<{ label?: string; condition?: { field: string; operator: string; value: unknown }; child?: DTNode }>;
  trueBranch?: DTNode;
  falseBranch?: DTNode;
  results?: Array<{ field: string; value: unknown }>;
}
function flattenTreePaths(root: DTNode | undefined): TreePath[] {
  if (!root) return [];
  const out: TreePath[] = [];
  const visit = (node: DTNode | undefined, conds: string[]) => {
    if (!node) return;
    if (node.results && node.results.length > 0 && !node.branches?.length && !node.trueBranch && !node.falseBranch) {
      out.push({
        pathLabel: conds.length > 0 ? conds.join('，且 ') : '預設情境',
        results: node.results,
      });
      return;
    }
    if (node.branches && node.branches.length > 0) {
      for (const b of node.branches) {
        const c = b.condition || node.condition;
        const label = c
          ? formatCondition({ field: c.field, operator: c.operator, value: c.value })
          : (b.label ?? '其他');
        visit(b.child, [...conds, label]);
      }
      return;
    }
    if (node.trueBranch || node.falseBranch) {
      if (node.trueBranch && node.condition) {
        visit(node.trueBranch, [...conds, formatCondition(node.condition)]);
      }
      if (node.falseBranch && node.condition) {
        const neg = `非（${formatCondition(node.condition)}）`;
        visit(node.falseBranch, [...conds, neg]);
      }
    }
  };
  visit(root, []);
  return out;
}

export default function BusinessReport({ result }: Props) {
  const { generate, validate, analyze, durationMs, originalDescription } = result;
  const rule = generate?.rule as Record<string, unknown> | undefined;
  const rules = useMemo(() => (rule?.rules as RuleRow[] | undefined) || [], [rule]);
  const inputs = useMemo(() => (rule?.inputs as FieldDef[] | undefined) || [], [rule]);
  const outputs = useMemo(() => (rule?.outputs as FieldDef[] | undefined) || [], [rule]);
  const evaluation = generate?.evaluation;
  const isDecisionTree = generate?.ruleType === 'DecisionTree';
  const ruleCountDisplay = isDecisionTree
    ? `${evaluation?.totalScenarios ?? 0} 條路徑`
    : `${rules.length} 條`;
  const now = new Date().toLocaleString('zh-TW', { hour12: false });

  const handlePrint = () => window.print();

  return (
    <div className="space-y-4">
      {/* 預覽 + 列印按鈕 */}
      <div className="flex items-center justify-between print:hidden">
        <h3 className="text-sm font-bold dark:text-text-primary text-light-text-primary">
          規則交付報告
        </h3>
        <button onClick={handlePrint}
          className="px-4 py-1.5 rounded-lg text-xs font-semibold text-white
            bg-accent hover:bg-accent/90 transition-colors cursor-pointer">
          下載 / 列印報告
        </button>
      </div>

      {/* 報告內容（列印時顯示） */}
      <div className="rounded-xl border p-6 space-y-6
        dark:bg-surface-1 dark:border-border bg-white border-light-border
        print:border-gray-300 print:shadow-none print:p-8">

        {/* 標題 */}
        <div className="text-center pb-4 border-b dark:border-border border-light-border print:border-gray-300">
          <h1 className="text-xl font-bold dark:text-text-primary text-light-text-primary print:text-black">
            {generate?.ruleType === 'DecisionTree' ? '決策樹' : '決策表'}交付報告
          </h1>
          <p className="text-xs dark:text-text-tertiary text-light-text-tertiary print:text-gray-500 mt-1">
            生成日期：{now} · 耗時：{durationMs ? `${(durationMs / 1000).toFixed(1)}s` : 'N/A'}
          </p>
          <p className="text-[11px] dark:text-text-secondary text-light-text-secondary print:text-gray-600 mt-2 leading-relaxed">
            本文件用於業務規格審查與交付對照。RuleEnvelope 為 engine-neutral 中介模型，
            正式導入集團規則引擎前需由 adapter 轉換欄位代碼、operator 與部署格式。
          </p>
        </div>

        {/* 1. 需求描述 */}
        <section>
          <h2 className="text-sm font-bold mb-2 dark:text-text-primary text-light-text-primary print:text-black">
            一、需求描述
          </h2>
          <p className="text-xs leading-relaxed dark:text-text-secondary text-light-text-secondary print:text-gray-700">
            {originalDescription || '(未記錄)'}
          </p>
        </section>

        {/* 2. 品質摘要 */}
        <section>
          <h2 className="text-sm font-bold mb-2 dark:text-text-primary text-light-text-primary print:text-black">
            二、品質摘要
          </h2>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            {[
              { label: isDecisionTree ? '決策路徑' : '規則數', value: ruleCountDisplay },
              { label: '覆蓋率', value: `${((evaluation?.coverageRate ?? 0) * 100).toFixed(1)}%` },
              { label: '完整性', value: evaluation?.completeness === 'COMPLETE' ? '完整' : '不完整' },
              { label: '衝突', value: evaluation?.conflictDetection === 'NO_CONFLICT' ? '無衝突' : '有衝突' },
            ].map(item => (
              <div key={item.label} className="p-2 rounded-lg dark:bg-surface-2 bg-light-surface-2 print:border print:border-gray-300 text-center">
                <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary print:text-gray-500">{item.label}</p>
                <p className="text-sm font-bold dark:text-text-primary text-light-text-primary print:text-black">{item.value}</p>
              </div>
            ))}
          </div>
          {validate && !validate.valid && (
            <p className="mt-2 text-xs text-red-500 print:text-red-700">
              驗證發現 {validate.errors?.length ?? 0} 個問題，請參閱驗證報告。
            </p>
          )}
        </section>

        {/* 3. 欄位定義 */}
        <section>
          <h2 className="text-sm font-bold mb-2 dark:text-text-primary text-light-text-primary print:text-black">
            三、欄位定義
          </h2>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <p className="text-[10px] font-medium mb-1 dark:text-text-tertiary text-light-text-tertiary print:text-gray-500">
                輸入欄位（{inputs.length} 個）
              </p>
              <div className="space-y-1">
                {inputs.map(f => (
                  <div key={f.name} className="text-xs dark:text-text-secondary text-light-text-secondary print:text-gray-700">
                    <span className="font-semibold">{cn(f.name)}</span>
                    <span className="opacity-60 ml-1">({f.typeRef})</span>
                    {f.allowedValues && <span className="opacity-50 ml-1">[{f.allowedValues.join(', ')}]</span>}
                  </div>
                ))}
              </div>
            </div>
            <div>
              <p className="text-[10px] font-medium mb-1 dark:text-text-tertiary text-light-text-tertiary print:text-gray-500">
                輸出欄位（{outputs.length} 個）
              </p>
              <div className="space-y-1">
                {outputs.map(f => (
                  <div key={f.name} className="text-xs dark:text-text-secondary text-light-text-secondary print:text-gray-700">
                    <span className="font-semibold">{cn(f.name)}</span>
                    <span className="opacity-60 ml-1">({f.typeRef})</span>
                    {f.allowedValues && <span className="opacity-50 ml-1">[{f.allowedValues.join(', ')}]</span>}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* 4. 規則明細（前 20 條） */}
        {isDecisionTree ? (
          <section>
            <h2 className="text-sm font-bold mb-2 dark:text-text-primary text-light-text-primary print:text-black">
              四、決策路徑明細（共 {ruleCountDisplay}）
            </h2>
            <div className="overflow-x-auto">
              <table className="w-full text-[11px] border-collapse">
                <thead>
                  <tr className="border-b-2 dark:border-border border-light-border">
                    <th className="text-left py-2 px-2 font-bold dark:text-text-secondary text-light-text-secondary w-10">編號</th>
                    <th className="text-left py-2 px-2 font-bold dark:text-text-secondary text-light-text-secondary">情境條件</th>
                    <th className="text-left py-2 px-2 font-bold dark:text-text-secondary text-light-text-secondary w-1/3">決策結果</th>
                  </tr>
                </thead>
                <tbody>
                  {flattenTreePaths(rule?.root as DTNode | undefined).map((p, idx) => (
                    <tr key={idx} className="border-b dark:border-border/30 border-light-border/30 align-top">
                      <td className="py-1.5 px-2 font-bold dark:text-text-tertiary text-light-text-tertiary">
                        {idx + 1}
                      </td>
                      <td className="py-1.5 px-2 dark:text-text-secondary text-light-text-secondary leading-relaxed">
                        {p.pathLabel}
                      </td>
                      <td className="py-1.5 px-2 dark:text-text-primary text-light-text-primary font-medium">
                        {p.results.map(r => `${cn(r.field)}：${String(r.value)}`).join('；')}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        ) : (
          <section>
            <h2 className="text-sm font-bold mb-2 dark:text-text-primary text-light-text-primary print:text-black">
              四、規則明細{rules.length > 20 ? `（前 20 條,共 ${rules.length} 條）` : ''}
            </h2>
            <div className="overflow-x-auto">
              <table className="w-full text-[11px] border-collapse">
                <thead>
                  <tr className="border-b-2 dark:border-border border-light-border print:border-gray-400">
                    <th className="text-left py-2 px-2 font-bold dark:text-text-secondary text-light-text-secondary print:text-black">ID</th>
                    <th className="text-left py-2 px-2 font-bold dark:text-text-secondary text-light-text-secondary print:text-black">條件</th>
                    {outputs.map(o => (
                      <th key={o.name} className="text-left py-2 px-2 font-bold dark:text-text-secondary text-light-text-secondary print:text-black">
                        {cn(o.name)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rules.slice(0, 20).map(r => (
                    <tr key={r.ruleId} className="border-b dark:border-border/30 border-light-border/30 print:border-gray-200">
                      <td className="py-1.5 px-2 font-bold dark:text-text-tertiary text-light-text-tertiary print:text-gray-600">
                        {r.ruleId}
                      </td>
                      <td className="py-1.5 px-2 dark:text-text-secondary text-light-text-secondary print:text-gray-700">
                        {r.conditions.map(formatCondition).join('、')}
                      </td>
                      {outputs.map(o => {
                        const res = r.results.find(res => res.field === o.name);
                        return (
                          <td key={o.name} className="py-1.5 px-2 dark:text-text-primary text-light-text-primary print:text-black font-medium">
                            {res ? String(res.value) : '—'}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {/* 5. 覆蓋率與缺口 */}
        {analyze && ((analyze.gaps?.length ?? 0) > 0 || (analyze.overlaps?.length ?? 0) > 0) && (
          <section>
            <h2 className="text-sm font-bold mb-2 dark:text-text-primary text-light-text-primary print:text-black">
              五、覆蓋率分析
            </h2>
            {analyze.gaps?.length > 0 && (
              <div className="mb-2">
                <p className="text-xs font-medium dark:text-red-400 text-red-600 print:text-red-700 mb-1">
                  缺口（{analyze.gaps.length} 個未覆蓋組合）
                </p>
                {analyze.gaps.slice(0, 5).map((g, i) => (
                  <p key={i} className="text-[11px] dark:text-text-secondary text-light-text-secondary print:text-gray-700 ml-3">
                    · {g.message || Object.entries(g.conditions).map(([k, v]) => `${cn(k)}=${v}`).join(', ')}
                  </p>
                ))}
              </div>
            )}
            {analyze.overlaps?.length > 0 && (
              <div>
                <p className="text-xs font-medium dark:text-amber-400 text-amber-600 print:text-amber-700 mb-1">
                  衝突（{analyze.overlaps.length} 組重疊規則）
                </p>
                {analyze.overlaps.slice(0, 5).map((o, i) => (
                  <p key={i} className="text-[11px] dark:text-text-secondary text-light-text-secondary print:text-gray-700 ml-3">
                    · {o.ruleIds.join(' vs ')}：{o.message || '規則條件重疊'}
                  </p>
                ))}
              </div>
            )}
          </section>
        )}

        {/* 簽核欄位（列印用） */}
        <section className="mt-4 hidden print:block">
          <h2 className="text-sm font-bold mb-3 print:text-black">六、簽核紀錄</h2>
          <table className="w-full text-[11px]">
            <thead>
              <tr>
                <th className="text-left py-2 w-1/4">業務確認</th>
                <th className="text-left py-2 w-1/4">主管核准</th>
                <th className="text-left py-2 w-1/4">精算複核</th>
                <th className="text-left py-2 w-1/4">日期</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="py-8 border-b border-gray-400" />
                <td className="py-8 border-b border-gray-400" />
                <td className="py-8 border-b border-gray-400" />
                <td className="py-8 border-b border-gray-400" />
              </tr>
            </tbody>
          </table>
        </section>

        {/* 頁尾 */}
        <div className="pt-4 border-t dark:border-border border-light-border text-center">
          <p className="text-[10px] dark:text-text-tertiary/40 text-light-text-tertiary/40">
            金控集團 · 業務規則治理與交付前處理服務 · 列印日期 {now}
          </p>
        </div>
      </div>
    </div>
  );
}
