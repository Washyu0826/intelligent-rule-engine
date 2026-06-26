package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.util.RocIdValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Case 1 整合測試（v3.14 場景驅動：ID + 國籍交叉檢核 family）。
 *
 * 驗收：
 *   1. 手刻 fixture 能通過 validate（0 errors，schema 符合 v3.13）
 *   2. 4 條規則對 16 種 input 組合產生正確的 errorMessage 觸發
 *   3. RocIdValidator 端到端：真實 ID 字串 → boolean → 規則命中
 *   4. 多條同時觸發（被保人錯 + 要保人錯）— 驗證 MULTI 確實多條命中
 *
 * 對應 需求方 給的 case 1 spec：
 *   1.1 被保人ID為身分證格式 ∧ 國籍別≠TW → 拋訊息「請選擇中華民國」
 *   1.2 被保人ID不為身分證格式 ∧ 國籍別=TW → 拋訊息「請選擇非中華民國」
 *   1.3 / 1.4 對要保人對稱
 */
@SpringBootTest
@DisplayName("Case 1 — ID + 國籍交叉檢核整合測試")
class Case1IdNationalityIntegrationTest {

    @Autowired private RuleService ruleService;
    @Autowired private RuleLookupService lookupService;
    @Autowired private ObjectMapper objectMapper;

    private static final String FIXTURE = "fixtures/case1-id-nationality.json";

    // ================================================================
    // 1. Schema 驗證 — fixture 能通過 validate
    // ================================================================

    @Test
    @DisplayName("case 1 fixture → validate 通過（0 errors）")
    void fixturePassesValidation() throws Exception {
        JsonNode json = loadJson(FIXTURE);
        ValidateResponse v = ruleService.validate(ValidateRequest.builder()
                .ruleJson(json).ruleType("DecisionTable").build());

        assertTrue(v.isValid(), "case 1 fixture should pass validation. Errors: " + v.getErrors());
    }

    // ================================================================
    // 2. 4 條規則各自觸發
    // ================================================================

    @Test
    @DisplayName("R01 被保人ID為身分證 + 國籍別NON_TW → 拋訊息 1.1")
    void r01_insuredIdRocButForeign() throws Exception {
        var matched = lookup(true, "NON_TW", false, "NON_TW");
        assertMatched(matched, "R01", "1.1 被保人ID為身分證格式，國籍別請選擇中華民國");
    }

    @Test
    @DisplayName("R02 被保人ID不為身分證 + 國籍別TW → 拋訊息 1.2")
    void r02_insuredIdForeignButTw() throws Exception {
        var matched = lookup(false, "TW", true, "TW");
        assertMatched(matched, "R02", "1.2 被保人ID不為身分證格式，國籍別請選擇非中華民國");
    }

    @Test
    @DisplayName("R03 要保人ID為身分證 + 國籍別NON_TW → 拋訊息 1.3")
    void r03_policyholderIdRocButForeign() throws Exception {
        var matched = lookup(false, "NON_TW", true, "NON_TW");
        assertMatched(matched, "R03", "1.3 要保人ID為身分證格式，國籍別請選擇中華民國");
    }

    @Test
    @DisplayName("R04 要保人ID不為身分證 + 國籍別TW → 拋訊息 1.4")
    void r04_policyholderIdForeignButTw() throws Exception {
        var matched = lookup(true, "TW", false, "TW");
        assertMatched(matched, "R04", "1.4 要保人ID不為身分證格式，國籍別請選擇非中華民國");
    }

    // ================================================================
    // 3. 一致的 input → 不命中任何規則（valid case）
    // ================================================================

    @Test
    @DisplayName("被保人/要保人 ID 與國籍一致 → 0 條命中")
    void consistentInputs_noMatch() throws Exception {
        // 兩個都是 TW 人 (身分證 + TW 國籍)
        var matched = lookup(true, "TW", true, "TW");
        assertTrue(matched.matchedRules().isEmpty(),
                "Consistent inputs should not trigger any error rule");

        // 兩個都是外籍 (非身分證 + NON_TW)
        matched = lookup(false, "NON_TW", false, "NON_TW");
        assertTrue(matched.matchedRules().isEmpty(),
                "Foreign consistent inputs should not trigger any error rule");
    }

    // ================================================================
    // 4. 多條同時觸發（MULTI 證明可同時多條 fail）
    // ================================================================

    @Test
    @DisplayName("被保人 + 要保人 同時錯 → R01 + R03 兩條同時命中")
    void multipleErrorsSimultaneous() throws Exception {
        // 兩個都是「ID 像身分證但國籍非 TW」
        var matched = lookup(true, "NON_TW", true, "NON_TW");
        assertEquals(2, matched.matchedRules().size(),
                "Both insured and policyholder errors should fire simultaneously");

        List<String> ruleIds = matched.matchedRules().stream().map(RuleLookupService.MatchedRule::ruleId).toList();
        assertTrue(ruleIds.contains("R01"), "R01 should match");
        assertTrue(ruleIds.contains("R03"), "R03 should match");
    }

    // ================================================================
    // 5. 端到端：真實 ID 字串 → RocIdValidator → 規則命中
    // ================================================================

    @Test
    @DisplayName("端到端：真實 ID 字串走過 RocIdValidator 後送進規則")
    void endToEnd_realIdStrings() throws Exception {
        // 被保人 ID = A123456789（合法身分證）但宣稱外籍 → 應觸發 R01
        String insuredId = "A123456789";
        String policyholderId = "PASSPORT12345";  // 護照、非 ROC 格式
        boolean insuredIdIsRoc = RocIdValidator.isRocId(insuredId);
        boolean policyholderIdIsRoc = RocIdValidator.isRocId(policyholderId);

        assertTrue(insuredIdIsRoc, "A123456789 should be valid ROC ID");
        assertFalse(policyholderIdIsRoc, "PASSPORT12345 should not be ROC ID");

        // 被保人是 TW 人但填 NON_TW、要保人外籍但填 TW
        var matched = lookup(insuredIdIsRoc, "NON_TW", policyholderIdIsRoc, "TW");
        assertEquals(2, matched.matchedRules().size(),
                "Both R01 (insured) and R04 (policyholder) should fire");

        List<String> ruleIds = matched.matchedRules().stream().map(RuleLookupService.MatchedRule::ruleId).toList();
        assertTrue(ruleIds.contains("R01"));
        assertTrue(ruleIds.contains("R04"));
    }

    // ================================================================
    // Helpers
    // ================================================================

    private RuleLookupService.LookupResponse lookup(
            boolean insuredIdIsRoc, String insuredNat,
            boolean policyholderIdIsRoc, String policyholderNat) throws Exception {
        RuleEnvelope envelope = objectMapper.readValue(loadString(FIXTURE), RuleEnvelope.class);
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("insuredIdFormatIsRocId", insuredIdIsRoc);
        inputs.put("insuredNationality", insuredNat);
        inputs.put("policyholderIdFormatIsRocId", policyholderIdIsRoc);
        inputs.put("policyholderNationality", policyholderNat);
        return lookupService.lookup(envelope, inputs);
    }

    private void assertMatched(RuleLookupService.LookupResponse resp, String expectedRuleId, String expectedMessage) {
        assertFalse(resp.matchedRules().isEmpty(), "Expected at least one matched rule for " + expectedRuleId);
        var match = resp.matchedRules().stream()
                .filter(m -> expectedRuleId.equals(m.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected " + expectedRuleId + " to match, got " + resp.matchedRules()));
        assertEquals(expectedMessage, match.results().get("errorMessage"),
                "errorMessage for " + expectedRuleId + " should match spec");
    }

    private JsonNode loadJson(String path) throws Exception {
        return objectMapper.readTree(loadString(path));
    }

    private String loadString(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
