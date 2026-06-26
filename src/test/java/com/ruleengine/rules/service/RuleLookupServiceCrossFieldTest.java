package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.12 — RuleLookupService cross-field & relative-date valueRef behaviour.
 *
 * 對應業務情境 2（投保始期檢核）：
 *   - "投保始期 > 隔日"   → policyStartDate > $today+1d
 *   - "投保始期 < 被保人生日" → policyStartDate < birthday
 */
class RuleLookupServiceCrossFieldTest {

    private final RuleLookupService service = new RuleLookupService();

    @Test
    @DisplayName("valueRef 跨欄位比較：投保始期 < 被保人生日 → 命中錯誤碼")
    void crossFieldDateComparisonMatches() {
        RuleEnvelope envelope = envelope(List.of(
                row("R01",
                        List.of(cond("policyStartDate", "lessThan").valueRef("birthday").build()),
                        "ERR_DATE_BEFORE_BIRTH",
                        "投保始期不得早於被保人生日")
        ));

        Map<String, Object> bad = Map.of(
                "policyStartDate", "1990-01-01",
                "birthday", "2000-06-15");
        Map<String, Object> good = Map.of(
                "policyStartDate", "2026-05-09",
                "birthday", "2000-06-15");

        assertTrue(service.lookup(envelope, bad).matched(),
                "投保始期早於生日應命中錯誤碼");
        assertFalse(service.lookup(envelope, good).matched(),
                "投保始期晚於生日不應命中");
    }

    @Test
    @DisplayName("valueRef 相對日期：投保始期 > $today+1d → 命中錯誤碼")
    void relativeDateComparisonMatches() {
        RuleEnvelope envelope = envelope(List.of(
                row("R02",
                        List.of(cond("policyStartDate", "greaterThan").valueRef("$today+1d").build()),
                        "ERR_START_TOO_FAR",
                        "投保始期不得晚於隔日")
        ));

        String farFuture = LocalDate.now().plusDays(5).toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();

        assertTrue(service.lookup(envelope, Map.of("policyStartDate", farFuture)).matched(),
                "始期 +5 日應命中");
        assertFalse(service.lookup(envelope, Map.of("policyStartDate", tomorrow)).matched(),
                "始期 = 隔日不應命中（greaterThan 嚴格）");
    }

    @Test
    @DisplayName("valueRef 相對日期：lessThanOrEqual $today 邊界正確")
    void relativeTodayBoundary() {
        RuleEnvelope envelope = envelope(List.of(
                row("R03",
                        List.of(cond("policyStartDate", "lessThanOrEqual").valueRef("$today").build()),
                        "ERR_NOT_FUTURE",
                        "投保始期需為未來日")
        ));

        String today = LocalDate.now().toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();

        assertTrue(service.lookup(envelope, Map.of("policyStartDate", today)).matched(),
                "= today 應命中（≤ today）");
        assertFalse(service.lookup(envelope, Map.of("policyStartDate", tomorrow)).matched(),
                "tomorrow 不應命中");
    }

    @Test
    @DisplayName("缺少 valueRef 指向的欄位 → 不命中（fail-safe）")
    void missingValueRefIsNotMatch() {
        RuleEnvelope envelope = envelope(List.of(
                row("R04",
                        List.of(cond("policyStartDate", "lessThan").valueRef("birthday").build()),
                        "ERR", "msg")
        ));

        assertFalse(service.lookup(envelope, Map.of("policyStartDate", "2026-01-01")).matched(),
                "未提供 birthday 時應視為不命中");
    }

    // ========= helpers =========

    private RuleEnvelope envelope(List<RuleRow> rules) {
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(Rule.builder()
                        .hitPolicy("MULTI")
                        .inputs(List.of(
                                FieldDef.builder().name("policyStartDate").typeRef("DATE").build(),
                                FieldDef.builder().name("birthday").typeRef("DATE").build()))
                        .outputs(List.of(
                                FieldDef.builder().name("errorCode").typeRef("STRING").build(),
                                FieldDef.builder().name("errorMessage").typeRef("STRING").build()))
                        .rules(rules)
                        .build())
                .build();
    }

    private Condition.ConditionBuilder cond(String field, String operator) {
        return Condition.builder().field(field).operator(operator);
    }

    private RuleRow row(String id, List<Condition> conds, String code, String msg) {
        return RuleRow.builder()
                .ruleId(id)
                .priority(1)
                .conditions(conds)
                .results(List.of(
                        Result.builder().field("errorCode").value(code).build(),
                        Result.builder().field("errorMessage").value(msg).build()))
                .build();
    }

}
