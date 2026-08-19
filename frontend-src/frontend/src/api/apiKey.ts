/**
 * API Key 保管（對應後端 `rules.security.api-key-enabled` + `X-API-Key`）。
 *
 * 為什麼由使用者在 UI 輸入，而不是打包進 bundle：
 * 後端的 API key 是一組共享密鑰。若用 `VITE_API_KEY` 之類的建置期變數注入，
 * 它會原封不動出現在所有瀏覽器都下載得到的 JS bundle 裡 —— 那等於公開這組密鑰，
 * 比不做認證更糟（因為看起來有保護）。因此改由操作者自行輸入、只存在本分頁。
 *
 * 存 sessionStorage 而非 localStorage：關掉分頁即失效，共用電腦上不會留存。
 *
 * 正式環境的正解仍是在服務前面擺 identity-aware proxy（IAP / SSO），
 * 由它處理人類使用者的身分；X-API-Key 本質上是給機器客戶端（MCP / 後端整合）用的。
 * 本機制的定位是「讓自帶 UI 在啟用 API key 的環境下仍可操作」，見 docs/deployment-playbook.md。
 */

const STORAGE_KEY = 'rules-mcp.api-key';

/** 訂閱者（讓 header 上的指示燈能即時反映設定狀態） */
const listeners = new Set<() => void>();

function safeSession(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null;
  } catch {
    // Safari 無痕模式等情境下存取 sessionStorage 會拋例外
    return null;
  }
}

export function getApiKey(): string {
  return safeSession()?.getItem(STORAGE_KEY) ?? '';
}

export function hasApiKey(): boolean {
  return getApiKey().length > 0;
}

export function setApiKey(key: string): void {
  const trimmed = key.trim();
  const store = safeSession();
  if (!store) return;
  if (trimmed) store.setItem(STORAGE_KEY, trimmed);
  else store.removeItem(STORAGE_KEY);
  listeners.forEach((fn) => fn());
}

export function clearApiKey(): void {
  setApiKey('');
}

export function subscribeApiKey(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/**
 * 要附加到 /tools/** 請求上的認證 header。
 * 未設定時回空物件 —— 後端關閉 API key 認證時（開發預設）行為完全不變。
 */
export function authHeaders(): Record<string, string> {
  const key = getApiKey();
  return key ? { 'X-API-Key': key } : {};
}
