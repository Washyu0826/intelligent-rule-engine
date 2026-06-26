package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 閉環驗收：Generate → Validate。
 *
 * 計畫書承諾：Generate 的輸出可被 validate 通過。
 * 設計文件 UC-01 Post-condition：回傳 JSON 可直接通過 /validate，valid = true
 */
@SpringBootTest
@DisplayName("V1.1.0 Phase 1 閉環驗收")
class GenerateValidateClosedLoopTest {

    @Autowired
    private RuleService ruleService;

    @Autowired
    private ObjectMapper objectMapper;

    // ================================================================
    // TC01: FIRST hitPolicy — 閉環
    // ================================================================
    @Test
    @DisplayName("TC01: 合法 FIRST DecisionTable → generate → validate = true")
    void tc01_closedLoop() throws Exception {
        String json = loadFixture("fixtures/tc01-valid-first.json");

        RuleEnvelope envelope = ruleService.generate(GenerateRequest.builder()
                .description(json).ruleType("DecisionTable").build());

        assertNotNull(envelope);
        assertEquals("DecisionTable", envelope.getRuleType());
        assertNotNull(envelope.getSchemaVersion());
        assertNotNull(envelope.getPromptVersion());
        assertNotNull(envelope.getEvaluation());

        // Closed loop: generate 輸出直接餵 validate
        JsonNode envelopeJson = objectMapper.valueToTree(envelope);
        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(envelopeJson).ruleType("DecisionTable").build());

        assertTrue(v.isValid(), "TC01 should pass validation. Errors: " + v.getErrors());
    }

    // ================================================================
    // TC02: MULTI hitPolicy — 閉環
    // ================================================================
    @Test
    @DisplayName("TC02: MULTI hitPolicy → generate → validate = true")
    void tc02_multi() throws Exception {
        String json = loadFixture("fixtures/tc02-valid-multi.json");

        RuleEnvelope envelope = ruleService.generate(GenerateRequest.builder()
                .description(json).ruleType("DecisionTable").build());

        JsonNode envelopeJson = objectMapper.valueToTree(envelope);
        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(envelopeJson).ruleType("DecisionTable").build());

        assertTrue(v.isValid(), "TC02 MULTI should pass. Errors: " + v.getErrors());
    }

    // ================================================================
    // TC07: INVALID_ENUM_VALUE
    // ================================================================
    @Test
    @DisplayName("TC07: ENUM 值錯誤 → validate = false + INVALID_ENUM_VALUE")
    void tc07_invalidEnum() throws Exception {
        JsonNode json = objectMapper.readTree(loadFixture("fixtures/tc07-invalid-enum.json"));

        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(json).ruleType("DecisionTable").build());

        assertFalse(v.isValid());
        assertTrue(v.getErrors().stream().anyMatch(e -> "INVALID_ENUM_VALUE".equals(e.getCode())),
                "Should contain INVALID_ENUM_VALUE");
    }

    // ================================================================
    // TC08: INCONSISTENT_TABLE
    // ================================================================
    @Test
    @DisplayName("TC08: 條件重疊 → validate = false + INCONSISTENT_TABLE (含觸發範例)")
    void tc08_conflict() throws Exception {
        JsonNode json = objectMapper.readTree(loadFixture("fixtures/tc08-conflict.json"));

        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(json).ruleType("DecisionTable").build());

        assertFalse(v.isValid());
        ValidationError conflict = v.getErrors().stream()
                .filter(e -> "INCONSISTENT_TABLE".equals(e.getCode()))
                .findFirst().orElse(null);
        assertNotNull(conflict, "Should contain INCONSISTENT_TABLE");
        assertTrue(conflict.getMessage().contains("R01"), "Should mention R01");
        assertTrue(conflict.getMessage().contains("R02"), "Should mention R02");
    }

    // ================================================================
    // TC09: MISSING_FIELD
    // ================================================================
    @Test
    @DisplayName("TC09: 缺 hitPolicy → validate = false + MISSING_FIELD")
    void tc09_missingField() throws Exception {
        JsonNode json = objectMapper.readTree(loadFixture("fixtures/tc09-missing-field.json"));

        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(json).ruleType("DecisionTable").build());

        assertFalse(v.isValid());
        assertTrue(v.getErrors().stream().anyMatch(e -> "MISSING_FIELD".equals(e.getCode())));
    }

    // ================================================================
    // Evaluation 指標
    // ================================================================
    @Test
    @DisplayName("generate 產出的 evaluation 指標正確")
    void evaluationMetrics() throws Exception {
        String json = loadFixture("fixtures/tc01-valid-first.json");

        RuleEnvelope envelope = ruleService.generate(GenerateRequest.builder()
                .description(json).ruleType("DecisionTable").build());

        assertNotNull(envelope.getEvaluation());
        RuleEnvelope.Evaluation eval = envelope.getEvaluation();
        assertNotNull(eval.getCompleteness());
        assertNotNull(eval.getTotalScenarios());
        assertNotNull(eval.getCoverageRate());
        assertNotNull(eval.getConflictDetection());
        assertEquals("NO_CONFLICT", eval.getConflictDetection());
        assertTrue(eval.getTotalScenarios() > 0, "Should have > 0 rules");
    }

    // ================================================================
    // 版本追溯
    // ================================================================
    @Test
    @DisplayName("generate 輸出包含 schemaVersion + promptVersion")
    void versionTracking() throws Exception {
        String json = loadFixture("fixtures/tc01-valid-first.json");

        RuleEnvelope envelope = ruleService.generate(GenerateRequest.builder()
                .description(json).ruleType("DecisionTable").build());

        assertNotNull(envelope.getSchemaVersion());
        assertNotNull(envelope.getPromptVersion());
        assertTrue(envelope.getSchemaVersion().matches("\\d+\\.\\d+\\.\\d+"));
        assertTrue(envelope.getPromptVersion().matches("p\\d+\\.\\d+\\.\\d+"));
    }

    // ================================================================
    // validate 面對 null 不拋 exception
    // ================================================================
    @Test
    @DisplayName("validate null input → valid=false，不拋 exception")
    void validateNull() {
        assertDoesNotThrow(() -> {
            ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                    .ruleJson(null).ruleType("DecisionTable").build());
            assertFalse(v.isValid());
        });
    }

    // ================================================================
    // Helper
    // ================================================================
    private String loadFixture(String path) throws Exception {
        return new org.springframework.core.io.ClassPathResource(path)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
