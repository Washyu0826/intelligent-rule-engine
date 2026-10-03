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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RuleWorkbenchController - 目錄/標籤、送審理由、審核單")
class RuleWorkbenchControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private static final String ENVELOPE = """
            {"ruleKey":"%s","envelope":{"ruleType":"DecisionTable","reason":"workbench test",
             "rule":{"hitPolicy":"FIRST","rules":[{"ruleId":"R01","priority":1,
             "conditions":[{"field":"score","operator":"greaterThanOrEqual","value":%d}],
             "results":[{"field":"decision","value":"approve"}]}]}}}""";

    private String token(String user) throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"demo-pass-2026\"}".formatted(user)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private long createDraft(String token, String key, int threshold) throws Exception {
        MvcResult r = mvc.perform(post("/rules").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(ENVELOPE.formatted(key, threshold)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private void submit(String token, long id, String reason) throws Exception {
        mvc.perform(post("/rules/" + id + "/submit").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode reviewSheet(String token, long id) throws Exception {
        MvcResult r = mvc.perform(get("/rules/" + id + "/review-sheet")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("送審不帶理由 → 400；理由空白 → 400")
    void submitRequiresReason() throws Exception {
        String maker = token("maker");
        long id = createDraft(maker, "wb.noreason", 700);

        mvc.perform(post("/rules/" + id + "/submit").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/rules/" + id + "/submit").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("首版送審：審核單帶理由與影響報告（首版、無差異、有分析）")
    void firstVersionReviewSheet() throws Exception {
        String maker = token("maker");
        String checker = token("checker");
        long id = createDraft(maker, "wb.first", 700);
        submit(maker, id, "新規則上線");

        JsonNode sheet = reviewSheet(checker, id);
        assertEquals("新規則上線", sheet.get("submitReason").asText());
        assertEquals("REVIEW", sheet.get("status").asText());
        assertTrue(sheet.get("before").isNull());
        assertTrue(sheet.get("impact").get("firstVersion").asBoolean());
        assertNotNull(sheet.get("impact").get("analysis"));
        assertFalse(sheet.get("impact").has("structuralDiff"));
    }

    @Test
    @DisplayName("改版送審：以生效版為基準比對，影響報告在送審時快照")
    void secondVersionDiffAgainstActive() throws Exception {
        String maker = token("maker");
        String checker = token("checker");
        long v1 = createDraft(maker, "wb.second", 700);
        submit(maker, v1, "初版");
        mvc.perform(post("/rules/" + v1 + "/approve").header("Authorization", "Bearer " + checker)
                .contentType("application/json").content("{\"comment\":\"ok\"}")).andExpect(status().isOk());
        mvc.perform(post("/rules/" + v1 + "/activate").header("Authorization", "Bearer " + checker))
                .andExpect(status().isOk());

        long v2 = createDraft(maker, "wb.second", 650);
        submit(maker, v2, "門檻由 700 降到 650");

        JsonNode sheet = reviewSheet(checker, v2);
        assertEquals(1, sheet.get("baseVersionNo").asInt());
        assertEquals(700, sheet.get("before").at("/rule/rules/0/conditions/0/value").asInt());
        assertEquals(650, sheet.get("after").at("/rule/rules/0/conditions/0/value").asInt());
        assertFalse(sheet.get("impact").get("firstVersion").asBoolean());
        assertTrue(sheet.get("impact").has("structuralDiff"));
        assertTrue(sheet.get("impact").has("behaviorDiff"));
        assertEquals("門檻由 700 降到 650", sheet.get("submitReason").asText());
    }

    @Test
    @DisplayName("目錄與標籤：放置、依標籤篩選、未知維度 400、checker 不能改分類")
    void placementAndTagFilter() throws Exception {
        String maker = token("maker");
        String checker = token("checker");
        createDraft(maker, "wb.tree.a", 700);
        createDraft(maker, "wb.tree.b", 700);

        mvc.perform(put("/rules/wb.tree.a/placement").header("Authorization", "Bearer " + maker)
                        .contentType("application/json")
                        .content("{\"path\":\"甲/乙\",\"tags\":{\"分類\":[\"X\"],\"主題\":[\"T1\",\"T2\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("甲/乙"));
        mvc.perform(put("/rules/wb.tree.b/placement").header("Authorization", "Bearer " + maker)
                        .contentType("application/json")
                        .content("{\"path\":\"甲/丙\",\"tags\":{\"分類\":[\"Y\"]}}"))
                .andExpect(status().isOk());

        MvcResult r = mvc.perform(get("/rules/tree").param("分類", "X")
                        .header("Authorization", "Bearer " + checker))
                .andExpect(status().isOk()).andReturn();
        JsonNode rules = objectMapper.readTree(r.getResponse().getContentAsString()).get("rules");
        boolean hasA = false;
        for (JsonNode rule : rules) {
            assertFalse(rule.get("ruleKey").asText().equals("wb.tree.b"), "標籤 Y 的規則不應出現在分類=X");
            if (rule.get("ruleKey").asText().equals("wb.tree.a")) {
                hasA = true;
                assertEquals(2, rule.at("/tags/主題").size());
            }
        }
        assertTrue(hasA);

        mvc.perform(put("/rules/wb.tree.a/placement").header("Authorization", "Bearer " + maker)
                        .contentType("application/json")
                        .content("{\"path\":\"甲\",\"tags\":{\"不存在的維度\":[\"x\"]}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/rules/wb.tree.a/placement").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content("{\"path\":\"甲//乙\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/rules/wb.tree.a/placement").header("Authorization", "Bearer " + checker)
                        .contentType("application/json").content("{\"path\":\"甲\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/rules/no.such.rule/placement").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content("{\"path\":\"甲\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("改規則建議：只有 maker 能用；沒描述 → 400")
    void suggestChangeGuards() throws Exception {
        String maker = token("maker");
        String checker = token("checker");
        long id = createDraft(maker, "wb.suggest", 700);

        mvc.perform(post("/rules/" + id + "/suggest-change").header("Authorization", "Bearer " + checker)
                        .contentType("application/json").content("{\"instruction\":\"門檻改 650\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/rules/" + id + "/suggest-change").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content("{\"instruction\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    private static final String GAPPY_ENVELOPE = """
            {"ruleKey":"%s","envelope":{"ruleType":"DecisionTable","reason":"gap test",
             "rule":{"hitPolicy":"FIRST",
             "inputs":[{"name":"age","typeRef":"INTEGER"},{"name":"smoker","typeRef":"BOOLEAN"}],
             "outputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","人工評估","拒保"]},
                        {"name":"note","typeRef":"STRING"}],
             "rules":[{"ruleId":"R1","priority":1,
               "conditions":[{"field":"age","operator":"between","value":[18,50]},{"field":"smoker","operator":"equals","value":false}],
               "results":[{"field":"decision","value":"承保"},{"field":"note","value":"ok"}]}]}}}""";

    @Test
    @DisplayName("缺口補成案例：影響報告的缺口帶條件；補列後多一列、決議為人工評估、其餘待填")
    void gapCaseAppendsUnfilledRow() throws Exception {
        String maker = token("maker");
        MvcResult created = mvc.perform(post("/rules").header("Authorization", "Bearer " + maker)
                        .contentType("application/json").content(GAPPY_ENVELOPE.formatted("wb.gap")))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        JsonNode sheet = reviewSheet(maker, id);
        JsonNode gaps = sheet.at("/impact/analysis/gaps");
        assertTrue(gaps.isArray() && gaps.size() > 0, "首版就該報出缺口");
        JsonNode firstGap = gaps.get(0);
        assertTrue(firstGap.has("message") && firstGap.has("conditions"), firstGap.toString());

        MvcResult r = mvc.perform(post("/rules/" + id + "/gap-case").header("Authorization", "Bearer " + maker)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("conditions", firstGap.get("conditions")))))
                .andExpect(status().isOk()).andReturn();
        JsonNode suggestion = objectMapper.readTree(r.getResponse().getContentAsString());
        JsonNode rules = suggestion.at("/proposed/rule/rules");
        assertEquals(2, rules.size());
        JsonNode added = rules.get(1);
        assertEquals("R2", added.get("ruleId").asText());
        assertEquals("人工評估", added.at("/results/0/value").asText());
        assertFalse(added.at("/results/1").has("value"), "note 應留空待填");
        assertTrue(suggestion.at("/validation/valid").asBoolean() == false, "待填列應被驗證標出");

        mvc.perform(post("/rules/" + id + "/gap-case").header("Authorization", "Bearer " + token("checker"))
                        .contentType("application/json").content("{\"conditions\":{\"age\":\"[51,120]\"}}"))
                .andExpect(status().isForbidden());
    }
}
