import { useCallback, useEffect, useState } from 'react';
import { workbenchApi, type ChainOutcome, type ChainStep, type TreeEntry } from '../../api/workbenchApi';
import { btnGhost, btnPrimary, card, errMsg, input, textPrimary, textSecondary, textTertiary } from './ui';

interface Props {
  chainKey: string | null;
  rules: TreeEntry[];
  canEdit: boolean;
  onSaved: (chainKey: string) => void;
}

interface KV { key: string; value: string }

function parseValue(raw: string): unknown {
  const t = raw.trim();
  if (t === 'true') return true;
  if (t === 'false') return false;
  if (t !== '' && !Number.isNaN(Number(t))) return Number(t);
  return raw;
}

function show(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v);
}

export default function ChainPanel({ chainKey, rules, canEdit, onSaved }: Props) {
  const [key, setKey] = useState(chainKey ?? '');
  const [name, setName] = useState('');
  const [steps, setSteps] = useState<ChainStep[]>([]);
  const [inputs, setInputs] = useState<KV[]>([{ key: '', value: '' }]);
  const [outcome, setOutcome] = useState<ChainOutcome | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setError('');
    setOutcome(null);
    setNotice('');
    if (!chainKey) {
      setKey('');
      setName('');
      setSteps([{ ruleKey: rules[0]?.ruleKey ?? '', stopOnHit: true, label: '檢核' }]);
      return;
    }
    try {
      const c = await workbenchApi.chain(chainKey);
      setKey(c.chainKey);
      setName(c.name);
      setSteps(c.steps);
    } catch (e) {
      setError(errMsg(e));
    }
  }, [chainKey, rules]);

  useEffect(() => {
    void load();
  }, [load]);

  const updateStep = (i: number, patch: Partial<ChainStep>) =>
    setSteps((prev) => prev.map((s, j) => (j === i ? { ...s, ...patch } : s)));
  const move = (i: number, dir: -1 | 1) =>
    setSteps((prev) => {
      const next = [...prev];
      const j = i + dir;
      if (j < 0 || j >= next.length) return prev;
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });

  const save = async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await workbenchApi.saveChain(key.trim(), name.trim(), steps);
      setNotice('流程已儲存');
      onSaved(saved.chainKey);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const run = async () => {
    setBusy(true);
    setError('');
    setOutcome(null);
    try {
      const payload: Record<string, unknown> = {};
      for (const kv of inputs) if (kv.key.trim()) payload[kv.key.trim()] = parseValue(kv.value);
      setOutcome(await workbenchApi.executeChain(key.trim(), payload));
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const stepLabel = (s: ChainStep, i: number) => s.label || `步驟 ${i + 1}`;

  return (
    <div className="space-y-4">
      <div className={`${card} p-4 space-y-3`}>
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <span className={`text-sm font-semibold ${textPrimary}`}>{chainKey ? `流程 ${chainKey}` : '新流程'}</span>
          <span className={`text-[11px] ${textTertiary}`}>依序執行各規則的生效版；前一步的輸出會變成下一步的輸入</span>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div className="space-y-1">
            <label htmlFor="chain-key" className={`text-xs ${textSecondary}`}>流程代號</label>
            <input id="chain-key" className={input} value={key} disabled={!!chainKey || !canEdit} onChange={(e) => setKey(e.target.value)} placeholder="例如 newbiz.main" />
          </div>
          <div className="space-y-1">
            <label htmlFor="chain-name" className={`text-xs ${textSecondary}`}>名稱</label>
            <input id="chain-name" className={input} value={name} disabled={!canEdit} onChange={(e) => setName(e.target.value)} placeholder="例如 新契約主流程" />
          </div>
        </div>

        <div className="space-y-2">
          {steps.map((s, i) => (
            <div key={i} className="grid grid-cols-[auto_1fr_8rem_auto_auto] gap-2 items-center">
              <span className={`text-xs font-mono ${textTertiary}`}>{i + 1}.</span>
              <select aria-label={`步驟 ${i + 1} 規則`} className={input} value={s.ruleKey} disabled={!canEdit} onChange={(e) => updateStep(i, { ruleKey: e.target.value })}>
                <option value="">選擇規則</option>
                {rules.map((r) => (
                  <option key={r.ruleKey} value={r.ruleKey}>
                    {r.ruleKey}{r.activeVersionNo == null ? '（尚未生效）' : ` v${r.activeVersionNo}`}
                  </option>
                ))}
              </select>
              <input aria-label={`步驟 ${i + 1} 標籤`} className={input} value={s.label ?? ''} disabled={!canEdit} placeholder="標籤" onChange={(e) => updateStep(i, { label: e.target.value })} />
              <label className={`text-xs flex items-center gap-1 ${textSecondary}`}>
                <input type="checkbox" checked={s.stopOnHit} disabled={!canEdit} onChange={(e) => updateStep(i, { stopOnHit: e.target.checked })} />
                命中即中斷
              </label>
              <div className="flex gap-1">
                <button type="button" className={btnGhost} disabled={!canEdit || i === 0} onClick={() => move(i, -1)} aria-label="上移">↑</button>
                <button type="button" className={btnGhost} disabled={!canEdit || i === steps.length - 1} onClick={() => move(i, 1)} aria-label="下移">↓</button>
                <button type="button" className={btnGhost} disabled={!canEdit || steps.length === 1} onClick={() => setSteps((p) => p.filter((_, j) => j !== i))} aria-label="移除">✕</button>
              </div>
            </div>
          ))}
          {canEdit && (
            <button type="button" className="text-xs cursor-pointer text-[var(--color-group-green-600)]" onClick={() => setSteps((p) => [...p, { ruleKey: '', stopOnHit: false, label: '' }])}>
              ＋ 新增步驟
            </button>
          )}
        </div>
        {canEdit && (
          <button type="button" className={btnPrimary} disabled={busy || !key.trim() || steps.some((s) => !s.ruleKey)} onClick={() => void save()}>
            儲存流程
          </button>
        )}
      </div>

      {chainKey && (
        <div className={`${card} p-4 space-y-3`}>
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <span className={`text-sm font-semibold ${textPrimary}`}>試跑流程</span>
            <span className={`text-[11px] ${textTertiary}`}>輸入第一步需要的欄位；後面步驟的輸入由前一步的輸出補上</span>
          </div>
          <div className="space-y-2">
            {inputs.map((kv, i) => (
              <div key={i} className="grid grid-cols-[1fr_1fr_auto] gap-2">
                <input aria-label={`輸入 ${i + 1} 欄位`} className={input} value={kv.key} placeholder="欄位" onChange={(e) => setInputs((p) => p.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))} />
                <input aria-label={`輸入 ${i + 1} 值`} className={input} value={kv.value} placeholder="值" onChange={(e) => setInputs((p) => p.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
                <button type="button" className={btnGhost} onClick={() => setInputs((p) => p.filter((_, j) => j !== i))} aria-label="移除輸入">✕</button>
              </div>
            ))}
            <button type="button" className="text-xs cursor-pointer text-[var(--color-group-green-600)]" onClick={() => setInputs((p) => [...p, { key: '', value: '' }])}>＋ 新增輸入</button>
          </div>
          <button type="button" className={btnPrimary} disabled={busy} onClick={() => void run()}>執行流程</button>

          {outcome && (
            <div className="space-y-2">
              <div className={`text-sm font-semibold ${outcome.stopped ? 'text-amber-600' : 'text-emerald-600'}`}>
                {outcome.stopped ? `流程在「${outcome.stoppedAt}」中斷` : '流程跑完'}
                <span className={`text-[11px] font-normal ml-2 ${textTertiary}`}>耗時 {(outcome.nanos / 1e6).toFixed(1)} ms</span>
              </div>
              <ol className="space-y-1.5">
                {outcome.steps.map((s, i) => (
                  <li key={i} className={`text-sm ${textPrimary}`}>
                    <span className={`font-mono text-xs mr-1.5 ${textTertiary}`}>{i + 1}.</span>
                    <b>{stepLabel({ ruleKey: s.ruleKey, stopOnHit: false, label: s.label }, i)}</b>
                    <span className={`text-xs ml-1 ${textTertiary}`}>{s.ruleKey}{s.versionNo != null ? ` v${s.versionNo}` : ''}</span>
                    <span className="ml-2">{s.matched ? `命中 ${s.hitRuleIds.join('、')}` : '未命中'}</span>
                    {Object.keys(s.outputs).length > 0 && (
                      <span className={`ml-2 text-xs ${textSecondary}`}>
                        {Object.entries(s.outputs).map(([k, v]) => `${k}=${show(v)}`).join('、')}
                      </span>
                    )}
                    {s.note && <span className={`ml-2 text-xs ${s.stopped ? 'text-amber-600' : textTertiary}`}>{s.note}</span>}
                  </li>
                ))}
              </ol>
              {!outcome.stopped && Object.keys(outcome.finalOutputs).length > 0 && (
                <div className={`text-sm ${textPrimary}`}>
                  最終輸出：{Object.entries(outcome.finalOutputs).map(([k, v]) => `${k}=${show(v)}`).join('、')}
                </div>
              )}
            </div>
          )}
        </div>
      )}
      {notice && <div className="text-xs text-emerald-600">{notice}</div>}
      {error && <div className="text-xs text-red-600">{error}</div>}
    </div>
  );
}
