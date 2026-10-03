import type { BoundsReport, RegressionReport } from '../../api/workbenchApi';
import { card, textPrimary, textSecondary, textTertiary } from './ui';

function show(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v);
}

export function BoundsPanel({ bounds }: { bounds?: BoundsReport }) {
  if (!bounds || !bounds.checked) {
    return (
      <div className={`${card} p-4 text-xs ${textTertiary}`}>
        精算邊界：{bounds ? '此規則型態不檢查' : '未檢查'}
      </div>
    );
  }
  const bad = bounds.violations.length > 0;
  return (
    <div className={`${card} p-4 space-y-2 ${bad ? 'border-l-4 border-l-red-600' : 'border-l-4 border-l-emerald-600'}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className={`text-sm font-semibold ${textPrimary}`}>精算邊界</span>
        <span className={`text-xs ${bad ? 'text-red-600 font-semibold' : 'text-emerald-600'}`}>
          {bad ? `${bounds.violations.length} 項越界，無法送審` : `通過（檢查 ${bounds.constraintCount} 條邊界）`}
        </span>
      </div>
      {bad && (
        <>
          <ul className={`text-xs list-disc pl-5 space-y-1 ${textSecondary}`}>
            {bounds.violations.map((v, i) => (
              <li key={i}>
                <b>{v.ruleId}</b>：{v.description}
                <span className={textTertiary}>（{v.detail}）</span>
              </li>
            ))}
          </ul>
          <div className="text-xs text-amber-600">
            邊界由精算設定，業務規則不得越過；如確有需要，請升級給 {bounds.escalateTo || '精算'}。
          </div>
        </>
      )}
    </div>
  );
}

export function RegressionPanel({ regression }: { regression?: RegressionReport }) {
  if (!regression) return null;
  const r = regression;
  const outputFields = Array.from(
    new Set(r.examples.flatMap((e) => [...Object.keys(e.before.outputs), ...Object.keys(e.after.outputs)])),
  );
  const inputFields = Array.from(new Set(r.examples.flatMap((e) => Object.keys(e.input))));
  return (
    <div className={`${card} p-4 space-y-2`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className={`text-sm font-semibold ${textPrimary}`}>合成資料回歸（新舊版各跑一次）</span>
        <span className={`text-xs tabular-nums ${r.changedCount ? 'text-amber-600 font-semibold' : 'text-emerald-600'}`}>
          {r.sampleCount} 筆中 {r.changedCount} 筆結果改變（{(r.changeRate * 100).toFixed(1)}%）
        </span>
      </div>
      <div className={`flex gap-4 text-xs tabular-nums ${textSecondary}`}>
        <span>新命中 {r.newlyMatched}</span>
        <span>不再命中 {r.newlyUnmatched}</span>
        <span>輸出改變 {r.outputChanged}</span>
      </div>
      {r.examples.length > 0 && (
        <div className="overflow-auto">
          <table className={`text-xs w-full ${textPrimary}`}>
            <thead>
              <tr className={`text-left ${textTertiary}`}>
                {inputFields.map((f) => <th key={f} className="py-1 pr-3">{f}</th>)}
                {outputFields.map((f) => <th key={f} className="py-1 pr-3">{f}（舊 → 新）</th>)}
              </tr>
            </thead>
            <tbody>
              {r.examples.map((e, i) => (
                <tr key={i} className="border-t dark:border-border/40 border-light-border">
                  {inputFields.map((f) => <td key={f} className="py-1 pr-3">{show(e.input[f])}</td>)}
                  {outputFields.map((f) => (
                    <td key={f} className="py-1 pr-3">
                      <span className={textTertiary}>{e.before.matched ? show(e.before.outputs[f]) : '未命中'}</span>
                      {' → '}
                      <b>{e.after.matched ? show(e.after.outputs[f]) : '未命中'}</b>
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {r.changedCount > r.examples.length && (
            <div className={`text-[11px] mt-1 ${textTertiary}`}>只列前 {r.examples.length} 筆</div>
          )}
        </div>
      )}
      <div className={`text-[11px] ${textTertiary}`}>案件由欄位型別與兩版規則的邊界值合成，不含任何真實資料。</div>
    </div>
  );
}
