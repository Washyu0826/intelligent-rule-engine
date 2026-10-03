import { useCallback, useEffect, useState } from 'react';
import { workbenchApi, type ReviewSheetData } from '../../api/workbenchApi';
import DiffView from '../Export/DiffView';
import ImpactPanel from './ImpactPanel';
import { BoundsPanel, FairnessPanel, RegressionPanel } from './ImpactExtras';
import RuleView from './RuleView';
import StatusBadge from './StatusBadge';
import TrialRunPanel from './TrialRunPanel';
import { btnDanger, btnGhost, btnPrimary, card, errMsg, input, textPrimary, textSecondary, textTertiary } from './ui';

interface Props {
  versionId: number;
  canReview: boolean;
  onChanged: () => void;
  /** 規則的標籤（含「法規」維度），審核時一併看對應到哪些法規／內規條款 */
  tags?: Record<string, string[]>;
}

export default function ReviewSheetPanel({ versionId, canReview, onChanged, tags }: Props) {
  const [sheet, setSheet] = useState<ReviewSheetData | null>(null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      setSheet(await workbenchApi.reviewSheet(versionId));
    } catch (e) {
      setError(errMsg(e));
    }
  }, [versionId]);

  useEffect(() => {
    setSheet(null);
    setComment('');
    setNotice('');
    void load();
  }, [load]);

  const act = async (label: string, fn: () => Promise<unknown>) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await fn();
      setNotice(`${label}完成`);
      setComment('');
      await load();
      onChanged();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  if (error && !sheet) return <div className="text-sm text-red-600">{error}</div>;
  if (!sheet) return <div className={`text-sm ${textTertiary}`}>載入審核單中…</div>;

  return (
    <div className="space-y-4">
      <div className={`${card} p-4 space-y-2`}>
        <div className="flex items-center gap-2">
          <span className={`text-sm font-semibold ${textPrimary}`}>
            {sheet.ruleKey} · v{sheet.versionNo}
          </span>
          <StatusBadge status={sheet.status} />
        </div>
        <div className={`text-xs ${textSecondary}`}>
          送審人：{sheet.submittedBy ?? '—'}
          {sheet.baseVersionNo != null && <span>&emsp;比對基準：v{sheet.baseVersionNo}</span>}
        </div>
        <div className={`text-sm ${textPrimary}`}>
          <span className={`text-xs ${textTertiary}`}>送審理由&emsp;</span>
          {sheet.submitReason || '（未填寫）'}
        </div>
        {tags && Object.keys(tags).length > 0 && (
          <div className={`text-xs ${textSecondary}`}>
            {Object.entries(tags).map(([dim, values]) => (
              <span key={dim} className="mr-3">
                <span className={textTertiary}>{dim}：</span>{values.join('、')}
              </span>
            ))}
          </div>
        )}
        {sheet.impact.note && <div className="text-xs text-amber-600">{sheet.impact.note}</div>}
      </div>

      <BoundsPanel bounds={sheet.impact.bounds} />
      <RegressionPanel regression={sheet.impact.regression} />
      <FairnessPanel fairness={sheet.impact.fairness} />
      {!sheet.impact.checklist && (
        <ImpactPanel analysis={sheet.impact.analysis} title="影響報告：缺口與重疊（送審當下的快照）" />
      )}

      {sheet.before ? (
        <DiffView current={sheet.after} previous={sheet.before} />
      ) : (
        <div className={`text-xs ${textTertiary}`}>這是首版，沒有舊版可比對；以下為完整內容。</div>
      )}
      <RuleView envelope={sheet.after} />
      <TrialRunPanel envelope={sheet.after} ruleKey={sheet.ruleKey} status={sheet.status} />

      {canReview && (
        <div className={`${card} p-4 space-y-3`}>
          <textarea
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            rows={2}
            placeholder="審核意見（核准可不填；退回必填）"
            className={input}
            disabled={busy}
          />
          <div className="flex flex-wrap gap-2">
            {sheet.status === 'REVIEW' && (
              <>
                <button
                  type="button"
                  className={btnPrimary}
                  disabled={busy}
                  onClick={() => act('核准', () => workbenchApi.approve(versionId, comment.trim()))}
                >
                  核准
                </button>
                <button
                  type="button"
                  className={btnDanger}
                  disabled={busy || !comment.trim()}
                  title={comment.trim() ? '' : '退回必須填寫意見'}
                  onClick={() => act('退回', () => workbenchApi.reject(versionId, comment.trim()))}
                >
                  退回
                </button>
              </>
            )}
            {sheet.status === 'APPROVED' && (
              <button
                type="button"
                className={btnPrimary}
                disabled={busy}
                onClick={() => act('生效', () => workbenchApi.activate(versionId))}
              >
                設為生效
              </button>
            )}
            <button type="button" className={btnGhost} disabled={busy} onClick={() => void load()}>
              重新整理
            </button>
          </div>
        </div>
      )}
      {notice && <div className="text-xs text-emerald-600">{notice}</div>}
      {error && <div className="text-xs text-red-600">{error}</div>}
    </div>
  );
}
