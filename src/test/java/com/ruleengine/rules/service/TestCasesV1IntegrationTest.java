package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * test-cases-v1.json 整合測試。
 *
 * 計畫書 Phase 1 KPI：10 筆案例閉環跑通。
 *
 * 測試策略：
 * - 已有 fixture 的案例（TC01, TC02, TC07-TC09）→ generate + validate 閉環
 * - 所有案例 → recommend 分類準確率
 * - test-run 端點 → 批次執行驗證
 */
@SpringBootTest
@DisplayName("test-cases-v1.json 整合測試（Phase 1 KPI）")
class TestCasesV1IntegrationTest {

    @Autowired
    private RuleService ruleService;

    @Autowired
    private ObjectMapper objectMapper;

    private List<Map<String, Object>> testCases;

    @BeforeEach
    void setUp() throws Exception {
        String json = new org.springframework.core.io.ClassPathResource("testcases/test-cases-v1.json")
                .getContentAsString(StandardCharsets.UTF_8);
        testCases = objectMapper.readValue(json, new TypeReference<>() {});
    }

    // ================================================================
    // 已有 fixture 的案例：generate → validate 閉環
    // ================================================================

    @Test
    @DisplayName("TC01: FIRST 壽險核保 fixture → generate → validate = true")
    void tc01ClosedLoop() throws Exception {
        String fixture = loadFixture("fixtures/tc01-valid-first.json");
        RuleEnvelope envelope = ruleService.generate(GenerateRequest.builder()
                .description(fixture).ruleType("DecisionTable").build());

        assertNotNull(envelope);
        assertEquals("DecisionTable", envelope.getRuleType());

        JsonNode envelopeJson = objectMapper.valueToTree(envelope);
        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(envelopeJson).ruleType("DecisionTable").build());
        assertTrue(v.isValid(), "TC01 should pass: " + v.getErrors());
    }

    @Test
    @DisplayName("TC02: MULTI 健康獎勵 fixture → generate → validate = true")
    void tc02ClosedLoop() throws Exception {
        String fixture = loadFixture("fixtures/tc02-valid-multi.json");
        RuleEnvelope envelope = ruleService.generate(GenerateRequest.builder()
                .description(fixture).ruleType("DecisionTable").build());

        JsonNode envelopeJson = objectMapper.valueToTree(envelope);
        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(envelopeJson).ruleType("DecisionTable").build());
        assertTrue(v.isValid(), "TC02 should pass: " + v.getErrors());
    }

    @Test
    @DisplayName("TC07: ENUM 白名單錯誤 fixture → validate = false + INVALID_ENUM_VALUE")
    void tc07InvalidEnum() throws Exception {
        JsonNode fixture = objectMapper.readTree(loadFixture("fixtures/tc07-invalid-enum.json"));
        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(fixture).ruleType("DecisionTable").build());

        assertFalse(v.isValid());
        assertTrue(v.getErrors().stream().anyMatch(e -> "INVALID_ENUM_VALUE".equals(e.getCode())));
    }

    @Test
    @DisplayName("TC08: 衝突偵測 fixture → validate = false + INCONSISTENT_TABLE")
    void tc08Conflict() throws Exception {
        JsonNode fixture = objectMapper.readTree(loadFixture("fixtures/tc08-conflict.json"));
        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(fixture).ruleType("DecisionTable").build());

        assertFalse(v.isValid());
        assertTrue(v.getErrors().stream().anyMatch(e -> "INCONSISTENT_TABLE".equals(e.getCode())));
    }

    @Test
    @DisplayName("TC09: 缺 hitPolicy fixture → validate = false + MISSING_FIELD")
    void tc09MissingField() throws Exception {
        JsonNode fixture = objectMapper.readTree(loadFixture("fixtures/tc09-missing-field.json"));
        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(fixture).ruleType("DecisionTable").build());

        assertFalse(v.isValid());
        assertTrue(v.getErrors().stream().anyMatch(e -> "MISSING_FIELD".equals(e.getCode())));
    }

    // ================================================================
    // 推薦分類準確率
    // ================================================================

    @Test
    @DisplayName("TC01~TC09 推薦分類 → 全部為 DecisionTable")
    void recommendDecisionTableCases() {
        for (int i = 0; i < 9; i++) {
            Map<String, Object> tc = testCases.get(i);
            String desc = (String) tc.get("description");
            String id = (String) tc.get("id");

            RecommendResponse resp = ruleService.recommend(
                    RecommendRequest.builder().description(desc).build());

            assertEquals("DecisionTable", resp.getRecommendedRuleType(),
                    id + " 應推薦 DecisionTable，但推薦了 " + resp.getRecommendedRuleType());
        }
    }

    @Test
    @DisplayName("TC10: DecisionTree 描述 → 推薦 DecisionTree")
    void recommendDecisionTree() {
        Map<String, Object> tc10 = testCases.get(9);
        String desc = (String) tc10.get("description");

        RecommendResponse resp = ruleService.recommend(
                RecommendRequest.builder().description(desc).build());

        assertEquals("DecisionTree", resp.getRecommendedRuleType(),
                "TC10 應推薦 DecisionTree，但推薦了 " + resp.getRecommendedRuleType());
    }

    // ================================================================
    // test-run 端點批次執行
    // ================================================================

    @Test
    @DisplayName("test-run: TC01+TC02 fixture 批次執行 → passRate > 0")
    void testRunBatch() throws Exception {
        String fixture1 = loadFixture("fixtures/tc01-valid-first.json");
        String fixture2 = loadFixture("fixtures/tc02-valid-multi.json");

        TestRunRequest request = TestRunRequest.builder()
                .testCases(List.of(
                        TestCase.builder().id("TC01").description(fixture1)
                                .expectedRuleType("DecisionTable").expectValid(true).build(),
                        TestCase.builder().id("TC02").description(fixture2)
                                .expectedRuleType("DecisionTable").expectValid(true).build()
                ))
                .build();

        TestRunResponse response = ruleService.testRun(request);

        assertEquals(2, response.getTotal());
        assertTrue(response.getPassed() >= 1, "至少 1 筆通過");
        assertTrue(response.getPassRate() > 0);
        assertNotNull(response.getResults());
        assertEquals(2, response.getResults().size());
    }

    // ================================================================
    // test-cases-v1.json 完整性驗證
    // ================================================================

    @Test
    @DisplayName("test-cases-v1.json 有 10 筆案例")
    void testCasesFileHas10Cases() {
        assertEquals(10, testCases.size());
    }

    @Test
    @DisplayName("每筆案例都有 id, description, expectedRuleType")
    void testCasesStructureValid() {
        for (Map<String, Object> tc : testCases) {
            assertNotNull(tc.get("id"), "缺少 id");
            assertNotNull(tc.get("description"), "缺少 description");
            assertNotNull(tc.get("expectedRuleType"), tc.get("id") + " 缺少 expectedRuleType");
        }
    }

    // ================================================================
    // Helper
    // ================================================================

    private String loadFixture(String path) throws Exception {
        return new org.springframework.core.io.ClassPathResource(path)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
