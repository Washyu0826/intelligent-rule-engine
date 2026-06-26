package com.ruleengine.rules.service.evaluator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.evaluator.GroundingGuardService.GroundingReport;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.8.0 — GroundingGuardService 符號幻覺偵測測試。
 *
 * 驗證思路：
 *   1. 所有欄位都能 ground → ratio=1.0、suspicion=LOW、無 ungrounded
 *   2. LLM 發明欄位 → 出現在 ungroundedFields
 *   3. LLM 發明 ENUM 值 → 出現在 ungroundedEnumValues
 *   4. allowedFields 白名單覆蓋 → 即使描述中沒有也視為 grounded
 *   5. 通用 output 名稱（decision / result 等）自動 grounded
 *   6. 中文描述 → 英文欄位名透過 DescriptionDimensionParser grounded
 */
class GroundingGuardServiceTest {

    private GroundingGuardService service;

    @BeforeEach
    void setUp() {
        service = new GroundingGuardService(new DescriptionDimensionParser());
    }

    // ============================================================
    // Case 1：全部 grounded
    // ============================================================

    @Test
    void allFieldsGrounded_ratioOne_suspicionLow() {
        String desc = "年齡大於 18 且有高血壓病史的人拒保，否則承保";
        RuleEnvelope env = envelope(
                List.of(field("age", "INTEGER", null),
                        field("hypertension", "BOOLEAN", null)),
                List.of(field("decision", "ENUM", List.of("承保", "拒保")))
        );

        GroundingReport r = service.check(desc, null, env);

        assertThat(r.getGroundingRatio()).isEqualTo(1.0);
        assertThat(r.getSuspicionLevel()).isEqualTo("LOW");
        assertThat(r.getUngroundedFields()).isNull();
        assertThat(r.getUngroundedEnumValues()).isNull();
    }

    // ============================================================
    // Case 2：LLM 發明新欄位
    // ============================================================

    @Test
    void llmInventsField_flaggedAsUngrounded() {
        String desc = "年齡大於 18 可承保";
        RuleEnvelope env = envelope(
                List.of(field("age", "INTEGER", null),
                        field("bmi", "DECIMAL", null),   // 描述中沒有提到 bmi
                        field("income", "INTEGER", null)),  // 描述中沒有提到 income
                List.of(field("decision", "STRING", null))
        );

        GroundingReport r = service.check(desc, null, env);

        assertThat(r.getUngroundedFields()).isNotNull();
        assertThat(r.getUngroundedFields())
                .extracting(GroundingGuardService.UngroundedField::getName)
                .contains("bmi", "income")
                .doesNotContain("age", "decision");
        assertThat(r.getSuspicionLevel()).isNotEqualTo("LOW");
    }

    // ============================================================
    // Case 3：LLM 發明 ENUM 值
    // ============================================================

    @Test
    void llmInventsEnumValue_flaggedAsUngrounded() {
        String desc = "承保或拒保的決議";
        RuleEnvelope env = envelope(
                List.of(),
                List.of(field("decision", "ENUM", List.of("承保", "拒保", "延後核保")))
        );

        GroundingReport r = service.check(desc, null, env);

        assertThat(r.getUngroundedEnumValues()).isNotNull();
        assertThat(r.getUngroundedEnumValues())
                .extracting(GroundingGuardService.UngroundedValue::getValue)
                .contains("延後核保")
                .doesNotContain("承保", "拒保");
    }

    // ============================================================
    // Case 4：allowedFields 白名單覆蓋
    // ============================================================

    @Test
    void allowedFieldsWhitelist_groundsFieldsNotInDescription() {
        String desc = "依規則判定";  // 幾乎沒有描述具體欄位
        RuleEnvelope env = envelope(
                List.of(field("creditScore", "INTEGER", null)),
                List.of(field("decision", "STRING", null))
        );

        GroundingReport r = service.check(desc, List.of("creditScore"), env);

        assertThat(r.getUngroundedFields()).isNull();
        assertThat(r.getGroundingRatio()).isEqualTo(1.0);
    }

    // ============================================================
    // Case 5：通用 output 名稱自動 grounded
    // ============================================================

    @Test
    void commonOutputNames_autoGrounded() {
        String desc = "年齡大於 60 就加費";  // 沒提到輸出名稱
        RuleEnvelope env = envelope(
                List.of(field("age", "INTEGER", null)),
                List.of(
                        field("result", "STRING", null),
                        field("action", "STRING", null),
                        field("remark", "STRING", null)
                )
        );

        GroundingReport r = service.check(desc, null, env);

        assertThat(r.getUngroundedFields()).isNull();
    }

    @Test
    void outputField_notCommon_flaggedUngroundedWhenAbsent() {
        String desc = "年齡大於 60";
        RuleEnvelope env = envelope(
                List.of(field("age", "INTEGER", null)),
                List.of(field("xyzCustom", "STRING", null))  // 不在通用列表、不在描述
        );

        GroundingReport r = service.check(desc, null, env);

        assertThat(r.getUngroundedFields()).isNotNull();
        assertThat(r.getUngroundedFields())
                .extracting(GroundingGuardService.UngroundedField::getName)
                .contains("xyzCustom");
    }

    // ============================================================
    // Case 6：中文 → 英文欄位名透過 parser grounded
    // ============================================================

    @Test
    void chineseDescription_englishFieldName_groundedViaParser() {
        String desc = "年齡大於 18 且有高血壓的被保人";
        RuleEnvelope env = envelope(
                List.of(field("age", "INTEGER", null),
                        field("hypertension", "BOOLEAN", null)),
                List.of(field("decision", "STRING", null))
        );

        GroundingReport r = service.check(desc, null, env);

        // 描述是純中文「年齡」「高血壓」，欄位名是英文 age/hypertension
        // parser 的中英對照應該讓這兩個欄位都 grounded
        assertThat(r.getUngroundedFields()).isNull();
        assertThat(r.getGroundingRatio()).isEqualTo(1.0);
    }

    // ============================================================
    // Case 7：Suspicion level 分級
    // ============================================================

    @Test
    void suspicionLevel_scalesWithUngroundedProportion() {
        String desc = "年齡";
        // 1 grounded (age) + 1 grounded output (decision) + 3 ungrounded
        RuleEnvelope env = envelope(
                List.of(field("age", "INTEGER", null),
                        field("foo", "INTEGER", null),
                        field("bar", "INTEGER", null),
                        field("baz", "INTEGER", null)),
                List.of(field("decision", "STRING", null))
        );

        GroundingReport r = service.check(desc, null, env);

        // 2 grounded / 5 total = 0.40 → HIGH
        assertThat(r.getSuspicionLevel()).isEqualTo("HIGH");
        assertThat(r.getGroundingRatio()).isLessThan(0.5);
    }

    // ============================================================
    // Case 8：空 envelope trivial report
    // ============================================================

    @Test
    void nullEnvelope_returnsTrivialReport() {
        GroundingReport r = service.check("desc", null, null);
        assertThat(r.getGroundingRatio()).isEqualTo(1.0);
        assertThat(r.getSuspicionLevel()).isEqualTo("LOW");
        assertThat(r.getTotalChecks()).isZero();
    }

    @Test
    void emptyDescription_stillRunsWithoutCrash() {
        RuleEnvelope env = envelope(
                List.of(field("age", "INTEGER", null)),
                List.of(field("decision", "STRING", null))
        );

        GroundingReport r = service.check("", null, env);

        // age 找不到、decision 是通用名 → 1/2 = 0.5 → HIGH
        assertThat(r.getTotalChecks()).isEqualTo(2);
        assertThat(r.getUngroundedFields()).isNotNull();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private RuleEnvelope.FieldDef field(String name, String typeRef, List<String> allowedValues) {
        return RuleEnvelope.FieldDef.builder()
                .name(name)
                .typeRef(typeRef)
                .allowedValues(allowedValues == null ? null : new ArrayList<>(allowedValues))
                .build();
    }

    private RuleEnvelope envelope(List<RuleEnvelope.FieldDef> inputs,
                                   List<RuleEnvelope.FieldDef> outputs) {
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(RuleEnvelope.Rule.builder()
                        .hitPolicy("FIRST")
                        .inputs(inputs)
                        .outputs(outputs)
                        .rules(List.of())
                        .build())
                .schemaVersion("1.0.0")
                .promptVersion("p3.8.0")
                .build();
    }
}
