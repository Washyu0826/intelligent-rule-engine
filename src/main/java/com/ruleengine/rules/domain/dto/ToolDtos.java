package com.ruleengine.rules.domain.dto;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;
import java.util.Map;

public final class ToolDtos {
    private ToolDtos() {}

    // ========================================
    // POST /tools/recommend
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecommendRequest {
        @NotBlank(message = "description 不可為空")
        @Size(max = 10000, message = "description 長度不可超過 10000 字元")
        private String description;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecommendResponse {
        private String recommendedRuleType;
        private String reason;
        private double confidence;
        private List<TypeCandidate> alternatives;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TypeCandidate {
        private String ruleType;
        private String reason;
        private double score;
    }

    // ========================================
    // POST /tools/generate
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GenerateRequest {
        @NotBlank(message = "description 不可為空")
        @Size(max = 50000, message = "description 長度不可超過 50000 字元")
        private String description;
        private String ruleType;
        private List<String> allowedFields;
        /** 生成模式：fast（快速）/ normal（一般）/ deep（深度），預設 normal */
        private String mode;
        /** Guided Mode：指定根節點分割欄位（DecisionTree 專用） */
        private String rootField;
        /** Guided Mode：指定欄位優先順序（DecisionTree 專用） */
        private List<String> fieldOrder;
        /** 是否在生成後自動執行 TreeOptimizer */
        private Boolean optimize;
        /** LLM Provider 選擇：claude / ollama / gemini（預設使用系統設定） */
        private String provider;
        /** Pre-flight 模式覆寫：off | warn | block（預設取 application.yml 的 rules.preflight.mode） */
        private String preflightMode;
    }

    // ========================================
    // POST /tools/generate — 完整回應（含 validate + analyze）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GenerateResponse {
        /** 生成的 RuleEnvelope */
        private com.ruleengine.rules.domain.envelope.RuleEnvelope envelope;
        /** 自動驗證結果 */
        private ValidateResponse validation;
        /** DMN 分析結果 */
        private AnalyzeResponse analysis;
        /**
         * v3.8.0：Symbolic grounding 檢查結果（AWS Automated Reasoning 2024 inspired）—
         * 檢查生成的欄位／ENUM 值是否全部能 ground 回原始描述，不依賴 LLM。
         */
        private com.ruleengine.rules.service.evaluator.GroundingGuardService.GroundingReport groundingCheck;
        /**
         * v3.10.0：Composite confidence — 聚合 validation / grounding / coverage / conflict /
         * rule count 五因子成 0-100 信心分數 + tier（PRODUCTION_READY / REVIEW_NEEDED / NOT_RECOMMENDED）。
         */
        private com.ruleengine.rules.service.evaluator.ConfidenceScorer.ConfidenceReport confidence;
        /**
         * 生成前輸入偵測報告（pre-flight gate）。warn 模式下永遠附帶；
         * block 模式被擋下時，envelope/validation/analysis 皆為 null、blocked=true。
         */
        private com.ruleengine.rules.service.PreflightService.PreflightReport preflight;
        /** 生成耗時（毫秒） */
        private long durationMs;
    }

    // ========================================
    // POST /tools/validate
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ValidateRequest {
        @jakarta.validation.constraints.NotNull(message = "ruleJson 不可為空")
        private JsonNode ruleJson;
        private String ruleType;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidateResponse {
        private boolean valid;
        private List<ValidationError> errors;
    }

    /**
     * 驗證錯誤（含可選 structured witness，v3.7.0）。
     *
     * witness 欄位靈感來自 IEEE 2024 論文
     * "Completeness and Consistency of Tabular Requirements: An SMT-Based Verification Approach"
     * — 形式化驗證應回傳結構化反例（concrete counter-example）而非純文字訊息。
     *
     * 目前僅 INCONSISTENT_TABLE 衝突偵測會填 witness（由 ConsistencyValidator 產生）。
     * 其他錯誤碼 witness 維持 null，並由 @JsonInclude(NON_NULL) 從 JSON 輸出隱藏。
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidationError {
        private String code;
        private String message;
        /**
         * 觸發此錯誤的具體輸入範例（IEEE 2024 structured witness）。
         * 例：{"age": "25", "hasHypertension": "true"} 表示該組輸入會觸發衝突。
         */
        private Map<String, String> witness;
        /**
         * 嚴重度（v3.15）：{@code null} 或 {@code "ERROR"} 視為致命，會使 {@code valid=false}；
         * {@code "WARNING"} 為提示性訊息（如冗餘規則），不影響 {@code valid}。
         * 採 {@code @JsonInclude(NON_NULL)}：未設定時不出現於 JSON，向後相容既有錯誤碼。
         */
        private String severity;
    }

    // ========================================
    // POST /tools/analyze（v1.4+ DMN 分析）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AnalyzeRequest {
        /** 完整的 RuleEnvelope JSON（容許 null：回 200 + coverageRate=0，見 ToolsControllerTest） */
        private JsonNode ruleJson;
        /** 規則型態，例如 "DecisionTable" */
        private String ruleType;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AnalyzeResponse {
        /** 覆蓋率 0.0 – 1.0 */
        private double coverageRate;
        /** 未覆蓋區域 */
        private List<GapInfo> gaps;
        /** 規則重疊 */
        private List<OverlapInfo> overlaps;
        /** 簡化建議 */
        private List<SimplificationHint> simplifications;
        /** 條件組合超過展開上限而被截斷的規則 ID（非空 = 重疊/缺口分析可能不完整） */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @Builder.Default
        private List<String> truncatedRuleIds = List.of();
    }

    /**
     * 未覆蓋區域描述。
     * 獨立 DTO 形式暴露給 REST API，避免 controller 層直接依賴 service 內部類別。
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GapInfo {
        /** 各維度的區間描述，例如 {"age": "[36,50]", "hypertension": "true"} */
        private Map<String, String> conditions;
        /** 人類可讀的說明 */
        private String message;
    }

    /**
     * 規則重疊描述。
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OverlapInfo {
        /** 重疊的規則 ID 列表 */
        private List<String> ruleIds;
        /** 重疊區域各維度描述 */
        private Map<String, String> intersection;
        /** 人類可讀的說明 */
        private String message;
    }

    /**
     * 規則簡化建議。
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SimplificationHint {
        /** 可簡化的規則 ID 列表 */
        private List<String> ruleIds;
        /** 建議的簡化方式 */
        private String suggestion;
    }

    // ========================================
    // POST /tools/test-run
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestRunRequest {
        private List<TestCase> testCases;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestCase {
        private String id;
        private String description;
        private String expectedRuleType;
        @Builder.Default
        private boolean expectValid = true;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestRunResponse {
        private int total;
        private int passed;
        private int failed;
        private double passRate;
        private Map<String, Integer> errorBreakdown;
        private List<TestCaseResult> results;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestCaseResult {
        private String id;
        private boolean passed;
        private String recommendedType;
        private boolean typeMatchExpected;
        private boolean jsonParseable;
        private boolean schemaValid;
        private List<ValidationError> errors;
        private long durationMs;
    }

    // ========================================
    // POST /tools/convert（Phase 3：雙向轉換）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ConvertRequest {
        /** 原始 RuleEnvelope JSON */
        @jakarta.validation.constraints.NotNull(message = "ruleJson 不可為空")
        private JsonNode ruleJson;
        /** 目標型態：DecisionTable 或 DecisionTree */
        @NotBlank(message = "targetType 不可為空")
        private String targetType;
    }

    // ========================================
    // POST /tools/optimize（DecisionTree 優化）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OptimizeRequest {
        /** DecisionTree 的 RuleEnvelope JSON */
        @jakarta.validation.constraints.NotNull(message = "ruleJson 不可為空")
        private JsonNode ruleJson;
        /** 規則類型（目前僅支援 DecisionTree） */
        private String ruleType;
        /** 是否啟用積極優化（含移除死碼分支） */
        private boolean aggressive;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OptimizeResponse {
        /** 優化後的 RuleEnvelope */
        private RuleEnvelope optimized;
        /** 移除的節點數 */
        private int nodesRemoved;
        /** 深度減少量 */
        private int depthReduction;
        /** 套用的優化項目 */
        private List<String> appliedOptimizations;
    }

    // ========================================
    // POST /tools/suggest（輸入建議）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SuggestRequest {
        @NotBlank(message = "description 不可為空")
        @Size(max = 10000, message = "description 長度不可超過 10000 字元")
        private String description;
    }

    // ========================================
    // POST /tools/tree-paths（決策樹路徑展開）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TreePathsRequest {
        @jakarta.validation.constraints.NotNull(message = "envelope 不可為空")
        private com.ruleengine.rules.domain.envelope.RuleEnvelope envelope;
    }

    // ========================================
    // POST /tools/diff-tree（DecisionTree 語意結構 diff）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DiffTreeRequest {
        @jakarta.validation.constraints.NotNull(message = "before 不可為空")
        private com.ruleengine.rules.domain.envelope.RuleEnvelope before;
        @jakarta.validation.constraints.NotNull(message = "after 不可為空")
        private com.ruleengine.rules.domain.envelope.RuleEnvelope after;
    }

    // ========================================
    // POST /tools/lookup（規則查詢）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LookupRequest {
        @jakarta.validation.constraints.NotNull(message = "envelope 不可為空")
        private RuleEnvelope envelope;
        @jakarta.validation.constraints.NotNull(message = "inputValues 不可為空")
        private Map<String, Object> inputValues;
    }

    // ========================================
    // POST /tools/scenario-expand
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ScenarioExpandRequest {
        @jakarta.validation.constraints.NotNull(message = "envelope is required")
        private RuleEnvelope envelope;
        private Integer maxScenarios;
        private String noMatchLabel;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScenarioExpandResponse {
        private int totalPossible;
        private int returned;
        private boolean truncated;
        private String noMatchLabel;
        private List<ScenarioDomain> domains;
        private List<ScenarioRow> scenarios;
        private List<String> warnings;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScenarioDomain {
        private String field;
        private String typeRef;
        private List<Object> values;
        private boolean finite;
        private String source;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScenarioRow {
        private String scenarioId;
        private Map<String, Object> inputValues;
        private boolean matched;
        private List<String> matchedRuleIds;
        private Map<String, Object> results;
        private String outcomeLabel;
        private List<String> evaluationPath;
    }

    // ========================================
    // POST /tools/explain — 每條規則 rationale 產生（v3.9.0）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExplainRequest {
        @NotBlank(message = "description 不可為空")
        @Size(max = 50000)
        private String description;

        @jakarta.validation.constraints.NotNull(message = "envelope 不可為空")
        private com.ruleengine.rules.domain.envelope.RuleEnvelope envelope;

        /** LLM Provider 選擇（可 null 用預設） */
        private String provider;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExplainResponse {
        /** 已回填 rationale 的完整 envelope */
        private com.ruleengine.rules.domain.envelope.RuleEnvelope envelope;
        /** 實際產出 rationale 的規則數 */
        private int rationalesApplied;
        /** 該次評估所用 provider */
        private String provider;
        /** 耗時（毫秒） */
        private long durationMs;
    }

    // ========================================
    // POST /tools/optimize/v2 — DecisionTree v2 sparsity-aware pruning（v3.13）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OptimizeRequestV2 {
        @jakarta.validation.constraints.NotNull(message = "ruleJson 不可為空")
        private JsonNode ruleJson;
        /** 可選配置；null 用預設 */
        private com.ruleengine.rules.service.optimizer.v2.OptimizeConfigV2 config;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OptimizeResponseV2 {
        private com.ruleengine.rules.domain.envelope.RuleEnvelope optimized;
        private com.ruleengine.rules.service.optimizer.v2.TreeQualityMetrics.Metrics metricsBefore;
        private com.ruleengine.rules.service.optimizer.v2.TreeQualityMetrics.Metrics metricsAfter;
        private double sparsityScoreBefore;
        private double sparsityScoreAfter;
        private List<String> appliedOptimizations;
        private Map<String, Integer> passContributions;
        private long durationMs;
    }

    // ========================================
    // POST /tools/narrate — 整體業務敘事（v3.12）
    // 給非工程使用者（業務／精算師）的一段式規則總覽
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NarrateRequest {
        @Size(max = 50000)
        private String description;

        @jakarta.validation.constraints.NotNull(message = "envelope 不可為空")
        private com.ruleengine.rules.domain.envelope.RuleEnvelope envelope;

        /** LLM Provider 選擇（可 null 用預設） */
        private String provider;
    }

    // ========================================
    // POST /tools/evaluate — LLM-as-Judge 評估（v3.6.0）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EvaluateRequest {
        @NotBlank(message = "description 不可為空")
        @Size(max = 50000, message = "description 長度不可超過 50000 字元")
        private String description;

        @jakarta.validation.constraints.NotNull(message = "envelope 不可為空")
        private RuleEnvelope envelope;

        /** 生成時使用的 LLM provider（用於跨 provider 評估，消除 self-enhancement bias） */
        private String generatorProvider;

        /** 是否啟用 self-consistency（兩次不同 criteria 順序，對抗 positional bias；成本 2x） */
        private Boolean selfConsistency;
    }

    // ========================================
    // 17 個錯誤碼（v3.14：v1.0.0 既有 11 個 + extensions 5 個 + xlsx 區域變數 1 個）
    // ========================================
    public static final class ErrorCodes {
        public static final String MISSING_FIELD = "MISSING_FIELD";
        public static final String UNKNOWN_OPERATOR = "UNKNOWN_OPERATOR";
        public static final String TYPE_MISMATCH = "TYPE_MISMATCH";
        public static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
        public static final String DUPLICATE_ID = "DUPLICATE_ID";
        public static final String ENUM_VALUE_MISSING = "ENUM_VALUE_MISSING";
        public static final String INVALID_ENUM_VALUE = "INVALID_ENUM_VALUE";
        public static final String INCONSISTENT_TABLE = "INCONSISTENT_TABLE";
        public static final String INVALID_MULTI = "INVALID_MULTI";
        public static final String MISSING_BRANCH = "MISSING_BRANCH";
        public static final String MISSING_RESULTS = "MISSING_RESULTS";
        // v3.14 — extensions
        public static final String INVALID_GLOBAL_GUARD = "INVALID_GLOBAL_GUARD";
        public static final String INVALID_FIELD_OR = "INVALID_FIELD_OR";
        public static final String INVALID_RULE_STATUS_REF = "INVALID_RULE_STATUS_REF";
        public static final String INVALID_FOOTNOTE_REF = "INVALID_FOOTNOTE_REF";
        public static final String MALFORMED_GROUPING = "MALFORMED_GROUPING";
        // v3.14 — xlsx schema rule：VARIABLE typeRef 屬輸出專用（sheet「使用說明」: 區域變數 條件欄位不開放）
        public static final String INVALID_VARIABLE_IN_CONDITION = "INVALID_VARIABLE_IN_CONDITION";
        // v3.15 — 冗餘規則：FIRST 下條件重疊但結果相同（無害，提示可合併，severity=WARNING，不致 valid=false）
        public static final String REDUNDANT_RULE = "REDUNDANT_RULE";
        private ErrorCodes() {}
    }

    /** 驗證錯誤嚴重度（v3.15）。{@code WARNING} 不影響 {@code valid}。 */
    public static final class Severity {
        public static final String ERROR = "ERROR";
        public static final String WARNING = "WARNING";
        private Severity() {}
    }

    // ========================================
    // 8 個 typeRef（v3.14：v1.0.0 既有 6 個 + TIMESTAMP / VARIABLE 兩個 xlsx-derived 型別）
    // 新值附加於 ALL 最末以保留宣告順序（MISSION.md §4.M3「at the end of the enum」）。
    // ========================================
    public static final class TypeRefs {
        public static final String INTEGER = "INTEGER";
        public static final String DECIMAL = "DECIMAL";
        public static final String BOOLEAN = "BOOLEAN";
        public static final String STRING = "STRING";
        public static final String ENUM = "ENUM";
        public static final String DATE = "DATE";
        // v3.14：附加於最末以保留 ordinal 穩定性
        public static final String TIMESTAMP = "TIMESTAMP";
        public static final String VARIABLE = "VARIABLE";
        public static final java.util.Set<String> ALL = java.util.Set.of(
                INTEGER, DECIMAL, BOOLEAN, STRING, ENUM, DATE,
                TIMESTAMP, VARIABLE
        );
        private TypeRefs() {}
    }

    // ========================================
    // 12 個 operator
    // ========================================
    public static final class Operators {
        public static final String EQUALS = "equals";
        public static final String NOT_EQUALS = "notEquals";
        public static final String GREATER_THAN = "greaterThan";
        public static final String GREATER_THAN_OR_EQUAL = "greaterThanOrEqual";
        public static final String LESS_THAN = "lessThan";
        public static final String LESS_THAN_OR_EQUAL = "lessThanOrEqual";
        public static final String BETWEEN = "between";
        public static final String IN = "in";
        public static final String NOT_IN = "notIn";
        public static final String IS_NULL = "isNull";
        public static final String IS_NOT_NULL = "isNotNull";
        public static final String ANYTHING = "anything";
        public static final java.util.Set<String> ALL = java.util.Set.of(
                EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL,
                LESS_THAN, LESS_THAN_OR_EQUAL, BETWEEN, IN, NOT_IN,
                IS_NULL, IS_NOT_NULL, ANYTHING
        );
        private Operators() {}
    }
}
