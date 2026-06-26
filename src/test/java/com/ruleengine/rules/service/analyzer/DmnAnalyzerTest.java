package com.ruleengine.rules.service.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DmnAnalyzer 單元測試 — 補上原本完全缺席的分析器覆蓋。
 *
 * 涵蓋：
 *   - 覆蓋率：n≤20 走 inclusion-exclusion 精確路徑、n>20 走 Monte Carlo
 *   - overlap / gap 偵測基本行為
 *   - expandCartesian 截斷透明化（truncatedRuleIds + summary 警示）
 */
@DisplayName("DmnAnalyzer - DMN 幾何分析")
class DmnAnalyzerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DmnAnalyzer analyzer = new DmnAnalyzer();

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ================================================================
    // 覆蓋率（inclusion-exclusion 精確路徑）
    // ================================================================

    @Test
    @DisplayName("互補的兩條規則 → 覆蓋率 1.0、無缺口")
    void complementaryRulesFullCoverage() {
        JsonNode envelope = json("""
            {"rule":{"inputs":[{"name":"age","typeRef":"INTEGER"}],
            "rules":[
              {"ruleId":"R1","conditions":[{"field":"age","operator":"lessThanOrEqual","value":60}],
               "results":[{"field":"decision","value":"approve"}]},
              {"ruleId":"R2","conditions":[{"field":"age","operator":"greaterThan","value":60}],
               "results":[{"field":"decision","value":"reject"}]}
            ]}}
            """);

        AnalysisResult result = analyzer.analyze(envelope);

        assertThat(result.getCoverageRate()).isEqualTo(1.0);
        assertThat(result.getGaps()).isEmpty();
        assertThat(result.getTruncatedRuleIds()).isEmpty();
    }

    @Test
    @DisplayName("部分覆蓋 → 覆蓋率 < 1.0 且回報缺口")
    void partialCoverageReportsGaps() {
        // age 全域被兩條規則撐到 [0,100]，但只有 [0,30] 被覆蓋條件涵蓋
        JsonNode envelope = json("""
            {"rule":{"inputs":[{"name":"age","typeRef":"INTEGER"}],
            "rules":[
              {"ruleId":"R1","conditions":[{"field":"age","operator":"between","value":[0,30]}],
               "results":[{"field":"decision","value":"approve"}]},
              {"ruleId":"R2","conditions":[{"field":"age","operator":"between","value":[90,100]}],
               "results":[{"field":"decision","value":"reject"}]}
            ]}}
            """);

        AnalysisResult result = analyzer.analyze(envelope);

        assertThat(result.getCoverageRate()).isLessThan(1.0);
        assertThat(result.getGaps()).isNotEmpty();
    }

    @Test
    @DisplayName("超過 20 條規則 → Monte Carlo 路徑仍回傳合理覆蓋率")
    void monteCarloPathForManyRules() {
        // 25 條規則：age 分帶 [i*4, i*4+3]，i=0..24 → 完整覆蓋 [0,99]
        String rules = IntStream.range(0, 25)
                .mapToObj(i -> """
                    {"ruleId":"R%d","conditions":[{"field":"age","operator":"between","value":[%d,%d]}],
                     "results":[{"field":"decision","value":"ok"}]}
                    """.formatted(i + 1, i * 4, i * 4 + 3))
                .collect(Collectors.joining(","));
        JsonNode envelope = json("""
            {"rule":{"inputs":[{"name":"age","typeRef":"INTEGER"}],"rules":[%s]}}
            """.formatted(rules));

        AnalysisResult result = analyzer.analyze(envelope);

        // 值域邊界含 10% padding：[0,99] → [0,109]，故理論覆蓋率 = 110 點中蓋 100 點 ≈ 0.909。
        // Monte Carlo 固定 seed(42)，給 ±3% 容差。
        assertThat(result.getCoverageRate()).isBetween(0.88, 0.94);
        assertThat(result.getTotalRules()).isEqualTo(25);

        // 固定 seed → 重跑結果必須一致（可重現性）
        AnalysisResult second = analyzer.analyze(envelope);
        assertThat(second.getCoverageRate()).isEqualTo(result.getCoverageRate());
    }

    // ================================================================
    // Overlap 偵測
    // ================================================================

    @Test
    @DisplayName("區間重疊的兩條規則 → 回報 overlap")
    void overlappingRulesDetected() {
        JsonNode envelope = json("""
            {"rule":{"inputs":[{"name":"age","typeRef":"INTEGER"}],
            "rules":[
              {"ruleId":"R1","conditions":[{"field":"age","operator":"between","value":[0,50]}],
               "results":[{"field":"decision","value":"approve"}]},
              {"ruleId":"R2","conditions":[{"field":"age","operator":"between","value":[40,100]}],
               "results":[{"field":"decision","value":"reject"}]}
            ]}}
            """);

        AnalysisResult result = analyzer.analyze(envelope);

        assertThat(result.getOverlaps()).isNotEmpty();
        assertThat(result.getOverlaps().get(0).getRuleIds())
                .containsExactlyInAnyOrder("R1", "R2");
    }

    @Test
    @DisplayName("互斥規則 → 無 overlap")
    void disjointRulesNoOverlap() {
        JsonNode envelope = json("""
            {"rule":{"inputs":[{"name":"age","typeRef":"INTEGER"}],
            "rules":[
              {"ruleId":"R1","conditions":[{"field":"age","operator":"between","value":[0,49]}],
               "results":[{"field":"decision","value":"approve"}]},
              {"ruleId":"R2","conditions":[{"field":"age","operator":"between","value":[50,100]}],
               "results":[{"field":"decision","value":"reject"}]}
            ]}}
            """);

        AnalysisResult result = analyzer.analyze(envelope);

        assertThat(result.getOverlaps()).isEmpty();
    }

    // ================================================================
    // 截斷透明化（v3.16：原本靜默截斷 → 必須回報）
    // ================================================================

    @Test
    @DisplayName("IN 笛卡爾積超過上限 → truncatedRuleIds 回報且 summary 警示")
    void cartesianExplosionReportsTruncation() {
        // 3 個欄位各 IN 11 個值 → 11^3 = 1331 > 1000 上限
        String inValues = IntStream.rangeClosed(1, 11)
                .mapToObj(String::valueOf).collect(Collectors.joining(","));
        JsonNode envelope = json("""
            {"rule":{"inputs":[
              {"name":"a","typeRef":"INTEGER"},
              {"name":"b","typeRef":"INTEGER"},
              {"name":"c","typeRef":"INTEGER"}],
            "rules":[
              {"ruleId":"BIG","conditions":[
                {"field":"a","operator":"in","value":[%s]},
                {"field":"b","operator":"in","value":[%s]},
                {"field":"c","operator":"in","value":[%s]}],
               "results":[{"field":"decision","value":"x"}]}
            ]}}
            """.formatted(inValues, inValues, inValues));

        AnalysisResult result = analyzer.analyze(envelope);

        assertThat(result.getTruncatedRuleIds()).containsExactly("BIG");
        assertThat(result.getSummary()).contains("不完整");
    }

    @Test
    @DisplayName("IN 組合在上限內 → 不標記截斷")
    void smallCartesianNotTruncated() {
        JsonNode envelope = json("""
            {"rule":{"inputs":[
              {"name":"a","typeRef":"INTEGER"},
              {"name":"b","typeRef":"INTEGER"}],
            "rules":[
              {"ruleId":"OK","conditions":[
                {"field":"a","operator":"in","value":[1,2,3]},
                {"field":"b","operator":"in","value":[1,2,3]}],
               "results":[{"field":"decision","value":"x"}]}
            ]}}
            """);

        AnalysisResult result = analyzer.analyze(envelope);

        assertThat(result.getTruncatedRuleIds()).isEmpty();
        assertThat(result.getSummary()).doesNotContain("不完整");
    }

    // ================================================================
    // 邊界
    // ================================================================

    @Test
    @DisplayName("空 rules → emptyResult 不拋例外")
    void emptyRulesReturnsEmptyResult() {
        JsonNode envelope = json("""
            {"rule":{"inputs":[{"name":"age","typeRef":"INTEGER"}],"rules":[]}}
            """);

        AnalysisResult result = analyzer.analyze(envelope);

        assertThat(result.getCoverageRate()).isEqualTo(0.0);
        assertThat(result.getTotalRules()).isZero();
    }
}
