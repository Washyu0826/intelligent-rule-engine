package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Case 2 不被 blocker 卡住的子段整合測試（v3.14 場景驅動）。
 *
 * 涵蓋兩個子段（其他 6 段卡 v3.14 schema 或 需求方 B-zone 回覆）：
 *   - §2.3：特約通路時授權書編號不可等於受理編號
 *   - §4.2：投保始期不可小於被保人生日
 *   - §4.1（stub）：非網投件 + 繳費非滿期金/解約金 → 投保始期不可超過隔日
 *     （目前只看 newContractPaymentMethod 單欄位，待 F2 anyOf 完成後補 renewalPaymentMethod）
 *
 * §4.2 使用 v3.13 的 valueRef 跨欄位功能；§4.1 使用 valueRef 相對日期 "$today+1d"。
 */
@SpringBootTest
@DisplayName("Case 2 — 未被卡住的子段整合測試（§2.3 + §4）")
class Case2UnblockedSubsetsIntegrationTest {

    @Autowired private RuleService ruleService;
    @Autowired private RuleLookupService lookupService;
    @Autowired private ObjectMapper objectMapper;

    // ================================================================
    // §2.3 — 特約 + 授權書編號 = 受理編號 → 拋訊息
    // ================================================================
    @Nested
    @DisplayName("§2.3 特約通路授權書檢核")
    class Section23 {
        private static final String FIXTURE = "fixtures/case2-section2-3.json";

        @Test
        @DisplayName("fixture 通過 validate（0 errors）")
        void validates() throws Exception {
            ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                    .ruleJson(loadJson(FIXTURE)).ruleType("DecisionTable").build());
            assertTrue(v.isValid(), "§2.3 fixture should validate. Errors: " + v.getErrors());
        }

        @Test
        @DisplayName("特約 + 授權書=受理編號 → 命中 R23")
        void fires_whenZhuanZhanAndAuthEqualsAcceptance() throws Exception {
            var matched = lookupSection23("特約", true);
            assertEquals(1, matched.matchedRules().size());
            assertEquals("R23", matched.matchedRules().get(0).ruleId());
            assertEquals("2.3 特約通路時授權書編號不可等於受理編號",
                    matched.matchedRules().get(0).results().get("errorMessage"));
        }

        @Test
        @DisplayName("特約 + 授權書≠受理編號 → 不命中")
        void noFire_whenZhuanZhanButAuthDifferent() throws Exception {
            var matched = lookupSection23("特約", false);
            assertTrue(matched.matchedRules().isEmpty());
        }

        @Test
        @DisplayName("非特約通路（保代/直效）+ 授權書=受理編號 → §2.3 不命中（§2.1/2.2 卡 B8/B9）")
        void noFire_whenOtherChannels() throws Exception {
            assertTrue(lookupSection23("保代", true).matchedRules().isEmpty());
            assertTrue(lookupSection23("直效", true).matchedRules().isEmpty());
        }

        private RuleLookupService.LookupResponse lookupSection23(String channel, boolean authMatches) throws Exception {
            RuleEnvelope envelope = objectMapper.readValue(loadString(FIXTURE), RuleEnvelope.class);
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("channel", channel);
            inputs.put("authMatchesAcceptanceNumber", authMatches);
            return lookupService.lookup(envelope, inputs);
        }
    }

    // ================================================================
    // §4 — 投保始期檢核
    // ================================================================
    @Nested
    @DisplayName("§4 投保始期檢核")
    class Section4 {
        private static final String FIXTURE = "fixtures/case2-section4-policy-start.json";

        @Test
        @DisplayName("fixture 通過 validate（0 errors）")
        void validates() throws Exception {
            ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                    .ruleJson(loadJson(FIXTURE)).ruleType("DecisionTable").build());
            assertTrue(v.isValid(), "§4 fixture should validate. Errors: " + v.getErrors());
        }

        // -------- §4.2：投保始期 < 被保人生日 --------

        @Test
        @DisplayName("§4.2 投保始期早於生日 → 命中 R42")
        void r42_fires_whenStartBeforeBirthday() throws Exception {
            var matched = lookupSection4(false, "信用卡", "信用卡",
                    LocalDate.of(2000, 1, 1), LocalDate.of(2005, 6, 15));
            assertTrue(matched.matchedRules().stream().anyMatch(m -> "R42".equals(m.ruleId())),
                    "R42 should fire when policyStartDate < insuredBirthday. Got: " + matched.matchedRules());
        }

        @Test
        @DisplayName("§4.2 投保始期晚於生日 → R42 不命中")
        void r42_noFire_whenStartAfterBirthday() throws Exception {
            // 給一個未來日期當 policyStartDate，避免 R41 (start > today+1) 干擾
            LocalDate today = LocalDate.now();
            var matched = lookupSection4(true, "信用卡", "信用卡",
                    today, LocalDate.of(1990, 1, 1));
            assertTrue(matched.matchedRules().stream().noneMatch(m -> "R42".equals(m.ruleId())));
        }

        // -------- §4.1：非網投 + 非滿期/解約 → start 不可超過隔日 --------

        @Test
        @DisplayName("§4.1 非網投+一般繳費+投保始期超過隔日 → 命中 R41")
        void r41_fires_whenNonNetAndTooFarFuture() throws Exception {
            LocalDate tooFar = LocalDate.now().plusDays(10);
            var matched = lookupSection4(false, "信用卡", "信用卡",
                    tooFar, LocalDate.of(1990, 1, 1));
            assertTrue(matched.matchedRules().stream().anyMatch(m -> "R41".equals(m.ruleId())),
                    "R41 should fire when non-net insurance with start > today+1. Got: " + matched.matchedRules());
        }

        @Test
        @DisplayName("§4.1 網投件 → R41 不命中（網投例外，金管會電子商務應注意事項）")
        void r41_noFire_whenNetInsurance() throws Exception {
            LocalDate tooFar = LocalDate.now().plusDays(10);
            var matched = lookupSection4(true, "信用卡", "信用卡",
                    tooFar, LocalDate.of(1990, 1, 1));
            assertTrue(matched.matchedRules().stream().noneMatch(m -> "R41".equals(m.ruleId())),
                    "R41 should NOT fire for net insurance (cooling-off period exception)");
        }

        @Test
        @DisplayName("§4.1 繳費為滿期金 → R41 不命中（接續保單例外）")
        void r41_noFire_whenMaturityPayment() throws Exception {
            LocalDate tooFar = LocalDate.now().plusDays(10);
            var matched = lookupSection4(false, "滿期金", "信用卡",
                    tooFar, LocalDate.of(1990, 1, 1));
            assertTrue(matched.matchedRules().stream().noneMatch(m -> "R41".equals(m.ruleId())),
                    "R41 should NOT fire when payment method is 滿期金 (rollover exception)");
        }

        @Test
        @DisplayName("§4.1 投保始期為今天 → R41 不命中（隔日上限以內）")
        void r41_noFire_whenStartIsToday() throws Exception {
            LocalDate today = LocalDate.now();
            var matched = lookupSection4(false, "信用卡", "信用卡",
                    today, LocalDate.of(1990, 1, 1));
            assertTrue(matched.matchedRules().stream().noneMatch(m -> "R41".equals(m.ruleId())));
        }

        // -------- §4.1 + §4.2 同時觸發 --------

        @Test
        @DisplayName("§4.1 + §4.2 同時違反 → R41 + R42 兩條同時命中（MULTI 證明）")
        void bothR41AndR42_canFireSimultaneously() throws Exception {
            // 投保始期 = 1980/1/1：既小於生日 (1990/1/1) 又超過 today+1
            // 但 1980 < today, R41 是 start > today+1d → 1980 < today, R41 不會命中
            // 改用：start = 9999/1/1（超過 today+1）+ birthday = 9999/1/2（start < birthday）
            LocalDate start = LocalDate.of(9999, 1, 1);
            LocalDate birthday = LocalDate.of(9999, 1, 2);
            var matched = lookupSection4(false, "信用卡", "信用卡", start, birthday);
            List<String> ruleIds = matched.matchedRules().stream()
                    .map(RuleLookupService.MatchedRule::ruleId).toList();
            assertTrue(ruleIds.contains("R41"), "Expected R41 in " + ruleIds);
            assertTrue(ruleIds.contains("R42"), "Expected R42 in " + ruleIds);
        }

        private RuleLookupService.LookupResponse lookupSection4(
                boolean isNet, String newPayment, String renewalPayment,
                LocalDate startDate, LocalDate birthday) throws Exception {
            RuleEnvelope envelope = objectMapper.readValue(loadString(FIXTURE), RuleEnvelope.class);
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("isNetInsurance", isNet);
            inputs.put("newContractPaymentMethod", newPayment);
            inputs.put("renewalPaymentMethod", renewalPayment);
            inputs.put("policyStartDate", startDate.toString());
            inputs.put("insuredBirthday", birthday.toString());
            return lookupService.lookup(envelope, inputs);
        }
    }

    // ================================================================
    // Helpers
    // ================================================================
    private JsonNode loadJson(String path) throws Exception {
        return objectMapper.readTree(loadString(path));
    }

    private String loadString(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
