package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DecisionTreeValidator 完整測試 — Phase 3。
 *
 * 涵蓋所有錯誤碼：
 * - MISSING_FIELD, MISSING_BRANCH, MISSING_RESULTS
 * - UNKNOWN_FIELD, UNKNOWN_OPERATOR, TYPE_MISMATCH
 * - DUPLICATE_ID, ENUM_VALUE_MISSING, INVALID_ENUM_VALUE
 */
class DecisionTreeValidatorTest {

    private DecisionTreeValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        validator = new DecisionTreeValidator();
        // 使用反射設定 maxTreeDepth（@Value 在非 Spring 環境無效）
        try {
            var field = DecisionTreeValidator.class.getDeclaredField("maxTreeDepth");
            field.setAccessible(true);
            field.setInt(validator, 10);
        } catch (Exception e) {
            fail("無法設定 maxTreeDepth: " + e.getMessage());
        }
    }

    // ================================================================
    // 載入 fixture 工具
    // ================================================================

    private JsonNode loadFixture(String filename) throws IOException {
        var resource = new ClassPathResource("fixtures/" + filename);
        String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private boolean hasError(List<ValidationError> errors, String code) {
        return errors.stream().anyMatch(e -> code.equals(e.getCode()));
    }

    private long countErrors(List<ValidationError> errors, String code) {
        return errors.stream().filter(e -> code.equals(e.getCode())).count();
    }

    // ================================================================
    // 正向測試
    // ================================================================

    @Nested
    @DisplayName("正向：有效的 DecisionTree")
    class ValidTreeTests {

        @Test
        @DisplayName("基本有效 DecisionTree — 0 錯誤")
        void validBasicTree() throws Exception {
            JsonNode tree = loadFixture("tree-valid-basic.json");
            List<ValidationError> errors = validator.validate(tree);
            assertTrue(errors.isEmpty(), "預期 0 錯誤，但有：" + errors);
        }
    }

    // ================================================================
    // 結構錯誤
    // ================================================================

    @Nested
    @DisplayName("結構：MISSING_FIELD / MISSING_BRANCH")
    class StructureTests {

        @Test
        @DisplayName("缺少 ruleType")
        void missingRuleType() throws Exception {
            String json = """
                    { "rule": { "inputs": [], "outputs": [], "root": { "nodeId": "N01", "results": [] } } }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.MISSING_FIELD));
        }

        @Test
        @DisplayName("缺少 rule")
        void missingRule() throws Exception {
            String json = """
                    { "ruleType": "DecisionTree" }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.MISSING_FIELD));
        }

        @Test
        @DisplayName("缺少 root")
        void missingRoot() throws Exception {
            String json = """
                    { "ruleType": "DecisionTree", "rule": { "inputs": [], "outputs": [] } }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.MISSING_FIELD));
        }

        @Test
        @DisplayName("缺少 falseBranch → MISSING_BRANCH")
        void missingFalseBranch() throws Exception {
            JsonNode tree = loadFixture("tree-missing-branch.json");
            List<ValidationError> errors = validator.validate(tree);
            assertTrue(hasError(errors, ErrorCodes.MISSING_BRANCH),
                    "預期 MISSING_BRANCH，但有：" + errors);
        }

        @Test
        @DisplayName("空 results 在葉節點 → MISSING_RESULTS")
        void emptyResults() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                        "outputs": [{ "name": "decision", "typeRef": "STRING" }],
                        "root": {
                          "nodeId": "N01",
                          "condition": { "field": "age", "operator": "greaterThan", "value": 60 },
                          "trueBranch": { "nodeId": "N02", "results": [] },
                          "falseBranch": { "nodeId": "N03", "results": [{ "field": "decision", "value": "OK" }] }
                        }
                      }
                    }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.MISSING_RESULTS));
        }

        @Test
        @DisplayName("既無 condition 也無 results 的節點 → MISSING_BRANCH")
        void emptyNode() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                        "outputs": [{ "name": "decision", "typeRef": "STRING" }],
                        "root": {
                          "nodeId": "N01",
                          "condition": { "field": "age", "operator": "greaterThan", "value": 60 },
                          "trueBranch": { "nodeId": "N02" },
                          "falseBranch": { "nodeId": "N03", "results": [{ "field": "decision", "value": "OK" }] }
                        }
                      }
                    }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.MISSING_BRANCH));
        }
    }

    // ================================================================
    // 欄位定義錯誤
    // ================================================================

    @Nested
    @DisplayName("欄位定義：ENUM_VALUE_MISSING / TYPE_MISMATCH")
    class FieldDefinitionTests {

        @Test
        @DisplayName("ENUM 缺 allowedValues → ENUM_VALUE_MISSING")
        void enumMissingAllowedValues() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [{ "name": "risk", "typeRef": "ENUM" }],
                        "outputs": [{ "name": "decision", "typeRef": "STRING" }],
                        "root": { "nodeId": "N01", "results": [{ "field": "decision", "value": "OK" }] }
                      }
                    }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.ENUM_VALUE_MISSING));
        }

        @Test
        @DisplayName("typeRef 不合法 → TYPE_MISMATCH")
        void invalidTypeRef() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [{ "name": "age", "typeRef": "BIGINT" }],
                        "outputs": [{ "name": "decision", "typeRef": "STRING" }],
                        "root": { "nodeId": "N01", "results": [{ "field": "decision", "value": "OK" }] }
                      }
                    }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.TYPE_MISMATCH));
        }
    }

    // ================================================================
    // Condition 語義錯誤
    // ================================================================

    @Nested
    @DisplayName("Condition 語義驗證")
    class ConditionTests {

        @Test
        @DisplayName("UNKNOWN_FIELD — condition 引用未定義欄位")
        void unknownField() throws Exception {
            JsonNode tree = loadFixture("tree-unknown-field.json");
            List<ValidationError> errors = validator.validate(tree);
            assertTrue(hasError(errors, ErrorCodes.UNKNOWN_FIELD));
        }

        @Test
        @DisplayName("UNKNOWN_OPERATOR — 不支援的 operator")
        void unknownOperator() throws Exception {
            JsonNode tree = loadFixture("tree-unknown-operator.json");
            List<ValidationError> errors = validator.validate(tree);
            assertTrue(hasError(errors, ErrorCodes.UNKNOWN_OPERATOR));
        }

        @Test
        @DisplayName("TYPE_MISMATCH — BOOLEAN 欄位用 greaterThan")
        void typeMismatchBooleanOp() throws Exception {
            JsonNode tree = loadFixture("tree-type-mismatch.json");
            List<ValidationError> errors = validator.validate(tree);
            assertTrue(hasError(errors, ErrorCodes.TYPE_MISMATCH));
        }

        @Test
        @DisplayName("INVALID_ENUM_VALUE — ENUM condition 值不在 allowedValues")
        void invalidEnumConditionValue() throws Exception {
            JsonNode tree = loadFixture("tree-invalid-enum.json");
            List<ValidationError> errors = validator.validate(tree);
            assertTrue(hasError(errors, ErrorCodes.INVALID_ENUM_VALUE),
                    "預期 INVALID_ENUM_VALUE，但有：" + errors);
        }
    }

    // ================================================================
    // Results 錯誤
    // ================================================================

    @Nested
    @DisplayName("Results 驗證")
    class ResultsTests {

        @Test
        @DisplayName("MISSING_RESULTS — 葉節點缺少 output 欄位")
        void missingOutputInResults() throws Exception {
            JsonNode tree = loadFixture("tree-missing-results.json");
            List<ValidationError> errors = validator.validate(tree);
            assertTrue(hasError(errors, ErrorCodes.MISSING_RESULTS),
                    "預期 MISSING_RESULTS（缺少 remark），但有：" + errors);
        }

        @Test
        @DisplayName("INVALID_ENUM_VALUE — result 值不在 allowedValues")
        void invalidEnumResultValue() throws Exception {
            JsonNode tree = loadFixture("tree-invalid-enum.json");
            List<ValidationError> errors = validator.validate(tree);
            // "退回" 不在 ["承保", "拒保"] 中
            assertTrue(hasError(errors, ErrorCodes.INVALID_ENUM_VALUE));
        }
    }

    // ================================================================
    // DUPLICATE_ID
    // ================================================================

    @Nested
    @DisplayName("DUPLICATE_ID — nodeId 重複")
    class DuplicateIdTests {

        @Test
        @DisplayName("相同 nodeId 出現多次 → DUPLICATE_ID")
        void duplicateNodeId() throws Exception {
            JsonNode tree = loadFixture("tree-duplicate-nodeid.json");
            List<ValidationError> errors = validator.validate(tree);
            assertTrue(hasError(errors, ErrorCodes.DUPLICATE_ID));
            assertTrue(countErrors(errors, ErrorCodes.DUPLICATE_ID) >= 2,
                    "N01 重複了兩次（trueBranch 和 falseBranch），應有 >= 2 個 DUPLICATE_ID 錯誤");
        }
    }

    // ================================================================
    // 深度限制
    // ================================================================

    @Nested
    @DisplayName("深度限制")
    class DepthLimitTests {

        @Test
        @DisplayName("超過最大深度 → TYPE_MISMATCH")
        void exceedMaxDepth() throws Exception {
            // 設定 maxTreeDepth = 2
            try {
                var field = DecisionTreeValidator.class.getDeclaredField("maxTreeDepth");
                field.setAccessible(true);
                field.setInt(validator, 2);
            } catch (Exception e) {
                fail("無法設定 maxTreeDepth");
            }

            // 深度 3 的樹
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [
                          { "name": "a", "typeRef": "BOOLEAN" },
                          { "name": "b", "typeRef": "BOOLEAN" },
                          { "name": "c", "typeRef": "BOOLEAN" }
                        ],
                        "outputs": [{ "name": "result", "typeRef": "STRING" }],
                        "root": {
                          "nodeId": "N01",
                          "condition": { "field": "a", "operator": "equals", "value": true },
                          "trueBranch": {
                            "nodeId": "N02",
                            "condition": { "field": "b", "operator": "equals", "value": true },
                            "trueBranch": {
                              "nodeId": "N03",
                              "condition": { "field": "c", "operator": "equals", "value": true },
                              "trueBranch": { "nodeId": "N04", "results": [{ "field": "result", "value": "yes" }] },
                              "falseBranch": { "nodeId": "N05", "results": [{ "field": "result", "value": "no" }] }
                            },
                            "falseBranch": { "nodeId": "N06", "results": [{ "field": "result", "value": "no" }] }
                          },
                          "falseBranch": { "nodeId": "N07", "results": [{ "field": "result", "value": "no" }] }
                        }
                      }
                    }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.TYPE_MISMATCH),
                    "預期深度超限錯誤，但有：" + errors);
            assertTrue(errors.stream().anyMatch(e -> e.getMessage().contains("深度")));
        }
    }

    // ================================================================
    // 分支節點同時有 results → INCONSISTENT_TABLE
    // ================================================================

    @Nested
    @DisplayName("不一致：分支節點帶 results")
    class InconsistencyTests {

        @Test
        @DisplayName("分支節點同時有 condition 和 results")
        void branchWithResults() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                        "outputs": [{ "name": "decision", "typeRef": "STRING" }],
                        "root": {
                          "nodeId": "N01",
                          "condition": { "field": "age", "operator": "greaterThan", "value": 60 },
                          "results": [{ "field": "decision", "value": "bad" }],
                          "trueBranch": { "nodeId": "N02", "results": [{ "field": "decision", "value": "OK" }] },
                          "falseBranch": { "nodeId": "N03", "results": [{ "field": "decision", "value": "OK" }] }
                        }
                      }
                    }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, "INCONSISTENT_TABLE"),
                    "分支節點不應有 results，但有：" + errors);
        }
    }

    // ================================================================
    // null / 邊界測試
    // ================================================================

    @Nested
    @DisplayName("邊界情況")
    class EdgeCaseTests {

        @Test
        @DisplayName("null 輸入 → MISSING_FIELD")
        void nullInput() {
            List<ValidationError> errors = validator.validate((JsonNode) null);
            assertFalse(errors.isEmpty());
            assertTrue(hasError(errors, ErrorCodes.MISSING_FIELD));
        }

        @Test
        @DisplayName("ruleType 不正確 → TYPE_MISMATCH")
        void wrongRuleType() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTable",
                      "rule": {
                        "inputs": [{ "name": "a", "typeRef": "INTEGER" }],
                        "outputs": [{ "name": "b", "typeRef": "STRING" }],
                        "root": { "nodeId": "N01", "results": [{ "field": "b", "value": "x" }] }
                      }
                    }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(hasError(errors, ErrorCodes.TYPE_MISMATCH));
        }

        @Test
        @DisplayName("只有根葉節點的最小合法樹")
        void minimalValidTree() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [{ "name": "a", "typeRef": "INTEGER" }],
                        "outputs": [{ "name": "b", "typeRef": "STRING" }],
                        "root": { "nodeId": "N01", "results": [{ "field": "b", "value": "x" }] }
                      }
                    }
                    """;
            List<ValidationError> errors = validator.validate(objectMapper.readTree(json));
            assertTrue(errors.isEmpty(), "最小合法樹應 0 錯誤，但有：" + errors);
        }
    }
}
