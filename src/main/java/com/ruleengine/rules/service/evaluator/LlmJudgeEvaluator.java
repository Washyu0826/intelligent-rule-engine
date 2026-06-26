package com.ruleengine.rules.service.evaluator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-as-Judge 評估器（v3.6.0 升級 — 基於 2024–2025 研究）。
 *
 * 用第二個 LLM 呼叫評估生成結果的：
 *   - Faithfulness（忠實度）、Completeness（完整度）
 *   - Hallucination（幻覺）、Consistency（一致性）
 *
 * 本次升級依下列文獻處理已知偏差：
 *   - arxiv 2411.15594 "A Survey on LLM-as-a-Judge"（2024-11）
 *   - arxiv 2412.05579 "LLMs-as-Judges: A Comprehensive Survey"（2024-12）
 *   - arxiv 2512.16041 "Are We on the Right Way to Assessing LLM-as-a-Judge?"（2025-12）
 *   - Spring AI LLM-as-Judge blog（2025-11）
 *
 * 三個已知偏差與本評估器對應處理：
 *   (1) Self-enhancement bias — 同一 provider 生成 + 評分會偏高。
 *       → pickJudgeProvider() 優先選**與生成者不同**的 provider。
 *   (2) Positional bias — 評估條目的先後順序會影響分數。
 *       → evaluateWithSelfConsistency() 以不同 criteria 順序跑兩次後取平均。
 *   (3) Verbosity bias — 較長的輸出往往被評較高。
 *       → diagnoseBiases() 偵測「規則數少但分數高」或「規則數大但幻覺分數低」等異常並以 biasWarnings 回報。
 */
@Service
@Slf4j
public class LlmJudgeEvaluator {

    private final LlmProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public LlmJudgeEvaluator(LlmProviderRegistry providerRegistry, ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    // ========================================
    // 評估結果 DTO
    // ========================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JudgeResult {
        private double faithfulness;
        private double completeness;
        /** 0.0 = 無幻覺（好），1.0 = 嚴重幻覺（差） */
        private double hallucination;
        private double consistency;
        private double overallScore;
        private String comment;
        private String suggestion;

        /** 評估用的 LLM provider */
        private String judgeProvider;
        /** 生成用的 LLM provider（若有提供） */
        private String generatorProvider;
        /** 是否為跨 provider 評估（true = 已規避 self-enhancement bias） */
        private Boolean crossProvider;
        /** self-consistency 變異數；0 = 單次評估，>0 = 多次評估之離散程度 */
        private Double selfConsistencyVariance;
        /** 偵測到的偏差警告（空陣列代表乾淨） */
        private List<String> biasWarnings;

        private long durationMs;
    }

    // ========================================
    // 公開 API
    // ========================================

    /** 便捷呼叫：不指定生成者 provider，單次評估。 */
    public JudgeResult evaluate(String originalDescription, RuleEnvelope envelope) {
        return evaluate(originalDescription, envelope, null);
    }

    /**
     * 單次評估（可指定生成者 provider，以啟用 cross-provider judge）。
     */
    public JudgeResult evaluate(String originalDescription, RuleEnvelope envelope,
                                String generatorProvider) {
        return runAndDiagnose(originalDescription, envelope, generatorProvider, CriteriaOrder.A);
    }

    /**
     * 帶 self-consistency 的評估（對抗 positional bias）。
     *
     * 以兩種 criteria 順序各跑一次，取平均；回報 variance。
     * 成本為單次的兩倍，只在需要高信心時使用。
     */
    public JudgeResult evaluateWithSelfConsistency(String originalDescription,
                                                   RuleEnvelope envelope,
                                                   String generatorProvider) {
        long startMs = System.currentTimeMillis();
        JudgeResult a = runAndDiagnose(originalDescription, envelope, generatorProvider, CriteriaOrder.A);
        JudgeResult b = runAndDiagnose(originalDescription, envelope, generatorProvider, CriteriaOrder.B);

        if (a == null || b == null) {
            return a != null ? a : b;
        }

        double avgFaith = (a.faithfulness + b.faithfulness) / 2.0;
        double avgComp  = (a.completeness + b.completeness) / 2.0;
        double avgHall  = (a.hallucination + b.hallucination) / 2.0;
        double avgCons  = (a.consistency + b.consistency) / 2.0;
        double avgOverall = (a.overallScore + b.overallScore) / 2.0;
        double variance = variance(
                a.faithfulness, b.faithfulness,
                a.completeness, b.completeness,
                a.hallucination, b.hallucination,
                a.consistency, b.consistency
        );

        List<String> warnings = new ArrayList<>(a.biasWarnings != null ? a.biasWarnings : List.of());
        if (variance > 0.05) {
            warnings.add("self-consistency variance 偏高（" + String.format("%.3f", variance)
                    + "）— 評分可能受 positional bias 影響，建議人工複核");
        }

        return JudgeResult.builder()
                .faithfulness(avgFaith)
                .completeness(avgComp)
                .hallucination(avgHall)
                .consistency(avgCons)
                .overallScore(avgOverall)
                .comment(a.comment)
                .suggestion(a.suggestion)
                .judgeProvider(a.judgeProvider)
                .generatorProvider(generatorProvider)
                .crossProvider(a.crossProvider)
                .selfConsistencyVariance(variance)
                .biasWarnings(warnings)
                .durationMs(System.currentTimeMillis() - startMs)
                .build();
    }

    // ========================================
    // Provider 選擇（消除 self-enhancement bias）
    // ========================================

    /**
     * 選評估 provider：優先選**與生成者不同**的可用 provider。
     * 若找不到其他 provider，fallback 到預設（並 flag bias warning）。
     */
    LlmProvider pickJudgeProvider(String generatorProvider) {
        if (generatorProvider == null || generatorProvider.isBlank()) {
            return providerRegistry.getDefault();
        }
        String normalized = generatorProvider.toLowerCase();
        for (String name : providerRegistry.getAvailableProviderNames()) {
            if (!name.equalsIgnoreCase(normalized) && !name.equalsIgnoreCase("offline")) {
                LlmProvider p = providerRegistry.getByName(name);
                if (p != null && p.isAvailable()) {
                    return p;
                }
            }
        }
        return providerRegistry.getDefault();
    }

    // ========================================
    // 核心評估流程
    // ========================================

    private JudgeResult runAndDiagnose(String description, RuleEnvelope envelope,
                                       String generatorProvider, CriteriaOrder order) {
        LlmProvider judge = pickJudgeProvider(generatorProvider);
        if (judge == null || !judge.isAvailable()) {
            log.warn("LLM Judge 不可用，跳過評估");
            return null;
        }

        long startMs = System.currentTimeMillis();
        String judgeName = extractProviderName(judge);
        boolean crossProvider = generatorProvider != null
                && !generatorProvider.isBlank()
                && !judgeName.equalsIgnoreCase(generatorProvider);

        try {
            String summary = buildSummary(envelope);
            String userPrompt = String.format(
                    order == CriteriaOrder.A ? USER_TEMPLATE_A : USER_TEMPLATE_B,
                    description, summary);
            String rawResponse = judge.generateRuleJson(SYSTEM_PROMPT + "\n\n" + userPrompt);

            if (rawResponse == null) {
                return fallbackResult(judgeName, generatorProvider, crossProvider,
                        System.currentTimeMillis() - startMs);
            }

            JsonNode json = parseLenient(rawResponse);
            JudgeResult result = JudgeResult.builder()
                    .faithfulness(clamp01(json.path("faithfulness").asDouble(0.5)))
                    .completeness(clamp01(json.path("completeness").asDouble(0.5)))
                    .hallucination(clamp01(json.path("hallucination").asDouble(0.5)))
                    .consistency(clamp01(json.path("consistency").asDouble(0.5)))
                    .overallScore(clamp01(json.path("overallScore").asDouble(0.5)))
                    .comment(json.path("comment").asText("無評語"))
                    .suggestion(json.path("suggestion").asText(""))
                    .judgeProvider(judgeName)
                    .generatorProvider(generatorProvider)
                    .crossProvider(crossProvider)
                    .selfConsistencyVariance(0.0)
                    .biasWarnings(diagnoseBiases(envelope, judgeName, generatorProvider, crossProvider, json))
                    .durationMs(System.currentTimeMillis() - startMs)
                    .build();

            log.info("LLM Judge | judge={} | generator={} | cross={} | overall={} | duration={}ms",
                    judgeName, generatorProvider, crossProvider, result.overallScore, result.durationMs);

            return result;

        } catch (Exception e) {
            log.error("LLM Judge 評估失敗: {}", e.getMessage());
            return fallbackResult(judgeName, generatorProvider, crossProvider,
                    System.currentTimeMillis() - startMs);
        }
    }

    // ========================================
    // 偏差診斷（verbosity & self-enhancement）
    // ========================================

    List<String> diagnoseBiases(RuleEnvelope envelope, String judgeName,
                                String generatorProvider, boolean crossProvider,
                                JsonNode judgeOutput) {
        List<String> warnings = new ArrayList<>();

        // self-enhancement：判斷若強制同 provider
        if (generatorProvider != null && !generatorProvider.isBlank() && !crossProvider) {
            warnings.add("self-enhancement risk：judge 與 generator 同為 " + judgeName
                    + "；若有其他 provider 可用，建議切換以獨立評估");
        }

        // verbosity：規則極少時 completeness 卻極高
        int ruleCount = 0;
        if (envelope != null && envelope.getRule() != null && envelope.getRule().getRules() != null) {
            ruleCount = envelope.getRule().getRules().size();
        }
        double completeness = clamp01(judgeOutput.path("completeness").asDouble(0.5));
        double hallucination = clamp01(judgeOutput.path("hallucination").asDouble(0.5));

        if (ruleCount > 0 && ruleCount < 3 && completeness > 0.85) {
            warnings.add("verbosity bias risk：僅 " + ruleCount
                    + " 條規則卻評 completeness=" + String.format("%.2f", completeness)
                    + "（通常高完整度需要多條規則覆蓋）");
        }
        if (ruleCount > 30 && hallucination < 0.10) {
            warnings.add("verbosity bias risk：" + ruleCount
                    + " 條規則卻評 hallucination=" + String.format("%.2f", hallucination)
                    + "（大量規則通常會引入未描述內容，低幻覺分數存疑）");
        }

        return warnings;
    }

    // ========================================
    // Envelope 摘要 / JSON parse / 工具
    // ========================================

    private String buildSummary(RuleEnvelope envelope) {
        StringBuilder sb = new StringBuilder();
        sb.append("ruleType: ").append(envelope.getRuleType()).append("\n");

        if (envelope.getRule() != null) {
            var rule = envelope.getRule();
            if (rule.getInputs() != null) {
                sb.append("inputs(").append(rule.getInputs().size()).append("): ");
                rule.getInputs().forEach(i -> sb.append(i.getName()).append("(").append(i.getTypeRef()).append(") "));
                sb.append("\n");
            }
            if (rule.getOutputs() != null) {
                sb.append("outputs(").append(rule.getOutputs().size()).append("): ");
                rule.getOutputs().forEach(o -> sb.append(o.getName()).append("(").append(o.getTypeRef()).append(") "));
                sb.append("\n");
            }
            if (rule.getRules() != null) {
                sb.append("rules: ").append(rule.getRules().size()).append(" 條\n");
                rule.getRules().stream().limit(3).forEach(r -> {
                    sb.append("  ").append(r.getRuleId()).append(": ");
                    if (r.getConditions() != null) {
                        r.getConditions().forEach(c ->
                                sb.append(c.getField()).append(" ").append(c.getOperator())
                                        .append(" ").append(c.getValue()).append(", "));
                    }
                    sb.append("→ ");
                    if (r.getResults() != null) {
                        r.getResults().forEach(res ->
                                sb.append(res.getField()).append("=").append(res.getValue()).append(", "));
                    }
                    sb.append("\n");
                });
                if (rule.getRules().size() > 3) {
                    sb.append("  ... 還有 ").append(rule.getRules().size() - 3).append(" 條\n");
                }
            }
        }

        if (envelope.getEvaluation() != null) {
            var eval = envelope.getEvaluation();
            sb.append("coverage: ").append(eval.getCoverageRate()).append("\n");
            sb.append("completeness: ").append(eval.getCompleteness()).append("\n");
            sb.append("conflicts: ").append(eval.getConflictDetection()).append("\n");
        }

        return sb.toString();
    }

    private JsonNode parseLenient(String raw) throws Exception {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```json?\\s*", "").replaceAll("```$", "").trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        return objectMapper.readTree(cleaned);
    }

    private JudgeResult fallbackResult(String judgeName, String generatorProvider,
                                       boolean crossProvider, long durationMs) {
        return JudgeResult.builder()
                .faithfulness(0.5).completeness(0.5).hallucination(0.5).consistency(0.5)
                .overallScore(0.5)
                .comment("LLM 評估不可用，使用預設分數")
                .judgeProvider(judgeName)
                .generatorProvider(generatorProvider)
                .crossProvider(crossProvider)
                .selfConsistencyVariance(0.0)
                .biasWarnings(List.of("LLM_UNAVAILABLE"))
                .durationMs(durationMs)
                .build();
    }

    private String extractProviderName(LlmProvider p) {
        String name = p.getProviderName();
        if (name == null) return "unknown";
        return name.toLowerCase().replace("service", "");
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 計算 pass A 與 pass B 在四個維度上的平均變異數（簡化 pooled variance）。 */
    private static double variance(double... pairs) {
        if (pairs.length < 2 || pairs.length % 2 != 0) return 0.0;
        double sum = 0.0;
        int pairCount = pairs.length / 2;
        for (int i = 0; i < pairs.length; i += 2) {
            double diff = pairs[i] - pairs[i + 1];
            sum += diff * diff;
        }
        return sum / pairCount;
    }

    // ========================================
    // Prompts — 兩種 criteria 順序對抗 positional bias
    // ========================================

    private enum CriteriaOrder { A, B }

    private static final String SYSTEM_PROMPT = """
            你是 AI 生成品質評審。評估一個 AI 系統根據使用者描述生成的業務規則品質。
            只輸出 JSON，不要 markdown 標記。
            依據提供的評估準則客觀評分，不受規則數量多寡影響（verbosity-neutral）。
            """;

    /** 順序 A：faithfulness → completeness → hallucination → consistency */
    private static final String USER_TEMPLATE_A = """
            # 使用者原始需求
            %s

            # AI 生成的規則摘要
            %s

            # 評估任務（依此順序評分）
            1. faithfulness（忠實度 0.0-1.0）：生成的規則是否忠實反映原始需求？欄位名、取值範圍、邏輯是否對應？
            2. completeness（完整度 0.0-1.0）：需求中提到的所有條件和結果，是否都被涵蓋？有無遺漏的維度？
            3. hallucination（幻覺程度 0.0-1.0）：是否生成了需求中未提及的欄位或規則？0.0 = 無幻覺（好），1.0 = 嚴重幻覺（差）
            4. consistency（一致性 0.0-1.0）：規則之間是否邏輯一致？有無矛盾？

            overallScore = (faithfulness + completeness + (1 - hallucination) + consistency) / 4

            用中文寫 comment（一句話評語）與 suggestion（改善建議）。

            JSON 格式：
            {
              "faithfulness": 0.0-1.0,
              "completeness": 0.0-1.0,
              "hallucination": 0.0-1.0,
              "consistency": 0.0-1.0,
              "overallScore": 0.0-1.0,
              "comment": "...",
              "suggestion": "..."
            }
            """;

    /** 順序 B：consistency → hallucination → completeness → faithfulness（positional bias 對抗用） */
    private static final String USER_TEMPLATE_B = """
            # 使用者原始需求
            %s

            # AI 生成的規則摘要
            %s

            # 評估任務（依此順序評分）
            1. consistency（一致性 0.0-1.0）：規則之間是否邏輯一致？有無矛盾？
            2. hallucination（幻覺程度 0.0-1.0）：是否生成了需求中未提及的欄位或規則？0.0 = 無幻覺（好），1.0 = 嚴重幻覺（差）
            3. completeness（完整度 0.0-1.0）：需求中提到的所有條件和結果，是否都被涵蓋？
            4. faithfulness（忠實度 0.0-1.0）：生成的規則是否忠實反映原始需求？

            overallScore = (faithfulness + completeness + (1 - hallucination) + consistency) / 4

            用中文寫 comment 與 suggestion。

            JSON 格式：
            {
              "faithfulness": 0.0-1.0,
              "completeness": 0.0-1.0,
              "hallucination": 0.0-1.0,
              "consistency": 0.0-1.0,
              "overallScore": 0.0-1.0,
              "comment": "...",
              "suggestion": "..."
            }
            """;
}
