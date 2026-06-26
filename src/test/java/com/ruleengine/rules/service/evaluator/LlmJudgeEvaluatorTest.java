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
 * LlmJudgeEvaluator v3.6.0 升級驗證 — bias mitigation 基於：
 *   - arxiv 2411.15594（self-enhancement bias）
 *   - arxiv 2412.05579（positional bias）
 *   - arxiv 2512.16041（robustness）
 */
class LlmJudgeEvaluatorTest {

    private LlmProviderRegistry registry;
    private LlmProvider claudeProvider;
    private LlmProvider geminiProvider;
    private ObjectMapper mapper;
    private LlmJudgeEvaluator evaluator;

    @BeforeEach
    void setUp() {
        claudeProvider = mock(LlmProvider.class);
        when(claudeProvider.getProviderName()).thenReturn("ClaudeService");
        when(claudeProvider.isAvailable()).thenReturn(true);

        geminiProvider = mock(LlmProvider.class);
        when(geminiProvider.getProviderName()).thenReturn("GeminiService");
        when(geminiProvider.isAvailable()).thenReturn(true);

        registry = mock(LlmProviderRegistry.class);
        when(registry.getAvailableProviderNames()).thenReturn(List.of("claude", "gemini"));
        when(registry.getByName("claude")).thenReturn(claudeProvider);
        when(registry.getByName("gemini")).thenReturn(geminiProvider);
        when(registry.getDefault()).thenReturn(claudeProvider);

        mapper = new ObjectMapper();
        evaluator = new LlmJudgeEvaluator(registry, mapper);
    }

    // ============================================================
    // Cross-provider selection — 消除 self-enhancement bias
    // ============================================================

    @Test
    void pickJudgeProvider_whenGeneratorIsClaude_picksGemini() {
        LlmProvider picked = evaluator.pickJudgeProvider("claude");
        assertThat(picked).isSameAs(geminiProvider);
    }

    @Test
    void pickJudgeProvider_whenGeneratorIsGemini_picksClaude() {
        LlmProvider picked = evaluator.pickJudgeProvider("gemini");
        assertThat(picked).isSameAs(claudeProvider);
    }

    @Test
    void pickJudgeProvider_whenOnlyOneProviderAvailable_fallsBackToDefault() {
        when(registry.getAvailableProviderNames()).thenReturn(List.of("claude"));
        LlmProvider picked = evaluator.pickJudgeProvider("claude");
        assertThat(picked).isSameAs(claudeProvider);
    }

    @Test
    void pickJudgeProvider_whenGeneratorNull_usesDefault() {
        LlmProvider picked = evaluator.pickJudgeProvider(null);
        assertThat(picked).isSameAs(claudeProvider);
    }

    // ============================================================
    // crossProvider flag — 跨 provider 評估時為 true
    // ============================================================

    @Test
    void evaluate_withCrossProvider_flagsCrossProviderTrue() {
        when(geminiProvider.generateRuleJson(anyString())).thenReturn(
                "{\"faithfulness\":0.9,\"completeness\":0.8,\"hallucination\":0.1," +
                        "\"consistency\":0.9,\"overallScore\":0.88,\"comment\":\"good\",\"suggestion\":\"\"}");

        var result = evaluator.evaluate("test", sampleEnvelope(5), "claude");

        assertThat(result).isNotNull();
        assertThat(result.getCrossProvider()).isTrue();
        assertThat(result.getJudgeProvider()).containsIgnoringCase("gemini");
        assertThat(result.getGeneratorProvider()).isEqualTo("claude");
    }

    @Test
    void evaluate_withSameProviderOnly_emitsSelfEnhancementWarning() {
        when(registry.getAvailableProviderNames()).thenReturn(List.of("claude"));
        when(claudeProvider.generateRuleJson(anyString())).thenReturn(
                "{\"faithfulness\":0.9,\"completeness\":0.9,\"hallucination\":0.1," +
                        "\"consistency\":0.9,\"overallScore\":0.9,\"comment\":\"\",\"suggestion\":\"\"}");

        var result = evaluator.evaluate("test", sampleEnvelope(5), "claude");

        assertThat(result.getCrossProvider()).isFalse();
        assertThat(result.getBiasWarnings())
                .anyMatch(w -> w.contains("self-enhancement"));
    }

    // ============================================================
    // Verbosity bias 診斷
    // ============================================================

    @Test
    void diagnoseBiases_fewRulesHighCompleteness_flagsVerbosityRisk() {
        when(geminiProvider.generateRuleJson(anyString())).thenReturn(
                "{\"faithfulness\":0.9,\"completeness\":0.95,\"hallucination\":0.1," +
                        "\"consistency\":0.9,\"overallScore\":0.91,\"comment\":\"\",\"suggestion\":\"\"}");

        var result = evaluator.evaluate("test", sampleEnvelope(1), "claude");

        assertThat(result.getBiasWarnings())
                .anyMatch(w -> w.contains("verbosity bias") && w.contains("completeness"));
    }

    @Test
    void diagnoseBiases_manyRulesLowHallucination_flagsVerbosityRisk() {
        when(geminiProvider.generateRuleJson(anyString())).thenReturn(
                "{\"faithfulness\":0.9,\"completeness\":0.9,\"hallucination\":0.05," +
                        "\"consistency\":0.9,\"overallScore\":0.93,\"comment\":\"\",\"suggestion\":\"\"}");

        var result = evaluator.evaluate("test", sampleEnvelope(50), "claude");

        assertThat(result.getBiasWarnings())
                .anyMatch(w -> w.contains("verbosity bias") && w.contains("hallucination"));
    }

    @Test
    void diagnoseBiases_reasonableScores_noBiasWarnings() {
        when(geminiProvider.generateRuleJson(anyString())).thenReturn(
                "{\"faithfulness\":0.8,\"completeness\":0.75,\"hallucination\":0.2," +
                        "\"consistency\":0.85,\"overallScore\":0.8,\"comment\":\"\",\"suggestion\":\"\"}");

        var result = evaluator.evaluate("test", sampleEnvelope(10), "claude");

        assertThat(result.getBiasWarnings()).isEmpty();
    }

    // ============================================================
    // Self-consistency — positional bias 對抗
    // ============================================================

    @Test
    void evaluateWithSelfConsistency_averagesTwoPassesAndReportsVariance() {
        when(geminiProvider.generateRuleJson(anyString()))
                .thenReturn("{\"faithfulness\":1.0,\"completeness\":1.0,\"hallucination\":0.0," +
                        "\"consistency\":1.0,\"overallScore\":1.0,\"comment\":\"\",\"suggestion\":\"\"}")
                .thenReturn("{\"faithfulness\":0.6,\"completeness\":0.6,\"hallucination\":0.4," +
                        "\"consistency\":0.6,\"overallScore\":0.6,\"comment\":\"\",\"suggestion\":\"\"}");

        var result = evaluator.evaluateWithSelfConsistency("test", sampleEnvelope(5), "claude");

        assertThat(result).isNotNull();
        assertThat(result.getFaithfulness()).isEqualTo(0.8);  // (1.0 + 0.6) / 2
        assertThat(result.getSelfConsistencyVariance()).isGreaterThan(0.0);
        assertThat(result.getBiasWarnings())
                .anyMatch(w -> w.contains("self-consistency variance"));
    }

    @Test
    void evaluateWithSelfConsistency_stableScores_noVarianceWarning() {
        when(geminiProvider.generateRuleJson(anyString()))
                .thenReturn("{\"faithfulness\":0.8,\"completeness\":0.8,\"hallucination\":0.1," +
                        "\"consistency\":0.8,\"overallScore\":0.83,\"comment\":\"\",\"suggestion\":\"\"}");

        var result = evaluator.evaluateWithSelfConsistency("test", sampleEnvelope(10), "claude");

        assertThat(result.getSelfConsistencyVariance()).isEqualTo(0.0);
        assertThat(result.getBiasWarnings())
                .noneMatch(w -> w.contains("self-consistency variance"));
    }

    // ============================================================
    // 基本行為：LLM 不可用時 fallback
    // ============================================================

    @Test
    void evaluate_llmUnavailable_returnsFallback() {
        when(geminiProvider.isAvailable()).thenReturn(false);
        when(claudeProvider.isAvailable()).thenReturn(false);
        when(registry.getDefault()).thenReturn(null);

        var result = evaluator.evaluate("test", sampleEnvelope(5), "claude");

        assertThat(result).isNull();
    }

    @Test
    void evaluate_llmReturnsNull_returnsFallbackWithUnavailableWarning() {
        when(geminiProvider.generateRuleJson(anyString())).thenReturn(null);

        var result = evaluator.evaluate("test", sampleEnvelope(5), "claude");

        assertThat(result).isNotNull();
        assertThat(result.getBiasWarnings()).contains("LLM_UNAVAILABLE");
    }

    // ============================================================
    // 工具
    // ============================================================

    private RuleEnvelope sampleEnvelope(int ruleCount) {
        List<RuleEnvelope.RuleRow> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= ruleCount; i++) {
            rows.add(RuleEnvelope.RuleRow.builder()
                    .ruleId(String.format("R%02d", i))
                    .priority(i)
                    .conditions(List.of(RuleEnvelope.Condition.builder()
                            .field("age").operator("greaterThan").value(18).build()))
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
                .evaluation(RuleEnvelope.Evaluation.builder()
                        .totalScenarios(ruleCount)
                        .coverageRate(1.0)
                        .completeness("COMPLETE")
                        .conflictDetection("NO_CONFLICT")
                        .recommendedStrategy("FIRST")
                        .build())
                .schemaVersion("1.0.0")
                .promptVersion("p3.6.0")
                .build();
    }

    @SuppressWarnings("unused")
    private Map<String, Object> unusedHelper() { return Map.of(); }
}
