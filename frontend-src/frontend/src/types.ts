// ========================================
// POST /tools/recommend
// ========================================
export interface RecommendRequest {
  description: string;
}

export interface TypeCandidate {
  ruleType: string;
  reason: string;
  score: number;
}

export interface RecommendResponse {
  recommendedRuleType: string;
  reason: string;
  confidence: number;
  alternatives: TypeCandidate[];
}

// ========================================
// POST /tools/generate
// ========================================
export type GenerateMode = 'fast' | 'normal' | 'deep';

export interface GenerateRequest {
  description: string;
  ruleType: string;
  allowedFields?: string[];
  mode?: GenerateMode;
  provider?: string;
  /** off | warn | block；預設取後端 application.yml */
  preflightMode?: string;
}

export interface LlmProviderInfo {
  name: string;
  displayName: string;
  available: boolean;
  isDefault: boolean;
}

export interface Evaluation {
  completeness: string;
  totalScenarios: number;
  coverageRate: number;
  conflictDetection: string;
  recommendedStrategy: string;
}

export interface AuditMetadata {
  createdBy?: string;
  createdAt?: string;
  modifiedBy?: string;
  modifiedAt?: string;
  operation?: string;
  businessOwner?: string;
  businessDomain?: string;
  effectiveDate?: string;
}

export interface RuleEnvelope {
  ruleType: string;
  reason: string;
  evaluation: Evaluation;
  rule: Record<string, unknown>;
  schemaVersion: string;
  promptVersion: string;
  versionId?: string;
  audit?: AuditMetadata;
  extensions?: unknown;
}

/** v3.8.0: Symbolic grounding check 結果（AWS Automated Reasoning pattern） */
export interface GroundingReport {
  groundingRatio: number;
  suspicionLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  ungroundedFields?: Array<{ name: string; source: 'inputs' | 'outputs' }>;
  ungroundedEnumValues?: Array<{ field: string; value: string }>;
  totalChecks: number;
  groundedChecks: number;
  durationMs: number;
}

/** v3.10.0: Composite confidence score — 聚合所有品質訊號 */
export interface ConfidenceReport {
  overallScore: number;  // 0-100
  tier: 'PRODUCTION_READY' | 'REVIEW_NEEDED' | 'NOT_RECOMMENDED';
  breakdown: Record<string, { raw: number; weight: number; earned: number }>;
  actionableWarnings?: string[];
}

/** v3.6.0: LLM-as-Judge evaluation 結果 */
/** v3.13: /tools/optimize/v2 — DecisionTree sparsity-aware 6-pass pipeline */
export interface TreeQualityMetricsDto {
  leafCount: number;
  branchNodeCount: number;
  maxDepth: number;
  avgPathLength: number;
  /** 0..1，越平衡越高 */
  balanceIndex: number;
  uniqueSubtrees: number;
  /** 0..1，越高表示重複子樹越多 */
  duplicationRatio: number;
}

export interface OptimizeConfigV2 {
  leafWeight?: number;
  depthWeight?: number;
  coverageFloor?: number;
  maxReconstructDepth?: number;
  enablePass4?: boolean;
  enablePass5?: boolean;
  enablePass6?: boolean;
}

export interface OptimizeResponseV2 {
  optimized: RuleEnvelope;
  metricsBefore: TreeQualityMetricsDto;
  metricsAfter: TreeQualityMetricsDto;
  sparsityScoreBefore: number;
  sparsityScoreAfter: number;
  appliedOptimizations: string[];
  passContributions: Record<string, number>;
  durationMs: number;
}

/** v3.12: /tools/narrate — 整體業務敘事，給非工程使用者閱讀 */
export interface BusinessNarrative {
  /** 一段 50-120 字的整體敘事 */
  summary: string;
  /** 2-4 個關鍵要點 */
  highlights: string[];
  /** 涵蓋範圍一句話 */
  coverage: string;
  /** 特殊例外（0-3 條） */
  exceptions: string[];
  /** 給精算師的一句話提醒 */
  actuarialNote: string;
  /** 使用的 provider 名（'fallback' 表示 LLM 不可用） */
  provider?: string;
  durationMs: number;
}

export interface JudgeResult {
  faithfulness: number;
  completeness: number;
  hallucination: number;
  consistency: number;
  overallScore: number;
  comment?: string;
  suggestion?: string;
  judgeProvider?: string;
  generatorProvider?: string;
  crossProvider?: boolean;
  selfConsistencyVariance?: number;
  biasWarnings?: string[];
  durationMs: number;
}

/** v2.2.0: /tools/generate 一次回傳 envelope + validation + analysis；v3.8.0 grounding；v3.10.0 confidence */
export interface GenerateResponse {
  envelope: RuleEnvelope;
  validation: ValidateResponse;
  analysis: AnalyzeResponse;
  /** v3.8.0: 符號幻覺偵測（生成後自動執行，HIGH 時應提醒使用者） */
  groundingCheck?: GroundingReport;
  /** v3.10.0: 聚合 confidence，用一個數字判斷是否可 production */
  confidence?: ConfidenceReport;
  /** 生成前輸入偵測報告（warn 模式附帶；block 模式被擋時 envelope 為 null） */
  preflight?: PreflightReport;
  durationMs: number;
}

// ========================================
// POST /tools/validate
// ========================================
export interface ValidateRequest {
  ruleJson: Record<string, unknown>;
  ruleType: string;
}

export interface ValidationError {
  code: string;
  message: string;
  /** v3.7.0: 結構化反例（IEEE 2024 witness pattern），觸發此錯誤的具體輸入範例 */
  witness?: Record<string, string>;
  /** v3.15: 嚴重度。"WARNING"（如 REDUNDANT_RULE）為提示性、不影響 valid；其餘視為致命 */
  severity?: string;
}

export interface ValidateResponse {
  valid: boolean;
  errors: ValidationError[];
}

// ========================================
// POST /tools/analyze
// ========================================
export interface AnalyzeRequest {
  ruleJson: Record<string, unknown>;
  ruleType: string;
}

export interface AnalyzeResponse {
  coverageRate: number;
  gaps: GapInfo[];
  overlaps: OverlapInfo[];
  simplifications: SimplificationHint[];
}

export interface GapInfo {
  conditions: Record<string, string>;
  message: string;
  /** 此缺口佔整體值域空間的體積比例（0–1），用於嚴重度排序與標色 */
  volumeRatio?: number;
}

export interface OverlapInfo {
  ruleIds: string[];
  intersection: Record<string, string>;
  message: string;
}

export interface SimplificationHint {
  ruleIds: string[];
  suggestion: string;
}

// ========================================
// POST /tools/suggest（輸入建議）
// ========================================
export interface SuggestRequest {
  description: string;
}

export interface DetectedDimension {
  chineseName: string;
  englishName: string;
  typeRef: string;
  values: string[];
}

export interface MissingDimension {
  name: string;
  englishName: string;
  reason: string;
}

export interface SuggestResponse {
  detectedInputs: DetectedDimension[];
  detectedOutputs: DetectedDimension[];
  missingDimensions: MissingDimension[];
  qualityScore: number;
  suggestions: string[];
  estimatedRuleCount: number;
  detectedDomain: string;
}

// ========================================
// POST /tools/preflight（生成前輸入偵測）
// ========================================
export interface LintFinding {
  code: string;
  severity: 'ERROR' | 'WARNING' | 'INFO';
  message: string;
  dimension?: string | null;
}

export interface PreflightReport {
  blocked: boolean;
  hasError: boolean;
  qualityScore: number;
  findings: LintFinding[];
  missing: MissingDimension[];
  suggestions: string[];
  estimatedRuleCount: number;
}

// ========================================
// POST /tools/diff-tree（DecisionTree 語意結構 diff）
// ========================================
export interface TreeDiffOp {
  type: 'MATCH' | 'UPDATE' | 'INSERT' | 'DELETE';
  nodeIdBefore?: string | null;
  nodeIdAfter?: string | null;
  detail: string;
}

export interface TreeDiffResult {
  comparable?: boolean;
  editDistance: number;
  nodesAdded: number;
  nodesRemoved: number;
  nodesModified: number;
  nodesUnchanged: number;
  operations: TreeDiffOp[];
  summary: string;
}

// ========================================
// POST /tools/diff-table（DecisionTable 行為比對 / 超矩形集合差）
// ========================================
export interface RegionDiff {
  type: 'LOST_COVERAGE' | 'NEW_COVERAGE' | 'CHANGED_DECISION';
  point: Record<string, string>;
  oldDecision?: string | null;
  newDecision?: string | null;
  message: string;
}

export interface TableDiffResult {
  comparable?: boolean;
  lostCount: number;
  newCount: number;
  changedCount: number;
  sampledPoints: number;
  approximate: boolean;
  regions: RegionDiff[];
  summary: string;
  /** 條件組合超過展開上限的規則 — 非空時 approximate=true，截斷區域的比對可能漏報或誤報 */
  truncatedRuleIds?: string[];
}

// ========================================
// POST /tools/test-run（批次測試）
// ========================================
export interface TestCase {
  id: string;
  description: string;
  expectedRuleType?: string;
  expectValid?: boolean;
}

export interface TestCaseResult {
  id: string;
  passed: boolean;
  recommendedType?: string;
  typeMatchExpected?: boolean;
  jsonParseable?: boolean;
  schemaValid?: boolean;
  errors?: Array<{ code: string; message: string }>;
  durationMs?: number;
}

export interface TestRunResponse {
  total: number;
  passed: number;
  failed: number;
  passRate: number;
  errorBreakdown?: Record<string, number>;
  results?: TestCaseResult[];
}

// POST /tools/diff-rules（DecisionTable 結構 diff，規則列對齊）
export interface RuleChange {
  type: 'ADDED' | 'REMOVED' | 'MODIFIED';
  ruleId: string;
  conditions: Record<string, string>;
  oldDecision?: string | null;
  newDecision?: string | null;
  detail: string;
}

export interface RuleSetDiffResult {
  comparable?: boolean;
  added: number;
  removed: number;
  modified: number;
  unchanged: number;
  changes: RuleChange[];
  summary: string;
}

// POST /tools/tree-paths（決策樹路徑展開）
export interface TreePathStep {
  field?: string | null;
  operator?: string | null;
  value?: unknown;
  negated: boolean;
  label?: string | null;
}

export interface DecisionPath {
  leafNodeId?: string | null;
  steps: TreePathStep[];
  outcome: Record<string, string>;
  narrative: string;
}

export interface TreePathsResult {
  totalPaths: number;
  paths: DecisionPath[];
  summary: string;
}

// ========================================
// POST /tools/lookup（規則查詢）
// ========================================
export interface LookupRequest {
  envelope: RuleEnvelope;
  inputValues: Record<string, unknown>;
}

export interface MatchedRule {
  ruleId: string;
  priority: number;
  results: Record<string, unknown>;
}

export interface LookupResponse {
  matched: boolean;
  matchedRules: MatchedRule[];
  evaluationPath: string[];
  unmatchedInputs: string[];
}

// ========================================
// POST /tools/export/group-json
// POST /tools/export/group-xlsx
// POST /tools/execute
// ========================================
export interface GroupTreePosition {
  x: number;
  y: number;
}

export interface GroupTreeOutput {
  field: string;
  value: unknown;
}

export interface GroupTreeNode {
  nodeId: string;
  type: 'BRANCH' | 'LEAF' | string;
  labelChinese?: string;
  expressionType?: string;
  fieldName?: string;
  position?: GroupTreePosition;
  outputs?: GroupTreeOutput[];
  results?: GroupTreeOutput[];
}

export interface GroupTreeEdge {
  from: string;
  to: string;
  label?: string;
  operator?: string;
  value?: unknown;
}

export interface GroupTree {
  treeId?: string;
  name?: string;
  version?: string;
  nodes: GroupTreeNode[];
  edges: GroupTreeEdge[];
}

export interface GroupJsonExportResponse {
  tree: GroupTree;
  warnings: string[];
}

export interface ExecutionResult {
  matched: boolean;
  hitRuleId?: string;
  hitNodeId?: string;
  hitPath?: string[];
  results?: GroupTreeOutput[] | Record<string, unknown>;
  evaluationPath?: string[];
  allMatches?: ExecutionResult[] | null;
  engineName?: string;
}

export interface GroupXlsxExportResponse {
  blob: Blob;
  filename: string;
  warnings: string[];
}

// ========================================
// POST /tools/scenario-expand
// ========================================
export interface ScenarioExpandRequest {
  envelope: RuleEnvelope;
  maxScenarios?: number;
  noMatchLabel?: string;
}

export interface ScenarioDomain {
  field: string;
  typeRef: string;
  values: unknown[];
  finite: boolean;
  source: string;
}

export interface ScenarioRow {
  scenarioId: string;
  inputValues: Record<string, unknown>;
  matched: boolean;
  matchedRuleIds: string[];
  results: Record<string, unknown>;
  outcomeLabel: string;
  evaluationPath: string[];
}

export interface ScenarioExpandResponse {
  totalPossible: number;
  returned: number;
  truncated: boolean;
  noMatchLabel: string;
  domains: ScenarioDomain[];
  scenarios: ScenarioRow[];
  warnings: string[];
}

// ========================================
// UI state types
// ========================================
export type PageView = 'input' | 'dashboard';
export type InputMode = 'natural' | 'json';
export type ThemeMode = 'dark' | 'light' | 'system';

export interface GenerationResult {
  recommend?: RecommendResponse;
  generate: RuleEnvelope;
  validate?: ValidateResponse;
  analyze?: AnalyzeResponse;
  /** v2.2.0: 生成耗時（毫秒） */
  durationMs?: number;
  /** v3.0.0: 使用者原始輸入描述 */
  originalDescription?: string;
  /** v3.8.0: 符號幻覺偵測結果（auto） */
  grounding?: GroundingReport;
  /** v3.10.0: 聚合 confidence（auto） */
  confidence?: ConfidenceReport;
}

export interface GenerationStep {
  id: string;
  label: string;
  status: 'pending' | 'active' | 'done' | 'error';
}

export interface ApiError {
  message: string;
  suggestion?: string;
  retryable: boolean;
}

// ============================================================
// Dashboard Internal Types (re-exported from Dashboard/types.ts)
// ============================================================
export type { TypeRef, Operator, HitPolicy } from './components/Dashboard/types';
export type { RuleInput, RuleOutput, RuleCondition, RuleResult, RuleEntry, DecisionTableRule, OperatorDisplayInfo } from './components/Dashboard/types';
export type { Branch, TreeNode, DecisionTreeRule } from './components/Dashboard/types';
export type { ErrorSeverity } from './components/Dashboard/types';
export { parseDecisionTable, parseDecisionTree, normalizeTreeNode, getOperatorDisplay, getBooleanDisplay, formatConditionValue } from './components/Dashboard/types';
export { isLeafNode, isBranchNode, countNodes, countLeaves, getTreeDepth } from './components/Dashboard/types';
export { ERROR_FIX_HINTS, getErrorSeverity, SEVERITY_ORDER, generateExampleInput } from './components/Dashboard/types';

// ========================================
// v3.14 Phase A — 領域字彙表
// ========================================
export interface GlossaryEntry {
  id: string;
  zh_TW: string;
  zh_CN?: string;
  en: string;
  synonyms?: string[];
  definition: string;
  examples?: string[];
  source: string;
  category: '核保' | '商品' | '理賠' | '通路' | '繳費' | '合規' | '精算' | string;
  regulatoryLayer?: 'law' | 'guild' | 'fsc' | 'company' | string;
  fieldCode?: string;
  dataType?: 'Money' | 'Enum' | 'Date' | 'Boolean' | 'String' | 'Integer' | 'Decimal' | string;
  unit?: string;
  valueRange?: string;
  status: 'active' | 'deprecated' | 'proposed';
  effectiveFrom?: string;
  deprecatedAt?: string;
  successor?: string;
  relatedTerms?: string[];
  businessOwner?: string;
  techOwner?: string;
  version?: string;
}

export interface GlossaryStats {
  total: number;
  active: number;
  deprecated: number;
  proposed: number;
  byCategory: Record<string, number>;
}
