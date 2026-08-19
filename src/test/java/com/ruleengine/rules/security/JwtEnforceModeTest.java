package com.ruleengine.rules.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * enforce 模式（{@code rules.security.jwt.mode=enforce}）的行為驗證（P1-S4）。
 *
 * <p>
 * 這就是 prod 的實際設定（application-prod.yml 已切 enforce）——
 * 在測試裡以 @TestPropertySource 提前驗證，不等 P1-S5 前端落地才發現問題。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "rules.security.jwt.mode=enforce",
        // SecurityStartupCheck 禁止「enforce + dev 預設 secret」（錯誤#7：新防線正確擋下了
        // 本測試原設定）—— enforce 測試必須配一把非預設的合法 secret，與 prod 的要求一致
        "rules.security.jwt.secret=test-only-enforce-secret-0123456789abcdef"
})
@DisplayName("JWT 認證（enforce 模式 = prod 行為）")
class JwtEnforceModeTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("/tools/** 無 token → 401，帶 token → 200")
    void toolsRequireToken() throws Exception {
        // 無 token：擋
        mvc.perform(post("/tools/recommend")
                        .contentType("application/json")
                        .content("{\"description\":\"年齡大於六十歲拒保\"}"))
                .andExpect(status().isUnauthorized());

        // 登入拿 token
        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"maker\",\"password\":\"demo-pass-2026\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("token").asText();

        // 帶 token：通
        mvc.perform(post("/tools/recommend")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"description\":\"年齡大於六十歲拒保\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("enforce 下豁免清單仍開放：login / health / 靜態資源")
    void exemptionsStillOpen() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"maker\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()); // 401 是「密碼錯」，不是被 filter 擋 —— 端點本身可達
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}
