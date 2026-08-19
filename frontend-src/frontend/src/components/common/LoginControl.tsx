import { useEffect, useRef, useState } from 'react';
import { getAuth, login, logout, subscribeAuth, type AuthState, type LoginError } from '../../api/auth';

/** 角色顯示對照（維持與後端 seed 一致的語意） */
const ROLE_LABELS: Record<string, string> = {
  MAKER: '制單',
  CHECKER: '審核',
  ADMIN: '管理',
};

/**
 * Header 上的登入控制項（P1-S5）。
 *
 * 未登入：顯示「登入」按鈕 → 開帳密彈窗。
 * 已登入：顯示使用者名 + 角色徽章 → 點開可登出。
 *
 * 後端目前是 permissive 模式（未登入也可用 /tools/**），所以這裡不擋主流程；
 * 切 enforce 後，401 的錯誤提示會引導使用者回到這裡。
 */
export default function LoginControl() {
  const [auth, setAuth] = useState<AuthState | null>(getAuth);
  const [open, setOpen] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const popoverRef = useRef<HTMLDivElement>(null);
  const userInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => subscribeAuth(() => setAuth(getAuth())), []);

  useEffect(() => {
    if (!open) return;
    setError('');
    if (!auth) userInputRef.current?.focus();
    const onDown = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, auth]);

  const doLogin = async () => {
    if (!username.trim() || !password || busy) return;
    setBusy(true);
    setError('');
    try {
      await login(username.trim(), password);
      setPassword('');
      setOpen(false);
    } catch (e) {
      setError((e as LoginError).message ?? '登入失敗');
    } finally {
      setBusy(false);
    }
  };

  const doLogout = () => {
    logout();
    setOpen(false);
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-label={auth ? `已登入：${auth.username}，點擊管理` : '登入'}
        className="
          group flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium
          transition-all duration-200 cursor-pointer
          dark:text-text-secondary dark:hover:text-text-primary dark:bg-surface-2 dark:hover:bg-surface-3
          text-light-text-secondary hover:text-light-text-primary bg-light-surface-2 hover:bg-light-surface-3
          border dark:border-border/60 border-light-border
        "
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="8" cy="5" r="3" stroke="currentColor" strokeWidth="1.5" />
          <path d="M2.5 14a5.5 5.5 0 0 1 11 0" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
        {auth ? (
          <span className="max-w-24 truncate">{auth.username}</span>
        ) : (
          <span>登入</span>
        )}
        <span
          className={`w-1.5 h-1.5 rounded-full ${auth ? 'bg-success' : 'bg-text-tertiary/50'}`}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div
          ref={popoverRef}
          role="dialog"
          aria-label={auth ? '帳號資訊' : '登入'}
          className="
            absolute right-0 top-full mt-2 w-80 p-3 rounded-xl z-50
            dark:bg-surface-1 bg-white
            border dark:border-border border-light-border
            shadow-lg
          "
        >
          {auth ? (
            <>
              <div className="text-sm font-semibold dark:text-text-primary text-light-text-primary">
                {auth.username}
              </div>
              <div className="mt-1.5 flex flex-wrap gap-1">
                {auth.roles.map((r) => (
                  <span
                    key={r}
                    className="
                      px-1.5 py-0.5 rounded text-[10px] font-medium
                      bg-[var(--color-group-green-600)]/10 text-[var(--color-group-green-600)]
                      border border-[var(--color-group-green-600)]/30
                    "
                  >
                    {ROLE_LABELS[r] ?? r}
                  </span>
                ))}
              </div>
              <p className="mt-2 text-[10px] dark:text-text-tertiary text-light-text-tertiary">
                登入效期至 {new Date(auth.expiresAt * 1000).toLocaleTimeString()}（逾時自動登出）
              </p>
              <button
                type="button"
                onClick={doLogout}
                className="
                  mt-2.5 w-full px-2.5 py-1.5 rounded-lg text-xs font-medium cursor-pointer
                  dark:text-text-secondary text-light-text-secondary
                  dark:bg-surface-2 bg-light-surface-2
                  border dark:border-border border-light-border
                  hover:opacity-90 transition-opacity
                "
              >
                登出
              </button>
            </>
          ) : (
            <>
              <label
                htmlFor="login-username"
                className="block text-[11px] font-semibold mb-1.5 dark:text-text-primary text-light-text-primary"
              >
                登入（demo：maker / checker / admin）
              </label>
              <input
                id="login-username"
                ref={userInputRef}
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="帳號"
                autoComplete="username"
                spellCheck={false}
                className="
                  w-full px-2.5 py-1.5 rounded-lg text-xs
                  dark:bg-surface-2 bg-light-surface-2
                  dark:text-text-primary text-light-text-primary
                  border dark:border-border border-light-border
                  focus:outline-none focus:ring-2 focus:ring-[var(--color-group-green-600)]/40
                "
              />
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') doLogin();
                }}
                placeholder="密碼"
                autoComplete="current-password"
                className="
                  mt-1.5 w-full px-2.5 py-1.5 rounded-lg text-xs
                  dark:bg-surface-2 bg-light-surface-2
                  dark:text-text-primary text-light-text-primary
                  border dark:border-border border-light-border
                  focus:outline-none focus:ring-2 focus:ring-[var(--color-group-green-600)]/40
                "
              />
              {error && (
                <p className="mt-1.5 text-[11px] text-danger" role="alert">
                  {error}
                </p>
              )}
              <button
                type="button"
                onClick={doLogin}
                disabled={busy || !username.trim() || !password}
                className="
                  mt-2.5 w-full px-2.5 py-1.5 rounded-lg text-xs font-medium cursor-pointer
                  bg-[var(--color-group-green-600)] text-white
                  hover:opacity-90 transition-opacity
                  disabled:opacity-40 disabled:cursor-not-allowed
                "
              >
                {busy ? '登入中…' : '登入'}
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
