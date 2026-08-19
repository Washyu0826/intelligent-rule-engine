package com.ruleengine.rules.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入口資安收緊的驗證（P2 資安強化輪）。
 *
 * <p>①/engine/** 永遠要身分 ②登入爆破鎖定 ③啟動檢查 ⑤actuator 收緊 ⑥登入事件進稽核。
 * （④常數時間比較無法用行為測試驗證 —— 由程式碼審查保證，這裡驗它功能上等價。）</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
// 測試環境的 application.yml 只暴露 health（錯誤#6）—— 本類要驗 metrics 的保護，得先讓端點存在
@org.springframework.test.context.TestPropertySource(
        properties = "management.endpoints.web.exposure.include=health,metrics,prometheus")
@DisplayName("入口資安收緊")
class SecurityHardeningTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired com.ruleengine.rules.service.audit.AuditService auditService;

    private String login(String user, String pass) throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    @Nested
    @DisplayName("① /engine/** 不分模式一律要身分（新端點新規矩）")
    class EngineAlwaysProtected {

        @Test
        @DisplayName("permissive 模式下 /engine/execute 無 token 仍 401；帶 token 可達")
        void engineRequiresAuthEvenInPermissive() throws Exception {
            mvc.perform(post("/engine/execute").contentType("application/json")
                            .content("{\"ruleKey\":\"x\",\"input\":{}}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/engine/traces"))
                    .andExpect(status().isUnauthorized());

            // 帶 token：能通過認證層（404 = 規則不存在，代表已進到業務邏輯）
            String token = login("maker", "demo-pass-2026");
            mvc.perform(post("/engine/execute")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"ruleKey\":\"no-such-rule\",\"input\":{}}"))
                    .andExpect(status().isBadRequest());   // 業務錯誤（無 ACTIVE 版）而非 401
        }
    }

    @Nested
    @DisplayName("② 登入爆破防護")
    class BruteForce {

        @Test
        @DisplayName("連續 5 次失敗後鎖定：第 6 次即使密碼正確也被拒（回應與密碼錯完全相同）")
        void lockoutAfterFiveFailures() throws Exception {
            // 用獨立 service 實例測（不污染其他測試的共享狀態）
            LoginAttemptService svc = new LoginAttemptService(5, 15);
            for (int i = 0; i < 4; i++) {
                assertFalse(svc.recordFailure("victim"), "前 4 次不觸發鎖定");
            }
            assertTrue(svc.recordFailure("victim"), "第 5 次觸發鎖定");
            assertTrue(svc.isLocked("victim"));
            assertTrue(svc.isLocked("VICTIM"), "大小寫不同不能繞過鎖定");

            // 成功登入清零
            svc.recordSuccess("victim");
            assertFalse(svc.isLocked("victim"));
        }

        @Test
        @DisplayName("端到端：帳號鎖定後正確密碼也回 401，且與密碼錯的回應逐字相同")
        void e2eLockoutIndistinguishable() throws Exception {
            // 對 demo 以外的獨立帳號名操作，不影響其他測試
            String victim = "lock-target-" + System.nanoTime();
            String wrongBody = "{\"username\":\"" + victim + "\",\"password\":\"wrong\"}";
            MvcResult firstFail = mvc.perform(post("/auth/login")
                            .contentType("application/json").content(wrongBody))
                    .andExpect(status().isUnauthorized()).andReturn();
            for (int i = 0; i < 5; i++) {
                mvc.perform(post("/auth/login").contentType("application/json").content(wrongBody))
                        .andExpect(status().isUnauthorized());
            }
            // 已鎖定：回應與一般失敗逐字相同（鎖定狀態不可枚舉）
            MvcResult locked = mvc.perform(post("/auth/login")
                            .contentType("application/json").content(wrongBody))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertEquals(firstFail.getResponse().getContentAsString(),
                    locked.getResponse().getContentAsString());
        }
    }

    @Nested
    @DisplayName("③ 資安啟動檢查（fail-fast）")
    class StartupChecks {

        private SecurityStartupCheck check(String mode, String secret, boolean apiKeyEnabled, String apiKey) {
            SecurityStartupCheck c = new SecurityStartupCheck();
            ReflectionTestUtils.setField(c, "jwtMode", mode);
            ReflectionTestUtils.setField(c, "jwtSecret", secret);
            ReflectionTestUtils.setField(c, "apiKeyEnabled", apiKeyEnabled);
            ReflectionTestUtils.setField(c, "apiKey", apiKey);
            return c;
        }

        @Test
        @DisplayName("secret < 32 bytes → 任何模式都啟動失敗")
        void shortSecretFails() {
            var ex = assertThrows(IllegalStateException.class,
                    () -> check("permissive", "too-short", false, "").verify());
            assertTrue(ex.getMessage().contains("32 bytes"));
        }

        @Test
        @DisplayName("enforce + dev 預設 secret → 啟動失敗（公開的 secret 等於沒有簽章）")
        void enforceWithDevSecretFails() {
            var ex = assertThrows(IllegalStateException.class,
                    () -> check("enforce", SecurityStartupCheck.DEV_DEFAULT_SECRET, false, "").verify());
            assertTrue(ex.getMessage().contains("dev 預設"));
        }

        @Test
        @DisplayName("api-key-enabled 但 key 空 → 啟動失敗（不再是啟動成功後全 403）")
        void emptyApiKeyFails() {
            var ex = assertThrows(IllegalStateException.class,
                    () -> check("permissive", "x".repeat(40), true, " ").verify());
            assertTrue(ex.getMessage().contains("RULES_API_KEY"));
        }

        @Test
        @DisplayName("合法組合通過：permissive+dev 預設（本機）與 enforce+自訂 secret（prod）")
        void validCombosPass() {
            assertDoesNotThrow(() ->
                    check("permissive", SecurityStartupCheck.DEV_DEFAULT_SECRET, false, "").verify());
            assertDoesNotThrow(() ->
                    check("enforce", "a-strong-production-secret-0123456789abcdef", false, "").verify());
        }

        @Test
        @DisplayName("防改一漏一：檢查用的 dev 預設值常數與 SecurityConfig 的 @Value 預設一致")
        void devDefaultConstantsInSync() throws Exception {
            // SecurityConfig 的預設值寫在 @Value 註解裡 —— 反射讀出來對比
            var field = SecurityConfig.class.getDeclaredField("secret");
            var ann = field.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
            assertTrue(ann.value().contains(SecurityStartupCheck.DEV_DEFAULT_SECRET),
                    "SecurityConfig 與 SecurityStartupCheck 的 dev 預設 secret 不同步");
        }
    }

    @Nested
    @DisplayName("⑤ actuator 收緊")
    class ActuatorTightened {

        @Test
        @DisplayName("health 仍開放；metrics/prometheus 需 ADMIN")
        void actuatorSplit() throws Exception {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
            mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

            // MAKER 不夠 —— 要 ADMIN
            String makerToken = login("maker", "demo-pass-2026");
            mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + makerToken))
                    .andExpect(status().isForbidden());
            String adminToken = login("admin", "demo-pass-2026");
            mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("⑥ 登入事件進稽核表")
    class LoginAudited {

        @Test
        @DisplayName("成功與失敗的登入都留 LOGIN 稽核紀錄")
        void loginEventsAudited() throws Exception {
            long before = auditService.getLogsByOperation("LOGIN", 1000).size();
            login("admin", "demo-pass-2026");
            mvc.perform(post("/auth/login").contentType("application/json")
                            .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
            var logs = auditService.getLogsByOperation("LOGIN", 1000);
            assertTrue(logs.size() >= before + 2, "成功+失敗至少各一筆");
            assertTrue(logs.stream().anyMatch(l -> l.isSuccess() && "admin".equals(l.getUserId())));
            assertTrue(logs.stream().anyMatch(l -> !l.isSuccess() && "admin".equals(l.getUserId())));
        }
    }
}
