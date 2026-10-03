import { bearerHeader } from './auth';
import type { RuleEnvelope } from '../types';

export interface VersionView {
  id: number;
  ruleKey: string;
  versionNo: number;
  ruleType: string;
  status: 'DRAFT' | 'REVIEW' | 'APPROVED' | 'ACTIVE' | 'REJECTED' | 'RETIRED';
  previousVersionId: number | null;
  createdBy: string;
  submittedBy: string | null;
  reviewedBy: string | null;
  reviewComment: string | null;
  submitReason: string | null;
  updatedAt: string;
}

export interface TreeEntry {
  ruleKey: string;
  path: string;
  tags: Record<string, string[]>;
  activeVersionNo: number | null;
  latestStatus: string;
  latestVersionNo: number;
}

export interface TreeView {
  tagDimensions: string[];
  rules: TreeEntry[];
}

export interface ImpactGap {
  message: string;
  conditions?: Record<string, string>;
  volumeRatio?: number;
}

export interface ImpactAnalysis {
  coverageRate: number;
  gapCount: number;
  overlapCount: number;
  gaps: ImpactGap[];
  overlaps: string[];
}

export interface BoundsViolation {
  constraintId: string;
  description: string;
  ruleId: string;
  detail: string;
}

export interface BoundsReport {
  checked: boolean;
  constraintCount: number;
  violations: BoundsViolation[];
  escalateTo?: string;
}

export interface RegressionOutcome {
  matched: boolean;
  outputs: Record<string, unknown>;
  hitRuleId?: string;
}

export interface RegressionReport {
  sampleCount: number;
  changedCount: number;
  changeRate: number;
  newlyMatched: number;
  newlyUnmatched: number;
  outputChanged: number;
  examples: { input: Record<string, unknown>; before: RegressionOutcome; after: RegressionOutcome }[];
}

export interface ChainStep {
  ruleKey: string;
  stopOnHit: boolean;
  label?: string;
}

export interface ChainView {
  chainKey: string;
  name: string;
  steps: ChainStep[];
  updatedBy: string;
  updatedAt: string;
}

export interface ChainOutcome {
  chainKey: string;
  steps: { ruleKey: string; label?: string; versionNo: number | null; matched: boolean; outputs: Record<string, unknown>; hitRuleIds: string[]; stopped: boolean; note?: string }[];
  finalOutputs: Record<string, unknown>;
  stopped: boolean;
  stoppedAt?: string;
  nanos: number;
}

export interface ImpactReport {
  firstVersion: boolean;
  note?: string;
  bounds?: BoundsReport;
  regression?: RegressionReport;
  /** 多重命中的檢核清單：不做缺口／重疊分析 */
  checklist?: boolean;
  analysis?: ImpactAnalysis;
  [key: string]: unknown;
}

export interface ReviewSheetData {
  id: number;
  ruleKey: string;
  versionNo: number;
  ruleType: string;
  status: string;
  submittedBy: string | null;
  submittedAt: string | null;
  submitReason: string | null;
  baseVersionNo: number | null;
  before: RuleEnvelope | null;
  after: RuleEnvelope;
  impact: ImpactReport;
}

export interface ChangeSuggestion {
  proposed: RuleEnvelope;
  validation: { valid?: boolean; errors?: { message?: string }[] } | null;
  impact: ImpactReport;
}

export interface EngineOutcome {
  traceId: number;
  ruleVersionId: number;
  versionNo: number;
  result: {
    matched: boolean;
    outputs?: Record<string, unknown>;
    matchedRules?: { ruleId: string; priority?: number; outputs?: Record<string, unknown> }[];
  };
}

export interface DmnCrossCheck {
  consistent: boolean;
  dmnMatched: boolean;
  dmnResults: Record<string, unknown>[];
  differences: string[];
  warnings: string[];
  dmnNanos: number;
}

export class WorkbenchError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

async function call<T>(method: string, url: string, body?: unknown, timeoutMs = 30_000): Promise<T> {
  let res: Response;
  try {
    res = await fetch(url, {
      method,
      headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...bearerHeader() },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (err) {
    const timeout = err instanceof DOMException && err.name === 'TimeoutError';
    throw new WorkbenchError(timeout ? '請求逾時' : '無法連線到後端服務', 0);
  }
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const j = await res.json();
      detail = j.message || j.error || detail;
    } catch {
      /* keep statusText */
    }
    const hint = res.status === 401 ? '（請先登入）' : res.status === 403 ? '（目前角色沒有這個操作的權限）' : '';
    throw new WorkbenchError(`${detail}${hint}`, res.status);
  }
  return res.json() as Promise<T>;
}

const enc = encodeURIComponent;

export const workbenchApi = {
  tree(filter: Record<string, string> = {}): Promise<TreeView> {
    const qs = new URLSearchParams(filter).toString();
    return call('GET', `/rules/tree${qs ? `?${qs}` : ''}`);
  },
  place(ruleKey: string, path: string, tags: Record<string, string[]>): Promise<TreeEntry> {
    return call('PUT', `/rules/${enc(ruleKey)}/placement`, { path, tags });
  },
  history(ruleKey: string): Promise<VersionView[]> {
    return call('GET', `/rules/${enc(ruleKey)}/history`);
  },
  reviewQueue(): Promise<VersionView[]> {
    return call('GET', '/rules/review-queue');
  },
  reviewSheet(id: number): Promise<ReviewSheetData> {
    return call('GET', `/rules/${id}/review-sheet`);
  },
  createDraft(ruleKey: string, envelope: RuleEnvelope): Promise<VersionView> {
    return call('POST', '/rules', { ruleKey, envelope });
  },
  suggestChange(id: number, instruction: string): Promise<ChangeSuggestion> {
    return call('POST', `/rules/${id}/suggest-change`, { instruction }, 430_000);
  },
  gapCase(id: number, conditions: Record<string, string>): Promise<ChangeSuggestion> {
    return call('POST', `/rules/${id}/gap-case`, { conditions });
  },
  executeActive(ruleKey: string, input: Record<string, unknown>): Promise<EngineOutcome> {
    return call('POST', '/engine/execute', { ruleKey, input, traceLevel: 'SUMMARY' });
  },
  dmnCheck(envelope: RuleEnvelope, inputValues: Record<string, unknown>): Promise<DmnCrossCheck> {
    return call('POST', '/tools/dmn/check', { envelope, inputValues });
  },
  async dmnExportXml(envelope: RuleEnvelope, name: string): Promise<string> {
    const res = await fetch('/tools/dmn/export.xml', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...bearerHeader() },
      body: JSON.stringify({ envelope, name }),
    });
    if (!res.ok) throw new WorkbenchError(`DMN 匯出失敗（${res.status}）`, res.status);
    return res.text();
  },
  submit(id: number, reason: string): Promise<VersionView> {
    return call('POST', `/rules/${id}/submit`, { reason });
  },
  withdraw(id: number): Promise<VersionView> {
    return call('POST', `/rules/${id}/withdraw`, {});
  },
  revise(id: number): Promise<VersionView> {
    return call('POST', `/rules/${id}/revise`, {});
  },
  approve(id: number, comment: string): Promise<VersionView> {
    return call('POST', `/rules/${id}/approve`, { comment });
  },
  reject(id: number, comment: string): Promise<VersionView> {
    return call('POST', `/rules/${id}/reject`, { comment });
  },
  activate(id: number): Promise<VersionView> {
    return call('POST', `/rules/${id}/activate`, {});
  },
  chains(): Promise<ChainView[]> {
    return call('GET', '/rules/chains');
  },
  chain(key: string): Promise<ChainView> {
    return call('GET', `/rules/chains/${enc(key)}`);
  },
  saveChain(key: string, name: string, steps: ChainStep[]): Promise<ChainView> {
    return call('PUT', `/rules/chains/${enc(key)}`, { name, steps });
  },
  executeChain(key: string, inputValues: Record<string, unknown>): Promise<ChainOutcome> {
    return call('POST', `/engine/chain/${enc(key)}/execute`, { input: inputValues });
  },
};
