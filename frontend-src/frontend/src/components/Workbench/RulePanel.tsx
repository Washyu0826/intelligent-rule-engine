import { useCallback, useEffect, useState } from 'react';
import { workbenchApi, type TreeEntry, type VersionView } from '../../api/workbenchApi';
import PlacementForm from './PlacementForm';
import ReviewSheetPanel from './ReviewSheetPanel';
import RuleEditor from './RuleEditor';
import StatusBadge from './StatusBadge';
import { errMsg, textSecondary, textTertiary } from './ui';

interface Props {
  entry: TreeEntry;
  dimensions: string[];
  isMaker: boolean;
  isChecker: boolean;
  initialVersionId: number | null;
  onChanged: () => void;
}

export default function RulePanel({ entry, dimensions, isMaker, isChecker, initialVersionId, onChanged }: Props) {
  const [history, setHistory] = useState<VersionView[]>([]);
  const [error, setError] = useState('');
  const [tab, setTab] = useState<'edit' | 'sheet'>('sheet');
  const [versionId, setVersionId] = useState<number | null>(initialVersionId);

  const reload = useCallback(async () => {
    setError('');
    try {
      const h = await workbenchApi.history(entry.ruleKey);
      setHistory([...h].sort((a, b) => b.versionNo - a.versionNo));
    } catch (e) {
      setError(errMsg(e));
    }
  }, [entry.ruleKey]);

  useEffect(() => {
    void reload();
  }, [reload, entry.latestStatus, entry.latestVersionNo]);

  useEffect(() => {
    setVersionId(initialVersionId);
    setTab(initialVersionId != null || !isMaker ? 'sheet' : 'edit');
  }, [entry.ruleKey, initialVersionId, isMaker]);

  const changed = () => {
    void reload();
    onChanged();
  };

  const latest = history[0];
  const shownVersion = versionId ?? latest?.id ?? null;

  return (
    <div className="space-y-4">
      <PlacementForm entry={entry} dimensions={dimensions} canEdit={isMaker} onSaved={onChanged} />

      {error && <div className="text-xs text-red-600">{error}</div>}

      <div className="flex flex-wrap items-center gap-2">
        <span className={`text-xs ${textTertiary}`}>版本</span>
        {history.map((v) => (
          <button
            key={v.id}
            type="button"
            onClick={() => {
              setVersionId(v.id);
              setTab('sheet');
            }}
            className={`px-2 py-1 rounded-md text-xs border cursor-pointer flex items-center gap-1.5 ${
              shownVersion === v.id && tab === 'sheet'
                ? 'border-[var(--color-group-green-600)] ' + textSecondary
                : 'dark:border-border border-light-border ' + textTertiary
            }`}
          >
            v{v.versionNo} <StatusBadge status={v.status} />
          </button>
        ))}
        {isMaker && latest && (
          <button
            type="button"
            onClick={() => setTab('edit')}
            className={`ml-auto px-3 py-1 rounded-md text-xs font-semibold cursor-pointer ${
              tab === 'edit' ? 'bg-[var(--color-group-green-600)] text-white' : 'border dark:border-border border-light-border ' + textSecondary
            }`}
          >
            修改規則
          </button>
        )}
      </div>

      {tab === 'edit' && isMaker && latest ? (
        <RuleEditor ruleKey={entry.ruleKey} latest={latest} onChanged={changed} />
      ) : shownVersion != null ? (
        <ReviewSheetPanel versionId={shownVersion} canReview={isChecker} onChanged={changed} tags={entry.tags} />
      ) : (
        <div className={`text-sm ${textTertiary}`}>載入中…</div>
      )}
    </div>
  );
}
