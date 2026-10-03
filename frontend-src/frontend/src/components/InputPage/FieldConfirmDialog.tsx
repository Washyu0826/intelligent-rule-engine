import { useState } from 'react';
import type { SuggestResponse } from '../../types';
import type { ConfirmedField, ConfirmedFields } from './fieldSpec';

interface Props {
  suggest: SuggestResponse;
  onConfirm: (fields: ConfirmedFields) => void;
  onSkip: () => void;
  onCancel: () => void;
}

const TYPES = ['INTEGER', 'DECIMAL', 'BOOLEAN', 'STRING', 'ENUM', 'DATE'];

/** 維度解析偶爾把句首連接詞帶進欄位名（「根據被保人年齡」），這裡只去前綴，不動其餘文字 */
function cleanName(name: string): string {
  return name.replace(/^(?:根據|依據|依照|按照|依|按|若|當|如果|被保人的|要保人的)/, '').trim() || name;
}

function fromSuggest(s: SuggestResponse): ConfirmedFields {
  const map = (d: SuggestResponse['detectedInputs']) =>
    d.map((x) => {
      const values = x.values ?? [];
      const freeText = values.some((v) => /^(文字|自由文字|說明|備註)/.test(v));
      return {
        name: cleanName(x.chineseName || x.englishName),
        typeRef: freeText ? 'STRING' : x.typeRef || 'STRING',
        values: freeText ? '' : values.join('／'),
      };
    });
  return { inputs: map(s.detectedInputs ?? []), outputs: map(s.detectedOutputs ?? []) };
}

export default function FieldConfirmDialog({ suggest, onConfirm, onSkip, onCancel }: Props) {
  const [fields, setFields] = useState<ConfirmedFields>(() => fromSuggest(suggest));

  const update = (kind: 'inputs' | 'outputs', i: number, patch: Partial<ConfirmedField>) =>
    setFields((prev) => ({ ...prev, [kind]: prev[kind].map((x, j) => (j === i ? { ...x, ...patch } : x)) }));
  const remove = (kind: 'inputs' | 'outputs', i: number) =>
    setFields((prev) => ({ ...prev, [kind]: prev[kind].filter((_, j) => j !== i) }));
  const add = (kind: 'inputs' | 'outputs') =>
    setFields((prev) => ({ ...prev, [kind]: [...prev[kind], { name: '', typeRef: 'STRING', values: '' }] }));

  const inputCls = 'rounded-md border px-2 py-1 text-xs dark:bg-surface-2 dark:border-border bg-white border-light-border dark:text-text-primary text-light-text-primary';

  const section = (kind: 'inputs' | 'outputs', title: string, hint: string) => (
    <div className="space-y-2">
      <div className="flex items-baseline justify-between">
        <span className="text-sm font-semibold dark:text-text-primary text-light-text-primary">{title}</span>
        <span className="text-[11px] dark:text-text-tertiary text-light-text-tertiary">{hint}</span>
      </div>
      {fields[kind].length === 0 && (
        <div className="text-xs dark:text-text-tertiary text-light-text-tertiary">沒有偵測到，可手動新增</div>
      )}
      {fields[kind].map((f, i) => (
        <div key={i} className="grid grid-cols-[1fr_7rem_1.5fr_auto] gap-2 items-center">
          <input aria-label={`${title} 名稱`} className={inputCls} value={f.name} placeholder="欄位名稱" onChange={(e) => update(kind, i, { name: e.target.value })} />
          <select aria-label={`${title} 型別`} className={inputCls} value={f.typeRef} onChange={(e) => update(kind, i, { typeRef: e.target.value })}>
            {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
          <input aria-label={`${title} 值域`} className={inputCls} value={f.values} placeholder="值域，用／分隔；數值可寫 18–35／36–50" onChange={(e) => update(kind, i, { values: e.target.value })} />
          <button type="button" className="text-xs px-2 py-1 cursor-pointer dark:text-text-tertiary text-light-text-tertiary hover:text-red-600" onClick={() => remove(kind, i)} aria-label="移除">✕</button>
        </div>
      ))}
      <button type="button" className="text-xs cursor-pointer text-[var(--color-group-green-600)]" onClick={() => add(kind)}>＋ 新增欄位</button>
    </div>
  );

  const ok = fields.inputs.some((x) => x.name.trim()) && fields.outputs.some((x) => x.name.trim());

  return (
    <div role="dialog" aria-modal="true" aria-labelledby="field-confirm-title" className="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/40">
      <div className="w-full max-w-3xl max-h-[90vh] overflow-auto rounded-xl border p-6 space-y-5 shadow-xl dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <div>
          <p className="text-[10px] uppercase tracking-[0.08em] font-medium dark:text-text-tertiary text-light-text-tertiary">第 1 步 / 2</p>
          <h2 id="field-confirm-title" className="mt-1 text-base font-bold dark:text-text-primary text-light-text-primary">先確認欄位，再產生規則內容</h2>
          <p className="mt-1 text-xs dark:text-text-secondary text-light-text-secondary">
            這是從你的規格裡讀出來的條件欄位與輸出欄位。欄位對了，後面的規則列才不會錯；可以改名、改型別、補值域。
          </p>
          {suggest.missingDimensions?.length > 0 && (
            <p className="mt-1 text-xs text-amber-600">
              可能漏掉：{suggest.missingDimensions.map((m) => m.name).join('、')}
            </p>
          )}
        </div>
        {section('inputs', '條件欄位（輸入）', '規則會依這些欄位比對')}
        {section('outputs', '輸出欄位', '每一列規則要給的結果')}
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" onClick={onCancel} className="px-3 py-1.5 rounded-md text-xs cursor-pointer dark:text-text-secondary text-light-text-secondary">取消</button>
          <button type="button" onClick={onSkip} className="px-3 py-1.5 rounded-md text-xs cursor-pointer border dark:border-border border-light-border dark:text-text-secondary text-light-text-secondary">跳過，直接生成</button>
          <button type="button" disabled={!ok} onClick={() => onConfirm(fields)} className="px-3 py-1.5 rounded-md text-xs font-semibold cursor-pointer text-white bg-[var(--color-group-green-600)] disabled:opacity-40 disabled:cursor-not-allowed">
            確認欄位，產生規則
          </button>
        </div>
      </div>
    </div>
  );
}
