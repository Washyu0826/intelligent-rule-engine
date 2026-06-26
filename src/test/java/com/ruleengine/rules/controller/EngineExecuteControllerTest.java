package com.ruleengine.rules.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link EngineExecuteController}
 * (POST /tools/execute) — Demo-sprint P1 #8.
 *
 * <p>Asserts the {@link com.ruleengine.rules.service.engine.ExecutionResult}
 * contract documented in DEMO_PLAN_3H §3.3 using the sample-A MULTI
 * DecisionTable fixture (4 rules: R01–R04 over insured/policyholder ID-format
 * × nationality consistency).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("EngineExecuteController — POST /tools/execute")
class EngineExecuteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ================================================================
    // sample-A R01 — insured 身分證 + 國籍 NON_TW (1.1 message)
    // ================================================================

    @Test
    @DisplayName("sample-A + insuredId=true, insuredNat=NON_TW → matched=true, hitRuleId=R01, errorMessage 開頭 1.1, engineName=mock-group")
    void executeSampleA_inputMatchesR01() throws Exception {
        String envelopeJson = loadGolden("sample-A/expected-envelope.json");

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("insuredIdFormatIsRocId", true);
        inputs.put("insuredNationality", "NON_TW");
        inputs.put("policyholderIdFormatIsRocId", true);
        inputs.put("policyholderNationality", "TW");

        String requestBody = "{\"envelope\":" + envelopeJson
                + ",\"inputValues\":" + objectMapper.writeValueAsString(inputs) + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvc.getResponse().getContentAsString());

        assertThat(response.get("matched").asBoolean()).isTrue();
        assertThat(response.get("hitRuleId").asText()).isEqualTo("R01");

        // results is a flat map { field → value } per ExecutionResult contract
        String errorMessage = response.path("results").path("errorMessage").asText("");
        assertThat(errorMessage)
                .as("results.errorMessage")
                .startsWith("1.1");

        assertThat(response.path("engineName").asText())
                .as("engineName")
                .isEqualTo("mock-group");
    }

    // ================================================================
    // sample-A all "happy" inputs — no rule matches
    // ================================================================

    @Test
    @DisplayName("sample-A + 全部一致組合 → matched=false, hitRuleId=null, results 空")
    void executeSampleA_inputMatchesNothing() throws Exception {
        String envelopeJson = loadGolden("sample-A/expected-envelope.json");

        // No rule should fire:
        //   R01 needs insuredNat=NON_TW (this has TW)
        //   R02 needs insuredIdFormat=false (this has true)
        //   R03 needs policyholderNat=NON_TW (this has TW)
        //   R04 needs policyholderIdFormat=false (this has true)
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("insuredIdFormatIsRocId", true);
        inputs.put("insuredNationality", "TW");
        inputs.put("policyholderIdFormatIsRocId", true);
        inputs.put("policyholderNationality", "TW");

        String requestBody = "{\"envelope\":" + envelopeJson
                + ",\"inputValues\":" + objectMapper.writeValueAsString(inputs) + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvc.getResponse().getContentAsString());

        assertThat(response.get("matched").asBoolean())
                .as("matched")
                .isFalse();
        // hitRuleId is serialised with @JsonInclude(NON_NULL) — when null it
        // simply is absent from the response, which is the contract-correct
        // shape for "no hit". Accept either absent or explicit null.
        JsonNode hitRuleId = response.get("hitRuleId");
        assertThat(hitRuleId == null || hitRuleId.isNull())
                .as("hitRuleId should be null/absent when matched=false; got " + hitRuleId)
                .isTrue();
        // results: an empty Map{} also gets emitted as {}; either absent or empty is acceptable
        JsonNode results = response.get("results");
        if (results != null) {
            assertThat(results.size())
                    .as("results map size when matched=false")
                    .isEqualTo(0);
        }
    }

    // ================================================================
    // sample-A MULTI hit — both R01 and R03 fire simultaneously
    // ================================================================

    @Test
    @DisplayName("sample-A MULTI + 雙重違規 → allMatches.size() > 1 或 hitPath.size() > 1")
    void executeMultiHitPolicy_returnsAllMatches() throws Exception {
        String envelopeJson = loadGolden("sample-A/expected-envelope.json");

        // sample-A's hitPolicy is MULTI. Triggering BOTH R01 and R03:
        //   R01: insuredIdFormat=true AND insuredNationality=NON_TW
        //   R03: policyholderIdFormat=true AND policyholderNationality=NON_TW
        // So the inputs below fire R01 (insured side) AND R03 (policyholder side).
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("insuredIdFormatIsRocId", true);
        inputs.put("insuredNationality", "NON_TW");
        inputs.put("policyholderIdFormatIsRocId", true);
        inputs.put("policyholderNationality", "NON_TW");

        String requestBody = "{\"envelope\":" + envelopeJson
                + ",\"inputValues\":" + objectMapper.writeValueAsString(inputs) + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvc.getResponse().getContentAsString());

        assertThat(response.get("matched").asBoolean()).isTrue();

        int allMatchesSize = response.path("allMatches").isArray()
                ? response.get("allMatches").size() : 0;
        int hitPathSize = response.path("hitPath").isArray()
                ? response.get("hitPath").size() : 0;

        assertThat(allMatchesSize > 1 || hitPathSize > 1)
                .as("expected allMatches.size() > 1 OR hitPath.size() > 1 for MULTI hit; "
                        + "allMatches=" + response.path("allMatches")
                        + ", hitPath=" + response.path("hitPath"))
                .isTrue();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private String loadGolden(String relativePath) throws Exception {
        Path p = Path.of("golden-tests", relativePath).toAbsolutePath();
        if (!Files.exists(p)) {
            p = Path.of("..", "golden-tests", relativePath).toAbsolutePath().normalize();
        }
        return Files.readString(p);
    }
}
