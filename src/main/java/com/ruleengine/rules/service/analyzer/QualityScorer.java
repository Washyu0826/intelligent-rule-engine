package com.ruleengine.rules.service.analyzer;

import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.validator.DecisionTableValidator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * QualityScorer — LLM 生成品質評分器（DECISIONS.md Q1/Q5/Q8）
 *
 * 評分指標（加權）：
 *   - Schema 驗證 (30%)：validate 錯誤數 = 0 → 滿分
 *   - 覆蓋率 (25%)：DmnAnalyzer 算真實覆蓋率，>= 0.9 → 滿分
 *   - 衝突數 (20%)：overlap 規則對數 = 0 → 滿分
 *   - 規則數合理性 (15%)：實際規則數 / 笛卡爾積 >= 0.5 → 滿分
 *   - reason 品質 (10%)：中文 >= 50 字 + 含關鍵詞 → 滿分
 *
 * 總分 < 80 → 產出 deductions 列表（扣分原因），用於回傳給 Gemini retry。
 */
@Component
@Slf4j
public class QualityScorer {

    private static final int WEIGHT_SCHEMA    = 30;
    private static final int WEIGHT_COVERAGE  = 25;
    private static final int WEIGHT_CONFLICT  = 20;
    private static final int WEIGHT_RULE_COUNT = 15;
    private static final int WEIGHT_REASON    = 10;

    private static final double COVERAGE_THRESHOLD    = 0.9;
    private static final double RULE_RATIO_THRESHOLD  = 0.5;
    private static final int    REASON_MIN_LENGTH     = 50;
    private static final List<String> REASON_KEYWORDS = List.of("條件", "結果", "策略");

    private final DecisionTableValidator validator;

    public QualityScorer(DecisionTableValidator validator) {
        this.validator = validator;
    }

    /**
     * 主方法：對 LLM 生成的 RuleEnvelope 進行品質評分。
     *
     * @param envelope  反序列化後的 RuleEnvelope（取 reason 等元資料）
     * @param ruleJson  原始 JSON（傳給 validator 和 DmnAnalyzer）
     * @param analyzer  Window 4 提供的 DmnAnalyzer 實例
     * @return QualityScore 含 0-100 分、各項分數明細、扣分原因列表
     */
    public QualityScore score(RuleEnvelope envelope, JsonNode ruleJson, DmnAnalyzer analyzer) {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        List<String> deductions = new ArrayList<>();

        // ========================================
        // 1) Schema 驗證 (30%)
        // ========================================
        int schemaScore = scoreSchema(ruleJson, deductions);
        breakdown.put("schema", schemaScore);

        // ========================================
        // 2) 覆蓋率 (25%)
        // ========================================
        int coverageScore = scoreCoverage(ruleJson, analyzer, deductions);
        breakdown.put("coverage", coverageScore);

        // ========================================
        // 3) 衝突數 (20%)
        // ========================================
        int conflictScore = scoreConflict(ruleJson, analyzer, deductions);
        breakdown.put("conflict", conflictScore);

        // ========================================
        // 4) 規則數合理性 (15%)
        // ========================================
        int ruleCountScore = scoreRuleCount(ruleJson, deductions);
        breakdown.put("ruleCount", ruleCountScore);

        // ========================================
        // 5) reason 品質 (10%)
        // ========================================
        int reasonScore = scoreReason(envelope, deductions);
        breakdown.put("reason", reasonScore);

        // ========================================
        // 加權總分
        // ========================================
        int totalScore = schemaScore + coverageScore + conflictScore + ruleCountScore + reasonScore;
        totalScore = Math.max(0, Math.min(100, totalScore));

        log.info("QualityScore 計算完成 | total={} | breakdown={} | deductions={}",
                totalScore, breakdown, deductions.size());

        if (totalScore < 80) {
            log.warn("品質分數低於 80 分（{}），建議 Gemini retry | 扣分原因：{}", totalScore, deductions);
        }

        return QualityScore.builder()
                .totalScore(totalScore)
                .breakdown(breakdown)
                .deductions(deductions)
                .build();
    }

    // ================================================================
    //  1) Schema 驗證 (30%)
    // ================================================================

    private int scoreSchema(JsonNode ruleJson, List<String> deductions) {
        try {
            List<ValidationError> errors = validator.validate(ruleJson);
            if (errors.isEmpty()) {
                return WEIGHT_SCHEMA;
            }
            // 有錯誤 → 0 分（Schema 不合格就是不合格）
            deductions.add(String.format("Schema 驗證失敗：共 %d 個錯誤。首個錯誤：%s",
                    errors.size(),
                    errors.get(0).getMessage()));
            return 0;
        } catch (Exception e) {
            deductions.add("Schema 驗證過程發生例外：" + e.getMessage());
            return 0;
        }
    }

    // ================================================================
    //  2) 覆蓋率 (25%)
    // ================================================================

    private int scoreCoverage(JsonNode ruleJson, DmnAnalyzer analyzer, List<String> deductions) {
        try {
            AnalysisResult result = analyzer.analyze(ruleJson);
            double rate = result.getCoverageRate();

            if (rate >= COVERAGE_THRESHOLD) {
                return WEIGHT_COVERAGE;
            }
            // 線性扣分：覆蓋率越低分越低
            int score = (int) Math.round(WEIGHT_COVERAGE * (rate / COVERAGE_THRESHOLD));
            score = Math.max(0, Math.min(WEIGHT_COVERAGE, score));

            deductions.add(String.format("覆蓋率不足：%.2f（門檻 %.1f）。請補齊缺失的條件組合",
                    rate, COVERAGE_THRESHOLD));
            return score;
        } catch (Exception e) {
            log.warn("DmnAnalyzer 覆蓋率計算失敗，給予保守分數：{}", e.getMessage());
            deductions.add("覆蓋率無法計算：" + e.getMessage());
            return 0;
        }
    }

    // ================================================================
    //  3) 衝突數 (20%)
    // ================================================================

    private int scoreConflict(JsonNode ruleJson, DmnAnalyzer analyzer, List<String> deductions) {
        try {
            AnalysisResult result = analyzer.analyze(ruleJson);
            List<?> overlaps = result.getOverlaps();

            if (overlaps == null || overlaps.isEmpty()) {
                return WEIGHT_CONFLICT;
            }
            // 有衝突 → 依衝突數量扣分，每對衝突扣 5 分，最低 0
            int penalty = overlaps.size() * 5;
            int score = Math.max(0, WEIGHT_CONFLICT - penalty);

            deductions.add(String.format("偵測到 %d 對規則衝突（overlap）。FIRST 策略下規則不可重疊，請修正重疊的條件範圍",
                    overlaps.size()));
            return score;
        } catch (Exception e) {
            log.warn("DmnAnalyzer 衝突偵測失敗：{}", e.getMessage());
            deductions.add("衝突偵測無法執行：" + e.getMessage());
            return 0;
        }
    }

    // ================================================================
    //  4) 規則數合理性 (15%)
    // ================================================================

    private int scoreRuleCount(JsonNode ruleJson, List<String> deductions) {
        try {
            JsonNode ruleNode = ruleJson.path("rule");
            JsonNode inputsNode = ruleNode.path("inputs");
            JsonNode rulesNode = ruleNode.path("rules");

            if (!inputsNode.isArray() || !rulesNode.isArray()) {
                deductions.add("無法計算規則數合理性：inputs 或 rules 節點缺失");
                return 0;
            }

            int actualRuleCount = rulesNode.size();
            if (actualRuleCount == 0) {
                deductions.add("規則數為 0，無法評估合理性");
                return 0;
            }

            // 計算笛卡爾積（各 input 欄位的值域大小相乘）
            long cartesianProduct = computeCartesianProduct(inputsNode);
            if (cartesianProduct <= 0) {
                // 無法計算笛卡爾積時給予一半分數
                return WEIGHT_RULE_COUNT / 2;
            }

            double ratio = (double) actualRuleCount / cartesianProduct;

            if (ratio >= RULE_RATIO_THRESHOLD) {
                return WEIGHT_RULE_COUNT;
            }
            // 線性扣分
            int score = (int) Math.round(WEIGHT_RULE_COUNT * (ratio / RULE_RATIO_THRESHOLD));
            score = Math.max(0, Math.min(WEIGHT_RULE_COUNT, score));

            deductions.add(String.format(
                    "規則數合理性不足：實際 %d 條 / 笛卡爾積 %d = %.2f（門檻 %.1f）。"
                            + "請確認是否窮舉了所有條件組合",
                    actualRuleCount, cartesianProduct, ratio, RULE_RATIO_THRESHOLD));
            return score;
        } catch (Exception e) {
            deductions.add("規則數合理性計算失敗：" + e.getMessage());
            return 0;
        }
    }

    /**
     * 估算笛卡爾積：各 input 維度的值域大小相乘。
     *
     * - BOOLEAN → 2
     * - ENUM → allowedValues.size()
     * - INTEGER/DECIMAL/DATE → 從 rules 中統計該欄位的 distinct 區段數
     * - STRING → 從 rules 中統計 distinct 值數
     */
    private long computeCartesianProduct(JsonNode inputsNode) {
        long product = 1;
        for (JsonNode input : inputsNode) {
            String typeRef = input.path("typeRef").asText("");
            long dimensionSize;

            switch (typeRef) {
                case "BOOLEAN":
                    dimensionSize = 2;
                    break;
                case "ENUM":
                    JsonNode allowed = input.path("allowedValues");
                    dimensionSize = allowed.isArray() && !allowed.isEmpty() ? allowed.size() : 3;
                    break;
                default:
                    // INTEGER/DECIMAL/DATE/STRING：保守估計 3 段
                    // （實際場景通常分 3-5 段，取保守值避免過度懲罰）
                    dimensionSize = 3;
                    break;
            }

            product *= dimensionSize;
            // 防止溢位
            if (product > 100_000) {
                return product;
            }
        }
        return product;
    }

    // ================================================================
    //  5) reason 品質 (10%)
    // ================================================================

    private int scoreReason(RuleEnvelope envelope, List<String> deductions) {
        String reason = envelope != null ? envelope.getReason() : null;

        if (reason == null || reason.isBlank()) {
            deductions.add("reason 欄位為空。請提供中文說明，至少 50 字，並包含「條件」「結果」「策略」關鍵詞");
            return 0;
        }

        // 移除非中文字符後計算中文字數
        String chineseOnly = reason.replaceAll("[^\\u4e00-\\u9fff]", "");
        int chineseCharCount = chineseOnly.length();

        // 檢查關鍵詞
        List<String> missingKeywords = new ArrayList<>();
        for (String keyword : REASON_KEYWORDS) {
            if (!reason.contains(keyword)) {
                missingKeywords.add(keyword);
            }
        }

        boolean lengthOk = chineseCharCount >= REASON_MIN_LENGTH;
        boolean keywordsOk = missingKeywords.isEmpty();

        if (lengthOk && keywordsOk) {
            return WEIGHT_REASON;
        }

        // 部分得分
        int score = 0;
        if (lengthOk) {
            score += 6; // 長度佔 6 分
        } else {
            deductions.add(String.format(
                    "reason 中文字數不足：%d 字（門檻 %d 字）。請詳細說明規則集的條件欄位、結果欄位、覆蓋場景和策略",
                    chineseCharCount, REASON_MIN_LENGTH));
        }
        if (keywordsOk) {
            score += 4; // 關鍵詞佔 4 分
        } else {
            deductions.add("reason 缺少關鍵詞：" + missingKeywords + "。請在說明中提及這些概念");
        }

        return Math.min(WEIGHT_REASON, score);
    }

    // ================================================================
    //  QualityScore 資料類別
    // ================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QualityScore {
        /** 總分 0-100 */
        private int totalScore;
        /** 各項分數明細：schema, coverage, conflict, ruleCount, reason */
        private Map<String, Integer> breakdown;
        /** 扣分原因列表（totalScore < 80 時用於回傳給 Gemini retry） */
        private List<String> deductions;

        /**
         * 是否通過品質門檻（>= 80 分）
         */
        public boolean isPassed() {
            return totalScore >= 80;
        }

        /**
         * 產出回饋給 Gemini 的扣分摘要（用於 retry prompt）
         */
        public String toRetryFeedback() {
            if (isPassed() || deductions == null || deductions.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("品質評分：").append(totalScore).append("/100（未通過 80 分門檻）\n");
            sb.append("扣分原因：\n");
            for (int i = 0; i < deductions.size(); i++) {
                sb.append(String.format("  %d. %s\n", i + 1, deductions.get(i)));
            }
            sb.append("請根據以上扣分原因修正後重新生成。");
            return sb.toString();
        }
    }
}
