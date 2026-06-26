package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.llm.OfflineFallbackService;
import com.ruleengine.rules.service.validator.DecisionTreeValidator;
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
 * DecisionTreeGenerator 測試 — Phase 3。
 *
 * 測試模式 A（JSON 解析 + 正規化 + evaluation 計算）
 * 以及 closed-loop 保證（generate 的輸出可被 validate 通過）。
 */
class DecisionTreeGeneratorTest {

    private DecisionTreeGenerator generator;
    private DecisionTreeValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        EnvelopeNormalizer envelopeNormalizer = new EnvelopeNormalizer();
        TreeNormalizer treeNormalizer = new TreeNormalizer(envelopeNormalizer);
        TreeEvaluationComputer treeEvaluationComputer = new TreeEvaluationComputer();
        OfflineFallbackService offlineFallbackService = new OfflineFallbackService();

        generator = new DecisionTreeGenerator(
                objectMapper, treeNormalizer, treeEvaluationComputer, offlineFallbackService);

        validator = new DecisionTreeValidator();
        try {
            var maxDepthField = DecisionTreeValidator.class.getDeclaredField("maxTreeDepth");
            maxDepthField.setAccessible(true);
            maxDepthField.setInt(validator, 10);
        } catch (Exception e) {
            fail("無法設定 maxTreeDepth");
        }

        // 注入 validator 到 generator
        try {
            var validatorSetter = DecisionTreeGenerator.class.getMethod(
                    "setDecisionTreeValidator", com.ruleengine.rules.service.validator.RuleValidator.class);
            validatorSetter.invoke(generator, validator);
        } catch (Exception e) {
            // ignore — validator 是可選的
        }

        // 設定 schemaVersion / promptVersion
        try {
            var svField = DecisionTreeGenerator.class.getDeclaredField("schemaVersion");
            svField.setAccessible(true);
            svField.set(generator, "1.0.0");
            var pvField = DecisionTreeGenerator.class.getDeclaredField("promptVersion");
            pvField.setAccessible(true);
            pvField.set(generator, "p2.0.0");
        } catch (Exception e) {
            fail("無法設定 version fields");
        }
    }

    private String loadFixture(String filename) throws IOException {
        var resource = new ClassPathResource("fixtures/" + filename);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    // ================================================================
    // 模式 A：JSON 輸入 → 正規化 → 回傳
    // ================================================================

    @Nested
    @DisplayName("模式 A — JSON 輸入")
    class ModeATests {

        @Test
        @DisplayName("完整 RuleEnvelope JSON → generate → 有效 envelope")
        void validTreeJson() throws Exception {
            String treeJson = loadFixture("tree-valid-basic.json");
            JsonNode result = generator.generate(treeJson, null);
            assertNotNull(result);

            RuleEnvelope envelope = objectMapper.treeToValue(result, RuleEnvelope.class);
            assertEquals("DecisionTree", envelope.getRuleType());
            assertNotNull(envelope.getRule());
            assertNotNull(envelope.getRule().getRoot());
            assertNotNull(envelope.getEvaluation());
            assertTrue(envelope.getEvaluation().getTotalScenarios() > 0,
                    "應有至少 1 個葉節點");
        }

        @Test
        @DisplayName("generate 的輸出可被 validate 通過（closed-loop）")
        void closedLoopGuarantee() throws Exception {
            String treeJson = loadFixture("tree-valid-basic.json");
            JsonNode result = generator.generate(treeJson, null);

            List<ValidationError> errors = validator.validate(result);
            assertTrue(errors.isEmpty(),
                    "generate 的輸出應該通過 validate，但有 " + errors.size() + " 個錯誤：" + errors);
        }

        @Test
        @DisplayName("Evaluation 指標正確：3 個葉節點")
        void evaluationMetrics() throws Exception {
            String treeJson = loadFixture("tree-valid-basic.json");
            JsonNode result = generator.generate(treeJson, null);
            RuleEnvelope envelope = objectMapper.treeToValue(result, RuleEnvelope.class);

            assertEquals(3, envelope.getEvaluation().getTotalScenarios(),
                    "tree-valid-basic.json 有 3 個葉節點");
            assertEquals("COMPLETE", envelope.getEvaluation().getCompleteness());
            assertEquals(1.0, envelope.getEvaluation().getCoverageRate());
            assertEquals("NO_CONFLICT", envelope.getEvaluation().getConflictDetection());
        }

        @Test
        @DisplayName("版本號正確")
        void versionTracking() throws Exception {
            String treeJson = loadFixture("tree-valid-basic.json");
            JsonNode result = generator.generate(treeJson, null);
            RuleEnvelope envelope = objectMapper.treeToValue(result, RuleEnvelope.class);

            assertEquals("1.0.0", envelope.getSchemaVersion());
            assertEquals("p2.0.0", envelope.getPromptVersion());
        }
    }

    // ================================================================
    // 正規化
    // ================================================================

    @Nested
    @DisplayName("正規化")
    class NormalizationTests {

        @Test
        @DisplayName("自動補 nodeId")
        void autoNodeId() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                        "outputs": [{ "name": "decision", "typeRef": "STRING" }],
                        "root": {
                          "condition": { "field": "age", "operator": "greaterThan", "value": 60 },
                          "trueBranch": { "results": [{ "field": "decision", "value": "A" }] },
                          "falseBranch": { "results": [{ "field": "decision", "value": "B" }] }
                        }
                      }
                    }
                    """;
            JsonNode result = generator.generate(json, null);
            RuleEnvelope envelope = objectMapper.treeToValue(result, RuleEnvelope.class);

            assertNotNull(envelope.getRule().getRoot().getNodeId(), "root 應有 nodeId");
            // 正規化後 trueBranch/falseBranch 已轉為 branches
            assertNotNull(envelope.getRule().getRoot().getBranches(), "root 應有 branches");
            assertEquals(2, envelope.getRule().getRoot().getBranches().size(), "二元樹應有 2 個 branches");
            assertNotNull(envelope.getRule().getRoot().getBranches().get(0).getChild().getNodeId());
            assertNotNull(envelope.getRule().getRoot().getBranches().get(1).getChild().getNodeId());
        }

        @Test
        @DisplayName("typeRef alias 修正（int → INTEGER）")
        void typeRefAlias() throws Exception {
            String json = """
                    {
                      "ruleType": "DecisionTree",
                      "rule": {
                        "inputs": [{ "name": "age", "typeRef": "int" }],
                        "outputs": [{ "name": "decision", "typeRef": "str" }],
                        "root": {
                          "nodeId": "N01",
                          "results": [{ "field": "decision", "value": "OK" }]
                        }
                      }
                    }
                    """;
            JsonNode result = generator.generate(json, null);
            RuleEnvelope envelope = objectMapper.treeToValue(result, RuleEnvelope.class);

            assertEquals("INTEGER", envelope.getRule().getInputs().get(0).getTypeRef());
            assertEquals("STRING", envelope.getRule().getOutputs().get(0).getTypeRef());
        }
    }

    // ================================================================
    // 空輸入
    // ================================================================

    @Test
    @DisplayName("空輸入 → stub envelope")
    void emptyInput() {
        JsonNode result = generator.generate("", null);
        assertNotNull(result);
        RuleEnvelope envelope;
        try {
            envelope = objectMapper.treeToValue(result, RuleEnvelope.class);
        } catch (Exception e) {
            fail("反序列化失敗");
            return;
        }
        assertEquals("DecisionTree", envelope.getRuleType());
        assertEquals("INCOMPLETE", envelope.getEvaluation().getCompleteness());
    }

    @Test
    @DisplayName("supportedType 是 DECISION_TREE")
    void supportedType() {
        assertEquals(RuleType.DECISION_TREE, generator.supportedType());
    }
}
