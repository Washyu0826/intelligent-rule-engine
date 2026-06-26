package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.service.generator.ConditionOverlapDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.14 — ExtensionsValidator (Layer 5) 完整測試。
 *
 * 覆蓋 5 個新錯誤碼：
 *   - INVALID_GLOBAL_GUARD
 *   - INVALID_FIELD_OR
 *   - INVALID_RULE_STATUS_REF
 *   - INVALID_FOOTNOTE_REF
 *   - MALFORMED_GROUPING
 *
 * 每個錯誤碼至少一個 positive（well-formed → 不觸發）+ 一個 negative（malformed → 觸發）。
 *
 * 驗證器以「整個 DecisionTableValidator + 5 個 layer」組裝，
 * 因為 Layer 5 需要 Layer 2 / Layer 3 已填入的 inputTypes / outputTypes / parsedRules。
 */
@DisplayName("ExtensionsValidator - Layer 5 五個新錯誤碼")
class ExtensionsValidatorTest {

    private DecisionTableValidator validator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        List<ValidationLayer> layers = List.of(
                new StructureValidator(),
                new FieldDefinitionValidator(),
                new RuleSemanticValidator(),
                new ConsistencyValidator(new ConditionOverlapDetector()),
                new ExtensionsValidator()  // Layer 5
        );
        validator = new DecisionTableValidator(layers);
    }

    private List<ValidationError> validate(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        return validator.validate(node);
    }

    private boolean hasCode(List<ValidationError> errs, String code) {
        return errs.stream().anyMatch(e -> code.equals(e.getCode()));
    }

    private long countCode(List<ValidationError> errs, String code) {
        return errs.stream().filter(e -> code.equals(e.getCode())).count();
    }

    /**
     * 一個合法 baseline envelope，含 R01 (boolean condition) + R02 (boolean condition)，
     * 同 input/output 結構，無 extensions block。
     * 子測試在此基礎上掛上不同的 extensions 內容。
     */
    private static final String BASE_RULE = """
            "ruleType": "DecisionTable",
            "rule": {
              "hitPolicy": "FIRST",
              "inputs": [
                { "name": "channel", "typeRef": "ENUM", "allowedValues": ["A", "B"] },
                { "name": "newCh", "typeRef": "STRING" },
                { "name": "renewalCh", "typeRef": "STRING" }
              ],
              "outputs": [
                { "name": "errorMessage", "typeRef": "STRING" }
              ],
              "rules": [
                {
                  "ruleId": "R01", "priority": 1,
                  "conditions": [
                    { "field": "channel", "operator": "equals", "value": "A" },
                    { "field": "newCh", "operator": "equals", "value": "X" },
                    { "field": "renewalCh", "operator": "equals", "value": "Y" }
                  ],
                  "results": [{ "field": "errorMessage", "value": "err1" }]
                },
                {
                  "ruleId": "R02", "priority": 2,
                  "conditions": [
                    { "field": "channel", "operator": "equals", "value": "B" },
                    { "field": "newCh", "operator": "equals", "value": "Y" },
                    { "field": "renewalCh", "operator": "equals", "value": "X" }
                  ],
                  "results": [{ "field": "errorMessage", "value": "err2" }]
                }
              ]
            }
            """;

    // ================================================================
    // VALID — 無 extensions / 五項全合法
    // ================================================================

    @Test
    @DisplayName("無 extensions 區塊 → 0 個 Layer-5 錯誤")
    void valid_emptyExtensions_passes() throws Exception {
        String json = "{" + BASE_RULE + "}";
        List<ValidationError> errs = validate(json);
        assertThat(countCode(errs, ErrorCodes.INVALID_GLOBAL_GUARD)).isZero();
        assertThat(countCode(errs, ErrorCodes.INVALID_FIELD_OR)).isZero();
        assertThat(countCode(errs, ErrorCodes.INVALID_RULE_STATUS_REF)).isZero();
        assertThat(countCode(errs, ErrorCodes.INVALID_FOOTNOTE_REF)).isZero();
        assertThat(countCode(errs, ErrorCodes.MALFORMED_GROUPING)).isZero();
    }

    @Test
    @DisplayName("五個 sub-list 都填且合法 → Layer-5 不報錯")
    void valid_allFivePopulated_passes() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "globalGuards": [{
                    "guardId": "G01",
                    "condition": { "field": "channel", "operator": "in", "value": ["A","B"] },
                    "onFailure": [{ "field": "errorMessage", "value": "guard fail" }]
                  }],
                  "fieldOr": [{
                    "orId": "FOR01",
                    "fields": ["newCh", "renewalCh"],
                    "predicate": { "operator": "equals", "value": "X" },
                    "appliesToRuleIds": ["R01"]
                  }],
                  "groupings": [{
                    "groupId": "RG01",
                    "title": "group A",
                    "memberRuleIds": ["R01", "R02"],
                    "level": 1
                  }],
                  "footnotes": [
                    { "marker": "*", "text": "table-level note" },
                    { "marker": "1", "text": "rule R01 note", "appliesToRuleId": "R01" }
                  ],
                  "ruleStatus": [
                    { "ruleId": "R01", "status": "ACTIVE" },
                    { "ruleId": "R02", "status": "RETIRED" }
                  ]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(countCode(errs, ErrorCodes.INVALID_GLOBAL_GUARD)).isZero();
        assertThat(countCode(errs, ErrorCodes.INVALID_FIELD_OR)).isZero();
        assertThat(countCode(errs, ErrorCodes.INVALID_RULE_STATUS_REF)).isZero();
        assertThat(countCode(errs, ErrorCodes.INVALID_FOOTNOTE_REF)).isZero();
        assertThat(countCode(errs, ErrorCodes.MALFORMED_GROUPING)).isZero();
    }

    // ================================================================
    // INVALID_GLOBAL_GUARD
    // ================================================================

    @Test
    @DisplayName("globalGuard.condition.field 不在 inputs 中 → INVALID_GLOBAL_GUARD")
    void invalid_globalGuard_fieldDoesNotExist() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "globalGuards": [{
                    "guardId": "G01",
                    "condition": { "field": "NOT_AN_INPUT", "operator": "equals", "value": "X" }
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_GLOBAL_GUARD))
                .as("envelope: " + errs).isTrue();
    }

    @Test
    @DisplayName("globalGuard.condition.operator 不合法 → INVALID_GLOBAL_GUARD")
    void invalid_globalGuard_unknownOperator() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "globalGuards": [{
                    "guardId": "G01",
                    "condition": { "field": "channel", "operator": "fooBar", "value": "X" }
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_GLOBAL_GUARD)).isTrue();
    }

    @Test
    @DisplayName("globalGuard.onFailure.field 不在 outputs 中 → INVALID_GLOBAL_GUARD")
    void invalid_globalGuard_onFailureFieldNotInOutputs() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "globalGuards": [{
                    "guardId": "G01",
                    "condition": { "field": "channel", "operator": "equals", "value": "A" },
                    "onFailure": [{ "field": "noSuchOutput", "value": "x" }]
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_GLOBAL_GUARD)).isTrue();
    }

    // ================================================================
    // INVALID_FIELD_OR
    // ================================================================

    @Test
    @DisplayName("fieldOr.fields 少於 2 個 → INVALID_FIELD_OR")
    void invalid_fieldOr_lessThanTwoFields() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "fieldOr": [{
                    "orId": "FOR01",
                    "fields": ["newCh"],
                    "predicate": { "operator": "equals", "value": "X" }
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_FIELD_OR)).isTrue();
    }

    @Test
    @DisplayName("fieldOr.fields 引用未定義欄位 → INVALID_FIELD_OR")
    void invalid_fieldOr_referencesNonExistentField() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "fieldOr": [{
                    "orId": "FOR01",
                    "fields": ["newCh", "GHOST_FIELD"],
                    "predicate": { "operator": "equals", "value": "X" }
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_FIELD_OR)).isTrue();
    }

    @Test
    @DisplayName("fieldOr.appliesToRuleIds 指向不存在的 ruleId → INVALID_FIELD_OR")
    void invalid_fieldOr_appliesToNonExistentRuleId() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "fieldOr": [{
                    "orId": "FOR01",
                    "fields": ["newCh", "renewalCh"],
                    "predicate": { "operator": "equals", "value": "X" },
                    "appliesToRuleIds": ["R99"]
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_FIELD_OR)).isTrue();
    }

    // ================================================================
    // INVALID_RULE_STATUS_REF
    // ================================================================

    @Test
    @DisplayName("ruleStatus.ruleId 不在 rules[] 中 → INVALID_RULE_STATUS_REF")
    void invalid_ruleStatus_referencesNonExistentRuleId() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "ruleStatus": [{ "ruleId": "R99", "status": "ACTIVE" }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_RULE_STATUS_REF)).isTrue();
    }

    @Test
    @DisplayName("ruleStatus 重複 ruleId → INVALID_RULE_STATUS_REF")
    void invalid_ruleStatus_duplicateRuleId() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "ruleStatus": [
                    { "ruleId": "R01", "status": "ACTIVE" },
                    { "ruleId": "R01", "status": "RETIRED" }
                  ]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_RULE_STATUS_REF)).isTrue();
    }

    @Test
    @DisplayName("ruleStatus.status 不在 {ACTIVE,DRAFT,RETIRED} → INVALID_RULE_STATUS_REF")
    void invalid_ruleStatus_unknownStatusValue() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "ruleStatus": [{ "ruleId": "R01", "status": "UNKNOWN_LIFECYCLE" }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_RULE_STATUS_REF)).isTrue();
    }

    // ================================================================
    // INVALID_FOOTNOTE_REF
    // ================================================================

    @Test
    @DisplayName("footnote.appliesToRuleId 指向不存在的 ruleId → INVALID_FOOTNOTE_REF")
    void invalid_footnote_referencesNonExistentRuleId() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "footnotes": [
                    { "marker": "*", "text": "note", "appliesToRuleId": "R99" }
                  ]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_FOOTNOTE_REF)).isTrue();
    }

    @Test
    @DisplayName("footnote.text 空白 → INVALID_FOOTNOTE_REF")
    void invalid_footnote_blankText() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "footnotes": [{ "marker": "*", "text": "   " }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_FOOTNOTE_REF)).isTrue();
    }

    // ================================================================
    // MALFORMED_GROUPING
    // ================================================================

    @Test
    @DisplayName("grouping.memberRuleIds 含 不存在的 ruleId → MALFORMED_GROUPING")
    void invalid_grouping_memberRuleIdsNotInRules() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "groupings": [{
                    "groupId": "RG01",
                    "title": "g1",
                    "memberRuleIds": ["R01", "R99"]
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.MALFORMED_GROUPING)).isTrue();
    }

    @Test
    @DisplayName("grouping.memberRuleIds 空陣列 → MALFORMED_GROUPING")
    void invalid_grouping_emptyMemberList() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "groupings": [{
                    "groupId": "RG01",
                    "title": "g1",
                    "memberRuleIds": []
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.MALFORMED_GROUPING)).isTrue();
    }

    @Test
    @DisplayName("grouping 重複 groupId → MALFORMED_GROUPING")
    void invalid_grouping_duplicateGroupId() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "groupings": [
                    { "groupId": "RG01", "title": "g1", "memberRuleIds": ["R01"] },
                    { "groupId": "RG01", "title": "g2", "memberRuleIds": ["R02"] }
                  ]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.MALFORMED_GROUPING)).isTrue();
    }

    @Test
    @DisplayName("grouping.level <= 0 → MALFORMED_GROUPING")
    void invalid_grouping_nonPositiveLevel() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "groupings": [{
                    "groupId": "RG01",
                    "title": "g1",
                    "memberRuleIds": ["R01"],
                    "level": 0
                  }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.MALFORMED_GROUPING)).isTrue();
    }

    // ================================================================
    // 五個錯誤碼互不干擾
    // ================================================================

    @Test
    @DisplayName("一次踩五個錯 → 五個錯誤碼都同時出現")
    void allFiveErrorCodesFireIndependently() throws Exception {
        String json = "{" + BASE_RULE + """
                ,
                "extensions": {
                  "globalGuards": [{
                    "guardId": "G01",
                    "condition": { "field": "NOT_DEFINED", "operator": "equals", "value": "X" }
                  }],
                  "fieldOr": [{ "orId": "FOR01", "fields": ["only_one"] }],
                  "groupings": [{ "groupId": "RG01", "memberRuleIds": [] }],
                  "footnotes": [{ "marker": "*", "text": "n", "appliesToRuleId": "R99" }],
                  "ruleStatus": [{ "ruleId": "R99", "status": "ACTIVE" }]
                }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_GLOBAL_GUARD)).isTrue();
        assertThat(hasCode(errs, ErrorCodes.INVALID_FIELD_OR)).isTrue();
        assertThat(hasCode(errs, ErrorCodes.MALFORMED_GROUPING)).isTrue();
        assertThat(hasCode(errs, ErrorCodes.INVALID_FOOTNOTE_REF)).isTrue();
        assertThat(hasCode(errs, ErrorCodes.INVALID_RULE_STATUS_REF)).isTrue();
    }
}
