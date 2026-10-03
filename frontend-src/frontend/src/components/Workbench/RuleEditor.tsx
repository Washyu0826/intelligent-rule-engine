import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/rulesApi';
import {
  workbenchApi,
  type ChangeSuggestion,
  type ImpactAnalysis,
  type ReviewSheetData,
  type VersionView,
} from '../../api/workbenchApi';
import DiffView from '../Export/DiffView';
import ImpactPanel from './ImpactPanel';
import { BoundsPanel, FairnessPanel, RegressionPanel } from './ImpactExtras';
import RuleView from './RuleView';
import StatusBadge from './StatusBadge';
import TrialRunPanel from './TrialRunPanel';
import { btnGhost, btnPrimary, card, errMsg, input, textPrimary, textSecondary, textTertiary } from './ui';

interface Props {
  ruleKey: string;
  latest: VersionView;
  onChanged: () => void;
}

export default function RuleEditor({ ruleKey, latest, onChanged }: Props) {
  const [current, setCurrent] = useState<ReviewSheetData | null>(null);
  const [liveAnalysis, setLiveAnalysis] = useState<ImpactAnalysis | undefined>();
  const [instruction, setInstruction] = useState('');
  const [suggestion, setSuggestion] = useState<ChangeSuggestion | null>(null);
  const [suggestionTitle, setSuggestionTitle] = useState('');
  const [reason, setReason] = useState('');
  const [showSubmit, setShowSubmit] = useState(false);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const sheet = await workbenchApi.reviewSheet(latest.id);
      setCurrent(sheet);
      setLiveAnalysis(sheet.impact.analysis);
      if (!sheet.impact.checklist && (sheet.after.ruleType === 'DecisionTable' || sheet.after.ruleType === 'DecisionTree')) {
        const a = await api.analyze(sheet.after.rule, sheet.after.ruleType);
        setLiveAnalysis({
          coverageRate: a.coverageRate,
          gapCount: a.gaps.length,
          overlapCount: a.overlaps.length,
          gaps: a.gaps.map((g) => ({ message: g.message, conditions: g.conditions, volumeRatio: g.volumeRatio })),
          overlaps: a.overlaps.map((o) => o.message),
        });
      }
    } catch (e) {
      setError(errMsg(e));
    }
  }, [latest.id]);

  useEffect(() => {
    setSuggestion(null);
    setInstruction('');
    setReason('');
    setShowSubmit(false);
    setNotice('');
    void load();
  }, [load]);

  const run = async (label: string, fn: () => Promise<void>) => {
    setBusy(label);
    setError('');
    setNotice('');
    try {
      await fn();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy('');
    }
  };

  const askSuggestion = () =>
    run('suggest', async () => {
      setSuggestion(await workbenchApi.suggestChange(latest.id, instruction.trim()));
      setSuggestionTitle('AI 的修改建議（尚未儲存）');
    });

  const fillGap = (conditions: Record<string, string>) =>
    run('gap', async () => {
      setSuggestion(await workbenchApi.gapCase(latest.id, conditions));
      setSuggestionTitle('缺口補成案例（尚未儲存，結果待填）');
    });

  const adopt = () =>
    run('adopt', async () => {
      if (!suggestion) return;
      await workbenchApi.createDraft(ruleKey, suggestion.proposed);
      setSuggestion(null);
      setInstruction('');
      setNotice('已存成新草稿，確認內容後可送審');
      onChanged();
    });

  const submit = () =>
    run('submit', async () => {
      await workbenchApi.submit(latest.id, reason.trim());
      setShowSubmit(false);
      setReason('');
      setNotice('已送審');
      onChanged();
    });

  const simple = (label: string, fn: () => Promise<unknown>, done: string) =>
    run(label, async () => {
      await fn();
      setNotice(done);
      onChanged();
    });

  const editable = latest.status === 'DRAFT' || latest.status === 'ACTIVE' || latest.status === 'APPROVED' || latest.status === 'RETIRED';

  return (
    <div className="space-y-4">
      <div className={`${card} p-4 flex flex-wrap items-center gap-3`}>
        <span className={`text-sm font-semibold ${textPrimary}`}>
          {ruleKey} · 最新版 v{latest.versionNo}
        </span>
        <StatusBadge status={latest.status} />
        {latest.status === 'REJECTED' && (
          <>
            <span className={`text-xs ${textSecondary}`}>退回意見：{latest.reviewComment ?? '—'}</span>
            <button
              type="button"
              className={btnGhost}
              disabled={!!busy}
              onClick={() => simple('revise', () => workbenchApi.revise(latest.id), '已退回草稿，可繼續修改')}
            >
              改回草稿
            </button>
          </>
        )}
        {latest.status === 'REVIEW' && (
          <button
            type="button"
            className={btnGhost}
            disabled={!!busy}
            onClick={() => simple('withdraw', () => workbenchApi.withdraw(latest.id), '已撤回審核')}
          >
            撤回送審
          </button>
        )}
        {latest.status === 'DRAFT' && (
          <button type="button" className={btnPrimary} disabled={!!busy} onClick={() => setShowSubmit((v) => !v)}>
            送審
          </button>
        )}
      </div>

      {showSubmit && (
        <div className={`${card} p-4 space-y-2`}>
          <label className={`text-xs ${textSecondary}`}>送審理由（必填，審核人會看到）</label>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            className={input}
            placeholder="例如：配合新商品條款，把門檻由 700 調整為 650"
          />
          <div className="flex gap-2">
            <button type="button" className={btnPrimary} disabled={!reason.trim() || !!busy} onClick={() => void submit()}>
              確認送審
            </button>
            <button type="button" className={btnGhost} onClick={() => setShowSubmit(false)}>
              取消
            </button>
          </div>
        </div>
      )}

      {current && <RuleView envelope={current.after} />}
      {current && <TrialRunPanel envelope={current.after} ruleKey={ruleKey} status={latest.status} />}
      {current && <BoundsPanel bounds={current.impact.bounds} />}
      {current && <RegressionPanel regression={current.impact.regression} />}
      {current && <FairnessPanel fairness={current.impact.fairness} />}
      {current?.impact.checklist ? (
        <div className={`${card} p-4 text-xs ${textTertiary}`}>檢核清單（多重命中）：每項各自獨立，不做缺口與重疊分析</div>
      ) : (
      <ImpactPanel
        analysis={liveAnalysis}
        title="目前版本：缺口與重疊"
        onFillGap={editable && current?.after.ruleType === 'DecisionTable' ? fillGap : undefined}
        busy={!!busy}
      />
      )}

      {editable && (
        <div className={`${card} p-4 space-y-2`}>
          <label className={`text-sm font-semibold ${textPrimary}`}>你想改什麼？</label>
          <p className={`text-xs ${textTertiary}`}>
            用中文描述即可，AI 只會提出修改建議，不會直接儲存；你確認前後差異後才會存成新草稿。
          </p>
          <textarea
            value={instruction}
            onChange={(e) => setInstruction(e.target.value)}
            rows={2}
            className={input}
            placeholder="例如：把信用分數門檻從 700 改成 650"
          />
          <button
            type="button"
            className={btnPrimary}
            disabled={!instruction.trim() || !!busy}
            onClick={() => void askSuggestion()}
          >
            {busy === 'suggest' ? 'AI 思考中…' : '請 AI 提出修改建議'}
          </button>
        </div>
      )}

      {suggestion && current && (
        <div className="space-y-3">
          <div className={`text-sm font-semibold ${textPrimary}`}>{suggestionTitle || '修改建議（尚未儲存）'}</div>
          {suggestion.validation && suggestion.validation.valid === false && (
            <div className="text-xs text-red-600">
              尚未通過驗證（存成草稿後仍可修改）：{(suggestion.validation.errors ?? []).map((e) => e.message).join('；') || '請調整後重試'}
            </div>
          )}
          <DiffView current={suggestion.proposed} previous={current.after} />
          <RuleView envelope={suggestion.proposed} />
          <ImpactPanel analysis={suggestion.impact.analysis} title="修改後：缺口與重疊" />
          <div className="flex gap-2">
            <button type="button" className={btnPrimary} disabled={!!busy} onClick={() => void adopt()}>
              採用並存成新草稿
            </button>
            <button type="button" className={btnGhost} onClick={() => setSuggestion(null)}>
              放棄這個建議
            </button>
          </div>
        </div>
      )}

      {notice && <div className="text-xs text-emerald-600">{notice}</div>}
      {error && <div className="text-xs text-red-600">{error}</div>}
    </div>
  );
}
