import { lazy, Suspense, useState } from 'react';
import type { RuleEnvelope } from '../../types';
import { parseDecisionTable, getOperatorDisplay, formatConditionValue, type RuleCondition } from '../Dashboard/types';
import { card, textPrimary, textSecondary, textTertiary } from './ui';

const DecisionTreeView = lazy(() => import('../Dashboard/DecisionTreeView'));

function condValue(c: RuleCondition): string {
  return c.valueRef ? `「${c.valueRef}」` : formatConditionValue(c);
}

function condText(c: RuleCondition): string {
  if (c.operator === 'anything') return '';
  return `${c.field} ${getOperatorDisplay(c.operator).labelCN} ${condValue(c)}`;
}

function valueText(v: unknown): string {
  if (v === null || v === undefined || v === '') return '（待填）';
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v);
}

function Toggle<T extends string>({ value, options, onChange }: { value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <div className="flex rounded-lg border dark:border-border border-light-border overflow-hidden text-xs">
      {options.map(([k, label]) => (
        <button
          key={k}
          type="button"
          onClick={() => onChange(k)}
          className={`px-3 py-1 cursor-pointer ${value === k ? 'bg-[var(--color-group-green-600)] text-white' : textSecondary}`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

// ─── 決策樹：縮排大綱 ───

interface TreeBranch { label?: string; condition?: RuleCondition; child?: TreeNode }
interface TreeNode {
  nodeId?: string;
  condition?: RuleCondition;
  branches?: TreeBranch[];
  trueBranch?: TreeNode;
  falseBranch?: TreeNode;
  results?: { field: string; value: unknown }[];
}

const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'];

function markerFor(depth: number, i: number): string {
  switch (depth) {
    case 0: return `${i + 1}.`;
    case 1: return `(${i + 1})`;
    case 2: return `${String.fromCharCode(65 + i)}.`;
    case 3: return `(${String.fromCharCode(97 + i)})`;
    case 4: return `${ROMAN[i] ?? i + 1}.`;
    default: return '-';
  }
}

function OutlineNode({ node, depth }: { node: TreeNode; depth: number }) {
  const leaf = node.results && node.results.length > 0 && !node.branches?.length && !node.trueBranch && !node.falseBranch;
  const children: { label: string; child: TreeNode }[] = [];
  if (node.branches?.length) {
    for (const b of node.branches) {
      if (b.child) children.push({ label: b.label || (b.condition ? condText(b.condition) : '—'), child: b.child });
    }
  } else {
    const field = node.condition?.field ?? '';
    const base = node.condition ? condText(node.condition) : field;
    if (node.trueBranch) children.push({ label: `${base} 成立`, child: node.trueBranch });
    if (node.falseBranch) children.push({ label: `${base} 不成立`, child: node.falseBranch });
  }
  return (
    <div style={{ paddingLeft: depth === 0 ? 0 : 18 }} className="space-y-1">
      {leaf ? (
        <div className={`text-sm ${textPrimary}`}>
          <span className={`font-mono text-[10px] mr-2 ${textTertiary}`}>{node.nodeId}</span>
          → {node.results!.map((r) => `${r.field} 為 ${valueText(r.value)}`).join('、')}
        </div>
      ) : (
        <>
          {node.condition && depth === 0 && (
            <div className={`text-xs ${textTertiary}`}>先看 <b className={textPrimary}>{node.condition.field}</b></div>
          )}
          {children.map((c, i) => (
            <div key={i} className="space-y-1">
              <div className={`text-sm ${textPrimary}`}>
                <span className={`font-mono text-xs mr-1.5 ${textTertiary}`}>{markerFor(depth, i)}</span>
                {c.label}
                {c.child.condition && !(c.child.results?.length) && (
                  <span className={`text-xs ml-2 ${textTertiary}`}>再看 {c.child.condition.field}</span>
                )}
              </div>
              <OutlineNode node={c.child} depth={depth + 1} />
            </div>
          ))}
        </>
      )}
    </div>
  );
}

function TreeRuleView({ envelope }: { envelope: RuleEnvelope }) {
  const [mode, setMode] = useState<'outline' | 'graph'>('outline');
  const root = (envelope.rule as { root?: TreeNode } | undefined)?.root;
  if (!root) {
    return <div className={`${card} p-4 text-xs ${textTertiary}`}>決策樹沒有根節點</div>;
  }
  return (
    <div className={`${card} p-4 space-y-3`}>
      <div className="flex items-center justify-between">
        <span className={`text-sm font-semibold ${textPrimary}`}>規則內容（決策樹）</span>
        <Toggle value={mode} options={[['outline', '縮排大綱'], ['graph', '樹圖']]} onChange={setMode} />
      </div>
      {mode === 'outline' ? (
        <OutlineNode node={root} depth={0} />
      ) : (
        <Suspense fallback={<div className={`text-xs ${textTertiary}`}>載入樹圖…</div>}>
          <DecisionTreeView envelope={envelope} />
        </Suspense>
      )}
    </div>
  );
}

// ─── 評分卡：各維度計分規則＋分數帶 ───

interface ScoringRule { ruleId?: string; condition?: RuleCondition; score?: number; description?: string }
interface ScoringDimension { field: string; weight?: number; scoringRules?: ScoringRule[] }
interface ScoreBand { bandId?: string; minScore?: number; maxScore?: number; results?: { field: string; value: unknown }[] }

function ScoreCardView({ envelope }: { envelope: RuleEnvelope }) {
  const rule = envelope.rule as { scoringDimensions?: ScoringDimension[]; scoreBands?: ScoreBand[] } | undefined;
  const dims = rule?.scoringDimensions ?? [];
  const bands = rule?.scoreBands ?? [];
  return (
    <div className={`${card} p-4 space-y-4`}>
      <div className="flex items-center justify-between">
        <span className={`text-sm font-semibold ${textPrimary}`}>規則內容（評分卡：{dims.length} 個維度、{bands.length} 個分數帶）</span>
        <span className={`text-[11px] ${textTertiary}`}>各維度取第一條成立的計分規則，乘以權重後加總</span>
      </div>
      {dims.map((d) => (
        <div key={d.field} className="space-y-1">
          <div className={`text-sm ${textPrimary}`}>
            <b>{d.field}</b>
            {d.weight != null && d.weight !== 1 && <span className={`text-xs ml-2 ${textTertiary}`}>權重 {d.weight}</span>}
          </div>
          <ol className="pl-5 space-y-0.5">
            {(d.scoringRules ?? []).map((r, i) => (
              <li key={r.ruleId ?? i} className={`text-sm ${textSecondary}`}>
                <span className={`font-mono text-[10px] mr-2 ${textTertiary}`}>{r.ruleId}</span>
                {r.condition && r.condition.operator !== 'anything' ? condText(r.condition) : '其他'}
                <span className={`ml-2 font-semibold ${textPrimary}`}>→ {r.score ?? 0} 分</span>
                {r.description && <span className={`ml-2 text-xs ${textTertiary}`}>{r.description}</span>}
              </li>
            ))}
          </ol>
        </div>
      ))}
      <div className="space-y-1">
        <div className={`text-sm font-semibold ${textPrimary}`}>分數帶</div>
        <table className={`text-xs ${textPrimary}`}>
          <tbody>
            {bands.map((b, i) => (
              <tr key={b.bandId ?? i}>
                <td className={`pr-3 py-0.5 font-mono ${textTertiary}`}>{b.bandId}</td>
                <td className="pr-3 py-0.5">總分 {b.minScore ?? '-∞'} ～ {b.maxScore ?? '∞'}</td>
                <td className="py-0.5">→ {(b.results ?? []).map((r) => `${r.field} 為 ${valueText(r.value)}`).join('、')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ─── 決策表：白話句子／表格；MULTI 顯示為檢核清單 ───

export default function RuleView({ envelope }: { envelope: RuleEnvelope }) {
  const [mode, setMode] = useState<'sentences' | 'table'>('sentences');
  const table = envelope.ruleType === 'DecisionTable' ? parseDecisionTable(envelope.rule) : null;

  if (envelope.ruleType === 'DecisionTree') return <TreeRuleView envelope={envelope} />;
  if (envelope.ruleType === 'ScoreCard') return <ScoreCardView envelope={envelope} />;

  if (!table) {
    return (
      <div className={`${card} p-4`}>
        <p className={`text-xs mb-2 ${textTertiary}`}>
          {envelope.ruleType} 目前以原始內容顯示（白話句子與表格切換僅支援決策表）
        </p>
        <pre className={`text-[11px] overflow-auto max-h-80 ${textSecondary}`}>
          {JSON.stringify(envelope.rule, null, 2)}
        </pre>
      </div>
    );
  }

  const rules = [...table.rules].sort((a, b) => a.priority - b.priority);
  const resultFields = Array.from(new Set(rules.flatMap((r) => r.results.map((x) => x.field))));
  const condFields = Array.from(new Set(rules.flatMap((r) => r.conditions.map((x) => x.field))));
  const checklist = String(table.hitPolicy).toUpperCase() === 'MULTI';

  if (checklist) {
    return (
      <div className={`${card} p-4 space-y-3`}>
        <div className="flex items-center justify-between">
          <span className={`text-sm font-semibold ${textPrimary}`}>檢核清單（{rules.length} 項）</span>
          <span className={`text-[11px] ${textTertiary}`}>每項各自檢核，可同時命中多項</span>
        </div>
        <ol className="space-y-1.5">
          {rules.map((r, i) => {
            const conds = r.conditions.filter((c) => c.operator !== 'anything');
            return (
              <li key={r.ruleId} className={`text-sm leading-relaxed flex gap-2 ${textPrimary}`}>
                <span className={`font-mono text-xs shrink-0 ${textTertiary}`}>{i + 1}.</span>
                <span>
                  若 {conds.map((c, j) => (
                    <span key={j}>{j > 0 && ' 且 '}<b>{c.field}</b> {getOperatorDisplay(c.operator).labelCN} {condValue(c)}</span>
                  ))}
                  {conds.length === 0 && '（所有案件）'}
                  <span className={textTertiary}> → </span>
                  {r.results.map((x) => (
                    <span key={x.field} className="mr-2">
                      <span className={`text-xs ${textTertiary}`}>{x.field}</span> {valueText(x.value)}
                    </span>
                  ))}
                </span>
              </li>
            );
          })}
        </ol>
      </div>
    );
  }

  return (
    <div className={`${card} p-4 space-y-3`}>
      <div className="flex items-center justify-between">
        <span className={`text-sm font-semibold ${textPrimary}`}>規則內容（{rules.length} 條）</span>
        <Toggle value={mode} options={[['sentences', '白話句子'], ['table', '表格']]} onChange={setMode} />
      </div>

      {mode === 'sentences' ? (
        <ol className="space-y-1.5">
          {rules.map((r) => {
            const conds = r.conditions.filter((c) => c.operator !== 'anything');
            return (
              <li key={r.ruleId} className={`text-sm leading-relaxed ${textPrimary}`}>
                <span className={`text-[10px] mr-2 font-mono ${textTertiary}`}>{r.ruleId}</span>
                {conds.length === 0 ? '不論條件' : '當 '}
                {conds.map((c, i) => (
                  <span key={i}>
                    {i > 0 && ' 且 '}
                    <b>{c.field}</b> {getOperatorDisplay(c.operator).labelCN} {condValue(c)}
                  </span>
                ))}
                ，則 {r.results.map((x) => `${x.field} 為 ${valueText(x.value)}`).join('、')}
              </li>
            );
          })}
        </ol>
      ) : (
        <div className="overflow-auto">
          <table className={`text-xs w-full ${textPrimary}`}>
            <thead>
              <tr className={`text-left ${textTertiary}`}>
                <th className="py-1 pr-3">規則</th>
                {condFields.map((f) => <th key={f} className="py-1 pr-3">{f}</th>)}
                {resultFields.map((f) => <th key={f} className="py-1 pr-3">→ {f}</th>)}
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.ruleId} className="border-t dark:border-border/40 border-light-border">
                  <td className="py-1 pr-3 font-mono">{r.ruleId}</td>
                  {condFields.map((f) => {
                    const c = r.conditions.find((x) => x.field === f);
                    return (
                      <td key={f} className="py-1 pr-3">
                        {c && c.operator !== 'anything'
                          ? `${getOperatorDisplay(c.operator).labelCN} ${condValue(c)}`
                          : '—'}
                      </td>
                    );
                  })}
                  {resultFields.map((f) => {
                    const res = r.results.find((x) => x.field === f);
                    return (
                      <td key={f} className="py-1 pr-3">
                        {res ? valueText(res.value) : '—'}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
