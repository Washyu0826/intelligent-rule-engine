package com.ruleengine.rules.service.evaluator;

import com.ruleengine.rules.domain.dto.ToolDtos.AnalyzeResponse;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidateResponse;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite Confidence Scorer (v3.10.0) — 規則生成可信度總分。
 *
 * <p>設計動機：v3.6（Judge）、v3.7（Witness）、v3.8（Grounding）、v3.9（Rationale）四個研究背書
 * 的品質訊號分散在不同欄位，BA 需要**一個數字**決定「能進 production 嗎」。
 *
 * <p>研究依據：
 * <ul>
 *   <li>arxiv 2404.15604 "Hybrid LLM/Rule-based Approaches to Business"
 *   <li>Brain.co 2024 "Executable IF-THEN Logic for LLM Explainability in Regulated Industries"
 *   <li>WEF 2025-12 Neurosymbolic AI — "auditable workings, real-world outcomes"
 * </ul>
 *
 * <p>計分加權（總 100）：
 * <table>
 *   <tr><th>因子</th><th>權重</th><th>來源</th></tr>
 *   <tr><td>Validation 通過</td><td>30</td><td>{@link ValidateResponse}</td></tr>
 *   <tr><td>Grounding ratio</td><td>25</td><td>v3.8 {@link GroundingGuardService.GroundingReport}</td></tr>
 *   <tr><td>Coverage rate</td><td>20</td><td>{@link RuleEnvelope.Evaluation#getCoverageRate()}</td></tr>
 *   <tr><td>No conflict</td><td>15</td><td>{@link AnalyzeResponse} / evaluation</td></tr>
 *   <tr><td>Rule count sanity</td><td>10</td><td>規則數 1-200 的合理性</td></tr>
 * </table>
 *
 * <p>Tier：
 * <ul>
 *   <li>≥ 85：PRODUCTION_READY — 可直接上線
 *   <li>70-84：REVIEW_NEEDED — BA 人工確認
 *   <li>&lt; 70：NOT_RECOMMENDED — 需要重新生成或大幅修改
 * </ul>
 */
@Service
@Slf4j
public class ConfidenceScorer {

    // ========================================
    // DTO
    // ========================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConfidenceReport {
        /** 總分 0-100 */
        private int overallScore;
        /** PRODUCTION_READY / REVIEW_NEEDED / NOT_RECOMMENDED */
        private String tier;
        /** 各因子的加權貢獻分數 */
        private Map<String, FactorScore> breakdown;
        /** 給 BA 的可行動警告（依影響程度排序） */
        private List<String> actionableWarnings;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactorScore {
        /** 本因子的原始得分 0.0-1.0 */
        private double raw;
        /** 最大可得分（即權重） */
        private int weight;
        /** 本因子實際貢獻 = raw × weight，四捨五入 */
        private int earned;
    }

    // ========================================
    // 權重常數
    // ========================================

    private static final int W_VALIDATION = 30;
    private static final int W_GROUNDING = 25;
    private static final int W_COVERAGE = 20;
    private static final int W_CONFLICT = 15;
    private static final int W_RULE_COUNT = 10;

    private static final int TIER_PRODUCTION = 85;
    private static final int TIER_REVIEW = 70;

    // ========================================
    // 公開 API
    // ========================================

    /**
     * 由 GenerateResponse 的 envelope / validation / grounding 計算 composite confidence。
     *
     * @param envelope        生成的 envelope（可 null）
     * @param validation      驗證結果（可 null）
     * @param grounding       grounding check 結果（可 null；v3.8+ 才有）
     * @return ConfidenceReport，若所有 input 皆 null 則回傳 overallScore=0 的警告
     */
    public ConfidenceReport score(RuleEnvelope envelope,
                                   ValidateResponse validation,
                                   GroundingGuardService.GroundingReport grounding) {
        Map<String, FactorScore> breakdown = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        // === Factor 1: Validation ===
        double validationRaw = scoreValidation(validation, warnings);
        breakdown.put("validation", factorOf(validationRaw, W_VALIDATION));

        // === Factor 2: Grounding ===
        double groundingRaw = scoreGrounding(grounding, warnings);
        breakdown.put("grounding", factorOf(groundingRaw, W_GROUNDING));

        // === Factor 3: Coverage ===
        double coverageRaw = scoreCoverage(envelope, warnings);
        breakdown.put("coverage", factorOf(coverageRaw, W_COVERAGE));

        // === Factor 4: No Conflict ===
        double conflictRaw = scoreConflict(envelope, warnings);
        breakdown.put("noConflict", factorOf(conflictRaw, W_CONFLICT));

        // === Factor 5: Rule count sanity ===
        double ruleCountRaw = scoreRuleCount(envelope, warnings);
        breakdown.put("ruleCount", factorOf(ruleCountRaw, W_RULE_COUNT));

        int total = breakdown.values().stream().mapToInt(FactorScore::getEarned).sum();
        String tier = tierOf(total);

        log.info("Confidence | total={} | tier={} | warnings={}", total, tier, warnings.size());

        return ConfidenceReport.builder()
                .overallScore(total)
                .tier(tier)
                .breakdown(breakdown)
                .actionableWarnings(warnings.isEmpty() ? null : warnings)
                .build();
    }

    // ========================================
    // 各因子計分
    // ========================================

    double scoreValidation(ValidateResponse validation, List<String> warnings) {
        if (validation == null) return 0.5;
        if (validation.isValid()) return 1.0;
        int errCount = validation.getErrors() == null ? 0 : validation.getErrors().size();
        // 每個錯誤扣 10%，最低 0
        double raw = Math.max(0.0, 1.0 - errCount * 0.10);
        if (errCount > 0) {
            warnings.add("驗證發現 " + errCount + " 個錯誤，請先修正（關鍵因子 30 分）");
        }
        return raw;
    }

    double scoreGrounding(GroundingGuardService.GroundingReport grounding, List<String> warnings) {
        if (grounding == null) return 0.75;  // 沒跑 grounding check → 中性分
        double raw = grounding.getGroundingRatio();
        if ("HIGH".equals(grounding.getSuspicionLevel())) {
            int n = (grounding.getUngroundedFields() == null ? 0 : grounding.getUngroundedFields().size())
                    + (grounding.getUngroundedEnumValues() == null ? 0 : grounding.getUngroundedEnumValues().size());
            warnings.add("符號 grounding 檢查偵測到 " + n
                    + " 個可能的幻覺欄位/值（請 review 或提供 allowedFields 白名單）");
        }
        return raw;
    }

    double scoreCoverage(RuleEnvelope envelope, List<String> warnings) {
        if (envelope == null || envelope.getEvaluation() == null
                || envelope.getEvaluation().getCoverageRate() == null) return 0.5;
        double rate = envelope.getEvaluation().getCoverageRate();
        if (rate < 0.8) {
            warnings.add("覆蓋率僅 " + String.format("%.0f%%", rate * 100)
                    + "，存在未被規則覆蓋的條件組合");
        }
        return rate;
    }

    double scoreConflict(RuleEnvelope envelope, List<String> warnings) {
        if (envelope == null || envelope.getEvaluation() == null
                || envelope.getEvaluation().getConflictDetection() == null) return 0.5;
        if ("NO_CONFLICT".equals(envelope.getEvaluation().getConflictDetection())) return 1.0;
        warnings.add("偵測到規則衝突（`INCONSISTENT_TABLE`），FIRST hitPolicy 下可能造成不可預期行為");
        return 0.0;
    }

    double scoreRuleCount(RuleEnvelope envelope, List<String> warnings) {
        if (envelope == null || envelope.getRule() == null
                || envelope.getRule().getRules() == null) return 0.5;
        int n = envelope.getRule().getRules().size();
        if (n == 0) {
            warnings.add("規則數為 0，無法執行任何決策");
            return 0.0;
        }
        if (n == 1) {
            warnings.add("僅有 1 條規則，decision table 偏簡化，確認是否已涵蓋所有情境");
            return 0.5;
        }
        if (n > 200) {
            warnings.add("規則數高達 " + n + " 條，建議考慮轉為 DecisionTree 或拆分");
            return 0.6;
        }
        // 2-200 為合理範圍
        return 1.0;
    }

    // ========================================
    // 工具
    // ========================================

    private FactorScore factorOf(double raw, int weight) {
        double clamped = Math.max(0.0, Math.min(1.0, raw));
        return FactorScore.builder()
                .raw(clamped)
                .weight(weight)
                .earned((int) Math.round(clamped * weight))
                .build();
    }

    private String tierOf(int total) {
        if (total >= TIER_PRODUCTION) return "PRODUCTION_READY";
        if (total >= TIER_REVIEW) return "REVIEW_NEEDED";
        return "NOT_RECOMMENDED";
    }
}
