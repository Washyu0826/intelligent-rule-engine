import { useMemo } from 'react';
import type { TreeView } from '../../api/workbenchApi';
import StatusBadge from './StatusBadge';
import { card, textPrimary, textSecondary, textTertiary } from './ui';

interface Props {
  tree: TreeView | null;
  selectedKey: string | null;
  onSelect: (ruleKey: string) => void;
  filter: Record<string, string>;
  onFilterChange: (filter: Record<string, string>) => void;
}

const UNPLACED = '（未分類）';

export default function RuleTree({ tree, selectedKey, onSelect, filter, onFilterChange }: Props) {
  const groups = useMemo(() => {
    const map = new Map<string, NonNullable<TreeView>['rules']>();
    for (const r of tree?.rules ?? []) {
      const key = r.path || UNPLACED;
      map.set(key, [...(map.get(key) ?? []), r]);
    }
    return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b, 'zh-Hant'));
  }, [tree]);

  const tagOptions = useMemo(() => {
    const out: Record<string, string[]> = {};
    for (const dim of tree?.tagDimensions ?? []) out[dim] = [];
    for (const r of tree?.rules ?? []) {
      for (const [dim, tags] of Object.entries(r.tags)) {
        out[dim] = Array.from(new Set([...(out[dim] ?? []), ...tags]));
      }
    }
    return out;
  }, [tree]);

  return (
    <div className={`${card} p-3 space-y-3`}>
      <div className={`text-sm font-semibold ${textPrimary}`}>規則目錄</div>

      {Object.entries(tagOptions).map(([dim, options]) => (
        <label key={dim} className="block">
          <span className={`text-[10px] ${textTertiary}`}>{dim}</span>
          <select
            value={filter[dim] ?? ''}
            onChange={(e) => {
              const next = { ...filter };
              if (e.target.value) next[dim] = e.target.value;
              else delete next[dim];
              onFilterChange(next);
            }}
            className="w-full mt-0.5 rounded-md border px-2 py-1 text-xs dark:bg-surface-2 dark:border-border bg-white border-light-border dark:text-text-primary text-light-text-primary"
          >
            <option value="">全部</option>
            {options.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        </label>
      ))}

      {!tree && <p className={`text-xs ${textTertiary}`}>載入中…</p>}
      {tree && tree.rules.length === 0 && <p className={`text-xs ${textTertiary}`}>沒有符合的規則</p>}

      <div className="space-y-2.5">
        {groups.map(([path, rules]) => (
          <div key={path}>
            <div className={`text-[11px] font-semibold ${textSecondary}`}>{path}</div>
            <ul className="mt-1 space-y-0.5">
              {rules.map((r) => (
                <li key={r.ruleKey}>
                  <button
                    type="button"
                    onClick={() => onSelect(r.ruleKey)}
                    className={`w-full text-left px-2 py-1.5 rounded-md text-xs flex items-center justify-between gap-2 cursor-pointer
                      ${selectedKey === r.ruleKey
                        ? 'bg-[var(--color-group-green-600)]/15 ' + textPrimary
                        : textSecondary + ' dark:hover:bg-surface-2 hover:bg-light-surface-2'}`}
                  >
                    <span className="truncate">{r.ruleKey}</span>
                    <StatusBadge status={r.latestStatus} />
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );
}
