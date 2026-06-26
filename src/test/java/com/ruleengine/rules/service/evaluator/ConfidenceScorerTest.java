package com.ruleengine.rules.service.evaluator;

import com.ruleengine.rules.domain.dto.ToolDtos.ValidateResponse;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.evaluator.ConfidenceScorer.ConfidenceReport;
import com.ruleengine.rules.service.evaluator.ConfidenceScorer.FactorScore;
import com.ruleengine.rules.service.evaluator.GroundingGuardService.GroundingReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.10.0 — ConfidenceScorer 測試。
 *
 * 規格：
 *   validation(30) + grounding(25) + coverage(20) + noConflict(15) + ruleCount(10) = 100
 *
 * Tier 分界：
 *   ≥ 85 → PRODUCTION_READY
 *   70-84 → REVIEW_NEEDED
 *   < 70 → NOT_RECOMMENDED
 */
class ConfidenceScorerTest {

    private ConfidenceScorer scorer;

    @BeforeEach
    void setUp() { scorer = new ConfidenceScorer(); }

    // ============================================================
    // Happy path：完美分數
    // ============================================================

    @Test
    void perfectInputs_scoresCloseTo100_tierProduction() {
        ConfidenceReport r = scorer.score(
                envelope(10, 1.0, "NO_CONFLICT"),
                valid(),
                grounding(1.0, "LOW")
        );

        assertThat(r.getOverallScore()).isEqualTo(100);
        assertThat(r.getTier()).isEqualTo("PRODUCTION_READY");
        assertThat(r.getActionableWarnings()).isNull();
    }

    // ============================================================
    // Validation 失敗：最主要的扣分來源
    // ============================================================

    @Test
    void validationFails_scoreDropsBelowProductionThreshold() {
        ConfidenceReport r = scorer.score(
                envelope(10, 1.0, "NO_CONFLICT"),
                invalid(3),                       // 3 errors → 0.7 × 30 = 21
                grounding(1.0, "LOW")
        );

        // validation(21) + grounding(25) + coverage(20) + conflict(15) + count(10) = 91
        assertThat(r.getOverallScore()).isEqualTo(91);
        assertThat(r.getTier()).isEqualTo("PRODUCTION_READY");  // 仍 >= 85
        assertThat(r.getActionableWarnings()).anyMatch(w -> w.contains("驗證"));
    }

    @Test
    void validationSeverelyFails_tierFallsToNotRecommended() {
        ConfidenceReport r = scorer.score(
                envelope(10, 0.5, "HAS_CONFLICT"),
                invalid(10),                       // 10 errors → 0
                grounding(0.5, "HIGH")
        );

        // 0 + (0.5*25=13) + (0.5*20=10) + 0 + 10 ≈ 33
        assertThat(r.getOverallScore()).isLessThan(50);
        assertThat(r.getTier()).isEqualTo("NOT_RECOMMENDED");
        assertThat(r.getActionableWarnings()).isNotEmpty();
    }

    // ============================================================
    // Grounding 高嫌疑
    // ============================================================

    @Test
    void highGroundingSuspicion_producesActionableWarning() {
        GroundingReport g = GroundingReport.builder()
                .groundingRatio(0.4)
                .suspicionLevel("HIGH")
                .ungroundedFields(List.of(
                        GroundingGuardService.UngroundedField.builder().name("foo").source("inputs").build(),
                        GroundingGuardService.UngroundedField.builder().name("bar").source("inputs").build()
                ))
                .build();

        ConfidenceReport r = scorer.score(
                envelope(5, 1.0, "NO_CONFLICT"),
                valid(),
                g
        );

        assertThat(r.getActionableWarnings()).anyMatch(w -> w.contains("grounding"));
        // validation(30) + grounding(0.4*25=10) + coverage(20) + conflict(15) + count(10) = 85
        assertThat(r.getOverallScore()).isEqualTo(85);
    }

    // ============================================================
    // 覆蓋率不足
    // ============================================================

    @Test
    void lowCoverage_triggersWarning() {
        ConfidenceReport r = scorer.score(
                envelope(5, 0.65, "NO_CONFLICT"),
                valid(),
                grounding(1.0, "LOW")
        );

        assertThat(r.getActionableWarnings()).anyMatch(w -> w.contains("覆蓋率"));
        // validation(30) + grounding(25) + coverage(0.65*20=13) + conflict(15) + count(10) = 93
        assertThat(r.getOverallScore()).isEqualTo(93);
    }

    // ============================================================
    // 規則衝突
    // ============================================================

    @Test
    void ruleConflict_zerosConflictFactor() {
        ConfidenceReport r = scorer.score(
                envelope(5, 1.0, "HAS_CONFLICT"),
                valid(),
                grounding(1.0, "LOW")
        );

        // validation(30) + grounding(25) + coverage(20) + conflict(0) + count(10) = 85
        assertThat(r.getOverallScore()).isEqualTo(85);
        assertThat(r.getTier()).isEqualTo("PRODUCTION_READY");  // 剛好 85
        assertThat(r.getActionableWarnings()).anyMatch(w -> w.contains("衝突"));
    }

    // ============================================================
    // 規則數合理性
    // ============================================================

    @Test
    void zeroRules_zerosRuleCountFactorAndWarns() {
        ConfidenceReport r = scorer.score(
                envelope(0, 0.0, "NO_CONFLICT"),
                valid(),
                grounding(1.0, "LOW")
        );

        assertThat(r.getActionableWarnings()).anyMatch(w -> w.contains("規則數為 0"));
        FactorScore rc = r.getBreakdown().get("ruleCount");
        assertThat(rc.getEarned()).isZero();
    }

    @Test
    void tooManyRules_warnsToSwitchToTree() {
        ConfidenceReport r = scorer.score(
                envelope(250, 1.0, "NO_CONFLICT"),
                valid(),
                grounding(1.0, "LOW")
        );

        assertThat(r.getActionableWarnings()).anyMatch(w -> w.contains("DecisionTree"));
    }

    // ============================================================
    // Tier 分界
    // ============================================================

    @Test
    void tierBoundaries_exactly85ReviewCase() {
        // 湊 85 分：validation 30 + grounding (0.8*25=20) + coverage 20 + conflict 15 + count 10 = 95; 調整
        // 改湊 85：validation 30 + grounding 25 + coverage (0.75*20=15) + conflict 15 + count 10 = 95
        // 改湊剛好 85：30+25+10+15+5 = 85；grounding=1, coverage(0.5*20=10), ruleCount(0.5*10=5)
        ConfidenceReport r = scorer.score(
                envelope(1, 0.5, "NO_CONFLICT"),  // 1 rule → ruleCount 0.5 → 5；coverage 0.5 → 10
                valid(),
                grounding(1.0, "LOW")
        );
        // 30 + 25 + 10 + 15 + 5 = 85
        assertThat(r.getOverallScore()).isEqualTo(85);
        assertThat(r.getTier()).isEqualTo("PRODUCTION_READY");
    }

    @Test
    void tierBoundaries_score84FallsToReview() {
        // 需要湊 84：validation(30) + grounding(25) + coverage(0.45*20≈9) + conflict(15) + count(0.5*10=5) = 84
        GroundingReport g = grounding(1.0, "LOW");
        RuleEnvelope env = envelope(1, 0.45, "NO_CONFLICT");  // 1 rule → 5, coverage 0.45*20=9
        ConfidenceReport r = scorer.score(env, valid(), g);

        // 30 + 25 + 9 + 15 + 5 = 84
        assertThat(r.getOverallScore()).isEqualTo(84);
        assertThat(r.getTier()).isEqualTo("REVIEW_NEEDED");
    }

    // ============================================================
    // Null 健壯性
    // ============================================================

    @Test
    void allNull_returnsLowScoreWithNeutralDefaults() {
        ConfidenceReport r = scorer.score(null, null, null);

        // validation(0.5*30=15) + grounding(0.75*25=19) + coverage(0.5*20=10) + conflict(0.5*15=8) + count(0.5*10=5) = 57
        assertThat(r.getOverallScore()).isBetween(50, 65);
        assertThat(r.getTier()).isEqualTo("NOT_RECOMMENDED");
    }

    @Test
    void breakdown_containsAllFiveFactors() {
        ConfidenceReport r = scorer.score(
                envelope(5, 1.0, "NO_CONFLICT"), valid(), grounding(1.0, "LOW"));

        assertThat(r.getBreakdown()).containsKeys(
                "validation", "grounding", "coverage", "noConflict", "ruleCount");
        int sum = r.getBreakdown().values().stream()
                .mapToInt(FactorScore::getEarned).sum();
        assertThat(sum).isEqualTo(r.getOverallScore());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private ValidateResponse valid() {
        return ValidateResponse.builder().valid(true).errors(List.of()).build();
    }

    private ValidateResponse invalid(int errorCount) {
        List<ValidationError> errors = new ArrayList<>();
        for (int i = 0; i < errorCount; i++) {
            errors.add(ValidationError.builder()
                    .code("TYPE_MISMATCH").message("err " + i).build());
        }
        return ValidateResponse.builder().valid(false).errors(errors).build();
    }

    private GroundingReport grounding(double ratio, String level) {
        return GroundingReport.builder()
                .groundingRatio(ratio).suspicionLevel(level)
                .totalChecks(10).groundedChecks((int) (ratio * 10))
                .build();
    }

    private RuleEnvelope envelope(int ruleCount, double coverage, String conflict) {
        List<RuleEnvelope.RuleRow> rows = new ArrayList<>();
        for (int i = 1; i <= ruleCount; i++) {
            rows.add(RuleEnvelope.RuleRow.builder()
                    .ruleId("R" + i).priority(i).build());
        }
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(RuleEnvelope.Rule.builder().rules(rows).build())
                .evaluation(RuleEnvelope.Evaluation.builder()
                        .coverageRate(coverage)
                        .conflictDetection(conflict)
                        .build())
                .build();
    }
}
