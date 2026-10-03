package com.ruleengine.rules.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RuleChainController - 檢核 → 核保 線性串接")
class RuleChainControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    static final String CHECKS = """
            {"ruleKey":"%s","envelope":{"ruleType":"DecisionTable","reason":"檢核","rule":{"hitPolicy":"MULTI",
             "inputs":[{"name":"idFormat","typeRef":"ENUM","allowedValues":["LOCAL","FOREIGN"]},{"name":"nationality","typeRef":"ENUM","allowedValues":["TW","OTHER"]}],
             "outputs":[{"name":"errorCode","typeRef":"STRING"}],
             "rules":[{"ruleId":"C1","priority":1,"conditions":[{"field":"idFormat","operator":"equals","value":"LOCAL"},{"field":"nationality","operator":"notEquals","value":"TW"}],"results":[{"field":"errorCode","value":"1.1"}]}]}}}""";

    static final String UNDERWRITING = """
            {"ruleKey":"%s","envelope":{"ruleType":"DecisionTable","reason":"核保","rule":{"hitPolicy":"FIRST",
             "inputs":[{"name":"age","typeRef":"INTEGER"}],
             "outputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","人工評估"]}],
             "rules":[{"ruleId":"R1","priority":1,"conditions":[{"field":"age","operator":"lessThanOrEqual","value":50}],"results":[{"field":"decision","value":"承保"}]},
                      {"ruleId":"R2","priority":2,"conditions":[{"field":"age","operator":"greaterThan","value":50}],"results":[{"field":"decision","value":"人工評估"}]}]}}}""";

    static final String PRICING = """
            {"ruleKey":"%s","envelope":{"ruleType":"DecisionTable","reason":"費率","rule":{"hitPolicy":"FIRST",
             "inputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","人工評估"]}],
             "outputs":[{"name":"factor","typeRef":"DECIMAL"}],
             "rules":[{"ruleId":"P1","priority":1,"conditions":[{"field":"decision","operator":"equals","value":"承保"}],"results":[{"field":"factor","value":1.0}]},
                      {"ruleId":"P2","priority":2,"conditions":[{"field":"decision","operator":"equals","value":"人工評估"}],"results":[{"field":"factor","value":1.5}]}]}}}""";

    private String token(String user) throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"demo-pass-2026\"}".formatted(user)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private void activate(String maker, String checker, String key, String envelopeTemplate) throws Exception {
        MvcResult r = mvc.perform(post("/rules").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content(envelopeTemplate.formatted(key)))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/rules/" + id + "/submit").header("Authorization", "Bearer " + maker)
                .contentType("application/json").content("{\"reason\":\"chain test\"}")).andExpect(status().isOk());
        mvc.perform(post("/rules/" + id + "/approve").header("Authorization", "Bearer " + checker)
                .contentType("application/json").content("{\"comment\":\"ok\"}")).andExpect(status().isOk());
        mvc.perform(post("/rules/" + id + "/activate").header("Authorization", "Bearer " + checker)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("檢核通過 → 核保輸出成為費率輸入；檢核命中 → 中斷")
    void chainRunsAndStops() throws Exception {
        String maker = token("maker"), checker = token("checker");
        activate(maker, checker, "chain.checks", CHECKS);
        activate(maker, checker, "chain.uw", UNDERWRITING);
        activate(maker, checker, "chain.pricing", PRICING);

        mvc.perform(put("/rules/chains/chain.main").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content("""
                        {"name":"新契約主流程","steps":[
                          {"ruleKey":"chain.checks","stopOnHit":true,"label":"檢核"},
                          {"ruleKey":"chain.uw","stopOnHit":false,"label":"核保"},
                          {"ruleKey":"chain.pricing","stopOnHit":false,"label":"費率"}]}"""))
                .andExpect(status().isOk());

        MvcResult ok = mvc.perform(post("/engine/chain/chain.main/execute").header("Authorization", "Bearer " + maker)
                        .contentType("application/json")
                        .content("{\"input\":{\"idFormat\":\"LOCAL\",\"nationality\":\"TW\",\"age\":60}}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode out = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertFalse(out.get("stopped").asBoolean(), out.toString());
        assertEquals(3, out.get("steps").size());
        assertEquals("人工評估", out.at("/finalOutputs/decision").asText());
        assertEquals(1.5, out.at("/finalOutputs/factor").asDouble(), 1e-9);

        MvcResult stopped = mvc.perform(post("/engine/chain/chain.main/execute").header("Authorization", "Bearer " + maker)
                        .contentType("application/json")
                        .content("{\"input\":{\"idFormat\":\"LOCAL\",\"nationality\":\"OTHER\",\"age\":30}}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode s = objectMapper.readTree(stopped.getResponse().getContentAsString());
        assertTrue(s.get("stopped").asBoolean());
        assertEquals("chain.checks", s.get("stoppedAt").asText());
        assertEquals(1, s.get("steps").size());
        assertEquals("1.1", s.at("/steps/0/outputs/errorCode").asText());

        mvc.perform(get("/rules/chains").header("Authorization", "Bearer " + maker)).andExpect(status().isOk());
        mvc.perform(put("/rules/chains/chain.x").header("Authorization", "Bearer " + checker)
                        .contentType("application/json").content("{\"name\":\"x\",\"steps\":[{\"ruleKey\":\"a\"}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("精算邊界：係數 2.5 的草稿送審被擋（422），訊息提示升級給精算")
    void submitBlockedByBounds() throws Exception {
        String maker = token("maker");
        MvcResult r = mvc.perform(post("/rules").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content("""
                        {"ruleKey":"bounds.block","envelope":{"ruleType":"DecisionTable","reason":"b","rule":{"hitPolicy":"FIRST",
                         "inputs":[{"name":"age","typeRef":"INTEGER"}],
                         "outputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","人工評估"]},{"name":"factor","typeRef":"DECIMAL"}],
                         "rules":[{"ruleId":"R1","priority":1,"conditions":[{"field":"age","operator":"between","value":[66,80]}],"results":[{"field":"decision","value":"承保"},{"field":"factor","value":2.5}]}]}}}"""))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();

        MvcResult sheet = mvc.perform(get("/rules/" + id + "/review-sheet").header("Authorization", "Bearer " + maker))
                .andExpect(status().isOk()).andReturn();
        JsonNode bounds = objectMapper.readTree(sheet.getResponse().getContentAsString()).at("/impact/bounds");
        assertTrue(bounds.get("checked").asBoolean());
        assertEquals(2, bounds.get("violations").size(), bounds.toString());

        MvcResult blocked = mvc.perform(post("/rules/" + id + "/submit").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content("{\"reason\":\"try\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode body = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("BOUNDS_VIOLATION", body.get("error").asText());
        assertTrue(body.get("message").asText().contains("精算"));
        assertEquals(2, body.get("violations").size());
    }
}
