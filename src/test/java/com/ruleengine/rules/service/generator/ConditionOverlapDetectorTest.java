package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConditionOverlapDetector 單元測試。
 *
 * 驗證條件互斥判斷的核心邏輯：
 * - 數值區間轉換 (toNumericRange)
 * - 互斥判斷 (areMutuallyExclusive)
 * - 集合操作 (setsIntersect, valueInSet, isSubset)
 * - 規則重疊偵測 (canOverlap, hasAnyOverlap)
 */
@DisplayName("ConditionOverlapDetector - 條件重疊偵測")
class ConditionOverlapDetectorTest {

    private ConditionOverlapDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ConditionOverlapDetector();
    }

    // ================================================================
    // toNumericRange
    // ================================================================

    @Test
    @DisplayName("equals 25 → [25, 25]")
    void toNumericRangeEquals() {
        Condition c = cond("age", "equals", 25);
        double[] range = detector.toNumericRange(c);
        assertNotNull(range);
        assertEquals(25.0, range[0]);
        assertEquals(25.0, range[1]);
    }

    @Test
    @DisplayName("between [18, 35] → [18, 35]")
    void toNumericRangeBetween() {
        Condition c = cond("age", "between", List.of(18, 35));
        double[] range = detector.toNumericRange(c);
        assertNotNull(range);
        assertEquals(18.0, range[0]);
        assertEquals(35.0, range[1]);
    }

    @Test
    @DisplayName("greaterThan 65 → [65+ε, MAX]")
    void toNumericRangeGreaterThan() {
        Condition c = cond("age", "greaterThan", 65);
        double[] range = detector.toNumericRange(c);
        assertNotNull(range);
        assertTrue(range[0] > 65.0);
        assertEquals(Double.MAX_VALUE, range[1]);
    }

    @Test
    @DisplayName("lessThanOrEqual 18 → [-MAX, 18]")
    void toNumericRangeLessThanOrEqual() {
        Condition c = cond("age", "lessThanOrEqual", 18);
        double[] range = detector.toNumericRange(c);
        assertNotNull(range);
        assertEquals(-Double.MAX_VALUE, range[0]);
        assertEquals(18.0, range[1]);
    }

    @Test
    @DisplayName("in 操作無法轉為數值區間 → null")
    void toNumericRangeInReturnsNull() {
        Condition c = cond("level", "in", List.of("A", "B"));
        assertNull(detector.toNumericRange(c));
    }

    @Test
    @DisplayName("null value → null")
    void toNumericRangeNullValue() {
        Condition c = Condition.builder().field("age").operator("equals").build();
        assertNull(detector.toNumericRange(c));
    }

    // ================================================================
    // areMutuallyExclusive — 數值區間
    // ================================================================

    @Test
    @DisplayName("不重疊的區間 → 互斥")
    void exclusiveNonOverlappingRanges() {
        Condition a = cond("age", "between", List.of(18, 35));
        Condition b = cond("age", "between", List.of(36, 50));
        assertTrue(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("重疊的區間 → 不互斥")
    void nonExclusiveOverlappingRanges() {
        Condition a = cond("age", "between", List.of(18, 35));
        Condition b = cond("age", "between", List.of(30, 50));
        assertFalse(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("equals 25 vs between [18,35] → 不互斥（25 在區間內）")
    void equalsWithinBetween() {
        Condition a = cond("age", "equals", 25);
        Condition b = cond("age", "between", List.of(18, 35));
        assertFalse(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("equals 50 vs between [18,35] → 互斥")
    void equalsOutsideBetween() {
        Condition a = cond("age", "equals", 50);
        Condition b = cond("age", "between", List.of(18, 35));
        assertTrue(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("greaterThan 50 vs lessThan 30 → 互斥")
    void greaterThanVsLessThan() {
        Condition a = cond("age", "greaterThan", 50);
        Condition b = cond("age", "lessThan", 30);
        assertTrue(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("greaterThan 30 vs lessThan 50 → 不互斥（有重疊）")
    void greaterThanOverlapsLessThan() {
        Condition a = cond("age", "greaterThan", 30);
        Condition b = cond("age", "lessThan", 50);
        assertFalse(detector.areMutuallyExclusive(a, b));
    }

    // ================================================================
    // areMutuallyExclusive — 離散值
    // ================================================================

    @Test
    @DisplayName("equals 'A' vs equals 'B' → 互斥")
    void exclusiveEqualsDifferentValues() {
        Condition a = cond("level", "equals", "A");
        Condition b = cond("level", "equals", "B");
        assertTrue(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("equals 'A' vs equals 'A' → 不互斥")
    void nonExclusiveEqualsSameValue() {
        Condition a = cond("level", "equals", "A");
        Condition b = cond("level", "equals", "A");
        assertFalse(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("in [A,B] vs in [C,D] → 互斥（無交集）")
    void exclusiveInNoIntersection() {
        Condition a = cond("level", "in", List.of("A", "B"));
        Condition b = cond("level", "in", List.of("C", "D"));
        assertTrue(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("in [A,B] vs in [B,C] → 不互斥（B 是交集）")
    void nonExclusiveInWithIntersection() {
        Condition a = cond("level", "in", List.of("A", "B"));
        Condition b = cond("level", "in", List.of("B", "C"));
        assertFalse(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("equals 'A' vs in [B,C] → 互斥")
    void exclusiveEqualsVsIn() {
        Condition a = cond("level", "equals", "A");
        Condition b = cond("level", "in", List.of("B", "C"));
        assertTrue(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("equals 'A' vs notIn [A,B] → 互斥")
    void exclusiveEqualsVsNotIn() {
        Condition a = cond("level", "equals", "A");
        Condition b = cond("level", "notIn", List.of("A", "B"));
        assertTrue(detector.areMutuallyExclusive(a, b));
    }

    @Test
    @DisplayName("in [A,B] vs notIn [A,B,C] → 互斥（A,B 是 notIn 的子集）")
    void exclusiveInSubsetOfNotIn() {
        Condition a = cond("level", "in", List.of("A", "B"));
        Condition b = cond("level", "notIn", List.of("A", "B", "C"));
        assertTrue(detector.areMutuallyExclusive(a, b));
    }

    // ================================================================
    // canOverlap — 規則層級
    // ================================================================

    @Test
    @DisplayName("兩條規則所有欄位都重疊 → canOverlap = true")
    void canOverlapAllFieldsOverlap() {
        RuleRow a = row(cond("age", "between", List.of(18, 35)));
        RuleRow b = row(cond("age", "between", List.of(30, 50)));
        assertTrue(detector.canOverlap(a, b));
    }

    @Test
    @DisplayName("至少一個欄位互斥 → canOverlap = false")
    void cannotOverlapOneFieldExclusive() {
        RuleRow a = row(
                cond("age", "between", List.of(18, 35)),
                cond("level", "equals", "A"));
        RuleRow b = row(
                cond("age", "between", List.of(30, 50)),
                cond("level", "equals", "B"));
        assertFalse(detector.canOverlap(a, b));
    }

    @Test
    @DisplayName("anything 不造成互斥")
    void anythingDoesNotExclude() {
        RuleRow a = row(cond("age", "anything", null));
        RuleRow b = row(cond("age", "between", List.of(18, 35)));
        assertTrue(detector.canOverlap(a, b));
    }

    // ================================================================
    // hasAnyOverlap
    // ================================================================

    @Test
    @DisplayName("找到至少一對重疊 → true")
    void hasAnyOverlapFound() {
        RuleRow a = row(cond("age", "between", List.of(18, 35)));
        RuleRow b = row(cond("age", "between", List.of(30, 50)));
        assertTrue(detector.hasAnyOverlap(List.of(a, b)));
    }

    @Test
    @DisplayName("全部互斥 → false")
    void hasAnyOverlapNone() {
        RuleRow a = row(cond("age", "between", List.of(18, 35)));
        RuleRow b = row(cond("age", "between", List.of(36, 50)));
        assertFalse(detector.hasAnyOverlap(List.of(a, b)));
    }

    @Test
    @DisplayName("空列表 → false")
    void hasAnyOverlapEmpty() {
        assertFalse(detector.hasAnyOverlap(List.of()));
        assertFalse(detector.hasAnyOverlap(null));
    }

    // ================================================================
    // findAllOverlaps
    // ================================================================

    @Test
    @DisplayName("找出所有重疊的索引對")
    void findAllOverlaps() {
        RuleRow a = row(cond("age", "between", List.of(18, 35)));
        RuleRow b = row(cond("age", "between", List.of(30, 50)));
        RuleRow c = row(cond("age", "between", List.of(60, 80)));

        List<int[]> overlaps = detector.findAllOverlaps(List.of(a, b, c));
        assertEquals(1, overlaps.size());
        assertArrayEquals(new int[]{0, 1}, overlaps.get(0));
    }

    // ================================================================
    // 集合工具方法
    // ================================================================

    @Test
    @DisplayName("setsIntersect: [A,B] ∩ [B,C] → true")
    void setsIntersectTrue() {
        assertTrue(detector.setsIntersect(List.of("A", "B"), List.of("B", "C")));
    }

    @Test
    @DisplayName("setsIntersect: [A,B] ∩ [C,D] → false")
    void setsIntersectFalse() {
        assertFalse(detector.setsIntersect(List.of("A", "B"), List.of("C", "D")));
    }

    @Test
    @DisplayName("valueInSet: 'A' in [A,B] → true")
    void valueInSetTrue() {
        assertTrue(detector.valueInSet("A", List.of("A", "B")));
    }

    @Test
    @DisplayName("valueInSet: 'C' in [A,B] → false")
    void valueInSetFalse() {
        assertFalse(detector.valueInSet("C", List.of("A", "B")));
    }

    @Test
    @DisplayName("isSubset: [A,B] ⊆ [A,B,C] → true")
    void isSubsetTrue() {
        assertTrue(detector.isSubset(List.of("A", "B"), List.of("A", "B", "C")));
    }

    @Test
    @DisplayName("isSubset: [A,D] ⊆ [A,B,C] → false")
    void isSubsetFalse() {
        assertFalse(detector.isSubset(List.of("A", "D"), List.of("A", "B", "C")));
    }

    // ================================================================
    // isAnything
    // ================================================================

    @Test
    @DisplayName("anything operator → true")
    void isAnythingTrue() {
        assertTrue(detector.isAnything(cond("x", "anything", null)));
    }

    @Test
    @DisplayName("null condition → true")
    void isAnythingNull() {
        assertTrue(detector.isAnything(null));
    }

    @Test
    @DisplayName("equals operator → false")
    void isAnythingFalse() {
        assertFalse(detector.isAnything(cond("x", "equals", 1)));
    }

    // ================================================================
    // Helper
    // ================================================================

    private Condition cond(String field, String operator, Object value) {
        return Condition.builder().field(field).operator(operator).value(value).build();
    }

    private RuleRow row(Condition... conditions) {
        return RuleRow.builder()
                .conditions(new ArrayList<>(List.of(conditions)))
                .results(new ArrayList<>())
                .build();
    }
}
