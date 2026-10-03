import { useEffect, useState } from 'react';
import { workbenchApi, type TreeEntry } from '../../api/workbenchApi';
import { btnGhost, btnPrimary, card, errMsg, input, textPrimary, textTertiary } from './ui';

interface Props {
  entry: TreeEntry;
  dimensions: string[];
  canEdit: boolean;
  onSaved: () => void;
}

export default function PlacementForm({ entry, dimensions, canEdit, onSaved }: Props) {
  const [open, setOpen] = useState(false);
  const [path, setPath] = useState(entry.path);
  const [tags, setTags] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setPath(entry.path);
    setTags(Object.fromEntries(dimensions.map((d) => [d, (entry.tags[d] ?? []).join('、')])));
    setError('');
  }, [entry, dimensions]);

  const save = async () => {
    setBusy(true);
    setError('');
    try {
      const parsed: Record<string, string[]> = {};
      for (const d of dimensions) {
        parsed[d] = (tags[d] ?? '').split(/[、,，\s]+/).map((s) => s.trim()).filter(Boolean);
      }
      await workbenchApi.place(entry.ruleKey, path.trim(), parsed);
      setOpen(false);
      onSaved();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const summary = [entry.path || '（未分類）', ...Object.entries(entry.tags).flatMap(([d, ts]) => ts.map((t) => `${d}:${t}`))];

  return (
    <div className={`${card} p-3 space-y-2`}>
      <div className="flex items-center justify-between gap-2">
        <div className={`text-xs ${textTertiary} truncate`}>目錄與標籤：{summary.join('　')}</div>
        {canEdit && (
          <button type="button" className={btnGhost} onClick={() => setOpen((v) => !v)}>
            {open ? '收合' : '調整分類'}
          </button>
        )}
      </div>
      {open && (
        <div className="space-y-2">
          <label className={`block text-xs ${textPrimary}`}>
            目錄路徑（用 / 分層，例如 核保/醫療險）
            <input value={path} onChange={(e) => setPath(e.target.value)} className={`${input} mt-1`} />
          </label>
          {dimensions.map((d) => (
            <label key={d} className={`block text-xs ${textPrimary}`}>
              {d}（多個標籤用頓號分隔）
              <input
                value={tags[d] ?? ''}
                onChange={(e) => setTags({ ...tags, [d]: e.target.value })}
                className={`${input} mt-1`}
              />
            </label>
          ))}
          <button type="button" className={btnPrimary} disabled={busy} onClick={() => void save()}>
            儲存分類
          </button>
          {error && <div className="text-xs text-red-600">{error}</div>}
        </div>
      )}
    </div>
  );
}
