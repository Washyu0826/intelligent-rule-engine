import { useState } from 'react';
import { workbenchApi } from '../../api/workbenchApi';
import type { RuleEnvelope } from '../../types';
import { btnGhost, btnPrimary, card, errMsg, input, textSecondary, textTertiary } from './ui';

export default function NewRuleForm({ onCreated }: { onCreated: (ruleKey: string) => void }) {
  const [open, setOpen] = useState(false);
  const [ruleKey, setRuleKey] = useState('');
  const [json, setJson] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const create = async () => {
    setBusy(true);
    setError('');
    try {
      const envelope = JSON.parse(json) as RuleEnvelope;
      await workbenchApi.createDraft(ruleKey.trim(), envelope);
      setOpen(false);
      setRuleKey('');
      setJson('');
      onCreated(ruleKey.trim());
    } catch (e) {
      setError(e instanceof SyntaxError ? 'JSON 格式不正確' : errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={`${card} p-3 space-y-2`}>
      <button type="button" className={btnGhost} onClick={() => setOpen((v) => !v)}>
        {open ? '收合' : '＋ 新增規則（貼上 RuleEnvelope）'}
      </button>
      {open && (
        <div className="space-y-2">
          <p className={`text-xs ${textTertiary}`}>
            可先在「規則生成」頁用中文描述產出規則，複製其 JSON 貼到這裡建立草稿。
          </p>
          <input
            value={ruleKey}
            onChange={(e) => setRuleKey(e.target.value)}
            placeholder="規則代號，例如 uw.medical.age-limit"
            className={input}
          />
          <textarea
            value={json}
            onChange={(e) => setJson(e.target.value)}
            rows={6}
            placeholder='{"ruleType":"DecisionTable", ...}'
            className={`${input} font-mono text-xs ${textSecondary}`}
          />
          <button type="button" className={btnPrimary} disabled={busy || !ruleKey.trim() || !json.trim()} onClick={() => void create()}>
            建立草稿
          </button>
          {error && <div className="text-xs text-red-600">{error}</div>}
        </div>
      )}
    </div>
  );
}
