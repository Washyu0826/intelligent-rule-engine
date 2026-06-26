package com.ruleengine.rules.service.narrative;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v3.12 — BusinessNarrativeService 測試。
 *
 * 覆蓋：
 *  - Happy path：LLM 回完整 JSON → 結構化欄位正確解析
 *  - Markdown 圍欄：LLM 回 ```json ...``` 仍能解析
 *  - LLM 不可用 → rule-based fallback，有 summary，不拋異常
 *  - LLM 回 null / garbage → fallback
 *  - 部分欄位缺失 → 補空字串／空 list，不拋 NPE
 *  - Prompt 結構 smoke：含描述、輸入欄位、rule 條列、用詞規範
 *  - 用詞規範 smoke：prompt 明確禁止 operator/typeRef 等技術詞
 */
class BusinessNarrativeServiceTest {

    private LlmProvider provider;
    private LlmProviderRegistry registry;
    private BusinessNarrativeService service;

    @BeforeEach
    void setUp() {
        provider = mock(LlmProvider.class);
        when(provider.getProviderName()).thenReturn("MockProvider");
        when(provider.isAvailable()).thenReturn(true);

        registry = mock(LlmProviderRegistry.class);
        when(registry.getDefault()).thenReturn(provider);
        when(registry.getByName(anyString())).thenReturn(provider);

        service = new BusinessNarrativeService(registry, new ObjectMapper());
    }

    // ============================================================
    // Happy path
    // ============================================================

    @Test
    void narrate_fullLlmResponse_parsesAllFields() {
        when(provider.generateRuleJson(anyString())).thenReturn("""
            {
              "summary": "本規則表決定 20-65 歲被保人的核保結果，共 4 條規則。命中第一條即停。",
              "highlights": [
                "涵蓋男女性投保人",
                "高血壓被保人走特別處理",
                "保額超過 500 萬加收附加保費"
              ],
              "coverage": "被保人年齡 20 至 65 歲，男女皆可，含三高狀況分流。",
              "exceptions": [
                "吸菸者一律加費 30%",
                "70 歲以上不予承保"
              ],
              "actuarialNote": "目前覆蓋率 92%，18-20 歲區段為覆蓋缺口，建議補規則。"
            }
            """);

        var n = service.narrate("20-65 歲承保，吸菸加費", envelopeWithTwoRules(), null);

        assertThat(n.getSummary()).contains("20-65").contains("核保");
        assertThat(n.getHighlights()).hasSize(3);
        assertThat(n.getCoverage()).contains("20").contains("65");
        assertThat(n.getExceptions()).hasSize(2);
        assertThat(n.getActuarialNote()).contains("92%");
        assertThat(n.getProvider()).isEqualTo("MockProvider");
        assertThat(n.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void narrate_markdownFencedJson_parsesCorrectly() {
        when(provider.generateRuleJson(anyString())).thenReturn("""
            ```json
            {
              "summary": "摘要",
              "highlights": ["要點 1"],
              "coverage": "涵蓋",
              "exceptions": [],
              "actuarialNote": ""
            }
            ```
            """);

        var n = service.narrate("desc", envelopeWithTwoRules(), null);

        assertThat(n.getSummary()).isEqualTo("摘要");
        assertThat(n.getHighlights()).containsExactly("要點 1");
    }

    // ============================================================
    // LLM 不可用 → fallback
    // ============================================================

    @Test
    void narrate_llmUnavailable_returnsRuleBasedFallback() {
        when(provider.isAvailable()).thenReturn(false);
        when(registry.getDefault()).thenReturn(null);

        var n = service.narrate("desc", envelopeWithTwoRules(), null);

        assertThat(n.getSummary()).isNotEmpty();
        assertThat(n.getSummary()).contains("2 條");  // 2 rules
        assertThat(n.getProvider()).isEqualTo("fallback");
        assertThat(n.getActuarialNote()).contains("LLM");
    }

    @Test
    void narrate_llmReturnsNull_returnsFallback() {
        when(provider.generateRuleJson(anyString())).thenReturn(null);

        var n = service.narrate("desc", envelopeWithTwoRules(), null);

        assertThat(n.getSummary()).isNotEmpty();
        assertThat(n.getProvider()).isEqualTo("fallback");
    }

    @Test
    void narrate_llmReturnsGarbage_returnsFallback() {
        when(provider.generateRuleJson(anyString())).thenReturn("this is not json");

        var n = service.narrate("desc", envelopeWithTwoRules(), null);

        assertThat(n.getSummary()).isNotEmpty();
        assertThat(n.getProvider()).isEqualTo("fallback");
    }

    // ============================================================
    // 部分欄位 / 邊界
    // ============================================================

    @Test
    void narrate_partialJson_fillsMissingWithEmpty() {
        when(provider.generateRuleJson(anyString())).thenReturn("""
            { "summary": "只有摘要" }
            """);

        var n = service.narrate("desc", envelopeWithTwoRules(), null);

        assertThat(n.getSummary()).isEqualTo("只有摘要");
        assertThat(n.getHighlights()).isEmpty();
        assertThat(n.getCoverage()).isEmpty();
        assertThat(n.getExceptions()).isEmpty();
        assertThat(n.getActuarialNote()).isEmpty();
    }

    @Test
    void narrate_nullEnvelope_returnsEmpty() {
        var n = service.narrate("desc", null, null);
        assertThat(n.getSummary()).isEmpty();
    }

    @Test
    void narrate_envelopeWithoutRule_returnsEmpty() {
        RuleEnvelope env = RuleEnvelope.builder().ruleType("DecisionTable").build();
        var n = service.narrate("desc", env, null);
        assertThat(n.getSummary()).isEmpty();
    }

    // ============================================================
    // Prompt 結構 smoke test
    // ============================================================

    @Test
    void buildPrompt_includesDescriptionInputsAndRulesRow() {
        String prompt = service.buildPrompt("20-65 歲承保", envelopeWithTwoRules());

        assertThat(prompt)
                .contains("20-65 歲承保")
                .contains("age")
                .contains("decision")
                .contains("R01:")
                .contains("R02:");
    }

    @Test
    void buildPrompt_enforcesBusinessLanguageRules() {
        String prompt = service.buildPrompt("desc", envelopeWithTwoRules());

        // Prompt 明文禁止使用技術詞
        assertThat(prompt).contains("禁用").contains("operator").contains("typeRef");
        // Prompt 鼓勵業務詞
        assertThat(prompt).contains("超過");
    }

    @Test
    void buildPrompt_largeRuleSetTruncatesTo20() {
        RuleEnvelope env = envelopeWithNRules(50);
        String prompt = service.buildPrompt("desc", env);

        assertThat(prompt).contains("省略");
        assertThat(prompt).contains("另有 30 條");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private RuleEnvelope envelopeWithTwoRules() {
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(RuleEnvelope.Rule.builder()
                        .hitPolicy("FIRST")
                        .inputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("age").typeRef("INTEGER").build(),
                                RuleEnvelope.FieldDef.builder().name("gender").typeRef("ENUM")
                                        .allowedValues(List.of("M", "F")).build()))
                        .outputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("decision").typeRef("STRING").build()))
                        .rules(List.of(
                                RuleEnvelope.RuleRow.builder()
                                        .ruleId("R01").priority(1)
                                        .conditions(List.of(RuleEnvelope.Condition.builder()
                                                .field("age").operator("greaterThan").value(18).build()))
                                        .results(List.of(RuleEnvelope.Result.builder()
                                                .field("decision").value("承保").build()))
                                        .build(),
                                RuleEnvelope.RuleRow.builder()
                                        .ruleId("R02").priority(2)
                                        .conditions(List.of(RuleEnvelope.Condition.builder()
                                                .field("age").operator("lessThanOrEqual").value(18).build()))
                                        .results(List.of(RuleEnvelope.Result.builder()
                                                .field("decision").value("拒保").build()))
                                        .build()))
                        .build())
                .evaluation(RuleEnvelope.Evaluation.builder()
                        .totalScenarios(2).coverageRate(1.0)
                        .completeness("COMPLETE").conflictDetection("NO_CONFLICT").build())
                .schemaVersion("1.0.0").promptVersion("p3.12.0")
                .build();
    }

    private RuleEnvelope envelopeWithNRules(int n) {
        List<RuleEnvelope.RuleRow> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            rows.add(RuleEnvelope.RuleRow.builder()
                    .ruleId(String.format("R%02d", i)).priority(i)
                    .conditions(List.of(RuleEnvelope.Condition.builder()
                            .field("age").operator("equals").value(i).build()))
                    .results(List.of(RuleEnvelope.Result.builder()
                            .field("decision").value("承保").build()))
                    .build());
        }
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(RuleEnvelope.Rule.builder()
                        .hitPolicy("FIRST")
                        .inputs(List.of(RuleEnvelope.FieldDef.builder()
                                .name("age").typeRef("INTEGER").build()))
                        .outputs(List.of(RuleEnvelope.FieldDef.builder()
                                .name("decision").typeRef("STRING").build()))
                        .rules(rows)
                        .build())
                .build();
    }
}
