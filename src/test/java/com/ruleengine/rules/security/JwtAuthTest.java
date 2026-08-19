package com.ruleengine.rules.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT 認證端到端測試（P1-S4）—— 預設 permissive 模式下的完整行為。
 *
 * <p>
 * 驗證面向：登入簽發、帳號枚舉防護、/auth/me 錨點的 401/200、
 * 篡改與過期 token 的拒絕、permissive 模式下 /tools/** 不受影響（相容性證明）。
 * enforce 模式的行為由 {@link JwtEnforceModeTest} 驗證。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("JWT 認證（permissive 預設模式）")
class JwtAuthTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtEncoder jwtEncoder;

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Nested
    @DisplayName("登入")
    class Login {

        @Test
        @DisplayName("正確帳密 → 取得 token，roles 與 seed 一致")
        void loginSuccess() throws Exception {
            MvcResult result = mvc.perform(post("/auth/login")
                            .contentType("application/json")
                            .content("{\"username\":\"maker\",\"password\":\"demo-pass-2026\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertFalse(body.get("token").asText().isBlank());
            assertEquals("Bearer", body.get("tokenType").asText());
            assertEquals("maker", body.get("username").asText());
            assertEquals("MAKER", body.get("roles").get(0).asText());
            // JWT 形狀：三段 base64url
            assertEquals(3, body.get("token").asText().split("\\.").length);
        }

        @Test
        @DisplayName("帳號枚舉防護：密碼錯與帳號不存在回完全相同的 401 body")
        void enumerationDefense() throws Exception {
            MvcResult wrongPass = mvc.perform(post("/auth/login")
                            .contentType("application/json")
                            .content("{\"username\":\"maker\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized()).andReturn();
            MvcResult noUser = mvc.perform(post("/auth/login")
                            .contentType("application/json")
                            .content("{\"username\":\"ghost-user\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertEquals(wrongPass.getResponse().getContentAsString(),
                    noUser.getResponse().getContentAsString(),
                    "兩種失敗的回應必須逐字相同，否則可被用來探測帳號是否存在");
        }
    }

    @Nested
    @DisplayName("驗證錨點 /auth/me（permissive 期也強制）")
    class MeEndpoint {

        @Test
        @DisplayName("無 token → 401 JSON（不是重導向）")
        void meWithoutToken() throws Exception {
            mvc.perform(get("/auth/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("帶 token → 還原 username 與 ROLE_ 前綴 authorities")
        void meWithToken() throws Exception {
            String token = login("checker", "demo-pass-2026");
            MvcResult result = mvc.perform(get("/auth/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertEquals("checker", body.get("username").asText());
            assertEquals("ROLE_CHECKER", body.get("authorities").get(0).asText(),
                    "roles claim 應由 JwtGrantedAuthoritiesConverter 加回 ROLE_ 前綴");
        }

        @Test
        @DisplayName("篡改 token（改 payload 一個字元）→ 401")
        void tamperedToken() throws Exception {
            String token = login("maker", "demo-pass-2026");
            String[] parts = token.split("\\.");
            // 動 payload 的第一個字元 —— 簽章立即失效
            char c = parts[1].charAt(0);
            parts[1] = (c == 'A' ? 'B' : 'A') + parts[1].substring(1);
            mvc.perform(get("/auth/me")
                            .header("Authorization", "Bearer " + String.join(".", parts)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("過期 token → 401（用 JwtEncoder 直接簽一個已過期的）")
        void expiredToken() throws Exception {
            Instant past = Instant.now().minusSeconds(3600);
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(JwtService.ISSUER).subject("maker")
                    .issuedAt(past.minusSeconds(60)).expiresAt(past)
                    .claim(JwtService.ROLES_CLAIM, List.of("MAKER"))
                    .build();
            String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
            mvc.perform(get("/auth/me").header("Authorization", "Bearer " + expired))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("相容性：permissive 模式下既有端點不受影響")
    class PermissiveCompat {

        @Test
        @DisplayName("/tools/recommend 無 token 仍可用（前端登入頁落地前的過渡保證）")
        void toolsStillOpen() throws Exception {
            mvc.perform(post("/tools/recommend")
                            .contentType("application/json")
                            .content("{\"description\":\"年齡大於六十歲拒保，其餘承保\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("/actuator/health 永遠開放")
        void healthOpen() throws Exception {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }
}
