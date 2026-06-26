package com.ruleengine.rules.domain.envelope;

import com.ruleengine.rules.domain.RuleEnvelopeExtensions;
import com.ruleengine.rules.service.analyzer.AnalysisResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

/**
 * RuleEnvelope — 統一輸出格式（output design v3.0 §3.1）
 *
 * 設計決策（§6.3）：
 * - 頂層用 ruleType（非 type），與 recommendedRuleType 語義區分明確
 * - rule 鍵名（非 payload），BA 一眼知道這是規則本體
 * - evaluation 獨立物件，品質指標不污染規則結構
 * - hitPolicy: FIRST（非 UNIQUE），更接近業務「第一條命中」
 * - hitPolicy: MULTI（非 COLLECT），多條命中比「收集」更直接
 * - validate 回 HTTP 200（非 4xx），驗證結果是業務回傳不是系統異常
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleEnvelope {

    /** "DecisionTable" | "DecisionTree" */
    private String ruleType;

    /** LLM 中文說明（為何選此規則型態） */
    private String reason;

    /** 品質評估指標 */
    private Evaluation evaluation;

    /** 規則本體 */
    private Rule rule;

    /** 輸出格式版本（計畫書要求追溯） */
    private String schemaVersion;

    /** 生成策略版本（計畫書要求追溯） */
    private String promptVersion;

    /** 規則版本 ID（版本管理用） */
    private String versionId;

    /** 前一版本 ID（版本鏈追溯） */
    private String previousVersionId;

    /** 稽核元資料（企業整合） */
    private AuditMetadata audit;

    /** v3.14: 額外結構（globalGuard / fieldOr / grouping / footnote / ruleStatus），預設 null。 */
    private RuleEnvelopeExtensions extensions;

    // ========================================
    // Evaluation（§3.1 + v1.4 DMN 分析擴充）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Evaluation {
        /** "COMPLETE" | "INCOMPLETE" */
        private String completeness;
        /** 規則條數 */
        private Integer totalScenarios;
        /** 覆蓋率 0.0 – 1.0 */
        private Double coverageRate;
        /** "NO_CONFLICT" | "HAS_CONFLICT" */
        private String conflictDetection;
        /** "FIRST" | "MULTI" */
        private String recommendedStrategy;

        // ===== v1.4+ DMN 分析結果（DECISIONS Q16：帶在 generate 回傳） =====

        /** 未覆蓋區域（@JsonInclude NON_NULL 確保舊回應不多 null） */
        private List<AnalysisResult.GapInfo> gaps;

        /** 規則重疊區域 */
        private List<AnalysisResult.OverlapInfo> overlaps;

        /** 規則簡化建議 */
        private List<AnalysisResult.SimplificationHint> simplifications;

        /** 條件組合超過展開上限而被截斷的規則 ID（非空 = 上述分析可能不完整；NON_NULL 確保舊回應不變） */
        private List<String> truncatedRuleIds;
    }

    // ========================================
    // Rule（§3.1 + Phase 3 DecisionTree 擴充）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Rule {
        /** "FIRST" | "MULTI"（DecisionTable 專用） */
        private String hitPolicy;
        /** 輸入欄位定義：name + typeRef + allowedValues? */
        private List<FieldDef> inputs;
        /** 輸出欄位定義：name + typeRef + allowedValues? */
        private List<FieldDef> outputs;
        /** 規則行列表（DecisionTable 專用） */
        private List<RuleRow> rules;
        /** 決策樹根節點（DecisionTree 專用，Phase 3） */
        private TreeNode root;
        /** 評分卡列表（ScoreCard 專用） */
        private List<ScoringDimension> scoringDimensions;
        /** 總分對應結果（ScoreCard 專用） */
        private List<ScoreBand> scoreBands;
    }

    // ========================================
    // TreeNode — DecisionTree 節點（Phase 3 + N-ary 擴充）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TreeNode {
        /** 節點 ID（N01, N02, ...） */
        private String nodeId;
        /** 分支節點：判斷條件（葉節點無此欄位） */
        private Condition condition;
        /** 分支節點：條件為 true 時的子節點（向後相容，正規化後轉為 branches） */
        private TreeNode trueBranch;
        /** 分支節點：條件為 false 時的子節點（向後相容，正規化後轉為 branches） */
        private TreeNode falseBranch;
        /** N-ary 分支列表（正規化後的標準格式） */
        private List<Branch> branches;
        /** 葉節點：結果列表（分支節點無此欄位） */
        private List<Result> results;
    }

    // ========================================
    // Branch — N-ary 分支（每個分支含標籤、條件、子節點）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Branch {
        /** 分支標籤（"TRUE"/"FALSE" 或 ENUM 值如 "NORTH"） */
        private String label;
        /** 進入該分支的條件 */
        private Condition condition;
        /** 子節點 */
        private TreeNode child;
    }

    // ========================================
    // FieldDef — inputs[] / outputs[]（v3.12：補 adapter metadata）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldDef {
        private String name;
        /** INTEGER | DECIMAL | BOOLEAN | STRING | ENUM | DATE（§3.2） */
        private String typeRef;
        /** ENUM 型別必填 */
        private List<String> allowedValues;
        /**
         * v3.12：集團規則引擎內部欄位代碼（例 INSURED_AGE / POLICY_START_DATE）。
         * 為 adapter mapping 保留位，業務規格用 name，下游用 fieldCode。
         */
        private String fieldCode;
        /** v3.12：單位（例 TWD / USD / YEAR / DAY / PERCENT），DECIMAL 計算需精度時必填。 */
        private String unit;
        /** v3.12：DECIMAL 精度位數（adapter 對接金額/利率時的 scale）。 */
        private Integer scale;
        /** v3.12：是否允許 null（預設 false）。adapter 需據此產生欄位約束。 */
        private Boolean nullable;
        /** v3.12：外部 enum 對照（例 TW→158、HK→344），key 為 allowedValues 之值。 */
        private java.util.Map<String, String> externalCodes;
    }

    // ========================================
    // RuleRow — rules[]
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RuleRow {
        private String ruleId;
        private Integer priority;
        private List<Condition> conditions;
        private List<Result> results;
        /**
         * v3.9.0：本條規則對應描述中的理由（可選，由 RuleExplanationService 生成）。
         * 2024-2025 "Explaining rules with LLMs" / reason+verify pattern 的落地欄位。
         * null 時經 @JsonInclude(NON_NULL) 不出現在 JSON 輸出。
         */
        private String rationale;
    }

    // ========================================
    // Condition — 12 個 operator（§3.3）+ v3.12 跨欄位/相對日期
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Condition {
        private String field;
        /**
         * equals | notEquals | greaterThan | greaterThanOrEqual |
         * lessThan | lessThanOrEqual | between | in | notIn |
         * isNull | isNotNull | anything
         */
        private String operator;
        /** 依 operator 而異：單一值 / [min,max] / ["a","b",...] / null */
        private Object value;
        /**
         * v3.12：跨欄位 / 相對日期參照（與 value 互斥）。
         * 支援：
         * - "&lt;fieldName&gt;"：引用同 inputs 內另一欄位的執行期值（例：valueRef = "birthday"）
         * - "$today"：以執行當天為基準
         * - "$today+1d"、"$today-30d"、"$today+1m"：相對日期偏移（d=日、m=月、y=年）
         * 為下游 adapter 保留 engine-neutral 表達；evaluator 於執行期解析。
         */
        private String valueRef;
    }

    // ========================================
    // Result
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Result {
        private String field;
        private Object value;
    }

    // ========================================
    // ScoreCard — 評分卡結構
    // ========================================

    /** 評分維度：每個輸入欄位的獨立計分規則 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScoringDimension {
        /** 維度名稱（對應 inputs 的 field name） */
        private String field;
        /** 維度權重（預設 1.0） */
        private Double weight;
        /** 計分規則列表 */
        private List<ScoringRule> scoringRules;
    }

    /** 單條計分規則：條件 → 分數 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScoringRule {
        /** 計分規則 ID（S01, S02, ...） */
        private String ruleId;
        /** 條件（與 Condition 相同格式） */
        private Condition condition;
        /** 該條件命中時的分數 */
        private Integer score;
        /** 說明 */
        private String description;
    }

    /** 總分區間對應結果 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScoreBand {
        /** 區間 ID（B01, B02, ...） */
        private String bandId;
        /** 最低分（含） */
        private Integer minScore;
        /** 最高分（含） */
        private Integer maxScore;
        /** 此區間的輸出結果 */
        private List<Result> results;
    }

    // ========================================
    // AuditMetadata — 稽核元資料（v3.12：補生效期/業務 owner）
    // ========================================
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuditMetadata {
        /** 建立者 */
        private String createdBy;
        /** 建立時間（ISO-8601 字串） */
        private String createdAt;
        /** 最後修改者 */
        private String modifiedBy;
        /** 最後修改時間（ISO-8601 字串） */
        private String modifiedAt;
        /** 修改原因 */
        private String changeReason;
        /** 操作類型：CREATE / UPDATE / OPTIMIZE / CONVERT */
        private String operation;
        /** v3.12：規則生效起日（yyyy-MM-dd），下游引擎部署排程用。 */
        private String effectiveDate;
        /** v3.12：規則失效日（yyyy-MM-dd），nullable 表示無屆期。 */
        private String expiryDate;
        /** v3.12：業務負責人（精算/核保/理賠主管 email 或 ID），與 createdBy 區分。 */
        private String businessOwner;
        /** v3.12：規則所屬業務領域標籤（例：核保前端檢核 / 理賠初核）。 */
        private String businessDomain;
    }
}
