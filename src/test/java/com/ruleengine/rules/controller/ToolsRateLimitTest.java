package com.ruleengine.rules.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * /tools 註解式限流的觸發測試（review 修復輪）。
 *
 * <p>
 * 修復前：@RateLimiter aspect 以單參查 registry、查無 instance 落到
 * ofDefaults（形同無限流）—— /tools 限流自 v3.0 起<b>從未觸發過</b>，
 * 且沒有任何測試斷言 429，所以壞了三個版本沒人知道。
 * 本測試用窄額度（diff=2/min）驗證：修復後第 3 次真的 429。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rules.rate-limit.diff-per-minute=2")
@DisplayName("/tools 限流 - 觸發驗證（修復從未生效的註解式限流）")
class ToolsRateLimitTest {

    @Autowired MockMvc mvc;

    // 欄位名是 before/after（DiffTreeRequest）—— 錯誤#10：第一版 body 用錯欄位名，
    // @Valid 在進 controller 方法前就拒絕（400），aspect 根本沒跑到、限流計數恆 0，
    // 測出來像「限流沒修好」實際是「請求沒進門」。驗限流必須用合法 body。
    private static final String DIFF_BODY = """
            {"before":{"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST","rules":[]}},
             "after":{"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST","rules":[]}}}""";

    @Test
    @DisplayName("diff 端點窄額度 2/min：前 2 次通過、第 3 次 429")
    void thirdDiffCallRateLimited() throws Exception {
        int[] codes = new int[3];
        for (int i = 0; i < 3; i++) {
            codes[i] = mvc.perform(post("/tools/diff-table")
                            .contentType("application/json").content(DIFF_BODY))
                    .andReturn().getResponse().getStatus();
        }
        // 前兩次不是 429（可能 200 或 400 視 body 驗證 —— 重點是「過了限流層」）
        assertTrue(codes[0] != 429 && codes[1] != 429,
                "前 2 次應通過限流層，實際: " + codes[0] + "," + codes[1]);
        assertTrue(codes[2] == 429,
                "第 3 次應被限流擋下（修復前這裡永遠不會 429），實際: " + codes[2]);
    }
}
