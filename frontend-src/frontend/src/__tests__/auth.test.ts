// @vitest-environment jsdom
//
// jsdom：auth.ts 用 sessionStorage 保管 token（同 apiKey.test.ts 的理由）。

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { getAuth, isLoggedIn, bearerHeader, login, logout, subscribeAuth } from '../api/auth';
import { setApiKey, clearApiKey } from '../api/apiKey';
import { api } from '../api/rulesApi';

/** 造一個結構正確（但簽章隨便）的 JWT —— 前端只 decode 不驗證，夠用 */
function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64(payload)}.fake-signature`;
}

function stubLoginOk(token: string) {
  const spy = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ token }) });
  vi.stubGlobal('fetch', spy);
  return spy;
}

describe('JWT 登入狀態（P1-S5）', () => {
  beforeEach(() => {
    logout();
    clearApiKey();
  });

  afterEach(() => {
    logout();
    clearApiKey();
    vi.restoreAllMocks();
  });

  describe('token 保管與解析', () => {
    it('未登入：getAuth null、bearerHeader 空 —— permissive 模式下行為與導入前相同', () => {
      expect(getAuth()).toBeNull();
      expect(isLoggedIn()).toBe(false);
      expect(bearerHeader()).toEqual({});
    });

    it('登入成功後解析出 username / roles / 過期時間', async () => {
      const exp = Math.floor(Date.now() / 1000) + 3600;
      stubLoginOk(fakeJwt({ sub: 'maker', roles: ['MAKER'], exp }));

      const auth = await login('maker', 'demo-pass-2026');
      expect(auth.username).toBe('maker');
      expect(auth.roles).toEqual(['MAKER']);
      expect(auth.expiresAt).toBe(exp);
      expect(bearerHeader().Authorization).toMatch(/^Bearer ey/);
    });

    it('過期 token 視同未登入並自動清除（UI 提前反映，後端本來也會 401）', async () => {
      const exp = Math.floor(Date.now() / 1000) - 10; // 已過期
      stubLoginOk(fakeJwt({ sub: 'maker', roles: ['MAKER'], exp }));
      await expect(login('maker', 'x')).rejects.toMatchObject({
        message: expect.stringContaining('無法解析'),
      });
      expect(getAuth()).toBeNull();
      expect(window.sessionStorage.getItem('rules-mcp.jwt')).toBeNull();
    });

    it('登入失敗透傳後端統一文案', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: false, status: 401,
        json: async () => ({ error: 'UNAUTHORIZED', message: '帳號或密碼錯誤' }),
      }));
      await expect(login('maker', 'wrong')).rejects.toMatchObject({ message: '帳號或密碼錯誤' });
      expect(isLoggedIn()).toBe(false);
    });

    it('登出清除狀態並通知訂閱者', async () => {
      const exp = Math.floor(Date.now() / 1000) + 3600;
      stubLoginOk(fakeJwt({ sub: 'admin', roles: ['ADMIN'], exp }));
      const events: boolean[] = [];
      const unsub = subscribeAuth(() => events.push(isLoggedIn()));
      await login('admin', 'x');
      logout();
      unsub();
      expect(events).toEqual([true, false]);
    });
  });

  describe('請求注入', () => {
    it('登入後 /tools/* 請求帶 Authorization: Bearer', async () => {
      const exp = Math.floor(Date.now() / 1000) + 3600;
      stubLoginOk(fakeJwt({ sub: 'maker', roles: ['MAKER'], exp }));
      await login('maker', 'x');

      const spy = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ recommendedRuleType: 'DecisionTable', reason: '', confidence: 1 }),
      });
      vi.stubGlobal('fetch', spy);
      await api.recommend('年齡大於 60 拒保');

      const [, init] = spy.mock.calls[0];
      expect((init.headers as Record<string, string>).Authorization).toMatch(/^Bearer /);
    });

    it('JWT 與 X-API-Key 是獨立通道，可同時附帶（人 + 機器整合並存）', async () => {
      const exp = Math.floor(Date.now() / 1000) + 3600;
      stubLoginOk(fakeJwt({ sub: 'maker', roles: ['MAKER'], exp }));
      await login('maker', 'x');
      setApiKey('machine-key');

      const spy = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ recommendedRuleType: 'DecisionTable', reason: '', confidence: 1 }),
      });
      vi.stubGlobal('fetch', spy);
      await api.recommend('測試');

      const headers = spy.mock.calls[0][1].headers as Record<string, string>;
      expect(headers.Authorization).toMatch(/^Bearer /);
      expect(headers['X-API-Key']).toBe('machine-key');
    });
  });
});
