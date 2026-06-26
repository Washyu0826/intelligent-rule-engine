package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EvaluationComputer 單元測試。
 *
 * 驗證 evaluation 指標計算：
 * - completeness (COMPLETE/INCOMPLETE)
 * - totalScenarios (規則數)
 * - coverageRate (笛卡爾積估算)
 * - conflictDetection (FIRST hitPolicy 下的重疊偵測)
 * - recommendedStrategy
 */
@DisplayName("EvaluationComputer - Evaluation 指標計算")
class EvaluationComputerTest {

    private EvaluationComputer computer;

    @BeforeEach
    void setUp() {
        ConditionOverlapDetector overlapDetector = new ConditionOverlapDetector();
        computer = new EvaluationComputer(overlapDetector);
    }

    // ================================================================
    // null / 空規則
    // ================================================================

    @Test
    @DisplayName("rule 為 null → INCOMPLETE, 0 scenarios")
    void nullRule() {
        RuleEnvelope envelope = RuleEnvelope.builder().build();
        computer.computeEvaluation(envelope);

        Evaluation eval = envelope.getEvaluation();
        assertNotNull(eval);
        assertEquals("INCOMPLETE", eval.getCompleteness());
        assertEquals(0, eval.getTotalScenarios());
        assertEquals(0.0, eval.getCoverageRate());
        assertEquals("NO_CONFLICT", eval.getConflictDetection());
    }

    @Test
    @DisplayName("空 rules 列表 → INCOMPLETE, 0 scenarios")
    void emptyRules() {
        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(
                        FieldDef.builder().name("age").typeRef("INTEGER").build())))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>())
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals("INCOMPLETE", envelope.getEvaluation().getCompleteness());
        assertEquals(0, envelope.getEvaluation().getTotalScenarios());
    }

    // ================================================================
    // BOOLEAN 欄位的笛卡爾積（2 值）
    // ================================================================

    @Test
    @DisplayName("1 個 BOOLEAN input + 2 條規則 → coverage 1.0, COMPLETE")
    void booleanFullCoverage() {
        FieldDef input = FieldDef.builder().name("active").typeRef("BOOLEAN").build();
        RuleRow row1 = makeRow("active", "equals", true);
        RuleRow row2 = makeRow("active", "equals", false);

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals(2, envelope.getEvaluation().getTotalScenarios());
        assertEquals(1.0, envelope.getEvaluation().getCoverageRate());
        assertEquals("COMPLETE", envelope.getEvaluation().getCompleteness());
    }

    @Test
    @DisplayName("1 個 BOOLEAN input + 1 條規則 → coverage 0.5")
    void booleanPartialCoverage() {
        FieldDef input = FieldDef.builder().name("active").typeRef("BOOLEAN").build();
        RuleRow row1 = makeRow("active", "equals", true);

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals(1, envelope.getEvaluation().getTotalScenarios());
        assertEquals(0.5, envelope.getEvaluation().getCoverageRate());
    }

    // ================================================================
    // ENUM 欄位的笛卡爾積
    // ================================================================

    @Test
    @DisplayName("ENUM 3 值 + 3 條規則 → coverage 1.0")
    void enumFullCoverage() {
        FieldDef input = FieldDef.builder()
                .name("level").typeRef("ENUM")
                .allowedValues(new ArrayList<>(List.of("gold", "silver", "bronze")))
                .build();
        RuleRow row1 = makeRow("level", "equals", "gold");
        RuleRow row2 = makeRow("level", "equals", "silver");
        RuleRow row3 = makeRow("level", "equals", "bronze");

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2, row3)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals(3, envelope.getEvaluation().getTotalScenarios());
        assertEquals(1.0, envelope.getEvaluation().getCoverageRate());
    }

    // ================================================================
    // 多維笛卡爾積（BOOLEAN × ENUM = 2 × 3 = 6）
    // ================================================================

    @Test
    @DisplayName("BOOLEAN × ENUM(3) = 6 組合，3 條規則 → coverage 0.5")
    void multiDimensionCoverage() {
        FieldDef boolInput = FieldDef.builder().name("active").typeRef("BOOLEAN").build();
        FieldDef enumInput = FieldDef.builder()
                .name("level").typeRef("ENUM")
                .allowedValues(new ArrayList<>(List.of("A", "B", "C")))
                .build();

        RuleRow row1 = makeRow2("active", "equals", true, "level", "equals", "A");
        RuleRow row2 = makeRow2("active", "equals", false, "level", "equals", "B");
        RuleRow row3 = makeRow2("active", "equals", true, "level", "equals", "C");

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(boolInput, enumInput)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2, row3)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals(3, envelope.getEvaluation().getTotalScenarios());
        assertEquals(0.5, envelope.getEvaluation().getCoverageRate()); // 3/6 = 0.5
    }

    // ================================================================
    // anything 維度不計入笛卡爾積
    // ================================================================

    @Test
    @DisplayName("所有規則的某欄位都是 anything → 該欄位不計入笛卡爾積")
    void anythingDimensionExcluded() {
        FieldDef ageInput = FieldDef.builder().name("age").typeRef("BOOLEAN").build();
        FieldDef genderInput = FieldDef.builder().name("gender").typeRef("BOOLEAN").build();

        // gender 在所有規則中都是 anything
        RuleRow row1 = RuleRow.builder().conditions(new ArrayList<>(List.of(
                Condition.builder().field("age").operator("equals").value(true).build(),
                Condition.builder().field("gender").operator("anything").build()
        ))).results(new ArrayList<>()).build();
        RuleRow row2 = RuleRow.builder().conditions(new ArrayList<>(List.of(
                Condition.builder().field("age").operator("equals").value(false).build(),
                Condition.builder().field("gender").operator("anything").build()
        ))).results(new ArrayList<>()).build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(ageInput, genderInput)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        // gender 是 anything → 只算 age 的 2 種值 → 2 條規則 → coverage 1.0
        assertEquals(1.0, envelope.getEvaluation().getCoverageRate());
        assertEquals("COMPLETE", envelope.getEvaluation().getCompleteness());
    }

    // ================================================================
    // 衝突偵測（FIRST hitPolicy）
    // ================================================================

    @Test
    @DisplayName("FIRST hitPolicy + 重疊條件 → HAS_CONFLICT")
    void conflictDetectionFirstOverlap() {
        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        // R01: age between [18, 35], R02: age between [30, 50] → 重疊 [30, 35]
        RuleRow row1 = RuleRow.builder().conditions(new ArrayList<>(List.of(
                Condition.builder().field("age").operator("between").value(List.of(18, 35)).build()
        ))).results(new ArrayList<>()).build();
        RuleRow row2 = RuleRow.builder().conditions(new ArrayList<>(List.of(
                Condition.builder().field("age").operator("between").value(List.of(30, 50)).build()
        ))).results(new ArrayList<>()).build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals("HAS_CONFLICT", envelope.getEvaluation().getConflictDetection());
    }

    @Test
    @DisplayName("FIRST hitPolicy + 互斥條件 → NO_CONFLICT")
    void conflictDetectionFirstNoOverlap() {
        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        RuleRow row1 = RuleRow.builder().conditions(new ArrayList<>(List.of(
                Condition.builder().field("age").operator("between").value(List.of(18, 35)).build()
        ))).results(new ArrayList<>()).build();
        RuleRow row2 = RuleRow.builder().conditions(new ArrayList<>(List.of(
                Condition.builder().field("age").operator("between").value(List.of(36, 50)).build()
        ))).results(new ArrayList<>()).build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals("NO_CONFLICT", envelope.getEvaluation().getConflictDetection());
    }

    @Test
    @DisplayName("MULTI hitPolicy → 不做衝突偵測")
    void multiHitPolicyNoConflictCheck() {
        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        // 即使有重疊，MULTI 也不報衝突
        RuleRow row1 = RuleRow.builder().conditions(new ArrayList<>(List.of(
                Condition.builder().field("age").operator("between").value(List.of(18, 35)).build()
        ))).results(new ArrayList<>()).build();
        RuleRow row2 = RuleRow.builder().conditions(new ArrayList<>(List.of(
                Condition.builder().field("age").operator("between").value(List.of(30, 50)).build()
        ))).results(new ArrayList<>()).build();

        Rule rule = Rule.builder()
                .hitPolicy("MULTI")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals("NO_CONFLICT", envelope.getEvaluation().getConflictDetection());
        assertEquals("MULTI", envelope.getEvaluation().getRecommendedStrategy());
    }

    // ================================================================
    // 數值型分段估算
    // ================================================================

    @Test
    @DisplayName("INTEGER between 分段 → 正確估算笛卡爾積")
    void integerSegmentEstimation() {
        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        // 3 個不同的 between 區間 + 1 個 greaterThan → 4 段
        RuleRow row1 = makeRow("age", "between", List.of(18, 35));
        RuleRow row2 = makeRow("age", "between", List.of(36, 50));
        RuleRow row3 = makeRow("age", "between", List.of(51, 65));
        RuleRow row4 = makeRow("age", "greaterThan", 65);

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2, row3, row4)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        computer.computeEvaluation(envelope);

        assertEquals(4, envelope.getEvaluation().getTotalScenarios());
        assertEquals(1.0, envelope.getEvaluation().getCoverageRate()); // 4/4 = 1.0
        assertEquals("COMPLETE", envelope.getEvaluation().getCompleteness());
    }

    // ================================================================
    // Helper
    // ================================================================

    private RuleEnvelope envelopeWith(Rule rule) {
        return RuleEnvelope.builder().ruleType("DecisionTable").rule(rule).build();
    }

    private RuleRow makeRow(String field, String operator, Object value) {
        return RuleRow.builder()
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field(field).operator(operator).value(value).build())))
                .results(new ArrayList<>())
                .build();
    }

    private RuleRow makeRow2(String f1, String op1, Object v1, String f2, String op2, Object v2) {
        return RuleRow.builder()
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field(f1).operator(op1).value(v1).build(),
                        Condition.builder().field(f2).operator(op2).value(v2).build())))
                .results(new ArrayList<>())
                .build();
    }
}
