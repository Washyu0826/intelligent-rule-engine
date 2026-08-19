package com.ruleengine.rules.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登入全域限流的專屬測試（資安收緊②第二層）。
 *
 * <p>
 * 獨立測試類 + 窄額度（3/min）：全套件共用的 context 把限流放寬到 10000
 * （避免測試互撞），這裡用自己的 context 收窄到 3，429 行為才可決定性驗證。
 * 這也是「顯式 acquirePermission 取代註解 AOP」修正的回歸測試 ——
 * 註解版在真機 12 連發從未觸發 429（發現於真機驗證）。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rules.security.login.rate-limit-per-minute=3")
@DisplayName("登入全域限流（password spraying 防線）")
class LoginRateLimitTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("第 4 次登入嘗試觸發 429 + Retry-After（前 3 次不論帳密對錯都放行到驗證層）")
    void fourthAttemptRateLimited() throws Exception {
        // 前 3 次：進到帳密驗證（401 = 密碼錯，代表通過了限流層）
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/auth/login").contentType("application/json")
                            .content("{\"username\":\"spray-" + i + "\",\"password\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        // 第 4 次：限流擋下 —— 429、帶 Retry-After、明確錯誤碼
        mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"spray-4\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"));
    }
}
