import { useCallback, useEffect, useState } from 'react';
import { getAuth, subscribeAuth, type AuthState } from '../../api/auth';
import { workbenchApi, type TreeView, type VersionView } from '../../api/workbenchApi';
import NewRuleForm from './NewRuleForm';
import RulePanel from './RulePanel';
import RuleTree from './RuleTree';
import StatusBadge from './StatusBadge';
import { card, errMsg, textPrimary, textSecondary, textTertiary } from './ui';

export default function WorkbenchTab() {
  const [auth, setAuth] = useState<AuthState | null>(getAuth);
  const [tree, setTree] = useState<TreeView | null>(null);
  const [filter, setFilter] = useState<Record<string, string>>({});
  const [queue, setQueue] = useState<VersionView[]>([]);
  const [selected, setSelected] = useState<{ ruleKey: string; versionId: number | null } | null>(null);
  const [error, setError] = useState('');

  useEffect(() => subscribeAuth(() => setAuth(getAuth())), []);

  const roles = auth?.roles ?? [];
  const isChecker = roles.includes('CHECKER');
  const isMaker = roles.includes('MAKER');

  const refresh = useCallback(async () => {
    if (!auth) return;
    setError('');
    try {
      setTree(await workbenchApi.tree(filter));
      setQueue(isChecker ? await workbenchApi.reviewQueue() : []);
    } catch (e) {
      setError(errMsg(e));
    }
  }, [auth, filter, isChecker]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  if (!auth) {
    return (
      <div className={`${card} max-w-xl mx-auto mt-16 p-8 text-center space-y-2`}>
        <div className={`text-base font-semibold ${textPrimary}`}>請先登入</div>
        <p className={`text-sm ${textSecondary}`}>
          審核工作台依角色顯示內容。請點右上角「登入」，使用制單（maker）或審核（checker）帳號。
        </p>
      </div>
    );
  }

  const entry = tree?.rules.find((r) => r.ruleKey === selected?.ruleKey) ?? null;
  const myWork = (tree?.rules ?? []).filter((r) => r.latestStatus === 'DRAFT' || r.latestStatus === 'REJECTED');

  return (
    <div className="max-w-7xl mx-auto py-6 grid grid-cols-1 lg:grid-cols-[18rem_1fr] gap-5">
      <aside className="space-y-3">
        {isMaker && <NewRuleForm onCreated={(k) => { void refresh(); setSelected({ ruleKey: k, versionId: null }); }} />}
        <RuleTree
          tree={tree}
          selectedKey={selected?.ruleKey ?? null}
          onSelect={(k) => setSelected({ ruleKey: k, versionId: null })}
          filter={filter}
          onFilterChange={setFilter}
        />
      </aside>

      <section className="min-w-0 space-y-4">
        {error && <div className="text-sm text-red-600">{error}</div>}

        {entry ? (
          <RulePanel
            entry={entry}
            dimensions={tree?.tagDimensions ?? []}
            isMaker={isMaker}
            isChecker={isChecker}
            initialVersionId={selected?.versionId ?? null}
            onChanged={() => void refresh()}
          />
        ) : (
          <div className="space-y-5">
            <div className={`text-sm ${textSecondary}`}>
              {auth.username}，你好。{isChecker ? '以下是等你審核的規則。' : isMaker ? '以下是需要你處理的規則。' : '請從左側選擇規則檢視。'}
            </div>

            {isChecker && (
              <div className={`${card} p-4 space-y-2`}>
                <div className={`text-sm font-semibold ${textPrimary}`}>待我審核（{queue.length}）</div>
                {queue.length === 0 && <div className={`text-xs ${textTertiary}`}>目前沒有待審項目</div>}
                {queue.map((v) => (
                  <button
                    key={v.id}
                    type="button"
                    onClick={() => setSelected({ ruleKey: v.ruleKey, versionId: v.id })}
                    className="w-full text-left px-3 py-2 rounded-lg border dark:border-border border-light-border cursor-pointer dark:hover:bg-surface-2 hover:bg-light-surface-2"
                  >
                    <div className={`text-sm ${textPrimary}`}>
                      {v.ruleKey} · v{v.versionNo}&emsp;<span className={`text-xs ${textTertiary}`}>送審人 {v.submittedBy}</span>
                    </div>
                    <div className={`text-xs ${textSecondary}`}>理由：{v.submitReason || '（未填寫）'}</div>
                  </button>
                ))}
              </div>
            )}

            {isMaker && (
              <div className={`${card} p-4 space-y-2`}>
                <div className={`text-sm font-semibold ${textPrimary}`}>我的草稿與被退回（{myWork.length}）</div>
                {myWork.length === 0 && <div className={`text-xs ${textTertiary}`}>沒有待處理的草稿</div>}
                {myWork.map((r) => (
                  <button
                    key={r.ruleKey}
                    type="button"
                    onClick={() => setSelected({ ruleKey: r.ruleKey, versionId: null })}
                    className="w-full text-left px-3 py-2 rounded-lg border dark:border-border border-light-border cursor-pointer flex items-center justify-between dark:hover:bg-surface-2 hover:bg-light-surface-2"
                  >
                    <span className={`text-sm ${textPrimary}`}>{r.ruleKey} · v{r.latestVersionNo}</span>
                    <StatusBadge status={r.latestStatus} />
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
