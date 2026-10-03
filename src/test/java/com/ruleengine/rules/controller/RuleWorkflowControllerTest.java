package com.ruleengine.rules.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 審核工作流 REST 測試（P2-S4）：三層授權的端到端驗證。
 *
 * <p>路徑層（/rules/** 要 token）→ 方法層（@PreAuthorize 角色）→
 * 件層（四眼原則，服務內檢查）。用 demo 三帳號跑真 JWT，不 mock security。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RuleWorkflowController - 三層授權 + API 旅程")
class RuleWorkflowControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private static final String ENVELOPE = """
            {"ruleKey":"%s","envelope":{"ruleType":"DecisionTable","reason":"api test",
             "rule":{"hitPolicy":"FIRST","rules":[{"ruleId":"R01","priority":1,
             "conditions":[{"field":"score","operator":"greaterThanOrEqual","value":700}],
             "results":[{"field":"decision","value":"approve"}]}]}}}""";

    private String token(String user) throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"demo-pass-2026\"}".formatted(user)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private long createDraft(String makerToken, String key) throws Exception {
        MvcResult r = mvc.perform(post("/rules")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType("application/json")
                        .content(ENVELOPE.formatted(key)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    @Nested
    @DisplayName("三層授權矩陣")
    class AuthorizationMatrix {

        @Test
        @DisplayName("路徑層：無 token 打 /rules 一律 401")
        void pathLayer() throws Exception {
            mvc.perform(get("/rules/keys")).andExpect(status().isUnauthorized());
            mvc.perform(post("/rules").contentType("application/json")
                    .content(ENVELOPE.formatted("x"))).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("方法層：checker 不能建草稿/送審（403）；maker 不能核准/進審核佇列（403）")
        void methodLayer() throws Exception {
            String checker = token("checker");
            String maker = token("maker");

            // checker 做 maker 的事 → 403
            mvc.perform(post("/rules").header("Authorization", "Bearer " + checker)
                            .contentType("application/json").content(ENVELOPE.formatted("mx.a")))
                    .andExpect(status().isForbidden());
            // maker 做 checker 的事 → 403
            long id = createDraft(maker, "mx.b");
            mvc.perform(post("/rules/" + id + "/approve")
                            .header("Authorization", "Bearer " + maker)
                            .contentType("application/json").content("{\"comment\":\"x\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/rules/review-queue").header("Authorization", "Bearer " + maker))
                    .andExpect(status().isForbidden());
            // retire 要 ADMIN —— checker 也 403
            mvc.perform(post("/rules/" + id + "/retire")
                            .header("Authorization", "Bearer " + checker))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("件層：admin（雙角色）送審自己的件後自己核准 → 409 四眼原則")
        void itemLayer() throws Exception {
            String admin = token("admin");
            long id = createDraft(admin, "mx.selfreview");
            mvc.perform(post("/rules/" + id + "/submit").contentType("application/json").content("{\"reason\":\"測試送審\"}")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());
            // admin 有 CHECKER 角色（過方法層），但件層擋下
            mvc.perform(post("/rules/" + id + "/approve")
                            .header("Authorization", "Bearer " + admin)
                            .contentType("application/json").content("{\"comment\":\"self\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("WORKFLOW_VIOLATION"));
        }
    }

    @Nested
    @DisplayName("端到端 API 旅程")
    class ApiJourney {

        @Test
        @DisplayName("maker 建+送審 → checker 佇列可見+核准+生效 → 引擎可執行 → 新版取代")
        void fullApiJourney() throws Exception {
            String maker = token("maker");
            String checker = token("checker");
            String key = "journey.api-" + System.nanoTime();

            // maker：建草稿 + 送審
            long v1 = createDraft(maker, key);
            mvc.perform(post("/rules/" + v1 + "/submit").contentType("application/json").content("{\"reason\":\"測試送審\"}").header("Authorization", "Bearer " + maker))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REVIEW"))
                    .andExpect(jsonPath("$.submittedBy").value("maker"));

            // checker：佇列看得到這件
            MvcResult queue = mvc.perform(get("/rules/review-queue")
                            .header("Authorization", "Bearer " + checker))
                    .andExpect(status().isOk()).andReturn();
            JsonNode queueJson = objectMapper.readTree(queue.getResponse().getContentAsString());
            boolean inQueue = false;
            for (JsonNode item : queueJson) if (item.get("id").asLong() == v1) inQueue = true;
            assertTrue(inQueue, "送審件應出現在審核佇列");

            // checker：核准 + 生效
            mvc.perform(post("/rules/" + v1 + "/approve").header("Authorization", "Bearer " + checker)
                            .contentType("application/json").content("{\"comment\":\"邏輯正確\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.reviewedBy").value("checker"));
            mvc.perform(post("/rules/" + v1 + "/activate").header("Authorization", "Bearer " + checker))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            // 生效後引擎立刻可執行（治理鏈接上營運鏈）
            mvc.perform(post("/engine/execute").header("Authorization", "Bearer " + maker)
                            .contentType("application/json")
                            .content("{\"ruleKey\":\"" + key + "\",\"input\":{\"score\":720}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.outputs.decision").value("approve"));

            // 第二版走完流程 → v1 自動退役
            long v2 = createDraft(maker, key);
            mvc.perform(post("/rules/" + v2 + "/submit").contentType("application/json").content("{\"reason\":\"測試送審\"}").header("Authorization", "Bearer " + maker))
                    .andExpect(status().isOk());
            mvc.perform(post("/rules/" + v2 + "/approve").header("Authorization", "Bearer " + checker)
                            .contentType("application/json").content("{\"comment\":\"v2\"}"))
                    .andExpect(status().isOk());
            mvc.perform(post("/rules/" + v2 + "/activate").header("Authorization", "Bearer " + checker))
                    .andExpect(status().isOk());

            MvcResult history = mvc.perform(get("/rules/" + key + "/history")
                            .header("Authorization", "Bearer " + maker))
                    .andExpect(status().isOk()).andReturn();
            JsonNode h = objectMapper.readTree(history.getResponse().getContentAsString());
            assertEquals("ACTIVE", h.get(0).get("status").asText(), "v2 生效");
            assertEquals("RETIRED", h.get(1).get("status").asText(), "v1 被取代退役");
        }

        @Test
        @DisplayName("退回旅程：reject 帶意見 → maker revise 回草稿；不帶意見 → 409")
        void rejectJourney() throws Exception {
            String maker = token("maker");
            String checker = token("checker");
            long id = createDraft(maker, "journey.reject-" + System.nanoTime());

            mvc.perform(post("/rules/" + id + "/submit").contentType("application/json").content("{\"reason\":\"測試送審\"}").header("Authorization", "Bearer " + maker))
                    .andExpect(status().isOk());
            // 不帶意見退回 → 409
            mvc.perform(post("/rules/" + id + "/reject").header("Authorization", "Bearer " + checker)
                            .contentType("application/json").content("{\"comment\":\"\"}"))
                    .andExpect(status().isConflict());
            // 帶意見退回 → REJECTED
            mvc.perform(post("/rules/" + id + "/reject").header("Authorization", "Bearer " + checker)
                            .contentType("application/json").content("{\"comment\":\"缺 ENUM 定義\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.reviewComment").value("缺 ENUM 定義"));
            // maker 改後重置回草稿
            mvc.perform(post("/rules/" + id + "/revise").header("Authorization", "Bearer " + maker))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }
    }
}
