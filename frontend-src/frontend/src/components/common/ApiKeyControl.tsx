import { useEffect, useRef, useState } from 'react';
import { getApiKey, setApiKey, clearApiKey, hasApiKey, subscribeApiKey } from '../../api/apiKey';

/**
 * Header 上的 API Key 設定入口。
 *
 * 後端在 prod profile 會啟用 `rules.security.api-key-enabled`，攔截所有 `/tools/**`。
 * 自帶的 SPA 與 API 同源，因此在那個環境下前端每個請求都會被擋成 401 ——
 * 這個控制項就是讓操作者把金鑰帶進來的地方（存在 sessionStorage，不進 bundle，
 * 理由見 api/apiKey.ts）。開發環境後端預設關閉認證，不填也完全不影響。
 */
export default function ApiKeyControl() {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [configured, setConfigured] = useState(hasApiKey);
  const popoverRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => subscribeApiKey(() => setConfigured(hasApiKey())), []);

  // 開啟時帶入現值並聚焦
  useEffect(() => {
    if (!open) return;
    setDraft(getApiKey());
    inputRef.current?.focus();
  }, [open]);

  // 點外面 / Esc 關閉
  useEffect(() => {
    if (!open) return;
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
  }, [open]);

  const save = () => {
    setApiKey(draft);
    setOpen(false);
  };

  const clear = () => {
    clearApiKey();
    setDraft('');
    setOpen(false);
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label={configured ? 'API Key 已設定，點擊修改' : 'API Key 未設定，點擊輸入'}
        aria-expanded={open}
        title={configured ? 'API Key 已設定（本分頁）' : 'API Key 未設定'}
        className="
          group flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium
          transition-all duration-200 cursor-pointer
          dark:text-text-secondary dark:hover:text-text-primary dark:bg-surface-2 dark:hover:bg-surface-3
          text-light-text-secondary hover:text-light-text-primary bg-light-surface-2 hover:bg-light-surface-3
          border dark:border-border/60 border-light-border
        "
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="5.5" cy="10.5" r="3" stroke="currentColor" strokeWidth="1.5" />
          <path
            d="M7.7 8.3 13 3M11 5l1.5 1.5M12.5 3.5 14 5"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
          />
        </svg>
        <span
          className={`w-1.5 h-1.5 rounded-full ${configured ? 'bg-success' : 'bg-text-tertiary/50'}`}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div
          ref={popoverRef}
          role="dialog"
          aria-label="API Key 設定"
          className="
            absolute right-0 top-full mt-2 w-80 p-3 rounded-xl z-50
            dark:bg-surface-1 bg-white
            border dark:border-border border-light-border
            shadow-lg
          "
        >
          <label
            htmlFor="api-key-input"
            className="block text-[11px] font-semibold mb-1.5 dark:text-text-primary text-light-text-primary"
          >
            API Key（X-API-Key）
          </label>
          <input
            id="api-key-input"
            ref={inputRef}
            type="password"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') save();
            }}
            placeholder="貼上後端設定的金鑰"
            autoComplete="off"
            spellCheck={false}
            className="
              w-full px-2.5 py-1.5 rounded-lg text-xs font-mono
              dark:bg-surface-2 bg-light-surface-2
              dark:text-text-primary text-light-text-primary
              border dark:border-border border-light-border
              focus:outline-none focus:ring-2 focus:ring-[var(--color-group-green-600)]/40
            "
          />
          <p className="mt-2 text-[10px] leading-relaxed dark:text-text-tertiary text-light-text-tertiary">
            僅存在此瀏覽器分頁（sessionStorage），關閉分頁即清除，不會寫進前端程式檔。
            後端未啟用認證時可留空。
          </p>
          <div className="mt-2.5 flex items-center gap-2">
            <button
              type="button"
              onClick={save}
              className="
                flex-1 px-2.5 py-1.5 rounded-lg text-xs font-medium cursor-pointer
                bg-[var(--color-group-green-600)] text-white
                hover:opacity-90 transition-opacity
              "
            >
              儲存
            </button>
            <button
              type="button"
              onClick={clear}
              disabled={!configured}
              className="
                px-2.5 py-1.5 rounded-lg text-xs font-medium cursor-pointer
                dark:text-text-secondary text-light-text-secondary
                dark:bg-surface-2 bg-light-surface-2
                border dark:border-border border-light-border
                hover:opacity-90 transition-opacity
                disabled:opacity-40 disabled:cursor-not-allowed
              "
            >
              清除
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
