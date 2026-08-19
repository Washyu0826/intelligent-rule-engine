/**
 * JWT 登入狀態管理（P1-S5，對應後端 /auth/login、/auth/me）。
 *
 * 為什麼存 sessionStorage 而非 httpOnly cookie：
 * cookie 會被瀏覽器自動附帶 —— 那正是 CSRF 的攻擊面，後端因為「Bearer header
 * 不會自動帶」才敢關 CSRF 防護（見 SecurityConfig）；改用 cookie 等於把攻擊面
 * 請回來。且同一組 API 也服務 MCP / 機器客戶端（都用 Bearer），兩通道統一。
 * sessionStorage 關分頁即失效，暴露窗口比 localStorage 小。
 *
 * 前端對 JWT 只做 decode（顯示 username/roles/過期時間用），
 * 絕不做「驗證」—— 簽章驗證是後端的事，前端拿到什麼顯示什麼，
 * 偽造的 token 到後端自然會 401。
 */

const STORAGE_KEY = 'rules-mcp.jwt';

export interface AuthState {
  token: string;
  username: string;
  roles: string[];
  /** epoch 秒 */
  expiresAt: number;
}

const listeners = new Set<() => void>();

function safeSession(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null;
  } catch {
    return null;
  }
}

/** 解 JWT payload（base64url → JSON）。只為顯示，不驗證。 */
function decodePayload(token: string): { sub?: string; roles?: string[]; exp?: number } | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(b64));
  } catch {
    return null;
  }
}

export function getAuth(): AuthState | null {
  const token = safeSession()?.getItem(STORAGE_KEY);
  if (!token) return null;
  const payload = decodePayload(token);
  if (!payload?.sub || !payload.exp) return null;
  // 過期的 token 視同未登入（後端也會拒絕，這裡只是讓 UI 提前反映）
  if (payload.exp * 1000 <= Date.now()) {
    safeSession()?.removeItem(STORAGE_KEY);
    return null;
  }
  return { token, username: payload.sub, roles: payload.roles ?? [], expiresAt: payload.exp };
}

export function isLoggedIn(): boolean {
  return getAuth() !== null;
}

/** 要附加到請求上的 Authorization header；未登入回空物件（permissive 模式行為不變）。 */
export function bearerHeader(): Record<string, string> {
  const auth = getAuth();
  return auth ? { Authorization: `Bearer ${auth.token}` } : {};
}

export function subscribeAuth(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function notify(): void {
  listeners.forEach((fn) => fn());
}

export interface LoginError {
  message: string;
}

/** 登入：成功則保存 token 並通知訂閱者；失敗拋 LoginError（統一文案來自後端）。 */
export async function login(username: string, password: string): Promise<AuthState> {
  let res: Response;
  try {
    res = await fetch('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    throw { message: '無法連線到伺服器' } as LoginError;
  }
  if (!res.ok) {
    let message = '登入失敗';
    try {
      message = (await res.json()).message ?? message;
    } catch {
      /* keep default */
    }
    throw { message } as LoginError;
  }
  const body = (await res.json()) as { token: string };
  safeSession()?.setItem(STORAGE_KEY, body.token);
  notify();
  const auth = getAuth();
  if (!auth) throw { message: '伺服器回傳的 token 無法解析' } as LoginError;
  return auth;
}

export function logout(): void {
  safeSession()?.removeItem(STORAGE_KEY);
  notify();
}
