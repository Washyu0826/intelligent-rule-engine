package com.ruleengine.rules.service.analyzer;

import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HyperRectangle 幾何運算單元測試 — 補上原本缺席的核心幾何覆蓋。
 */
@DisplayName("HyperRectangle - 幾何運算")
class HyperRectangleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static HyperRectangle rect(String id, double[][] intervals) {
        return new HyperRectangle(id, List.of("x", "y"), intervals);
    }

    // ================================================================
    // 基本幾何運算
    // ================================================================

    @Test
    @DisplayName("intersects/intersection：部分重疊")
    void partialOverlapIntersection() {
        HyperRectangle a = rect("A", new double[][]{{0, 10}, {0, 10}});
        HyperRectangle b = rect("B", new double[][]{{5, 15}, {5, 15}});

        assertThat(a.intersects(b)).isTrue();
        HyperRectangle inter = a.intersection(b);
        assertThat(inter).isNotNull();
        assertThat(inter.getIntervals()[0]).containsExactly(5, 10);
        assertThat(inter.getIntervals()[1]).containsExactly(5, 10);
    }

    @Test
    @DisplayName("intersects：任一維不相交即整體不相交")
    void disjointOnOneDimension() {
        HyperRectangle a = rect("A", new double[][]{{0, 10}, {0, 10}});
        HyperRectangle b = rect("B", new double[][]{{5, 15}, {11, 20}});

        assertThat(a.intersects(b)).isFalse();
        assertThat(a.intersection(b)).isNull();
    }

    @Test
    @DisplayName("contains：完全包含 vs 部分重疊")
    void containsSemantics() {
        HyperRectangle outer = rect("O", new double[][]{{0, 10}, {0, 10}});
        HyperRectangle inner = rect("I", new double[][]{{2, 8}, {2, 8}});
        HyperRectangle partial = rect("P", new double[][]{{5, 15}, {5, 8}});

        assertThat(outer.contains(inner)).isTrue();
        assertThat(inner.contains(outer)).isFalse();
        assertThat(outer.contains(partial)).isFalse();
    }

    @Test
    @DisplayName("volume：離散維度 +1、連續維度直接寬度")
    void volumeDiscreteVsContinuous() {
        HyperRectangle r = rect("V", new double[][]{{0, 4}, {0, 2}});

        // 兩維皆離散：(4-0+1) × (2-0+1) = 15
        assertThat(r.volume(new boolean[]{true, true})).isEqualTo(15.0);
        // 兩維皆連續：4 × 2 = 8
        assertThat(r.volume(new boolean[]{false, false})).isEqualTo(8.0);
    }

    // ================================================================
    // fromJsonRuleWithInfo：截斷透明化
    // ================================================================

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<FieldDef> intFields(String... names) {
        return List.of(names).stream()
                .map(n -> FieldDef.builder().name(n).typeRef("INTEGER").build())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("IN 笛卡爾積超過 MAX_EXPANSION → truncated=true 且只展開上限數量")
    void expansionTruncatedAtLimit() {
        String inValues = IntStream.rangeClosed(1, 11)
                .mapToObj(String::valueOf).collect(Collectors.joining(","));
        JsonNode conditions = json("""
            [{"field":"a","operator":"in","value":[%s]},
             {"field":"b","operator":"in","value":[%s]},
             {"field":"c","operator":"in","value":[%s]}]
            """.formatted(inValues, inValues, inValues));
        double[][] bounds = {{1, 11}, {1, 11}, {1, 11}};

        HyperRectangle.Expansion expansion = HyperRectangle.fromJsonRuleWithInfo(
                "BIG", conditions, intFields("a", "b", "c"), Map.of(), bounds);

        assertThat(expansion.truncated()).isTrue();
        assertThat(expansion.totalCombinations()).isEqualTo(11L * 11 * 11);
        assertThat(expansion.rects()).hasSize(HyperRectangle.MAX_EXPANSION);
    }

    @Test
    @DisplayName("組合在上限內 → truncated=false 且完整展開")
    void expansionWithinLimit() {
        JsonNode conditions = json("""
            [{"field":"a","operator":"in","value":[1,2,3]},
             {"field":"b","operator":"in","value":[4,5]}]
            """);
        double[][] bounds = {{1, 3}, {4, 5}};

        HyperRectangle.Expansion expansion = HyperRectangle.fromJsonRuleWithInfo(
                "OK", conditions, intFields("a", "b"), Map.of(), bounds);

        assertThat(expansion.truncated()).isFalse();
        assertThat(expansion.totalCombinations()).isEqualTo(6);
        assertThat(expansion.rects()).hasSize(6);
    }

    @Test
    @DisplayName("單一區間條件走快速路徑 → 一個超矩形、不截斷")
    void singleIntervalFastPath() {
        JsonNode conditions = json("""
            [{"field":"a","operator":"between","value":[10,20]},
             {"field":"b","operator":"greaterThan","value":5}]
            """);
        double[][] bounds = {{0, 100}, {0, 100}};

        HyperRectangle.Expansion expansion = HyperRectangle.fromJsonRuleWithInfo(
                "S", conditions, intFields("a", "b"), Map.of(), bounds);

        assertThat(expansion.truncated()).isFalse();
        assertThat(expansion.rects()).hasSize(1);
        assertThat(expansion.rects().get(0).getIntervals()[0]).containsExactly(10, 20);
    }
}
