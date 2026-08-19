import { authHeaders } from './apiKey';
import { bearerHeader } from './auth';
import type {
  RecommendRequest,
  RecommendResponse,
  GenerateRequest,
  GenerateResponse,
  ValidateRequest,
  ValidateResponse,
  AnalyzeRequest,
  AnalyzeResponse,
  SuggestResponse,
  LookupRequest,
  LookupResponse,
  GroupJsonExportResponse,
  GroupXlsxExportResponse,
  ExecutionResult,
  RuleEnvelope,
  ApiError,
} from '../types';

/** 各類端點的 timeout（毫秒）：LLM 重型呼叫給長上限，純計算/查詢給短上限 */
// generate / evaluate / narrate / explain — 後端非同步視窗 = rules.llm.timeout-seconds + 60s 緩衝
// （local profile 360+60=420s 為最長）；前端略大於它，讓後端先 504、客戶端顯示伺服器的訊息而非單方面放棄
const TIMEOUT_LLM = 430_000;
const TIMEOUT_DEFAULT = 60_000; // validate / analyze / diff 等純計算端點
const TIMEOUT_QUERY = 10_000; // glossary / providers 等輕量 GET

class ApiClient {
  private async request<T>(
    endpoint: string,
    body: unknown,
    timeoutMs: number = TIMEOUT_DEFAULT,
  ): Promise<T> {
    let res: Response;
    try {
      res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders(), ...bearerHeader() },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (err) {
      const isTimeout = err instanceof DOMException && err.name === 'TimeoutError';
      throw {
        message: isTimeout
          ? `請求逾時（${Math.round(timeoutMs / 1000)} 秒未回應）`
          : `無法連線到後端服務: ${String(err)}`,
        suggestion: isTimeout ? '後端處理時間過長，請稍後重試' : '請確認後端服務是否正常運行',
        retryable: true,
      } as ApiError;
    }

    if (!res.ok) {
      let detail = '';
      try {
        const text = await res.text();
        try {
          const err = JSON.parse(text);
          detail = err.message || err.error || text;
        } catch {
          detail = text;
        }
      } catch {
        detail = res.statusText;
      }

      const apiError: ApiError = {
        message: `${res.status} ${res.statusText}: ${detail}`,
        suggestion: this.getSuggestion(res.status),
        retryable: res.status >= 500 || res.status === 429,
      };
      throw apiError;
    }

    return res.json() as Promise<T>;
  }

  private getSuggestion(status: number): string {
    switch (status) {
      case 400:
        return '請檢查輸入格式是否正確';
      case 401:
        return '需要登入 —— 請點右上角「登入」；若是機器整合請改用 X-API-Key（鑰匙圖示）';
      case 403:
        return '權限不足或憑證不正確 —— 請確認登入帳號的角色，或重新輸入 API Key';
      case 429:
        return 'AI 服務目前繁忙，請稍候 30 秒重試，或切換到「直接貼上規則資料」模式';
      case 500:
        return '規則生成發生問題，請重試或更換 AI 模型';
      case 503:
        return '服務暫時不可用，請確認系統是否已啟動';
      default:
        return '發生未預期的錯誤，請重試';
    }
  }

  async recommend(description: string): Promise<RecommendResponse> {
    const body: RecommendRequest = { description };
    return this.request<RecommendResponse>('/tools/recommend', body);
  }

  async generate(request: GenerateRequest): Promise<GenerateResponse> {
    return this.request<GenerateResponse>('/tools/generate', request, TIMEOUT_LLM);
  }

  async validate(ruleJson: Record<string, unknown>, ruleType: string): Promise<ValidateResponse> {
    const body: ValidateRequest = { ruleJson, ruleType };
    return this.request<ValidateResponse>('/tools/validate', body);
  }

  async analyze(ruleJson: Record<string, unknown>, ruleType: string): Promise<AnalyzeResponse> {
    const body: AnalyzeRequest = { ruleJson, ruleType };
    return this.request<AnalyzeResponse>('/tools/analyze', body);
  }

  /**
   * v2.2.0: SSE Streaming 生成 — 即時接收進度事件。
   * 比 generate() 多了即時 step 回饋，最終結果相同。
   */
  async generateStream(
    request: GenerateRequest,
    onStep: (step: string, status: string) => void,
    onToken?: (token: string) => void,
  ): Promise<GenerateResponse> {
    return new Promise((resolve, reject) => {
      const processEventBlock = (
        block: string,
      ): { result?: GenerateResponse; error?: ApiError } => {
        const lines = block
          .split('\n')
          .map((line) => line.trimEnd())
          .filter((line) => line.length > 0);

        let eventName = 'message';
        const dataLines: string[] = [];

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trim());
          }
        }

        if (dataLines.length === 0) {
          return {};
        }

        const dataStr = dataLines.join('\n');

        try {
          const data = JSON.parse(dataStr);
          if (eventName === 'step' || (!data.envelope && data.step)) {
            onStep(data.step, data.status);
            return {};
          }

          if (eventName === 'token' && onToken) {
            onToken(data.token || dataStr);
            return {};
          }

          if (eventName === 'error') {
            return {
              error: {
                message: data.message || dataStr,
                retryable: true,
              },
            };
          }

          if (eventName === 'result' || data.envelope) {
            return { result: data as GenerateResponse };
          }
        } catch {
          if (eventName === 'token' && onToken) {
            onToken(dataStr);
          }
        }

        return {};
      };

      // 使用 fetch + ReadableStream 處理 SSE（因為 EventSource 不支援 POST）
      fetch('/tools/generate/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders(), ...bearerHeader() },
        body: JSON.stringify(request),
        // 與後端 SseEmitter(600s) 對齊；逾時會中斷整條串流並落入 catch
        signal: AbortSignal.timeout(600_000),
      }).then(async (res) => {
        if (!res.ok) {
          const text = await res.text();
          reject({ message: `${res.status}: ${text}`, retryable: res.status >= 500 } as ApiError);
          return;
        }

        const reader = res.body?.getReader();
        if (!reader) { reject({ message: 'No response body', retryable: false }); return; }

        const decoder = new TextDecoder();
        let buffer = '';
        let finalResult: GenerateResponse | null = null;
        let streamError: ApiError | null = null;

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');

          let boundary = buffer.indexOf('\n\n');
          while (boundary !== -1) {
            const block = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);

            const { result, error } = processEventBlock(block);
            if (result) {
              finalResult = result;
            }
            if (error) {
              streamError = error;
              break;
            }

            boundary = buffer.indexOf('\n\n');
          }

          if (streamError) {
            break;
          }
        }

        if (!streamError && buffer.trim().length > 0) {
          const { result, error } = processEventBlock(buffer);
          if (result) {
            finalResult = result;
          }
          if (error) {
            streamError = error;
          }
        }

        if (streamError) {
          reject(streamError);
        } else if (finalResult) {
          resolve(finalResult);
        } else {
          reject({ message: 'Stream ended without result', retryable: true } as ApiError);
        }
      }).catch((err) => {
        reject({ message: String(err), retryable: true } as ApiError);
      });
    });
  }

  async getProviders(): Promise<import('../types').LlmProviderInfo[]> {
    try {
      const res = await fetch('/tools/providers', {
        headers: { ...authHeaders(), ...bearerHeader() },
        signal: AbortSignal.timeout(TIMEOUT_QUERY),
      });
      if (!res.ok) return [];
      return res.json();
    } catch {
      return [];
    }
  }

  /** v3.14 Phase A: 領域字彙表查詢 */
  async getGlossary(category?: string, q?: string): Promise<import('../types').GlossaryEntry[]> {
    const params = new URLSearchParams();
    if (category) params.set('category', category);
    if (q) params.set('q', q);
    const url = '/tools/glossary' + (params.toString() ? `?${params}` : '');
    try {
      const res = await fetch(url, { headers: { ...authHeaders(), ...bearerHeader() }, signal: AbortSignal.timeout(TIMEOUT_QUERY) });
      if (!res.ok) return [];
      return res.json();
    } catch {
      return [];
    }
  }

  async getGlossaryEntry(id: string): Promise<import('../types').GlossaryEntry | null> {
    try {
      const res = await fetch(`/tools/glossary/${encodeURIComponent(id)}`, {
        headers: { ...authHeaders(), ...bearerHeader() },
        signal: AbortSignal.timeout(TIMEOUT_QUERY),
      });
      if (!res.ok) return null;
      return res.json();
    } catch {
      return null;
    }
  }

  async getGlossaryStats(): Promise<import('../types').GlossaryStats | null> {
    try {
      const res = await fetch('/tools/glossary/stats', {
        headers: { ...authHeaders(), ...bearerHeader() },
        signal: AbortSignal.timeout(TIMEOUT_QUERY),
      });
      if (!res.ok) return null;
      return res.json();
    } catch {
      return null;
    }
  }

  async suggest(description: string): Promise<SuggestResponse> {
    return this.request<SuggestResponse>('/tools/suggest', { description });
  }

  /** 生成前輸入偵測（純符號、0 token）— 回 findings 與完整度供前端紅黃綠燈 */
  async preflight(description: string): Promise<import('../types').PreflightReport> {
    return this.request<import('../types').PreflightReport>('/tools/preflight', { description });
  }

  /** 方向③: DecisionTree 語意結構 diff（樹編輯距離，不受 nodeId 改名/分支重排干擾） */
  async diffTree(before: RuleEnvelope, after: RuleEnvelope): Promise<import('../types').TreeDiffResult> {
    return this.request<import('../types').TreeDiffResult>('/tools/diff-tree', { before, after });
  }

  /** 方向③ L2: DecisionTable 行為比對（超矩形集合差，回歸/決策改變/新增覆蓋） */
  async diffTable(before: RuleEnvelope, after: RuleEnvelope): Promise<import('../types').TableDiffResult> {
    return this.request<import('../types').TableDiffResult>('/tools/diff-table', { before, after });
  }

  /** 方向③: DecisionTable 結構比對（規則列對齊，新增/刪除/決策改變，不靠 ruleId） */
  async diffRules(before: RuleEnvelope, after: RuleEnvelope): Promise<import('../types').RuleSetDiffResult> {
    return this.request<import('../types').RuleSetDiffResult>('/tools/diff-rules', { before, after });
  }

  /** 方向②: 決策樹路徑展開（每條 root→leaf 一句白話規則） */
  async treePaths(envelope: RuleEnvelope): Promise<import('../types').TreePathsResult> {
    return this.request<import('../types').TreePathsResult>('/tools/tree-paths', { envelope });
  }

  /** v3.6.0: LLM-as-Judge 跨 provider 評估 */
  async evaluate(
    description: string,
    envelope: RuleEnvelope,
    generatorProvider?: string,
    selfConsistency: boolean = false,
  ): Promise<import('../types').JudgeResult> {
    return this.request<import('../types').JudgeResult>('/tools/evaluate', {
      description, envelope, generatorProvider, selfConsistency,
    }, TIMEOUT_LLM);
  }

  /** v3.13: DecisionTree sparsity-aware 優化（6-pass pipeline） */
  async optimizeV2(
    ruleJson: Record<string, unknown>,
    config?: import('../types').OptimizeConfigV2,
  ): Promise<import('../types').OptimizeResponseV2> {
    return this.request<import('../types').OptimizeResponseV2>('/tools/optimize/v2', {
      ruleJson, config,
    });
  }

  /** v3.12: 為整張規則表產出業務敘事（非工程使用者導向） */
  async narrate(
    description: string,
    envelope: RuleEnvelope,
    provider?: string,
  ): Promise<import('../types').BusinessNarrative> {
    return this.request<import('../types').BusinessNarrative>('/tools/narrate', {
      description, envelope, provider,
    }, TIMEOUT_LLM);
  }

  /** v3.9.0: 為每條規則產生 rationale（回傳含 rationale 的 envelope） */
  async explain(
    description: string,
    envelope: RuleEnvelope,
    provider?: string,
  ): Promise<{ envelope: RuleEnvelope; rationalesApplied: number; provider?: string; durationMs: number }> {
    return this.request('/tools/explain', { description, envelope, provider }, TIMEOUT_LLM);
  }

  /**
   * 批次測試 — 每個 case 內部走 generate（可能打 LLM），後端 timeout 隨 case 數縮放（上限 30 分鐘）。
   * 前端視窗取後端公式再加緩衝，讓後端先回 504/結果，而不是客戶端先斷線留下殭屍工作。
   */
  async testRun(testCases: import('../types').TestCase[]): Promise<import('../types').TestRunResponse> {
    // 鏡像後端 LlmTimeoutPolicy：(LLM timeout 最長 360s + 60s 緩衝) × case 數，封頂 1800s；外加 10s 讓後端先回
    const timeoutMs = Math.min(testCases.length * 430 + 10, 1810) * 1000;
    return this.request<import('../types').TestRunResponse>('/tools/test-run', { testCases }, timeoutMs);
  }

  async lookup(envelope: RuleEnvelope, inputValues: Record<string, unknown>): Promise<LookupResponse> {
    const body: LookupRequest = { envelope, inputValues };
    return this.request<LookupResponse>('/tools/lookup', body);
  }

  async exportGroupJson(envelope: RuleEnvelope): Promise<GroupJsonExportResponse> {
    return this.request<GroupJsonExportResponse>('/tools/export/group-json', { envelope });
  }

  async execute(envelope: RuleEnvelope, inputValues: Record<string, unknown>): Promise<ExecutionResult> {
    return this.request<ExecutionResult>('/tools/execute', { envelope, inputValues });
  }

  async exportGroupXlsx(envelope: RuleEnvelope): Promise<GroupXlsxExportResponse> {
    const res = await fetch('/tools/export/group-xlsx', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders(), ...bearerHeader() },
      body: JSON.stringify({ envelope }),
      signal: AbortSignal.timeout(TIMEOUT_DEFAULT),
    });

    if (!res.ok) {
      let detail = '';
      try {
        detail = await res.text();
      } catch {
        detail = res.statusText;
      }
      throw {
        message: `${res.status} ${res.statusText}: ${detail || res.statusText}`,
        suggestion: this.getSuggestion(res.status),
        retryable: res.status >= 500 || res.status === 429,
      } as ApiError;
    }

    const disposition = res.headers.get('content-disposition') ?? '';
    const warningsHeader = res.headers.get('x-group-export-warnings') ?? '';
    const filenameMatch = disposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i);
    const rawFilename = filenameMatch?.[1] ?? `field-spec-${Date.now()}.xlsx`;
    const filename = decodeURIComponent(rawFilename.replace(/^"|"$/g, ''));
    let warnings: string[] = [];
    if (warningsHeader) {
      try {
        const parsed = JSON.parse(decodeURIComponent(warningsHeader));
        warnings = Array.isArray(parsed) ? parsed.map(String) : [String(parsed)];
      } catch {
        warnings = warningsHeader.split(/[|,]/).map((warning) => warning.trim()).filter(Boolean);
      }
    }
    return { blob: await res.blob(), filename, warnings };
  }

  async expandScenarios(
    envelope: RuleEnvelope,
    maxScenarios: number = 256,
    noMatchLabel: string = 'PASS',
  ): Promise<import('../types').ScenarioExpandResponse> {
    return this.request<import('../types').ScenarioExpandResponse>('/tools/scenario-expand', {
      envelope,
      maxScenarios,
      noMatchLabel,
    });
  }

  async healthCheck(): Promise<boolean> {
    try {
      const res = await fetch('/health', { signal: AbortSignal.timeout(3000) });
      if (!res.ok) return false;
      const data = await res.json();
      return data.status === 'UP';
    } catch {
      return false;
    }
  }
}

export const api = new ApiClient();
