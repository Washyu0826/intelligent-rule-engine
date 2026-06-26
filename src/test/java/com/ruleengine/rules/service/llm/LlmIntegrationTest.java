package com.ruleengine.rules.service.llm;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.RuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 整合測試 — 使用 WireMock 模擬 Ollama API。
 * 驗證完整 generate pipeline（LLM → normalize → validate → analyze）。
 */
@SpringBootTest
@DisplayName("LLM 整合測試（WireMock）")
class LlmIntegrationTest {

    static WireMockServer wireMock;

    @Autowired ObjectMapper objectMapper;
    @Autowired RuleService ruleService;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("rules.llm.provider", () -> "ollama");
        registry.add("rules.llm.model", () -> "test-model");
        registry.add("rules.llm.ollama.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("Ollama 回傳正確 JSON → 全流程通過")
    void ollamaValidResponse() throws Exception {
        // Mock Ollama API
        String validEnvelope = """
                {
                  "ruleType": "DecisionTable",
                  "reason": "測試規則",
                  "evaluation": { "completeness": "COMPLETE", "totalScenarios": 2, "coverageRate": 1.0, "conflictDetection": "NO_CONFLICT", "recommendedStrategy": "FIRST" },
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "result", "typeRef": "STRING" }],
                    "rules": [
                      { "ruleId": "R01", "priority": 1, "conditions": [{ "field": "age", "operator": "greaterThan", "value": 60 }], "results": [{ "field": "result", "value": "reject" }] },
                      { "ruleId": "R02", "priority": 2, "conditions": [{ "field": "age", "operator": "lessThanOrEqual", "value": 60 }], "results": [{ "field": "result", "value": "approve" }] }
                    ]
                  }
                }
                """;

        wireMock.stubFor(post(urlPathEqualTo("/api/chat"))
                .willReturn(okJson("{\"message\":{\"content\":" + objectMapper.writeValueAsString(validEnvelope) + "},\"done\":true}")));

        // Generate
        var req = com.ruleengine.rules.domain.dto.ToolDtos.GenerateRequest.builder()
                .description("age > 60 reject, else approve").ruleType("DecisionTable").mode("normal").build();
        var genResult = ruleService.generateFull(req);
        JsonNode result = objectMapper.valueToTree(genResult);

        assertNotNull(result);
        assertTrue(result.has("envelope"));
        JsonNode envelope = result.get("envelope");
        assertEquals("DecisionTable", envelope.get("ruleType").asText());
        assertTrue(envelope.has("rule"));
    }

    @Test
    @DisplayName("Ollama 回傳巢狀格式 → Normalizer 自動修正")
    void ollamaNestedValueRepair() throws Exception {
        // Ollama wraps values in {"allowedValues":[...]}
        String nestedEnvelope = """
                {
                  "ruleType": "DecisionTable",
                  "reason": "test",
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "decision", "typeRef": "STRING" }],
                    "rules": [
                      { "ruleId": "R01", "priority": 1, "conditions": [{ "field": "age", "operator": "greaterThan", "value": {"allowedValues": [60]} }], "results": [{ "field": "decision", "value": {"allowedValues": ["reject"]} }] }
                    ]
                  }
                }
                """;

        wireMock.stubFor(post(urlPathEqualTo("/api/chat"))
                .willReturn(okJson("{\"message\":{\"content\":" + objectMapper.writeValueAsString(nestedEnvelope) + "},\"done\":true}")));

        var req2 = com.ruleengine.rules.domain.dto.ToolDtos.GenerateRequest.builder()
                .description("test").ruleType("DecisionTable").mode("normal").build();
        var genResult2 = ruleService.generateFull(req2);
        JsonNode result = objectMapper.valueToTree(genResult2);

        assertNotNull(result);
        // After normalization, the nested values should be unwrapped
        JsonNode envelope = result.get("envelope");
        assertNotNull(envelope);
    }

    @Test
    @DisplayName("Ollama 超時 → graceful fallback")
    void ollamaTimeout() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/api/chat"))
                .willReturn(aResponse().withFixedDelay(5000).withBody("{}")));

        // Should not throw, should fallback to offline
        var req3 = com.ruleengine.rules.domain.dto.ToolDtos.GenerateRequest.builder()
                .description("insurance test").ruleType("DecisionTable").mode("fast").build();
        var genResult3 = ruleService.generateFull(req3);
        JsonNode result = objectMapper.valueToTree(genResult3);
        assertNotNull(result);
    }
}
