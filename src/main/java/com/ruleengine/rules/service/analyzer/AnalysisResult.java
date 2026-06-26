package com.ruleengine.rules.service.analyzer;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * DMN 決策表幾何分析結果。
 *
 * <p>由 {@link DmnAnalyzer#analyze} 產生，包含四類分析資訊：</p>
 * <ul>
 *   <li>coverageRate — 真實覆蓋率（規則聯集覆蓋的空間 / 整體值域空間）</li>
 *   <li>gaps — 未被任何規則覆蓋的條件區域</li>
 *   <li>overlaps — 有重疊（可能衝突）的規則對</li>
 *   <li>simplifications — 可合併的規則建議</li>
 * </ul>
 *
 * <p>設計參考：Calvanese, Dumas et al. (BPM 2016)</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResult {

    /**
     * 真實覆蓋率（0.0 – 1.0）。
     * 計算方式：所有規則超矩形聯集體積 / 整體值域空間體積。
     */
    private double coverageRate;

    /**
     * 缺失的條件區域列表（Gap Detection）。
     * 每個 GapInfo 描述一塊未被任何規則覆蓋的條件組合。
     */
    @Builder.Default
    private List<GapInfo> gaps = List.of();

    /**
     * 重疊的規則對列表（Overlap Detection）。
     * 每個 OverlapInfo 描述兩條（或多條）規則的超矩形有非空交集。
     */
    @Builder.Default
    private List<OverlapInfo> overlaps = List.of();

    /**
     * 可合併的規則建議列表（Rule Simplification）。
     * 每個 SimplificationHint 建議哪些規則可以合併以簡化決策表。
     */
    @Builder.Default
    private List<SimplificationHint> simplifications = List.of();

    /**
     * 分析摘要（人類可讀的中文描述）。
     */
    private String summary;

    /**
     * 條件組合超過展開上限而被截斷的規則 ID 列表。
     * 非空表示 overlap/gap 分析在這些規則的截斷區域可能漏報，結果不完整。
     */
    @Builder.Default
    private List<String> truncatedRuleIds = List.of();

    /**
     * 規則總數。
     */
    private int totalRules;

    /**
     * 輸入維度數。
     */
    private int totalDimensions;

    // ================================================================
    // GapInfo — 缺失條件區域
    // ================================================================

    /**
     * 描述一塊未被任何規則覆蓋的條件區域。
     *
     * <p>範例：{age: "[36,50]", hypertension: "true"} 沒有對應規則。</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GapInfo {

        /**
         * 缺失區域的條件描述。
         * Key = 欄位名稱，Value = 未覆蓋的區間描述。
         * 只列出非「全域」的維度。
         */
        private Map<String, String> conditions;

        /**
         * 人類可讀的描述訊息。
         */
        private String message;

        /**
         * 此缺口的估計體積比例（佔整體空間的百分比）。
         */
        private Double volumeRatio;
    }

    // ================================================================
    // OverlapInfo — 規則重疊
    // ================================================================

    /**
     * 描述兩條（或多條）規則的超矩形有非空交集。
     * 在 FIRST hitPolicy 下，重疊意味著可能的優先權衝突。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OverlapInfo {

        /**
         * 重疊的規則 ID 列表。
         */
        private List<String> ruleIds;

        /**
         * 交集區域的條件描述。
         * Key = 欄位名稱，Value = 重疊區間描述。
         */
        private Map<String, String> intersection;

        /**
         * 人類可讀的描述訊息。
         */
        private String message;
    }

    // ================================================================
    // SimplificationHint — 規則合併建議
    // ================================================================

    /**
     * 建議哪些規則可以合併以簡化決策表。
     * 合併條件：兩條規則結果（outputs）相同，且超矩形在某個維度上相鄰，其餘維度完全相同。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SimplificationHint {

        /**
         * 建議合併的規則 ID 列表。
         */
        private List<String> ruleIds;

        /**
         * 合併建議的描述。
         */
        private String suggestion;
    }
}
