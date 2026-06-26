package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.service.generator.ConditionOverlapDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DecisionTableValidator 完整測試 — 覆蓋所有 11 個錯誤碼。
 *
 * 測試策略：
 * - 每個錯誤碼至少一個正向測試（觸發錯誤）
 * - 每個 valid fixture 至少一個反向測試（驗證通過）
 * - 邊界值測試（between 邊界、空陣列等）
 *
 * 對應計畫書 §二 KPI：
 * - /validate 可完整回報錯誤位置與原因（errors[]）
 * - 輸出 100% 為合法 JSON
 */
@DisplayName("DecisionTableValidator - 11 Error Codes")
class DecisionTableValidatorTest {

    private DecisionTableValidator validator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ConditionOverlapDetector overlapDetector = new ConditionOverlapDetector();
        // 按 @Order 順序組裝四層驗證
        List<ValidationLayer> layers = List.of(
                new StructureValidator(),
                new FieldDefinitionValidator(),
                new RuleSemanticValidator(),
                new ConsistencyValidator(overlapDetector)
        );
        validator = new DecisionTableValidator(layers);
    }

    // ================================================================
    // Helper
    // ================================================================

    private JsonNode loadFixture(String filename) throws Exception {
        String json = new ClassPathResource("fixtures/" + filename)
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private boolean hasErrorCode(List<ValidationError> errors, String code) {
        return errors.stream().anyMatch(e -> code.equals(e.getCode()));
    }

    private long countErrorCode(List<ValidationError> errors, String code) {
        return errors.stream().filter(e -> code.equals(e.getCode())).count();
    }

    private ValidationError findFirstError(List<ValidationError> errors, String code) {
        return errors.stream().filter(e -> code.equals(e.getCode())).findFirst().orElse(null);
    }

    // ================================================================
    // 1. VALID cases — 驗證通過
    // ================================================================

    @Test
    @DisplayName("TC01: 合法 DecisionTable FIRST — 驗證通過，0 errors")
    void tc01_validFirst() throws Exception {
        JsonNode json = loadFixture("tc01-valid-first.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(errors.isEmpty(),
                "TC01 應該通過驗證，但有 " + errors.size() + " 個錯誤：" + errors);
    }

    @Test
    @DisplayName("TC02: 合法 DecisionTable MULTI — 驗證通過")
    void tc02_validMulti() throws Exception {
        JsonNode json = loadFixture("tc02-valid-multi.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(errors.isEmpty(),
                "TC02 應該通過驗證，但有 " + errors.size() + " 個錯誤：" + errors);
    }

    // ================================================================
    // 2. MISSING_FIELD
    // ================================================================

    @Test
    @DisplayName("TC09: 缺少 hitPolicy → MISSING_FIELD")
    void tc09_missingHitPolicy() throws Exception {
        JsonNode json = loadFixture("tc09-missing-field.json");
        List<ValidationError> errors = validator.validate(json);
        assertFalse(errors.isEmpty(), "缺少 hitPolicy 應該觸發錯誤");
        assertTrue(hasErrorCode(errors, ErrorCodes.MISSING_FIELD),
                "應觸發 MISSING_FIELD，實際：" + errors);
        // 訊息應提到 hitPolicy
        ValidationError e = findFirstError(errors, ErrorCodes.MISSING_FIELD);
        assertNotNull(e);
        assertTrue(e.getMessage().contains("hitPolicy"),
                "錯誤訊息應提到 hitPolicy：" + e.getMessage());
    }

    @Test
    @DisplayName("缺少 ruleType → MISSING_FIELD")
    void missingRuleType() throws Exception {
        String json = """
                { "rule": { "hitPolicy": "FIRST", "inputs": [], "outputs": [], "rules": [] } }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.MISSING_FIELD));
    }

    @Test
    @DisplayName("缺少 rule → MISSING_FIELD")
    void missingRule() throws Exception {
        String json = """
                { "ruleType": "DecisionTable" }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.MISSING_FIELD));
    }

    @Test
    @DisplayName("input 缺少 name → MISSING_FIELD")
    void inputMissingName() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "result", "typeRef": "STRING" }],
                    "rules": []
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.MISSING_FIELD));
    }

    @Test
    @DisplayName("input 缺少 typeRef → MISSING_FIELD")
    void inputMissingTypeRef() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age" }],
                    "outputs": [{ "name": "result", "typeRef": "STRING" }],
                    "rules": []
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.MISSING_FIELD));
    }

    // ================================================================
    // 3. UNKNOWN_OPERATOR
    // ================================================================

    @Test
    @DisplayName("使用不存在的 operator 'like' → UNKNOWN_OPERATOR")
    void unknownOperator() throws Exception {
        JsonNode json = loadFixture("unknown-operator.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.UNKNOWN_OPERATOR),
                "應觸發 UNKNOWN_OPERATOR：" + errors);
        ValidationError e = findFirstError(errors, ErrorCodes.UNKNOWN_OPERATOR);
        assertTrue(e.getMessage().contains("like"), "訊息應包含錯誤的 operator 名稱");
    }

    // ================================================================
    // 4. TYPE_MISMATCH
    // ================================================================

    @Test
    @DisplayName("BOOLEAN 欄位用 greaterThan → TYPE_MISMATCH (operator 不相容)")
    void typeMismatchOperatorCompat() throws Exception {
        JsonNode json = loadFixture("type-mismatch-op.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH),
                "BOOLEAN 不可用 greaterThan：" + errors);
    }

    @Test
    @DisplayName("BOOLEAN 欄位 value 不是 true/false → TYPE_MISMATCH")
    void typeMismatchBooleanValue() throws Exception {
        JsonNode json = loadFixture("type-mismatch-boolean.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH),
                "BOOLEAN value='yes' 應觸發 TYPE_MISMATCH：" + errors);
    }

    @Test
    @DisplayName("between min > max → TYPE_MISMATCH")
    void betweenMinGtMax() throws Exception {
        JsonNode json = loadFixture("between-min-gt-max.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH),
                "between [65,18] 應觸發 TYPE_MISMATCH：" + errors);
        ValidationError e = findFirstError(errors, ErrorCodes.TYPE_MISMATCH);
        assertTrue(e.getMessage().contains("min") || e.getMessage().contains(">"),
                "訊息應提到 min > max：" + e.getMessage());
    }

    @Test
    @DisplayName("between value 不是長度 2 陣列 → TYPE_MISMATCH")
    void betweenNotArray() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [{
                      "ruleId": "R01", "priority": 1,
                      "conditions": [{ "field": "age", "operator": "between", "value": 25 }],
                      "results": [{ "field": "r", "value": "ok" }]
                    }]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH));
    }

    @Test
    @DisplayName("INTEGER 欄位 value 有小數 → TYPE_MISMATCH")
    void integerWithDecimalValue() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [{
                      "ruleId": "R01", "priority": 1,
                      "conditions": [{ "field": "age", "operator": "equals", "value": 25.5 }],
                      "results": [{ "field": "r", "value": "ok" }]
                    }]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH));
    }

    @Test
    @DisplayName("in operator 用在 INTEGER 欄位 → TYPE_MISMATCH")
    void inOnIntegerField() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [{
                      "ruleId": "R01", "priority": 1,
                      "conditions": [{ "field": "age", "operator": "in", "value": [20, 30] }],
                      "results": [{ "field": "r", "value": "ok" }]
                    }]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH));
    }

    @Test
    @DisplayName("DATE 欄位 value 格式錯誤 → TYPE_MISMATCH")
    void dateFormatError() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "purchase_date", "typeRef": "DATE" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [{
                      "ruleId": "R01", "priority": 1,
                      "conditions": [{ "field": "purchase_date", "operator": "equals", "value": "2024/01/01" }],
                      "results": [{ "field": "r", "value": "ok" }]
                    }]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH));
    }

    @Test
    @DisplayName("DATE 欄位正確格式 yyyy-MM-dd → 通過")
    void dateFormatCorrect() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "purchase_date", "typeRef": "DATE" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [{
                      "ruleId": "R01", "priority": 1,
                      "conditions": [{ "field": "purchase_date", "operator": "equals", "value": "2024-01-01" }],
                      "results": [{ "field": "r", "value": "ok" }]
                    }]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertFalse(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH),
                "yyyy-MM-dd 應通過 DATE 驗證：" + errors);
    }

    // ================================================================
    // 5. UNKNOWN_FIELD
    // ================================================================

    @Test
    @DisplayName("condition 引用不存在的 input 欄位 → UNKNOWN_FIELD")
    void unknownField() throws Exception {
        JsonNode json = loadFixture("unknown-field.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.UNKNOWN_FIELD),
                "condition 引用 'bmi' 不在 inputs 中：" + errors);
        ValidationError e = findFirstError(errors, ErrorCodes.UNKNOWN_FIELD);
        assertTrue(e.getMessage().contains("bmi"), "訊息應包含欄位名稱 bmi");
    }

    // ================================================================
    // 6. DUPLICATE_ID
    // ================================================================

    @Test
    @DisplayName("ruleId 重複 → DUPLICATE_ID")
    void duplicateId() throws Exception {
        JsonNode json = loadFixture("duplicate-id.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.DUPLICATE_ID),
                "R01 重複應觸發 DUPLICATE_ID：" + errors);
    }

    // ================================================================
    // 7. ENUM_VALUE_MISSING
    // ================================================================

    @Test
    @DisplayName("ENUM typeRef 缺少 allowedValues → ENUM_VALUE_MISSING")
    void enumValueMissing() throws Exception {
        JsonNode json = loadFixture("enum-value-missing.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.ENUM_VALUE_MISSING),
                "ENUM 缺少 allowedValues：" + errors);
    }

    // ================================================================
    // 8. INVALID_ENUM_VALUE
    // ================================================================

    @Test
    @DisplayName("TC07: result ENUM 值不在 allowedValues → INVALID_ENUM_VALUE")
    void tc07_invalidEnumValue() throws Exception {
        JsonNode json = loadFixture("tc07-invalid-enum.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.INVALID_ENUM_VALUE),
                "「拒絕」不在 [承保, 人工評估, 拒保] 中：" + errors);
        ValidationError e = findFirstError(errors, ErrorCodes.INVALID_ENUM_VALUE);
        assertTrue(e.getMessage().contains("拒絕"), "訊息應包含錯誤的值 '拒絕'");
        assertTrue(e.getMessage().contains("allowedValues"), "訊息應提到 allowedValues");
    }

    // ================================================================
    // 9. INCONSISTENT_TABLE
    // ================================================================

    @Test
    @DisplayName("TC08: 條件重疊衝突 → INCONSISTENT_TABLE (含觸發條件範例 + 結構化 witness)")
    void tc08_inconsistentTable() throws Exception {
        JsonNode json = loadFixture("tc08-conflict.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.INCONSISTENT_TABLE),
                "R01 與 R02 條件重疊應觸發 INCONSISTENT_TABLE：" + errors);
        ValidationError e = findFirstError(errors, ErrorCodes.INCONSISTENT_TABLE);
        assertNotNull(e);
        // 設計文件要求：精確列出衝突規則對，並提供觸發條件範例
        assertTrue(e.getMessage().contains("R01"), "訊息應包含 R01");
        assertTrue(e.getMessage().contains("R02"), "訊息應包含 R02");
        assertTrue(e.getMessage().contains("觸發條件範例"),
                "訊息應包含觸發條件範例：" + e.getMessage());
        // v3.7.0：IEEE 2024 structured witness 應獨立於 message 之外可機器解析
        assertNotNull(e.getWitness(),
                "衝突錯誤應含 structured witness（IEEE 2024 verification pattern）");
        assertFalse(e.getWitness().isEmpty(), "witness map 不應為空");
    }

    @Test
    @DisplayName("v3.7.0: 其他錯誤碼（非衝突）witness 應為 null（不污染 JSON）")
    void nonConflictError_hasNullWitness() throws Exception {
        JsonNode json = loadFixture("tc09-missing-field.json");
        List<ValidationError> errors = validator.validate(json);
        // 找出任一非 INCONSISTENT_TABLE 的錯誤
        ValidationError nonConflict = errors.stream()
                .filter(err -> !ErrorCodes.INCONSISTENT_TABLE.equals(err.getCode()))
                .findFirst()
                .orElse(null);
        assertNotNull(nonConflict, "fixture 應觸發至少一個非衝突類錯誤");
        assertNull(nonConflict.getWitness(),
                "非衝突類錯誤 witness 應為 null，經 @JsonInclude(NON_NULL) 會從 JSON 消失");
    }

    @Test
    @DisplayName("v3.7.0: between vs in (ENUM) 條件重疊 → witness 同時含兩欄位")
    void inconsistentTable_betweenAndIn_producesWitness() throws Exception {
        String envelope = """
            {
              "ruleType": "DecisionTable",
              "rule": {
                "hitPolicy": "FIRST",
                "inputs": [
                  { "name": "age", "typeRef": "INTEGER" },
                  { "name": "region", "typeRef": "ENUM", "allowedValues": ["N","C","S"] }
                ],
                "outputs": [ { "name": "grade", "typeRef": "STRING" } ],
                "rules": [
                  { "ruleId": "R01", "priority": 1,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [18, 30] },
                      { "field": "region", "operator": "in", "value": ["N","C"] }
                    ],
                    "results": [ { "field": "grade", "value": "A" } ] },
                  { "ruleId": "R02", "priority": 2,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [25, 40] },
                      { "field": "region", "operator": "in", "value": ["C","S"] }
                    ],
                    "results": [ { "field": "grade", "value": "B" } ] }
                ]
              },
              "schemaVersion": "1.0.0",
              "promptVersion": "p3.7.0"
            }
            """;
        JsonNode json = objectMapper.readTree(envelope);
        List<ValidationError> errors = validator.validate(json);
        ValidationError e = findFirstError(errors, ErrorCodes.INCONSISTENT_TABLE);
        assertNotNull(e, "between ∩ between + in ∩ in 會交集，應觸發衝突");
        assertNotNull(e.getWitness(), "witness 應存在");
        assertTrue(e.getWitness().containsKey("age"), "witness 應含 age 欄位：" + e.getWitness());
        assertTrue(e.getWitness().containsKey("region"), "witness 應含 region 欄位：" + e.getWitness());
    }

    @Test
    @DisplayName("v3.15 E1: 條件重疊但結果相同 → REDUNDANT_RULE(WARNING)，非 INCONSISTENT_TABLE")
    void redundantRule_sameResultsOverlap_isWarningNotConflict() throws Exception {
        String envelope = """
            {
              "ruleType": "DecisionTable",
              "rule": {
                "hitPolicy": "FIRST",
                "inputs": [ { "name": "age", "typeRef": "INTEGER" } ],
                "outputs": [ { "name": "grade", "typeRef": "STRING" } ],
                "rules": [
                  { "ruleId": "R01", "priority": 1,
                    "conditions": [ { "field": "age", "operator": "between", "value": [18, 40] } ],
                    "results": [ { "field": "grade", "value": "A" } ] },
                  { "ruleId": "R02", "priority": 2,
                    "conditions": [ { "field": "age", "operator": "between", "value": [30, 50] } ],
                    "results": [ { "field": "grade", "value": "A" } ] }
                ]
              },
              "schemaVersion": "1.0.0", "promptVersion": "p3.15.0"
            }
            """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(envelope));
        assertFalse(hasErrorCode(errors, ErrorCodes.INCONSISTENT_TABLE),
                "結果相同的重疊不應報 INCONSISTENT_TABLE：" + errors);
        ValidationError redundant = findFirstError(errors, ErrorCodes.REDUNDANT_RULE);
        assertNotNull(redundant, "結果相同的重疊應報 REDUNDANT_RULE：" + errors);
        assertEquals("WARNING", redundant.getSeverity(), "REDUNDANT_RULE 應為 WARNING 嚴重度");
    }

    @Test
    @DisplayName("v3.15 E1+E3: 條件重疊但結果不同 → INCONSISTENT_TABLE，witness 取重疊中點 + 訊息附區間")
    void conflict_differentResults_witnessIsMidpoint() throws Exception {
        String envelope = """
            {
              "ruleType": "DecisionTable",
              "rule": {
                "hitPolicy": "FIRST",
                "inputs": [ { "name": "age", "typeRef": "INTEGER" } ],
                "outputs": [ { "name": "grade", "typeRef": "STRING" } ],
                "rules": [
                  { "ruleId": "R01", "priority": 1,
                    "conditions": [ { "field": "age", "operator": "between", "value": [10, 20] } ],
                    "results": [ { "field": "grade", "value": "A" } ] },
                  { "ruleId": "R02", "priority": 2,
                    "conditions": [ { "field": "age", "operator": "between", "value": [14, 18] } ],
                    "results": [ { "field": "grade", "value": "B" } ] }
                ]
              },
              "schemaVersion": "1.0.0", "promptVersion": "p3.15.0"
            }
            """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(envelope));
        ValidationError conflict = findFirstError(errors, ErrorCodes.INCONSISTENT_TABLE);
        assertNotNull(conflict, "結果不同的重疊應報 INCONSISTENT_TABLE：" + errors);
        // E3：重疊區間 [14,18] → 中點 16
        assertEquals("16", conflict.getWitness().get("age"), "witness 應為重疊區間中點 16");
        assertTrue(conflict.getMessage().contains("重疊區間"), "訊息應附重疊區間：" + conflict.getMessage());
    }

    @Test
    @DisplayName("v3.7.0: equals vs notEquals 同值互斥 → 不觸發衝突、無 witness")
    void noWitness_whenEqualsAndNotEqualsMutuallyExclusive() throws Exception {
        String envelope = """
            {
              "ruleType": "DecisionTable",
              "rule": {
                "hitPolicy": "FIRST",
                "inputs": [ { "name": "status", "typeRef": "STRING" } ],
                "outputs": [ { "name": "action", "typeRef": "STRING" } ],
                "rules": [
                  { "ruleId": "R01", "priority": 1,
                    "conditions": [ { "field": "status", "operator": "equals", "value": "ACTIVE" } ],
                    "results": [ { "field": "action", "value": "PASS" } ] },
                  { "ruleId": "R02", "priority": 2,
                    "conditions": [ { "field": "status", "operator": "notEquals", "value": "ACTIVE" } ],
                    "results": [ { "field": "action", "value": "BLOCK" } ] }
                ]
              },
              "schemaVersion": "1.0.0",
              "promptVersion": "p3.7.0"
            }
            """;
        JsonNode json = objectMapper.readTree(envelope);
        List<ValidationError> errors = validator.validate(json);
        assertFalse(hasErrorCode(errors, ErrorCodes.INCONSISTENT_TABLE),
                "equals=ACTIVE vs notEquals=ACTIVE 互斥，不應觸發衝突：" + errors);
    }

    @Test
    @DisplayName("FIRST hitPolicy 且條件不重疊 → 不觸發 INCONSISTENT_TABLE")
    void noConflictWhenDisjoint() throws Exception {
        JsonNode json = loadFixture("tc01-valid-first.json");
        List<ValidationError> errors = validator.validate(json);
        assertFalse(hasErrorCode(errors, ErrorCodes.INCONSISTENT_TABLE),
                "TC01 的規則條件不重疊，不應觸發 INCONSISTENT_TABLE");
    }

    @Test
    @DisplayName("MULTI hitPolicy → 不做衝突偵測")
    void multiHitPolicyNoConflictCheck() throws Exception {
        JsonNode json = loadFixture("tc02-valid-multi.json");
        List<ValidationError> errors = validator.validate(json);
        assertFalse(hasErrorCode(errors, ErrorCodes.INCONSISTENT_TABLE),
                "MULTI hitPolicy 不應觸發 INCONSISTENT_TABLE");
    }

    // ================================================================
    // 10. MISSING_RESULTS
    // ================================================================

    @Test
    @DisplayName("規則缺少某個 output 欄位的結果 → MISSING_RESULTS")
    void missingResults() throws Exception {
        JsonNode json = loadFixture("missing-results.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, ErrorCodes.MISSING_RESULTS),
                "缺少 remark 結果應觸發 MISSING_RESULTS：" + errors);
        ValidationError e = findFirstError(errors, ErrorCodes.MISSING_RESULTS);
        assertTrue(e.getMessage().contains("remark"), "訊息應提到缺少的欄位 'remark'");
    }

    // ================================================================
    // 11. INVALID_MULTI
    // ================================================================

    @Test
    @DisplayName("MULTI hitPolicy 但只有 1 條規則 → INVALID_MULTI")
    void invalidMultiSingleRule() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "MULTI",
                    "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [{
                      "ruleId": "R01", "priority": 1,
                      "conditions": [{ "field": "age", "operator": "between", "value": [18, 65] }],
                      "results": [{ "field": "r", "value": "ok" }]
                    }]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.INVALID_MULTI),
                "MULTI 只有 1 條規則應觸發 INVALID_MULTI：" + errors);
    }

    // ================================================================
    // Edge cases
    // ================================================================

    @Test
    @DisplayName("空 rules 陣列 → 驗證通過（合法但無規則）")
    void emptyRules() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": []
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(errors.isEmpty(), "空 rules 應通過驗證：" + errors);
    }

    @Test
    @DisplayName("typeRef 不合法 → TYPE_MISMATCH")
    void invalidTypeRef() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age", "typeRef": "FLOAT" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": []
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(hasErrorCode(errors, ErrorCodes.TYPE_MISMATCH),
                "typeRef=FLOAT 不合法：" + errors);
    }

    @Test
    @DisplayName("isNull operator 不需要 value → 不報 TYPE_MISMATCH")
    void isNullNoValue() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "note", "typeRef": "STRING" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [{
                      "ruleId": "R01", "priority": 1,
                      "conditions": [{ "field": "note", "operator": "isNull" }],
                      "results": [{ "field": "r", "value": "空值" }]
                    }]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(errors.isEmpty(), "isNull 不需要 value：" + errors);
    }

    @Test
    @DisplayName("anything operator → 不限制任何東西，通過")
    void anythingOperator() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [{
                      "ruleId": "R01", "priority": 1,
                      "conditions": [{ "field": "age", "operator": "anything" }],
                      "results": [{ "field": "r", "value": "全部" }]
                    }]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(errors.isEmpty(), "anything 應通過：" + errors);
    }

    @Test
    @DisplayName("多個錯誤同時出現 → 全部回報")
    void multipleErrors() throws Exception {
        // 同時有 ENUM_VALUE_MISSING + UNKNOWN_OPERATOR + DUPLICATE_ID
        String json = """
                {
                  "ruleType": "DecisionTable",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "region", "typeRef": "ENUM" },
                      { "name": "age", "typeRef": "INTEGER" }
                    ],
                    "outputs": [{ "name": "r", "typeRef": "STRING" }],
                    "rules": [
                      { "ruleId": "R01", "priority": 1,
                        "conditions": [
                          { "field": "region", "operator": "equals", "value": "亞洲" },
                          { "field": "age", "operator": "regex", "value": "\\\\d+" }
                        ],
                        "results": [{ "field": "r", "value": "ok" }]
                      },
                      { "ruleId": "R01", "priority": 2,
                        "conditions": [
                          { "field": "region", "operator": "equals", "value": "歐洲" },
                          { "field": "age", "operator": "between", "value": [18, 65] }
                        ],
                        "results": [{ "field": "r", "value": "ok" }]
                      }
                    ]
                  }
                }
                """;
        List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
        assertTrue(errors.size() >= 3, "至少 3 個錯誤：" + errors);
        assertTrue(hasErrorCode(errors, ErrorCodes.ENUM_VALUE_MISSING), "ENUM_VALUE_MISSING");
        assertTrue(hasErrorCode(errors, ErrorCodes.UNKNOWN_OPERATOR), "UNKNOWN_OPERATOR");
        assertTrue(hasErrorCode(errors, ErrorCodes.DUPLICATE_ID), "DUPLICATE_ID");
    }

    @Test
    @DisplayName("null 輸入 → MISSING_FIELD")
    void nullInput() {
        List<ValidationError> errors = validator.validate((JsonNode) null);
        assertFalse(errors.isEmpty(), "null 輸入應觸發錯誤");
        assertTrue(hasErrorCode(errors, ErrorCodes.MISSING_FIELD));
    }

    // ================================================================
    // V1.1.0 fixture: 同一條規則中重複的 condition field
    // ================================================================

    @Test
    @DisplayName("v110: 同一條規則中 age 出現兩次 → INCONSISTENT_TABLE")
    void v110DupCondField() throws Exception {
        JsonNode json = loadFixture("v110-dup-cond-field.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, "INCONSISTENT_TABLE"),
                "同一規則中重複的 condition field 應觸發 INCONSISTENT_TABLE: " + errors);
    }

    // ================================================================
    // V1.1.0 fixture: input/output 欄位名稱重複
    // ================================================================

    @Test
    @DisplayName("v110: input 和 output 有相同的欄位名稱 → INCONSISTENT_TABLE")
    void v110NameOverlap() throws Exception {
        JsonNode json = loadFixture("v110-name-overlap.json");
        List<ValidationError> errors = validator.validate(json);
        assertTrue(hasErrorCode(errors, "INCONSISTENT_TABLE"),
                "input/output 名稱重複應觸發 INCONSISTENT_TABLE: " + errors);
    }
}
