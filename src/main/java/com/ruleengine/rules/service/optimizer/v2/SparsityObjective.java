package com.ruleengine.rules.service.optimizer.v2;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.generator.TreeEvaluationComputer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SparsityObjective — GOSDT 風格的 pruning 目標函數。
 *
 * <p>定義：{@code Objective(T) = CoverageLoss(T) + λ·Leaves(T) + μ·Depth(T)}
 *
 * <p>使用場景：每個 v2 pass 後比較 before/after 的 objective：
 * <ul>
 *   <li>降低 → 接受變動</li>
 *   <li>持平 → 接受（以原順序為準的無差異改寫）</li>
 *   <li>升高 → 拒絕（rollback 該 pass）</li>
 *   <li>coverage 低於 {@code coverageFloor} → 無條件拒絕（不看 λ/μ）</li>
 * </ul>
 *
 * <p>此類別**不**修改 envelope，只負責計分與比較。
 */
@Component
@Slf4j
public class SparsityObjective {

    private final TreeEvaluationComputer evaluationComputer;
    private final TreeQualityMetrics qualityMetrics;

    public SparsityObjective(TreeEvaluationComputer evaluationComputer,
                             TreeQualityMetrics qualityMetrics) {
        this.evaluationComputer = evaluationComputer;
        this.qualityMetrics = qualityMetrics;
    }

    // ============================================================
    // 公開 API
    // ============================================================

    /**
     * 計算 envelope 的 sparsity objective 分數。分數越低越好。
     *
     * @param envelope 已正規化的 tree envelope
     * @param config   參數（若 null 用 {@link OptimizeConfigV2#defaults()}）
     * @return objective 分數（非負）
     */
    public double evaluate(RuleEnvelope envelope, OptimizeConfigV2 config) {
        if (envelope == null || envelope.getRule() == null) return Double.POSITIVE_INFINITY;
        OptimizeConfigV2 cfg = config == null ? OptimizeConfigV2.defaults() : config.withDefaults();

        TreeQualityMetrics.Metrics m = qualityMetrics.compute(envelope);
        double coverage = currentCoverage(envelope);
        double coverageLoss = 1.0 - coverage;

        double score = coverageLoss
                + cfg.getLeafWeight() * m.getLeafCount()
                + cfg.getDepthWeight() * m.getMaxDepth();
        return round4(score);
    }

    /**
     * 比較兩個 envelope 的 objective。
     *
     * @return {@link Decision#ACCEPT} 若 after ≤ before 且 coverage 未跌破地板；
     *         {@link Decision#REJECT_COVERAGE} 若 coverage 跌破；
     *         {@link Decision#REJECT_OBJECTIVE} 若 after > before
     */
    public Decision compare(RuleEnvelope before, RuleEnvelope after, OptimizeConfigV2 config) {
        OptimizeConfigV2 cfg = config == null ? OptimizeConfigV2.defaults() : config.withDefaults();

        double coverageAfter = currentCoverage(after);
        if (coverageAfter < cfg.getCoverageFloor()) {
            log.debug("SparsityObjective: 拒絕，coverage {} < floor {}",
                    coverageAfter, cfg.getCoverageFloor());
            return Decision.REJECT_COVERAGE;
        }
        double b = evaluate(before, cfg);
        double a = evaluate(after, cfg);
        if (a <= b + EPS) return Decision.ACCEPT;
        log.debug("SparsityObjective: 拒絕，objective {} > {} (delta {})", a, b, a - b);
        return Decision.REJECT_OBJECTIVE;
    }

    /**
     * 快速檢查：若只有 metrics（不重跑 evaluation）時使用。
     * 僅用於 pass 內部快速比較，不替代正式 {@link #compare}。
     */
    public double quickScore(TreeQualityMetrics.Metrics m, double coverage, OptimizeConfigV2 config) {
        OptimizeConfigV2 cfg = config == null ? OptimizeConfigV2.defaults() : config.withDefaults();
        double coverageLoss = 1.0 - coverage;
        return round4(coverageLoss
                + cfg.getLeafWeight() * m.getLeafCount()
                + cfg.getDepthWeight() * m.getMaxDepth());
    }

    // ============================================================
    // 私有輔助
    // ============================================================

    /**
     * 重新計算 envelope 的 coverage。
     * 複用既有 TreeEvaluationComputer（非破壞性 — 只讀 coverage 後不 persist）。
     */
    double currentCoverage(RuleEnvelope envelope) {
        if (envelope == null || envelope.getRule() == null
                || envelope.getRule().getRoot() == null) {
            return 0.0;
        }
        try {
            // TreeEvaluationComputer 會寫入 envelope.evaluation；先保留原值再還原
            RuleEnvelope.Evaluation original = envelope.getEvaluation();
            evaluationComputer.computeEvaluation(envelope);
            RuleEnvelope.Evaluation fresh = envelope.getEvaluation();
            double c = fresh == null || fresh.getCoverageRate() == null
                    ? 0.0 : fresh.getCoverageRate();
            // 還原舊值（不改動呼叫者的 envelope 語意）
            envelope.setEvaluation(original);
            return c;
        } catch (Exception e) {
            log.warn("SparsityObjective: coverage 計算失敗，fallback=0.0 — {}", e.getMessage());
            return 0.0;
        }
    }

    private static final double EPS = 1e-9;

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ============================================================
    // Decision enum
    // ============================================================

    public enum Decision {
        ACCEPT,
        REJECT_OBJECTIVE,
        REJECT_COVERAGE
    }
}
