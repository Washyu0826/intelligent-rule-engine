package com.ruleengine.rules.service;

import com.ruleengine.rules.service.SpecLintService.LintFinding;
import com.ruleengine.rules.service.SpecLintService.LintReport;
import com.ruleengine.rules.service.generator.ConditionOverlapDetector;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpecLintService 單元測試。
 *
 * 所有描述都經真實 {@link DescriptionDimensionParser} 解析後再 lint，
 * 確保「描述 → 維度 → 預檢」整條路徑正確，而非只測 lint 本身。
 */
@DisplayName("SpecLintService - 描述層邏輯預檢")
class SpecLintServiceTest {

    private SpecLintService lint;

    @BeforeEach
    void setUp() {
        lint = new SpecLintService(new DescriptionDimensionParser(), new ConditionOverlapDetector());
    }

    private static boolean has(LintReport r, String code) {
        return r.findings().stream().anyMatch(f -> code.equals(f.code()));
    }

    // ================================================================
    // 邊界
    // ================================================================

    @Test
    @DisplayName("null / 空白描述 → 無 finding、無 error")
    void emptyDescription() {
        assertFalse(lint.lint(null).hasError());
        assertTrue(lint.lint("   ").findings().isEmpty());
    }

    @Test
    @DisplayName("完整且自洽的描述 → 無 finding")
    void cleanDescription() {
        LintReport r = lint.lint("年齡（18-30／31-50／51-99）。輸出：核保決議（核准／婉拒）");
        assertTrue(r.findings().isEmpty(), "乾淨描述不應有任何 finding，實得：" + r.findings());
        assertFalse(r.hasError());
    }

    // ================================================================
    // CONTRADICTORY_RANGE（唯一 ERROR 級）
    // ================================================================

    @Test
    @DisplayName("區間起點大於終點 → CONTRADICTORY_RANGE + hasError")
    void contradictoryRange() {
        LintReport r = lint.lint("年齡（60-30）。輸出：決議（准／拒）");
        assertTrue(has(r, SpecLintService.CONTRADICTORY_RANGE));
        assertTrue(r.hasError());
        LintFinding f = r.findings().stream()
                .filter(x -> SpecLintService.CONTRADICTORY_RANGE.equals(x.code())).findFirst().orElseThrow();
        assertEquals(SpecLintService.ERROR, f.severity());
        assertEquals("年齡", f.dimension());
    }

    // ================================================================
    // OVERLAPPING_RANGE
    // ================================================================

    @Test
    @DisplayName("同維度數值區間重疊 → OVERLAPPING_RANGE（WARNING，非 error）")
    void overlappingRange() {
        LintReport r = lint.lint("年齡（18-30／25-40）。輸出：決議（准／拒）");
        assertTrue(has(r, SpecLintService.OVERLAPPING_RANGE));
        assertFalse(r.hasError(), "重疊只是 WARNING，不應 hasError");
    }

    @Test
    @DisplayName("不相交的區間 → 不報 OVERLAPPING_RANGE")
    void disjointRangesNoOverlap() {
        LintReport r = lint.lint("年齡（18-30／31-50）。輸出：決議（准／拒）");
        assertFalse(has(r, SpecLintService.OVERLAPPING_RANGE));
    }

    // ================================================================
    // EMPTY_VALUE_DOMAIN
    // ================================================================

    @Test
    @DisplayName("ENUM 維度沒列允許值 → EMPTY_VALUE_DOMAIN")
    void emptyEnumDomain() {
        // [受理通路] 經 bracket 解析為 ENUM 且無值
        LintReport r = lint.lint("若[受理通路]不符就擋件。輸出：決議（准／拒）");
        assertTrue(has(r, SpecLintService.EMPTY_VALUE_DOMAIN));
    }

    // ================================================================
    // DUPLICATE_VALUE
    // ================================================================

    @Test
    @DisplayName("同維度重複值 → DUPLICATE_VALUE")
    void duplicateValue() {
        LintReport r = lint.lint("性別（男／女／男）。輸出：決議（准／拒）");
        assertTrue(has(r, SpecLintService.DUPLICATE_VALUE));
    }

    // ================================================================
    // NO_OUTPUT_ON_HIT
    // ================================================================

    @Test
    @DisplayName("有輸入但無輸出 → NO_OUTPUT_ON_HIT")
    void noOutputOnHit() {
        LintReport r = lint.lint("年齡（18-30／31-50）");
        assertTrue(has(r, SpecLintService.NO_OUTPUT_ON_HIT));
        assertFalse(r.hasError());
    }

    // ================================================================
    // UNBOUNDED_CARTESIAN
    // ================================================================

    @Test
    @DisplayName("笛卡爾積超過上限 → UNBOUNDED_CARTESIAN（INFO）")
    void unboundedCartesian() {
        // age(10) × bmi(10) × claimCount(6) = 600 > 500；三者皆有字典映射可存活去重
        LintReport r = lint.lint(
                "年齡（1／2／3／4／5／6／7／8／9／10）BMI（1／2／3／4／5／6／7／8／9／10）理賠次數（1／2／3／4／5／6）");
        assertTrue(has(r, SpecLintService.UNBOUNDED_CARTESIAN));
        assertFalse(r.hasError());
    }
}
