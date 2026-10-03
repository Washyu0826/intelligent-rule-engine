import { useMemo, useState } from 'react';
import { api } from '../../api/rulesApi';
import { workbenchApi, type DmnCrossCheck } from '../../api/workbenchApi';
import type { RuleEnvelope } from '../../types';
import { btnGhost, btnPrimary, card, errMsg, input, textPrimary, textSecondary, textTertiary } from './ui';

interface FieldDef {
  name: string;
  typeRef: string;
  allowedValues?: string[];
}

interface Outcome {
  matched: boolean;
  hitRuleIds: string[];
  outputs: Record<string, unknown>;
  path?: string[];
  traceId?: number;
  elapsedMs: number;
}

interface Props {
  envelope: RuleEnvelope;
  ruleKey: string;
  status: string;
}

function coerce(field: FieldDef, raw: string): unknown {
  if (raw === '') return undefined;
  switch (field.typeRef) {
    case 'BOOLEAN': return raw === 'true';
    case 'INTEGER': return Number.parseInt(raw, 10);
    case 'DECIMAL': return Number(raw);
    default: return raw;
  }
}

function toOutputs(results: unknown): Record<string, unknown> {
  if (Array.isArray(results)) {
    return Object.fromEntries(
      results.map((r) => {
        const o = r as { field?: string; name?: string; value?: unknown };
        return [o.field ?? o.name ?? '?', o.value];
      }),
    );
  }
  return (results as Record<string, unknown>) ?? {};
}

function show(v: unknown): string {
  if (v === null || v === undefined || v === '') return '（空）';
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v);
}

export default function TrialRunPanel({ envelope, ruleKey, status }: Props) {
  const inputs = useMemo<FieldDef[]>(() => {
    const rule = envelope.rule as Record<string, unknown> | undefined;
    return ((rule?.inputs as FieldDef[] | undefined) ?? []).filter((f) => f && f.name);
  }, [envelope]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [dmn, setDmn] = useState<DmnCrossCheck | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const isActive = status === 'ACTIVE';
  const missing = inputs.filter((f) => !values[f.name]);

  const run = async () => {
    setBusy(true);
    setError('');
    setOutcome(null);
    const payload: Record<string, unknown> = {};
    for (const f of inputs) {
      const v = coerce(f, values[f.name] ?? '');
      if (v !== undefined) payload[f.name] = v;
    }
    const t0 = performance.now();
    try {
      if (isActive) {
        const r = await workbenchApi.executeActive(ruleKey, payload);
        setOutcome({
          matched: r.result.matched,
          hitRuleIds: (r.result.matchedRules ?? []).map((m) => m.ruleId),
          outputs: r.result.outputs ?? {},
          traceId: r.traceId,
          elapsedMs: performance.now() - t0,
        });
      } else {
        const r = await api.execute(envelope, payload);
        setOutcome({
          matched: r.matched,
          hitRuleIds: r.hitRuleId ? [r.hitRuleId] : [],
          outputs: toOutputs(r.results),
          path: r.hitPath ?? r.evaluationPath,
          elapsedMs: performance.now() - t0,
        });
        setDmn(null);
        try {
          setDmn(await workbenchApi.dmnCheck(envelope, payload));
        } catch (e) {
          setDmn({ consistent: false, dmnMatched: false, dmnResults: [], differences: [errMsg(e)], warnings: [], dmnNanos: 0 });
        }
      }
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const downloadDmn = async () => {
    try {
      const xml = await workbenchApi.dmnExportXml(envelope, ruleKey);
      const url = URL.createObjectURL(new Blob([xml], { type: 'application/xml' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `${ruleKey.replace(/[^A-Za-z0-9_.-]/g, '_')}.dmn`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  if (inputs.length === 0) {
    return (
      <div className={`${card} p-4 text-xs ${textTertiary}`}>試算：此規則沒有宣告輸入欄位，無法產生表單</div>
    );
  }

  return (
    <div className={`${card} p-4 space-y-3`}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className={`text-sm font-semibold ${textPrimary}`}>試算</span>
        <span className={`text-[11px] ${textTertiary}`}>
          {isActive
            ? '以生效版本執行，會留下可回放的決策軌跡'
            : '用內建引擎模擬這個版本，不留軌跡；正式決策仍走正式引擎'}
        </span>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        {inputs.map((f) => {
          const id = `trial-${ruleKey}-${f.name}`;
          const v = values[f.name] ?? '';
          const set = (next: string) => setValues((prev) => ({ ...prev, [f.name]: next }));
          const label = (
            <label htmlFor={id} className={`text-xs ${textSecondary}`}>
              {f.name} <span className={textTertiary}>({f.typeRef})</span>
            </label>
          );
          if (f.typeRef === 'BOOLEAN') {
            return (
              <div key={f.name} className="space-y-1">
                {label}
                <select id={id} className={input} value={v} onChange={(e) => set(e.target.value)}>
                  <option value="">請選擇</option>
                  <option value="true">是</option>
                  <option value="false">否</option>
                </select>
              </div>
            );
          }
          if (f.typeRef === 'ENUM' && f.allowedValues?.length) {
            return (
              <div key={f.name} className="space-y-1">
                {label}
                <select id={id} className={input} value={v} onChange={(e) => set(e.target.value)}>
                  <option value="">請選擇</option>
                  {f.allowedValues.map((a) => <option key={a} value={a}>{a}</option>)}
                </select>
              </div>
            );
          }
          const type = f.typeRef === 'INTEGER' || f.typeRef === 'DECIMAL' ? 'number' : f.typeRef === 'DATE' ? 'date' : 'text';
          return (
            <div key={f.name} className="space-y-1">
              {label}
              <input
                id={id}
                type={type}
                step={f.typeRef === 'DECIMAL' ? 'any' : undefined}
                className={input}
                value={v}
                onChange={(e) => set(e.target.value)}
              />
            </div>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <button type="button" className={btnPrimary} disabled={busy || missing.length > 0} onClick={() => void run()}>
          {busy ? '執行中…' : isActive ? '執行並記錄' : '模擬執行'}
        </button>
        {missing.length > 0 && (
          <span className={`text-[11px] ${textTertiary}`}>還有 {missing.length} 個欄位未填</span>
        )}
        {(envelope.ruleType === 'DecisionTable' || envelope.ruleType === 'DecisionTree') && (
          <button type="button" className={btnGhost} disabled={busy} onClick={() => void downloadDmn()}>
            下載 DMN
          </button>
        )}
      </div>

      {outcome && (
        <div className={`rounded-lg border p-3 space-y-2 dark:border-border border-light-border ${outcome.matched ? '' : 'border-l-4 border-l-amber-500'}`}>
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span className={`font-semibold ${outcome.matched ? 'text-emerald-600' : 'text-amber-600'}`}>
              {outcome.matched ? '命中' : '未命中任何規則'}
            </span>
            {outcome.hitRuleIds.length > 0 && (
              <span className={textSecondary}>規則 {outcome.hitRuleIds.join('、')}</span>
            )}
            <span className={`text-[11px] tabular-nums ${textTertiary}`}>耗時 {outcome.elapsedMs.toFixed(0)} ms（含網路）</span>
            {outcome.traceId != null && (
              <span className={`text-[11px] ${textTertiary}`}>軌跡 #{outcome.traceId}</span>
            )}
          </div>
          {outcome.matched && (
            <table className={`text-xs ${textPrimary}`}>
              <tbody>
                {Object.entries(outcome.outputs).map(([k, v]) => (
                  <tr key={k}>
                    <td className={`pr-4 py-0.5 ${textTertiary}`}>{k}</td>
                    <td className="py-0.5 font-medium">{show(v)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {outcome.path && outcome.path.length > 0 && (
            <div className={`text-[11px] ${textTertiary}`}>路徑：{outcome.path.join(' → ')}</div>
          )}
          {dmn && (
            <div className={`text-[11px] pt-1 border-t dark:border-border border-light-border ${dmn.consistent ? textSecondary : 'text-red-600'}`}>
              標準 DMN 引擎（Camunda）比對：{dmn.consistent ? '一致' : '不一致'}
              {dmn.dmnNanos > 0 && <span className={textTertiary}>&emsp;DMN 引擎耗時 {(dmn.dmnNanos / 1e6).toFixed(1)} ms</span>}
              {dmn.differences.length > 0 && <div>{dmn.differences.join('；')}</div>}
              {dmn.warnings.length > 0 && <div className={textTertiary}>{dmn.warnings.join('；')}</div>}
            </div>
          )}
        </div>
      )}
      {error && <div className="text-xs text-red-600">{error}</div>}
    </div>
  );
}
