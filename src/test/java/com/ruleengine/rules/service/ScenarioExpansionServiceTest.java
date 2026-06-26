package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioExpandRequest;
import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioExpandResponse;
import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioRow;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioExpansionServiceTest {

    private final ScenarioExpansionService service =
            new ScenarioExpansionService(new RuleLookupService());

    @Test
    @DisplayName("MULTI validation rules expand to all scenarios without expanding formal rules")
    void expandsCompactMultiRulesToScenarioMatrix() {
        RuleEnvelope envelope = buildIdNationalityEnvelope();

        ScenarioExpandResponse response = service.expand(ScenarioExpandRequest.builder()
                .envelope(envelope)
                .maxScenarios(50)
                .noMatchLabel("PASS")
                .build());

        assertEquals(4, envelope.getRule().getRules().size());
        assertEquals(16, response.getTotalPossible());
        assertEquals(16, response.getReturned());
        assertFalse(response.isTruncated());

        long passCount = response.getScenarios().stream()
                .filter(row -> !row.isMatched())
                .filter(row -> "PASS".equals(row.getOutcomeLabel()))
                .count();
        assertEquals(4, passCount);

        ScenarioRow bothApplicantAndInsuredErrors = response.getScenarios().stream()
                .filter(row -> row.getInputValues().equals(Map.of(
                        "insuredIdFormat", "TW_ID",
                        "insuredNationality", "OTHER",
                        "applicantIdFormat", "TW_ID",
                        "applicantNationality", "OTHER")))
                .findFirst()
                .orElseThrow();

        assertTrue(bothApplicantAndInsuredErrors.isMatched());
        assertEquals(List.of("R01", "R03"), bothApplicantAndInsuredErrors.getMatchedRuleIds());
        assertEquals(List.of("1.1", "1.3"),
                bothApplicantAndInsuredErrors.getResults().get("errorCode"));
    }

    private RuleEnvelope buildIdNationalityEnvelope() {
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(RuleEnvelope.Rule.builder()
                        .hitPolicy("MULTI")
                        .inputs(List.of(
                                field("insuredIdFormat", "ENUM", List.of("TW_ID", "OTHER")),
                                field("insuredNationality", "ENUM", List.of("TW", "OTHER")),
                                field("applicantIdFormat", "ENUM", List.of("TW_ID", "OTHER")),
                                field("applicantNationality", "ENUM", List.of("TW", "OTHER"))))
                        .outputs(List.of(
                                field("errorCode", "STRING", null),
                                field("errorMessage", "STRING", null)))
                        .rules(List.of(
                                row("R01", 1,
                                        List.of(cond("insuredIdFormat", "equals", "TW_ID"),
                                                cond("insuredNationality", "notEquals", "TW")),
                                        "1.1",
                                        "insured ID format requires TW nationality"),
                                row("R02", 2,
                                        List.of(cond("insuredIdFormat", "notEquals", "TW_ID"),
                                                cond("insuredNationality", "equals", "TW")),
                                        "1.2",
                                        "insured non-TW ID requires non-TW nationality"),
                                row("R03", 3,
                                        List.of(cond("applicantIdFormat", "equals", "TW_ID"),
                                                cond("applicantNationality", "notEquals", "TW")),
                                        "1.3",
                                        "applicant ID format requires TW nationality"),
                                row("R04", 4,
                                        List.of(cond("applicantIdFormat", "notEquals", "TW_ID"),
                                                cond("applicantNationality", "equals", "TW")),
                                        "1.4",
                                        "applicant non-TW ID requires non-TW nationality")))
                        .build())
                .build();
    }

    @Test
    @DisplayName("v3.12 分層抽樣：截斷時每個維度的所有值都至少出現一次（避免偏向第一維）")
    void truncatedExpansionCoversEveryDimensionValue() {
        // 5 個維度各 4 個值 → totalPossible = 1024，maxScenarios = 32 → 強制截斷
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(RuleEnvelope.Rule.builder()
                        .hitPolicy("MULTI")
                        .inputs(List.of(
                                field("dimA", "ENUM", List.of("A1", "A2", "A3", "A4")),
                                field("dimB", "ENUM", List.of("B1", "B2", "B3", "B4")),
                                field("dimC", "ENUM", List.of("C1", "C2", "C3", "C4")),
                                field("dimD", "ENUM", List.of("D1", "D2", "D3", "D4")),
                                field("dimE", "ENUM", List.of("E1", "E2", "E3", "E4"))))
                        .outputs(List.of(field("err", "STRING", null)))
                        .rules(List.of())
                        .build())
                .build();

        ScenarioExpandResponse response = service.expand(ScenarioExpandRequest.builder()
                .envelope(envelope)
                .maxScenarios(32)
                .noMatchLabel("PASS")
                .build());

        assertEquals(1024, response.getTotalPossible());
        assertEquals(32, response.getReturned());
        assertTrue(response.isTruncated());

        // 關鍵斷言：每個維度的所有 4 個值都至少出現一次
        for (String dim : List.of("dimA", "dimB", "dimC", "dimD", "dimE")) {
            long uniqueValues = response.getScenarios().stream()
                    .map(row -> row.getInputValues().get(dim))
                    .distinct()
                    .count();
            assertEquals(4, uniqueValues,
                    "維度 " + dim + " 應在截斷後仍涵蓋全部 4 個值（舊版 break 偏向第一維會壞掉）");
        }
    }

    private RuleEnvelope.FieldDef field(String name, String typeRef, List<String> allowedValues) {
        return RuleEnvelope.FieldDef.builder()
                .name(name)
                .typeRef(typeRef)
                .allowedValues(allowedValues)
                .build();
    }

    private RuleEnvelope.Condition cond(String field, String operator, Object value) {
        return RuleEnvelope.Condition.builder()
                .field(field)
                .operator(operator)
                .value(value)
                .build();
    }

    private RuleEnvelope.RuleRow row(String ruleId, int priority,
                                     List<RuleEnvelope.Condition> conditions,
                                     String errorCode,
                                     String errorMessage) {
        return RuleEnvelope.RuleRow.builder()
                .ruleId(ruleId)
                .priority(priority)
                .conditions(conditions)
                .results(List.of(
                        RuleEnvelope.Result.builder().field("errorCode").value(errorCode).build(),
                        RuleEnvelope.Result.builder().field("errorMessage").value(errorMessage).build()))
                .build();
    }
}
