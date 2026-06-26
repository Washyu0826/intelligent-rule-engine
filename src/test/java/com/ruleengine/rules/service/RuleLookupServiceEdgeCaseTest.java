package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleLookupService - edge-case matching")
class RuleLookupServiceEdgeCaseTest {

    private final RuleLookupService service = new RuleLookupService();

    @Test
    @DisplayName("DATE between uses chronological comparison")
    void dateBetweenMatchesChronologically() {
        RuleEnvelope envelope = envelope("policyDate", "DATE",
                condition("policyDate", "between", List.of("2026-01-01", "2026-12-31")));

        assertTrue(service.lookup(envelope, Map.of("policyDate", "2026-06-05")).matched());
        assertFalse(service.lookup(envelope, Map.of("policyDate", "2027-01-01")).matched());
    }

    @Test
    @DisplayName("missing fields do not satisfy negative operators")
    void missingFieldDoesNotMatchNegativeOperators() {
        assertFalse(service.lookup(
                envelope("status", "STRING", condition("status", "notEquals", "blocked")),
                Map.of()).matched());
        assertFalse(service.lookup(
                envelope("status", "STRING", condition("status", "notIn", List.of("blocked"))),
                Map.of()).matched());
    }

    @Test
    @DisplayName("explicit null remains distinguishable from a missing field")
    void explicitNullCanMatchIsNull() {
        Map<String, Object> input = new HashMap<>();
        input.put("status", null);

        assertTrue(service.lookup(
                envelope("status", "STRING", condition("status", "isNull", null)),
                input).matched());
        assertFalse(service.lookup(
                envelope("status", "STRING", condition("status", "isNull", null)),
                Map.of()).matched());
    }

    @Test
    @DisplayName("invalid boolean strings do not coerce to false")
    void booleanCoercionIsStrict() {
        Condition expectsFalse = condition("flag", "equals", false);
        assertFalse(service.matchesCondition(expectsFalse, "not-a-boolean"));
        assertTrue(service.matchesCondition(expectsFalse, "false"));
    }

    @Test
    @DisplayName("unresolved valueRef fails safely even when actual value is null")
    void unresolvedValueRefDoesNotMatchNull() {
        Condition condition = Condition.builder()
                .field("left")
                .operator("equals")
                .valueRef("right")
                .build();
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("left", null);

        assertFalse(service.matchesCondition(condition, null, inputs));
    }

    private RuleEnvelope envelope(String field, String typeRef, Condition condition) {
        RuleRow row = RuleRow.builder()
                .ruleId("R1")
                .priority(1)
                .conditions(List.of(condition))
                .results(List.of(Result.builder().field("decision").value("hit").build()))
                .build();
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(Rule.builder()
                        .hitPolicy("FIRST")
                        .inputs(List.of(FieldDef.builder().name(field).typeRef(typeRef).build()))
                        .outputs(List.of(FieldDef.builder().name("decision").typeRef("STRING").build()))
                        .rules(List.of(row))
                        .build())
                .build();
    }

    private Condition condition(String field, String operator, Object value) {
        return Condition.builder().field(field).operator(operator).value(value).build();
    }
}
