package com.ruleengine.rules.service.evaluator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * v3.9.0 — RuleExplanationService 測試。
 *
 * 覆蓋：
 *  - Happy path：LLM 回 JSON → rationale map 填回每條 RuleRow
 *  - 部分成功：LLM 只回部分 ruleId 的 rationale → 只填回有的
 *  - LLM 不可用 → 空 map, 不拋異常
 *  - JSON parse 失敗 → 空 map（non-fatal）
 *  - Prompt 結構 smoke test：包含描述、conditions、outputs
 */
class RuleExplanationServiceTest {

    private LlmProvider provider;
    private LlmProviderRegistry registry;
    private RuleExplanationService service;

    @BeforeEach
    void setUp() {
        provider = mock(LlmProvider.class);
        when(provider.getProviderName()).thenReturn("MockProvider");
        when(provider.isAvailable()).thenReturn(true);

        registry = mock(LlmProviderRegistry.class);
        when(registry.getDefault()).thenReturn(provider);
        when(registry.getByName(anyString())).thenReturn(provider);

        service = new RuleExplanationService(registry, new ObjectMapper());
    }

    // ============================================================
    // Happy path
    // ============================================================

    @Test
    void explainAndApply_populatesRationaleOnEveryRule() {
        when(provider.generateRuleJson(anyString())).thenReturn("""
            {
              "R01": "依描述中『年齡大於 18』規定，成年人可承保。",
              "R02": "依描述中『未成年』要求，拒保並給出建議。"
            }
            """);

        RuleEnvelope env = envelopeWithTwoRules();
        int applied = service.explainAndApply("年齡大於 18 可承保，未成年拒保", env, null);

        assertThat(applied).isEqualTo(2);
        assertThat(env.getRule().getRules().get(0).getRationale()).contains("18");
        assertThat(env.getRule().getRules().get(1).getRationale()).contains("未成年");
    }

    // ============================================================
    // 部分成功
    // ============================================================

    @Test
    void explainAndApply_partialResponse_appliesOnlyWhatIsReturned() {
        when(provider.generateRuleJson(anyString())).thenReturn("""
            { "R01": "依描述成年人承保規定" }
            """);

        RuleEnvelope env = envelopeWithTwoRules();
        int applied = service.explainAndApply("desc", env, null);

        assertThat(applied).isEqualTo(1);
        assertThat(env.getRule().getRules().get(0).getRationale()).isNotNull();
        assertThat(env.getRule().getRules().get(1).getRationale()).isNull();
    }

    // ============================================================
    // LLM 不可用
    // ============================================================

    @Test
    void explainAll_llmUnavailable_returnsEmptyMap() {
        when(provider.isAvailable()).thenReturn(false);
        when(registry.getDefault()).thenReturn(null);

        Map<String, String> result = service.explainAll("desc", envelopeWithTwoRules(), null);

        assertThat(result).isEmpty();
        // 沒 rationale 被填
        assertThat(envelopeWithTwoRules().getRule().getRules().get(0).getRationale()).isNull();
    }

    @Test
    void explainAll_llmReturnsNull_returnsEmptyMap() {
        when(provider.generateRuleJson(anyString())).thenReturn(null);

        Map<String, String> result = service.explainAll("desc", envelopeWithTwoRules(), null);

        assertThat(result).isEmpty();
    }

    @Test
    void explainAll_llmReturnsGarbage_returnsEmptyMap() {
        when(provider.generateRuleJson(anyString())).thenReturn("not json at all");

        Map<String, String> result = service.explainAll("desc", envelopeWithTwoRules(), null);

        assertThat(result).isEmpty();
    }

    @Test
    void explainAll_llmReturnsMarkdownFencedJson_parsesCorrectly() {
        when(provider.generateRuleJson(anyString())).thenReturn("""
            ```json
            { "R01": "...", "R02": "..." }
            ```
            """);

        Map<String, String> result = service.explainAll("desc", envelopeWithTwoRules(), null);

        assertThat(result).hasSize(2);
        assertThat(result).containsKeys("R01", "R02");
    }

    // ============================================================
    // 邊界
    // ============================================================

    @Test
    void explainAll_nullEnvelope_returnsEmptyMap() {
        assertThat(service.explainAll("desc", null, null)).isEmpty();
    }

    @Test
    void explainAll_emptyRules_returnsEmptyMap() {
        RuleEnvelope env = RuleEnvelope.builder()
                .rule(RuleEnvelope.Rule.builder().rules(List.of()).build())
                .build();
        assertThat(service.explainAll("desc", env, null)).isEmpty();
    }

    // ============================================================
    // Prompt 結構 smoke test
    // ============================================================

    @Test
    void buildPrompt_includesDescriptionRuleIdConditionsAndOutputs() {
        RuleEnvelope env = envelopeWithTwoRules();
        String prompt = service.buildPrompt("年齡大於 18 可承保", env);

        assertThat(prompt)
                .contains("年齡大於 18 可承保")
                .contains("R01:").contains("R02:")
                .contains("age")
                .contains("decision")
                .contains("JSON");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private RuleEnvelope envelopeWithTwoRules() {
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(RuleEnvelope.Rule.builder()
                        .hitPolicy("FIRST")
                        .inputs(List.of(RuleEnvelope.FieldDef.builder()
                                .name("age").typeRef("INTEGER").build()))
                        .outputs(List.of(RuleEnvelope.FieldDef.builder()
                                .name("decision").typeRef("STRING").build()))
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
                                        .build()
                        ))
                        .build())
                .schemaVersion("1.0.0")
                .promptVersion("p3.9.0")
                .build();
    }
}
