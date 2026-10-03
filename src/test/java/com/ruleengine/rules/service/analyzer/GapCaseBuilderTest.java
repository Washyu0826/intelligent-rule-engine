package com.ruleengine.rules.service.analyzer;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GapCaseBuilderTest {

    private final GapCaseBuilder builder = new GapCaseBuilder();

    private RuleEnvelope envelope() {
        RuleEnvelope.Rule rule = RuleEnvelope.Rule.builder()
                .hitPolicy("FIRST")
                .inputs(List.of(
                        FieldDef.builder().name("age").typeRef("INTEGER").build(),
                        FieldDef.builder().name("gender").typeRef("ENUM").allowedValues(List.of("男", "女")).build(),
                        FieldDef.builder().name("hypertension").typeRef("BOOLEAN").build(),
                        FieldDef.builder().name("bmi").typeRef("DECIMAL").build()))
                .outputs(List.of(
                        FieldDef.builder().name("decision").typeRef("ENUM").allowedValues(List.of("承保", "人工評估", "拒保")).build(),
                        FieldDef.builder().name("factor").typeRef("DECIMAL").build(),
                        FieldDef.builder().name("note").typeRef("STRING").build()))
                .rules(List.of(
                        RuleRow.builder().ruleId("R1").priority(1).conditions(List.of()).results(List.of()).build(),
                        RuleRow.builder().ruleId("R7").priority(2).conditions(List.of()).results(List.of()).build()))
                .build();
        return RuleEnvelope.builder().ruleType("DecisionTable").rule(rule).build();
    }

    @Test
    @DisplayName("區間、列舉、布林、小數各自變成正確 operator；未提到的欄位為 anything")
    void parsesEveryShape() {
        RuleRow row = builder.build(envelope(), Map.of(
                "age", "[36,50]", "gender", "[男,女]", "hypertension", "true"));

        Map<String, Condition> byField = row.getConditions().stream()
                .collect(java.util.stream.Collectors.toMap(Condition::getField, c -> c));
        assertEquals("between", byField.get("age").getOperator());
        assertEquals(List.of(36L, 50L), byField.get("age").getValue());
        assertEquals("in", byField.get("gender").getOperator());
        assertEquals(List.of("男", "女"), byField.get("gender").getValue());
        assertEquals("equals", byField.get("hypertension").getOperator());
        assertEquals(Boolean.TRUE, byField.get("hypertension").getValue());
        assertEquals("anything", byField.get("bmi").getOperator());
        assertNull(byField.get("bmi").getValue());
    }

    @Test
    @DisplayName("單值：整數 equals、列舉單一 equals、小數區間 between")
    void parsesPointValues() {
        RuleRow row = builder.build(envelope(), Map.of("age", "42", "gender", "女", "bmi", "[18.5,24.9]"));
        Map<String, Condition> byField = row.getConditions().stream()
                .collect(java.util.stream.Collectors.toMap(Condition::getField, c -> c));
        assertEquals(42L, byField.get("age").getValue());
        assertEquals("equals", byField.get("gender").getOperator());
        assertEquals("女", byField.get("gender").getValue());
        assertEquals(List.of(18.5, 24.9), byField.get("bmi").getValue());
    }

    @Test
    @DisplayName("結果：決議類填人工評估，其餘留空；ruleId 與 priority 接在最大值之後")
    void resultsStayUnfilledExceptManualDecision() {
        RuleRow row = builder.build(envelope(), Map.of("age", "[66,120]"));
        assertEquals("R8", row.getRuleId());
        assertEquals(8, row.getPriority());
        assertEquals("人工評估", row.getResults().get(0).getValue());
        assertNull(row.getResults().get(1).getValue());
        assertNull(row.getResults().get(2).getValue());
        assertTrue(row.getRationale().contains("待填"));
    }
}
