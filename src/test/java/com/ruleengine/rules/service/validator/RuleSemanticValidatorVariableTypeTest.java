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
 * v3.14 — VARIABLE / TIMESTAMP typeRef 語義驗證測試。
 *
 * 兩件事：
 *   1. INVALID_VARIABLE_IN_CONDITION：VARIABLE typeRef 出現於 inputs[] → 觸發；出現於 outputs[] → 不觸發。
 *      對應 Group xlsx「使用說明」: 8.variable - 區域變數 (條件欄位不開放)。
 *   2. TIMESTAMP 在 inputs / outputs 中均被接受（typeRef 合法且不觸發 TYPE_MISMATCH）。
 *
 * 由 RuleSemanticValidator (Layer 3) 實作；對應 design §6.2 routing 決定。
 */
@DisplayName("RuleSemanticValidator - VARIABLE / TIMESTAMP typeRef (v3.14)")
class RuleSemanticValidatorVariableTypeTest {

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
                new ExtensionsValidator()
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

    private ValidationError findCode(List<ValidationError> errs, String code) {
        return errs.stream().filter(e -> code.equals(e.getCode())).findFirst().orElse(null);
    }

    // ================================================================
    // INVALID_VARIABLE_IN_CONDITION — VARIABLE 在 inputs 中
    // ================================================================

    @Test
    @DisplayName("VARIABLE 在 inputs → INVALID_VARIABLE_IN_CONDITION（訊息含 'VARIABLE'+欄位名）")
    void variableInInputs_negative() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "regionalVar", "typeRef": "VARIABLE" }
                    ],
                    "outputs": [
                      { "name": "errorMessage", "typeRef": "STRING" }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01", "priority": 1,
                        "conditions": [
                          { "field": "regionalVar", "operator": "equals", "value": "x" }
                        ],
                        "results": [{ "field": "errorMessage", "value": "err" }]
                      }
                    ]
                  }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_VARIABLE_IN_CONDITION))
                .as("應觸發 INVALID_VARIABLE_IN_CONDITION：" + errs).isTrue();

        ValidationError e = findCode(errs, ErrorCodes.INVALID_VARIABLE_IN_CONDITION);
        assertThat(e.getMessage())
                .contains("VARIABLE")
                .contains("regionalVar")
                .contains("output");  // 訊息提到「reserved for output fields」
    }

    @Test
    @DisplayName("VARIABLE 在 outputs → 不觸發 INVALID_VARIABLE_IN_CONDITION")
    void variableInOutputs_positive() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "age", "typeRef": "INTEGER" }
                    ],
                    "outputs": [
                      { "name": "computedVar", "typeRef": "VARIABLE" }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01", "priority": 1,
                        "conditions": [
                          { "field": "age", "operator": "equals", "value": 25 }
                        ],
                        "results": [{ "field": "computedVar", "value": "anything" }]
                      }
                    ]
                  }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_VARIABLE_IN_CONDITION))
                .as("output-only VARIABLE 不應觸發此錯誤；errs=" + errs).isFalse();
    }

    @Test
    @DisplayName("多個 VARIABLE input 欄位 → 每個都觸發一次 INVALID_VARIABLE_IN_CONDITION")
    void multipleVariableInputs_eachTriggersError() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "var1", "typeRef": "VARIABLE" },
                      { "name": "var2", "typeRef": "VARIABLE" },
                      { "name": "normal", "typeRef": "STRING" }
                    ],
                    "outputs": [
                      { "name": "errorMessage", "typeRef": "STRING" }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01", "priority": 1,
                        "conditions": [
                          { "field": "normal", "operator": "equals", "value": "x" }
                        ],
                        "results": [{ "field": "errorMessage", "value": "err" }]
                      }
                    ]
                  }
                }""";
        List<ValidationError> errs = validate(json);
        long count = errs.stream()
                .filter(e -> ErrorCodes.INVALID_VARIABLE_IN_CONDITION.equals(e.getCode()))
                .count();
        assertThat(count).as("應有兩個 INVALID_VARIABLE_IN_CONDITION").isEqualTo(2);
    }

    // ================================================================
    // TIMESTAMP — 在 inputs / outputs 均合法
    // ================================================================

    @Test
    @DisplayName("TIMESTAMP 在 inputs：greaterThan + ISO-8601 字串 → 不觸發 TYPE_MISMATCH")
    void timestampInInputs_allowed() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "submittedAt", "typeRef": "TIMESTAMP" }
                    ],
                    "outputs": [
                      { "name": "accepted", "typeRef": "BOOLEAN" }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01", "priority": 1,
                        "conditions": [
                          { "field": "submittedAt", "operator": "greaterThan",
                            "value": "2026-01-31T08:00:00Z" }
                        ],
                        "results": [{ "field": "accepted", "value": true }]
                      }
                    ]
                  }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.TYPE_MISMATCH))
                .as("TIMESTAMP greaterThan 應合法；errs=" + errs).isFalse();
        assertThat(hasCode(errs, ErrorCodes.INVALID_VARIABLE_IN_CONDITION)).isFalse();
    }

    @Test
    @DisplayName("TIMESTAMP 在 inputs：$today 相對日期 valueRef → 不觸發 TYPE_MISMATCH")
    void timestampInInputs_relativeDateValueRefAllowed() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "submittedAt", "typeRef": "TIMESTAMP" }
                    ],
                    "outputs": [
                      { "name": "accepted", "typeRef": "BOOLEAN" }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01", "priority": 1,
                        "conditions": [
                          { "field": "submittedAt", "operator": "greaterThan",
                            "valueRef": "$today+1d" }
                        ],
                        "results": [{ "field": "accepted", "value": true }]
                      }
                    ]
                  }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.TYPE_MISMATCH))
                .as("TIMESTAMP + $today+1d 應合法；errs=" + errs).isFalse();
    }

    @Test
    @DisplayName("TIMESTAMP 在 outputs → 不觸發任何錯誤")
    void timestampInOutputs_allowed() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "age", "typeRef": "INTEGER" }
                    ],
                    "outputs": [
                      { "name": "expiresAt", "typeRef": "TIMESTAMP" }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01", "priority": 1,
                        "conditions": [
                          { "field": "age", "operator": "greaterThan", "value": 18 }
                        ],
                        "results": [{ "field": "expiresAt", "value": "2027-01-01T00:00:00Z" }]
                      }
                    ]
                  }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.INVALID_VARIABLE_IN_CONDITION)).isFalse();
        // TIMESTAMP output 應該無 TYPE_MISMATCH
        long mismatchCount = errs.stream()
                .filter(e -> ErrorCodes.TYPE_MISMATCH.equals(e.getCode()))
                .count();
        assertThat(mismatchCount).as("errs=" + errs).isZero();
    }

    @Test
    @DisplayName("TIMESTAMP value 不是 ISO-8601 字串 → TYPE_MISMATCH")
    void timestamp_invalidValueFormat_typeMismatch() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "submittedAt", "typeRef": "TIMESTAMP" }
                    ],
                    "outputs": [
                      { "name": "accepted", "typeRef": "BOOLEAN" }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01", "priority": 1,
                        "conditions": [
                          { "field": "submittedAt", "operator": "equals",
                            "value": "not-a-timestamp" }
                        ],
                        "results": [{ "field": "accepted", "value": true }]
                      }
                    ]
                  }
                }""";
        List<ValidationError> errs = validate(json);
        assertThat(hasCode(errs, ErrorCodes.TYPE_MISMATCH))
                .as("非 ISO-8601 timestamp 字串應觸發 TYPE_MISMATCH；errs=" + errs).isTrue();
    }
}
