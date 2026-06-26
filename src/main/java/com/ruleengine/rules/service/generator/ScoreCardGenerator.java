package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.OfflineFallbackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ScoreCard 生成器。
 *
 * 評分卡結構：
 * - scoringDimensions: 每個輸入欄位獨立計分（condition → score）
 * - scoreBands: 總分區間 → 輸出結果
 *
 * 生成模式：
 * - Mode A (MCP): 外部 AI 傳入完整 JSON
 * - Mode B (LLM): 自然語言 → LLM 生成
 * - Fallback: 離線場景匹配
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScoreCardGenerator implements RuleGenerator {

    private final ObjectMapper objectMapper;
    private final EnvelopeNormalizer envelopeNormalizer;
    private final EvaluationComputer evaluationComputer;
    private final OfflineFallbackService offlineFallbackService;

    @Value("${rules.schema-version:1.0.0}")
    private String schemaVersion;
    @Value("${rules.prompt-version:p3.0.0}")
    private String promptVersion;

    private LlmProvider llmProvider;

    @Autowired(required = false)
    public void setLlmProvider(com.ruleengine.rules.service.llm.LlmProviderRegistry registry) {
        this.llmProvider = registry.getDefault();
    }

    @Override
    public RuleType supportedType() {
        return RuleType.SCORE_CARD;
    }

    @Override
    public JsonNode generate(String description, List<String> allowedFields) {
        return generate(description, allowedFields, "normal");
    }

    @Override
    public JsonNode generate(String description, List<String> allowedFields, String mode) {
        log.info("ScoreCard generate START | mode={}", mode);

        try {
            RuleEnvelope envelope = resolveEnvelope(description, mode);

            // 正規化
            normalizeScoreCard(envelope);

            // 計算評估指標
            computeScoreCardEvaluation(envelope);

            log.info("ScoreCard generate END | dimensions={} | bands={}",
                    envelope.getRule() != null && envelope.getRule().getScoringDimensions() != null
                            ? envelope.getRule().getScoringDimensions().size() : 0,
                    envelope.getRule() != null && envelope.getRule().getScoreBands() != null
                            ? envelope.getRule().getScoreBands().size() : 0);

            return objectMapper.valueToTree(envelope);
        } catch (Exception e) {
            log.error("ScoreCard generate ERROR: {}", e.getMessage(), e);
            throw new com.ruleengine.rules.exception.RuleGenerationException("ScoreCard 生成失敗: " + e.getMessage(), e);
        }
    }

    private RuleEnvelope resolveEnvelope(String description, String mode) {
        // 嘗試解析為 JSON
        RuleEnvelope fromJson = tryParseJson(description);
        if (fromJson != null) return fromJson;

        // LLM 生成
        if (llmProvider != null && llmProvider.isAvailable() && !"fast".equals(mode)) {
            try {
                String json = llmProvider.generateRuleJson(description, mode, "ScoreCard");
                RuleEnvelope envelope = objectMapper.readValue(json, RuleEnvelope.class);
                if (envelope != null && envelope.getRule() != null) {
                    return envelope;
                }
            } catch (Exception e) {
                log.warn("ScoreCard LLM 生成失敗，嘗試離線備援: {}", e.getMessage());
            }
        }

        // 離線備援
        String offlineJson = offlineFallbackService.tryOffline(description);
        if (offlineJson != null) {
            try {
                return objectMapper.readValue(offlineJson, RuleEnvelope.class);
            } catch (Exception e) {
                log.warn("離線場景解析失敗: {}", e.getMessage());
            }
        }

        // 最終備援：空白模板
        return buildStubEnvelope(description);
    }

    private RuleEnvelope tryParseJson(String input) {
        if (input == null || !input.trim().startsWith("{")) return null;
        try {
            RuleEnvelope envelope = objectMapper.readValue(input, RuleEnvelope.class);
            if (envelope.getRule() != null && envelope.getRule().getScoringDimensions() != null) {
                return envelope;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void normalizeScoreCard(RuleEnvelope envelope) {
        if (envelope.getSchemaVersion() == null) envelope.setSchemaVersion(schemaVersion);
        if (envelope.getPromptVersion() == null) envelope.setPromptVersion(promptVersion);
        envelope.setRuleType("ScoreCard");

        Rule rule = envelope.getRule();
        if (rule == null) return;

        // 確保 list 可變
        if (rule.getInputs() != null) rule.setInputs(new ArrayList<>(rule.getInputs()));
        if (rule.getOutputs() != null) rule.setOutputs(new ArrayList<>(rule.getOutputs()));
        if (rule.getScoringDimensions() != null) {
            rule.setScoringDimensions(new ArrayList<>(rule.getScoringDimensions()));
        }
        if (rule.getScoreBands() != null) {
            rule.setScoreBands(new ArrayList<>(rule.getScoreBands()));
        }

        // 自動補 scoring rule IDs
        if (rule.getScoringDimensions() != null) {
            AtomicInteger counter = new AtomicInteger(1);
            for (ScoringDimension dim : rule.getScoringDimensions()) {
                if (dim.getWeight() == null) dim.setWeight(1.0);
                if (dim.getScoringRules() != null) {
                    for (ScoringRule sr : dim.getScoringRules()) {
                        if (sr.getRuleId() == null || sr.getRuleId().isBlank()) {
                            sr.setRuleId(String.format("S%02d", counter.getAndIncrement()));
                        }
                    }
                }
            }
        }

        // 自動補 band IDs
        if (rule.getScoreBands() != null) {
            for (int i = 0; i < rule.getScoreBands().size(); i++) {
                ScoreBand band = rule.getScoreBands().get(i);
                if (band.getBandId() == null || band.getBandId().isBlank()) {
                    band.setBandId(String.format("B%02d", i + 1));
                }
            }
        }
    }

    private void computeScoreCardEvaluation(RuleEnvelope envelope) {
        Rule rule = envelope.getRule();
        if (rule == null) {
            envelope.setEvaluation(Evaluation.builder()
                    .completeness("INCOMPLETE").totalScenarios(0)
                    .coverageRate(0.0).conflictDetection("NO_CONFLICT")
                    .recommendedStrategy("SCORE").build());
            return;
        }

        int totalScoringRules = 0;
        int dimensionCount = 0;
        if (rule.getScoringDimensions() != null) {
            dimensionCount = rule.getScoringDimensions().size();
            for (ScoringDimension dim : rule.getScoringDimensions()) {
                if (dim.getScoringRules() != null) {
                    totalScoringRules += dim.getScoringRules().size();
                }
            }
        }

        int bandCount = rule.getScoreBands() != null ? rule.getScoreBands().size() : 0;

        // 覆蓋率：檢查分數區間是否連續
        double coverageRate = computeBandCoverage(rule.getScoreBands(), rule.getScoringDimensions());

        String completeness = (coverageRate >= 1.0 && dimensionCount > 0 && bandCount > 0) ? "COMPLETE" : "INCOMPLETE";

        envelope.setEvaluation(Evaluation.builder()
                .completeness(completeness)
                .totalScenarios(totalScoringRules)
                .coverageRate(Math.round(coverageRate * 10000.0) / 10000.0)
                .conflictDetection("NO_CONFLICT")
                .recommendedStrategy("SCORE")
                .build());
    }

    private double computeBandCoverage(List<ScoreBand> bands, List<ScoringDimension> dimensions) {
        if (bands == null || bands.isEmpty() || dimensions == null || dimensions.isEmpty()) return 0.0;

        // 計算理論最大/最小分數
        int theoreticalMin = 0;
        int theoreticalMax = 0;
        for (ScoringDimension dim : dimensions) {
            if (dim.getScoringRules() == null) continue;
            int dimMin = Integer.MAX_VALUE, dimMax = Integer.MIN_VALUE;
            for (ScoringRule sr : dim.getScoringRules()) {
                if (sr.getScore() != null) {
                    dimMin = Math.min(dimMin, sr.getScore());
                    dimMax = Math.max(dimMax, sr.getScore());
                }
            }
            if (dimMin != Integer.MAX_VALUE) {
                double w = dim.getWeight() != null ? dim.getWeight() : 1.0;
                theoreticalMin += (int)(dimMin * w);
                theoreticalMax += (int)(dimMax * w);
            }
        }

        if (theoreticalMax <= theoreticalMin) return 1.0;

        // 檢查 bands 是否覆蓋 [theoreticalMin, theoreticalMax]
        int totalRange = theoreticalMax - theoreticalMin + 1;
        Set<Integer> covered = new HashSet<>();
        for (ScoreBand band : bands) {
            int lo = band.getMinScore() != null ? band.getMinScore() : theoreticalMin;
            int hi = band.getMaxScore() != null ? band.getMaxScore() : theoreticalMax;
            for (int i = lo; i <= hi && i <= theoreticalMax; i++) {
                covered.add(i);
            }
        }

        return Math.min(1.0, (double) covered.size() / totalRange);
    }

    private RuleEnvelope buildStubEnvelope(String description) {
        return RuleEnvelope.builder()
                .ruleType("ScoreCard")
                .reason("ScoreCard 空白模板（描述：" + (description != null ? description.substring(0, Math.min(50, description.length())) : "") + "）")
                .schemaVersion(schemaVersion)
                .promptVersion(promptVersion)
                .rule(Rule.builder()
                        .inputs(new ArrayList<>())
                        .outputs(new ArrayList<>())
                        .scoringDimensions(new ArrayList<>())
                        .scoreBands(new ArrayList<>())
                        .build())
                .build();
    }
}
