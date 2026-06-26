package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.RuleEnvelopeExtensions;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.ruleengine.rules.domain.extensions.FieldOrSpec;
import com.ruleengine.rules.domain.extensions.Footnote;
import com.ruleengine.rules.domain.extensions.GlobalGuard;
import com.ruleengine.rules.domain.extensions.RuleGrouping;
import com.ruleengine.rules.domain.extensions.RuleStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvelopeNormalizer 單元測試。
 *
 * 驗證 AI 常見生成錯誤的自動修復：
 * - typeRef alias 修正
 * - operator alias 修正
 * - BOOLEAN 字串修復
 * - INTEGER/DECIMAL 字串轉數值
 * - ENUM allowedValues 自動推斷
 * - 規則去重
 * - priority 排序
 * - hitPolicy 正規化
 */
@DisplayName("EnvelopeNormalizer - 正規化邏輯")
class EnvelopeNormalizerTest {

    private EnvelopeNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new EnvelopeNormalizer();
    }

    // ================================================================
    // typeRef alias 修正
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "int, INTEGER",
            "INT, INTEGER",
            "LONG, INTEGER",
            "NUMBER, INTEGER",
            "double, DECIMAL",
            "FLOAT, DECIMAL",
            "DEC, DECIMAL",
            "BIGDECIMAL, DECIMAL",
            "bool, BOOLEAN",
            "BOOL, BOOLEAN",
            "str, STRING",
            "TEXT, STRING",
            "VARCHAR, STRING",
            "ENUMERATION, ENUM",
            "LOCALDATE, DATE",
            // v3.14: DATETIME 不再 collapse 到 DATE，而是路由到 TIMESTAMP，
            // 保留 time-of-day 語義（修 M1 risk register #1）。
            "DATETIME, TIMESTAMP",
            "INTEGER, INTEGER",
            "DECIMAL, DECIMAL",
            "BOOLEAN, BOOLEAN",
            "STRING, STRING",
            "ENUM, ENUM",
            "DATE, DATE"
    })
    @DisplayName("typeRef alias → 正確型別")
    void normalizeTypeRef(String input, String expected) {
        assertEquals(expected, normalizer.normalizeTypeRef(input));
    }

    @Test
    @DisplayName("typeRef null → null")
    void normalizeTypeRefNull() {
        assertNull(normalizer.normalizeTypeRef(null));
    }

    // ================================================================
    // operator alias 修正
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "eq, equals",
            "equal, equals",
            "=, equals",
            "==, equals",
            "neq, notEquals",
            "!=, notEquals",
            "<>, notEquals",
            "gt, greaterThan",
            ">, greaterThan",
            "gte, greaterThanOrEqual",
            ">=, greaterThanOrEqual",
            "lt, lessThan",
            "<, lessThan",
            "lte, lessThanOrEqual",
            "<=, lessThanOrEqual",
            "range, between",
            "oneof, in",
            "one_of, in",
            "notoneof, notIn",
            "null, isNull",
            "isnull, isNull",
            "notnull, isNotNull",
            "any, anything",
            "*, anything",
            "all, anything",
            "equals, equals",
            "between, between"
    })
    @DisplayName("operator alias → 正確 operator")
    void normalizeOperator(String input, String expected) {
        assertEquals(expected, normalizer.normalizeOperator(input));
    }

    @Test
    @DisplayName("不認識的 operator → 保留原值")
    void normalizeOperatorUnknown() {
        assertEquals("like", normalizer.normalizeOperator("like"));
    }

    @Test
    @DisplayName("operator null → null")
    void normalizeOperatorNull() {
        assertNull(normalizer.normalizeOperator(null));
    }

    // ================================================================
    // 完整 normalize() 流程
    // ================================================================

    @Test
    @DisplayName("補版本號：schemaVersion + promptVersion")
    void normalizeAddsVersions() {
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(emptyRule())
                .build();

        normalizer.normalize(envelope, "1.0.0", "p2.0.0");

        assertEquals("1.0.0", envelope.getSchemaVersion());
        assertEquals("p2.0.0", envelope.getPromptVersion());
    }

    @Test
    @DisplayName("不覆蓋已存在的版本號")
    void normalizeKeepsExistingVersions() {
        RuleEnvelope envelope = RuleEnvelope.builder()
                .schemaVersion("2.0.0")
                .promptVersion("p3.0.0")
                .ruleType("DecisionTable")
                .rule(emptyRule())
                .build();

        normalizer.normalize(envelope, "1.0.0", "p2.0.0");

        assertEquals("2.0.0", envelope.getSchemaVersion());
        assertEquals("p3.0.0", envelope.getPromptVersion());
    }

    @Test
    @DisplayName("hitPolicy null → 預設 FIRST")
    void normalizeDefaultHitPolicy() {
        Rule rule = Rule.builder().build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("FIRST", envelope.getRule().getHitPolicy());
    }

    @Test
    @DisplayName("hitPolicy 小寫 → 大寫")
    void normalizeHitPolicyUpperCase() {
        Rule rule = Rule.builder().hitPolicy("multi").build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("MULTI", envelope.getRule().getHitPolicy());
    }

    @Test
    @DisplayName("自動補 ruleId + priority")
    void normalizeAutoRuleIdAndPriority() {
        // 兩條規則必須有不同的 conditions/results，否則會被去重
        RuleRow row1 = RuleRow.builder()
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field("a").operator("equals").value(1).build())))
                .results(new ArrayList<>()).build();
        RuleRow row2 = RuleRow.builder()
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field("a").operator("equals").value(2).build())))
                .results(new ArrayList<>()).build();
        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(
                        FieldDef.builder().name("a").typeRef("INTEGER").build())))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("R01", rule.getRules().get(0).getRuleId());
        assertEquals("R02", rule.getRules().get(1).getRuleId());
        assertEquals(1, rule.getRules().get(0).getPriority());
        assertEquals(2, rule.getRules().get(1).getPriority());
    }

    @Test
    @DisplayName("不覆蓋已存在的 ruleId")
    void normalizeKeepsExistingRuleId() {
        RuleRow row = RuleRow.builder()
                .ruleId("CUSTOM-01")
                .conditions(new ArrayList<>())
                .results(new ArrayList<>())
                .build();
        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>())
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("CUSTOM-01", rule.getRules().get(0).getRuleId());
    }

    // ================================================================
    // BOOLEAN 字串修復
    // ================================================================

    @Test
    @DisplayName("BOOLEAN condition value 字串 'true' → boolean true")
    void repairBooleanStringTrue() {
        Condition cond = Condition.builder()
                .field("hypertension").operator("equals").value("true").build();
        FieldDef input = FieldDef.builder().name("hypertension").typeRef("BOOLEAN").build();
        Rule rule = ruleWithCondition(cond, input);
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        Object val = rule.getRules().get(0).getConditions().get(0).getValue();
        assertInstanceOf(Boolean.class, val);
        assertEquals(true, val);
    }

    @Test
    @DisplayName("BOOLEAN condition value 字串 'FALSE' → boolean false")
    void repairBooleanStringFalse() {
        Condition cond = Condition.builder()
                .field("active").operator("equals").value("FALSE").build();
        FieldDef input = FieldDef.builder().name("active").typeRef("BOOLEAN").build();
        Rule rule = ruleWithCondition(cond, input);
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        Object val = rule.getRules().get(0).getConditions().get(0).getValue();
        assertEquals(false, val);
    }

    // ================================================================
    // INTEGER/DECIMAL 字串轉數值
    // ================================================================

    @Test
    @DisplayName("INTEGER condition value 字串 '25' → long 25")
    void repairIntegerString() {
        Condition cond = Condition.builder()
                .field("age").operator("equals").value("25").build();
        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        Rule rule = ruleWithCondition(cond, input);
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        Object val = rule.getRules().get(0).getConditions().get(0).getValue();
        assertEquals(25L, val);
    }

    @Test
    @DisplayName("DECIMAL condition value 字串 '1.5' → double 1.5")
    void repairDecimalString() {
        Condition cond = Condition.builder()
                .field("rate").operator("equals").value("1.5").build();
        FieldDef input = FieldDef.builder().name("rate").typeRef("DECIMAL").build();
        Rule rule = ruleWithCondition(cond, input);
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        Object val = rule.getRules().get(0).getConditions().get(0).getValue();
        assertEquals(1.5, val);
    }

    @Test
    @DisplayName("between 陣列中的字串也會被修復")
    void repairBetweenStringValues() {
        Condition cond = Condition.builder()
                .field("age").operator("between").value(new ArrayList<>(List.of("18", "35"))).build();
        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        Rule rule = ruleWithCondition(cond, input);
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        Object val = rule.getRules().get(0).getConditions().get(0).getValue();
        assertInstanceOf(List.class, val);
        List<?> list = (List<?>) val;
        // 修復後應為數值（Long 或 Number），不再是字串
        assertInstanceOf(Number.class, list.get(0), "修復後 min 應為數值");
        assertInstanceOf(Number.class, list.get(1), "修復後 max 應為數值");
        assertEquals(18, ((Number) list.get(0)).intValue());
        assertEquals(35, ((Number) list.get(1)).intValue());
    }

    // ================================================================
    // ENUM allowedValues 自動推斷
    // ================================================================

    @Test
    @DisplayName("ENUM 缺 allowedValues → 從 rules 中推斷")
    void autoInferEnumAllowedValues() {
        FieldDef genderInput = FieldDef.builder().name("gender").typeRef("ENUM").build();
        FieldDef decisionOutput = FieldDef.builder().name("decision").typeRef("ENUM").build();

        RuleRow row1 = RuleRow.builder()
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field("gender").operator("equals").value("male").build())))
                .results(new ArrayList<>(List.of(
                        Result.builder().field("decision").value("approve").build())))
                .build();
        RuleRow row2 = RuleRow.builder()
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field("gender").operator("equals").value("female").build())))
                .results(new ArrayList<>(List.of(
                        Result.builder().field("decision").value("review").build())))
                .build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(genderInput)))
                .outputs(new ArrayList<>(List.of(decisionOutput)))
                .rules(new ArrayList<>(List.of(row1, row2)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        // input ENUM
        List<String> inputAllowed = rule.getInputs().get(0).getAllowedValues();
        assertNotNull(inputAllowed);
        assertTrue(inputAllowed.contains("male"));
        assertTrue(inputAllowed.contains("female"));

        // output ENUM
        List<String> outputAllowed = rule.getOutputs().get(0).getAllowedValues();
        assertNotNull(outputAllowed);
        assertTrue(outputAllowed.contains("approve"));
        assertTrue(outputAllowed.contains("review"));
    }

    @Test
    @DisplayName("ENUM 已有 allowedValues → 不覆蓋")
    void keepExistingEnumAllowedValues() {
        FieldDef input = FieldDef.builder()
                .name("gender").typeRef("ENUM")
                .allowedValues(new ArrayList<>(List.of("M", "F")))
                .build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>())
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        List<String> allowed = rule.getInputs().get(0).getAllowedValues();
        assertEquals(List.of("M", "F"), allowed);
    }

    // ================================================================
    // 規則去重
    // ================================================================

    @Test
    @DisplayName("完全相同的 conditions+results → 移除重複")
    void deduplicateIdenticalRules() {
        Condition cond = Condition.builder().field("age").operator("equals").value(25).build();
        Result res = Result.builder().field("decision").value("approve").build();

        RuleRow row1 = RuleRow.builder()
                .ruleId("R01")
                .conditions(new ArrayList<>(List.of(cond)))
                .results(new ArrayList<>(List.of(res)))
                .build();
        RuleRow row2 = RuleRow.builder()
                .ruleId("R02")
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field("age").operator("equals").value(25).build())))
                .results(new ArrayList<>(List.of(
                        Result.builder().field("decision").value("approve").build())))
                .build();

        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        FieldDef output = FieldDef.builder().name("decision").typeRef("STRING").build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>(List.of(output)))
                .rules(new ArrayList<>(List.of(row1, row2)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals(1, rule.getRules().size(), "重複規則應被移除");
    }

    // ================================================================
    // priority 排序
    // ================================================================

    @Test
    @DisplayName("按 priority 排序（亂序 → 正序）")
    void sortByPriority() {
        // 三條規則必須有不同 conditions，否則會被去重
        RuleRow row1 = RuleRow.builder().ruleId("R01").priority(3)
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field("x").operator("equals").value(1).build())))
                .results(new ArrayList<>()).build();
        RuleRow row2 = RuleRow.builder().ruleId("R02").priority(1)
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field("x").operator("equals").value(2).build())))
                .results(new ArrayList<>()).build();
        RuleRow row3 = RuleRow.builder().ruleId("R03").priority(2)
                .conditions(new ArrayList<>(List.of(
                        Condition.builder().field("x").operator("equals").value(3).build())))
                .results(new ArrayList<>()).build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(
                        FieldDef.builder().name("x").typeRef("INTEGER").build())))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row1, row2, row3)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("R02", rule.getRules().get(0).getRuleId());
        assertEquals("R03", rule.getRules().get(1).getRuleId());
        assertEquals("R01", rule.getRules().get(2).getRuleId());
    }

    // ================================================================
    // 空 condition / result 過濾
    // ================================================================

    @Test
    @DisplayName("field 為 null 的 condition → 被過濾")
    void filterEmptyConditions() {
        Condition good = Condition.builder().field("age").operator("equals").value(25).build();
        Condition bad = Condition.builder().field(null).operator("equals").value(10).build();
        Condition blank = Condition.builder().field("").operator("equals").value(10).build();

        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        RuleRow row = RuleRow.builder()
                .ruleId("R01")
                .conditions(new ArrayList<>(List.of(good, bad, blank)))
                .results(new ArrayList<>())
                .build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row)))
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals(1, rule.getRules().get(0).getConditions().size());
        assertEquals("age", rule.getRules().get(0).getConditions().get(0).getField());
    }

    // ================================================================
    // typeRef alias 在完整流程中的修正
    // ================================================================

    @Test
    @DisplayName("normalize 修正 input/output 的 typeRef alias")
    void normalizeFixesTypeRefAliasInFields() {
        FieldDef input = FieldDef.builder().name("age").typeRef("int").build();
        FieldDef output = FieldDef.builder().name("rate").typeRef("double").build();

        Rule rule = Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>(List.of(output)))
                .rules(new ArrayList<>())
                .build();
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("INTEGER", rule.getInputs().get(0).getTypeRef());
        assertEquals("DECIMAL", rule.getOutputs().get(0).getTypeRef());
    }

    // ================================================================
    // operator alias 在完整流程中的修正
    // ================================================================

    @Test
    @DisplayName("normalize 修正 condition 的 operator alias")
    void normalizeFixesOperatorAlias() {
        Condition cond = Condition.builder().field("age").operator(">=").value(18).build();
        FieldDef input = FieldDef.builder().name("age").typeRef("INTEGER").build();
        Rule rule = ruleWithCondition(cond, input);
        RuleEnvelope envelope = envelopeWith(rule);

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("greaterThanOrEqual", rule.getRules().get(0).getConditions().get(0).getOperator());
    }

    // ================================================================
    // null rule 安全
    // ================================================================

    @Test
    @DisplayName("rule 為 null → 不拋例外")
    void normalizeNullRuleSafe() {
        RuleEnvelope envelope = RuleEnvelope.builder().build();
        assertDoesNotThrow(() -> normalizer.normalize(envelope, "1.0.0", "p1.0.0"));
        assertEquals("DecisionTable", envelope.getRuleType());
    }

    // ================================================================
    // v3.14: typeRef alias 新增（TIMESTAMP / VARIABLE）
    // ================================================================

    @Test
    @DisplayName("v3.14: DATETIME 路由至 TIMESTAMP（不再 collapse 到 DATE）")
    void normalizeTypeRef_DATETIME_routesToTimestamp() {
        assertEquals("TIMESTAMP", normalizer.normalizeTypeRef("DATETIME"));
        assertEquals("TIMESTAMP", normalizer.normalizeTypeRef("datetime"));
        assertEquals("TIMESTAMP", normalizer.normalizeTypeRef("LocalDateTime"));
        assertEquals("TIMESTAMP", normalizer.normalizeTypeRef("INSTANT"));
        assertEquals("TIMESTAMP", normalizer.normalizeTypeRef("TS"));
        assertEquals("TIMESTAMP", normalizer.normalizeTypeRef("ZONEDDATETIME"));
    }

    @Test
    @DisplayName("v3.14: VAR / VARIABLE_REF / RUNTIME_VAR / DERIVED → VARIABLE")
    void normalizeTypeRef_VAR_routesToVariable() {
        assertEquals("VARIABLE", normalizer.normalizeTypeRef("VAR"));
        assertEquals("VARIABLE", normalizer.normalizeTypeRef("var"));
        assertEquals("VARIABLE", normalizer.normalizeTypeRef("VARIABLE_REF"));
        assertEquals("VARIABLE", normalizer.normalizeTypeRef("RUNTIME_VAR"));
        assertEquals("VARIABLE", normalizer.normalizeTypeRef("DERIVED"));
        assertEquals("VARIABLE", normalizer.normalizeTypeRef("VARIABLE"));
    }

    @Test
    @DisplayName("v3.14: LOCALDATE 仍然映射至 DATE（與 DATETIME 分流）")
    void normalizeTypeRef_LOCALDATE_keepsDate() {
        assertEquals("DATE", normalizer.normalizeTypeRef("LOCALDATE"));
        assertEquals("DATE", normalizer.normalizeTypeRef("localdate"));
    }

    // ================================================================
    // v3.14: extensions 正規化（normalizeExtensions 第 9 趟）
    // ================================================================

    @Test
    @DisplayName("v3.14: envelope.extensions=null → normalize 不報錯且不修改任何狀態")
    void normalizeExtensions_nullSafe_returnsImmediately() {
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(emptyRule())
                .build();
        // 確保 extensions 為 null（builder 預設）
        assertNull(envelope.getExtensions());

        assertDoesNotThrow(() -> normalizer.normalize(envelope, "1.0.0", "p1.0.0"));

        // 不會被自動建立
        assertNull(envelope.getExtensions(), "normalize 不應為 extensions==null 的 envelope 自動建立 extensions");
    }

    @Test
    @DisplayName("v3.14: globalGuards 自動補 guardId（G01..Gnn）")
    void normalizeExtensions_autoAssignsGuardId() {
        GlobalGuard g1 = GlobalGuard.builder().description("desc1").build();
        GlobalGuard g2 = GlobalGuard.builder().description("desc2").build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .globalGuards(new ArrayList<>(List.of(g1, g2)))
                .build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("G01", g1.getGuardId());
        assertEquals("G02", g2.getGuardId());
    }

    @Test
    @DisplayName("v3.14: globalGuards.description 兩端空白被 trim")
    void normalizeExtensions_trimsGuardDescription() {
        GlobalGuard g = GlobalGuard.builder()
                .guardId("G01")
                .description("  guard desc  ").build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .globalGuards(new ArrayList<>(List.of(g))).build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("guard desc", g.getDescription());
    }

    @Test
    @DisplayName("v3.14: fieldOr 不足 2 欄位被丟棄")
    void normalizeExtensions_dropsMalformedFieldOr() {
        FieldOrSpec malformed = FieldOrSpec.builder()
                .orId("FOR01")
                .fields(new ArrayList<>(List.of("onlyOne")))
                .build();
        FieldOrSpec good = FieldOrSpec.builder()
                .fields(new ArrayList<>(List.of("a", "b")))
                .build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .fieldOr(new ArrayList<>(List.of(malformed, good))).build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals(1, ext.getFieldOr().size());
        // good 取得自動 orId
        assertEquals("FOR01", ext.getFieldOr().get(0).getOrId());
    }

    @Test
    @DisplayName("v3.14: fieldOr predicate.operator alias (eq) → equals")
    void normalizeExtensions_normalizesFieldOrPredicateOperator() {
        Condition predicate = Condition.builder().operator("eq").value("指定帳戶轉帳").build();
        FieldOrSpec spec = FieldOrSpec.builder()
                .fields(new ArrayList<>(List.of("newCh", "renewalCh")))
                .predicate(predicate)
                .build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .fieldOr(new ArrayList<>(List.of(spec))).build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("equals", spec.getPredicate().getOperator());
    }

    @Test
    @DisplayName("v3.14: groupings 自動補 groupId（RG01..RGnn）並 trim title")
    void normalizeExtensions_groupingAutoIdAndTrim() {
        RuleGrouping g1 = RuleGrouping.builder()
                .title("  2.3 特約通路檢核 ")
                .memberRuleIds(new ArrayList<>(List.of("R01")))
                .build();
        RuleGrouping g2 = RuleGrouping.builder()
                .title("2.4 ABC")
                .memberRuleIds(new ArrayList<>(List.of("R02")))
                .build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .groupings(new ArrayList<>(List.of(g1, g2))).build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("RG01", g1.getGroupId());
        assertEquals("RG02", g2.getGroupId());
        assertEquals("2.3 特約通路檢核", g1.getTitle());
    }

    @Test
    @DisplayName("v3.14: footnotes text 兩端空白被 trim")
    void normalizeExtensions_trimsFootnoteText() {
        Footnote f = Footnote.builder().marker("註1").text("  hello  ").build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .footnotes(new ArrayList<>(List.of(f))).build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals("hello", f.getText());
    }

    @Test
    @DisplayName("v3.14: footnotes 空白 text 被丟棄")
    void normalizeExtensions_dropsBlankFootnote() {
        Footnote blank = Footnote.builder().marker("註1").text("   ").build();
        Footnote good = Footnote.builder().marker("註2").text("real text").build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .footnotes(new ArrayList<>(List.of(blank, good))).build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals(1, ext.getFootnotes().size());
        assertEquals("real text", ext.getFootnotes().get(0).getText());
    }

    @Test
    @DisplayName("v3.14: ruleStatus 缺 status 預設 ACTIVE")
    void normalizeExtensions_defaultsMissingRuleStatusToActive() {
        RuleStatus s = RuleStatus.builder().ruleId("R01").build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .ruleStatus(new ArrayList<>(List.of(s))).build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals(RuleStatus.Lifecycle.ACTIVE, s.getStatus());
    }

    @Test
    @DisplayName("v3.14: ruleStatus 重複 ruleId 僅保留首筆")
    void normalizeExtensions_dedupesDuplicateRuleStatusByRuleId() {
        RuleStatus s1 = RuleStatus.builder().ruleId("R01").status(RuleStatus.Lifecycle.RETIRED).build();
        RuleStatus s2 = RuleStatus.builder().ruleId("R01").status(RuleStatus.Lifecycle.DRAFT).build();
        RuleStatus s3 = RuleStatus.builder().ruleId("R02").status(RuleStatus.Lifecycle.ACTIVE).build();
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .ruleStatus(new ArrayList<>(List.of(s1, s2, s3))).build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertEquals(2, ext.getRuleStatus().size());
        assertEquals(RuleStatus.Lifecycle.RETIRED, ext.getRuleStatus().get(0).getStatus(),
                "保留首筆 R01 (RETIRED)，丟棄重複的 DRAFT");
    }

    @Test
    @DisplayName("v3.14: 空 list 在 normalize 後變 null（配合 @JsonInclude(NON_NULL)）")
    void normalizeExtensions_emptyListsBecomeNullAfterNormalize() {
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .fieldOr(new ArrayList<>())  // 空 list
                .footnotes(new ArrayList<>())
                .ruleStatus(new ArrayList<>())
                .build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable").rule(emptyRule()).extensions(ext).build();

        normalizer.normalize(envelope, "1.0.0", "p1.0.0");

        assertNull(ext.getFieldOr(), "fieldOr 應變 null 以便 @JsonInclude(NON_NULL) 隱藏");
        assertNull(ext.getFootnotes(), "footnotes 應變 null");
        assertNull(ext.getRuleStatus(), "ruleStatus 應變 null");
    }

    // ================================================================
    // Helper
    // ================================================================

    private Rule emptyRule() {
        return Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>())
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>())
                .build();
    }

    private RuleEnvelope envelopeWith(Rule rule) {
        return RuleEnvelope.builder().ruleType("DecisionTable").rule(rule).build();
    }

    private Rule ruleWithCondition(Condition cond, FieldDef input) {
        RuleRow row = RuleRow.builder()
                .conditions(new ArrayList<>(List.of(cond)))
                .results(new ArrayList<>())
                .build();
        return Rule.builder()
                .hitPolicy("FIRST")
                .inputs(new ArrayList<>(List.of(input)))
                .outputs(new ArrayList<>())
                .rules(new ArrayList<>(List.of(row)))
                .build();
    }
}
