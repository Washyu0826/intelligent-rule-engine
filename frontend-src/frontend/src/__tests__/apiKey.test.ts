// @vitest-environment jsdom
//
// 需要 jsdom：apiKey.ts 用 sessionStorage 保管金鑰。vite.config.ts 沒有 test 區塊，
// vitest 預設是 node 環境（既有測試都是純函式所以沒踩到），故在此檔案層級指定。

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { authHeaders, getApiKey, setApiKey, clearApiKey, hasApiKey, subscribeApiKey } from '../api/apiKey';
import { api } from '../api/rulesApi';

/**
 * 回歸測試：prod profile 會啟用 `rules.security.api-key-enabled` 攔截所有 `/tools/**`，
 * 而打包進同一個 jar 的這個 SPA 先前完全沒有帶 X-API-Key —— 兩個賣點
 * （單一 jar 服務 UI+API、prod API key 認證）同時開啟時前端必然全數 401。
 */
describe('API Key', () => {
  beforeEach(() => {
    clearApiKey();
  });

  afterEach(() => {
    clearApiKey();
    vi.restoreAllMocks();
  });

  describe('保管', () => {
    it('未設定時 authHeaders 為空 —— 後端未啟用認證時行為完全不變', () => {
      expect(hasApiKey()).toBe(false);
      expect(authHeaders()).toEqual({});
    });

    it('設定後產生 X-API-Key header', () => {
      setApiKey('secret-123');
      expect(getApiKey()).toBe('secret-123');
      expect(authHeaders()).toEqual({ 'X-API-Key': 'secret-123' });
    });

    it('前後空白會被去除；設為空字串等同清除', () => {
      setApiKey('  padded  ');
      expect(getApiKey()).toBe('padded');
      setApiKey('   ');
      expect(hasApiKey()).toBe(false);
    });

    it('金鑰只存在 sessionStorage，不落 localStorage', () => {
      setApiKey('secret-123');
      expect(window.sessionStorage.getItem('rules-mcp.api-key')).toBe('secret-123');
      expect(window.localStorage.getItem('rules-mcp.api-key')).toBeNull();
    });

    it('訂閱者會在變更時收到通知（header 指示燈用）', () => {
      const seen: boolean[] = [];
      const unsubscribe = subscribeApiKey(() => seen.push(hasApiKey()));
      setApiKey('k');
      clearApiKey();
      unsubscribe();
      setApiKey('後續變更不應再通知');
      expect(seen).toEqual([true, false]);
    });
  });

  describe('請求附加', () => {
    function stubFetch() {
      const spy = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ recommendedRuleType: 'DecisionTable', reason: '', confidence: 1 }),
      });
      vi.stubGlobal('fetch', spy);
      return spy;
    }

    it('已設定金鑰時，POST /tools/* 會帶上 X-API-Key', async () => {
      const spy = stubFetch();
      setApiKey('secret-123');

      await api.recommend('年齡大於 60 拒保');

      const [, init] = spy.mock.calls[0];
      expect((init.headers as Record<string, string>)['X-API-Key']).toBe('secret-123');
      expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    });

    it('未設定金鑰時不送出該 header（不送空字串）', async () => {
      const spy = stubFetch();

      await api.recommend('年齡大於 60 拒保');

      const [, init] = spy.mock.calls[0];
      expect(init.headers as Record<string, string>).not.toHaveProperty('X-API-Key');
    });

    it('GET 端點（providers）同樣帶上金鑰', async () => {
      const spy = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
      vi.stubGlobal('fetch', spy);
      setApiKey('secret-123');

      await api.getProviders();

      const [, init] = spy.mock.calls[0];
      expect((init.headers as Record<string, string>)['X-API-Key']).toBe('secret-123');
    });

    it('401 / 403 會給出「去設定金鑰」的可行動提示', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({ ok: false, status: 401, statusText: 'Unauthorized', text: async () => '' }),
      );

      await expect(api.recommend('測試')).rejects.toMatchObject({
        suggestion: expect.stringContaining('鑰匙'),
      });
    });
  });
});
