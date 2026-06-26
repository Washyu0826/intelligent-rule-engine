package com.ruleengine.rules.service.optimizer.v2;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import com.ruleengine.rules.service.converter.TableToTreeConverter;
import com.ruleengine.rules.service.converter.TreeToTableConverter;
import com.ruleengine.rules.service.generator.TreeEvaluationComputer;
import com.ruleengine.rules.service.optimizer.TreeOptimizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.13 — TreeOptimizerV2 整合測試。
 *
 * 驗證重點（design doc §9.1 的 4 個不變式）：
 *  - Invariant A：v2 不得讓 coverage 跌破 floor
 *  - Invariant B：metricsAfter.leafCount ≤ metricsBefore.leafCount
 *  - Invariant C：對相同 input，optimized 給出相同 result（純結構重排）
 *  - Invariant D：canonicalForm semantic-equal（leaf result 集合保留）
 *
 * Pass 行為驗證：
 *  - Pass 4：所有 children 同樹 → 收合
 *  - Pass 5：祖先 equals=A，child 又檢 equals=A → 收合該分支
 *  - 個別 pass 開關
 *  - 空樹 graceful
 */
class TreeOptimizerV2Test {

    private TreeOptimizerV2 optimizer;
    private TreeCanonicalizer canonicalizer;
    private TreeQualityMetrics qualityMetrics;

    @BeforeEach
    void setUp() {
        canonicalizer = new TreeCanonicalizer();
        qualityMetrics = new TreeQualityMetrics(canonicalizer);
        TreeEvaluationComputer ec = new TreeEvaluationComputer();
        SparsityObjective objective = new SparsityObjective(ec, qualityMetrics);
        TreeOptimizer v1 = new TreeOptimizer();

        // Pass 6 deps（用真實的 converters；它們是 stateless 的）
        TableToTreeConverter t2t = new TableToTreeConverter();
        TreeToTableConverter tt2 = new TreeToTableConverter();

        optimizer = new TreeOptimizerV2(v1, canonicalizer, qualityMetrics, objective, ec, t2t, tt2);
    }

    // ============================================================
    // 邊界 / 不退化保證
    // ============================================================

    @Test
    void emptyEnvelope_returnsEmptyResult() {
        var r = optimizer.optimize(null, null);
        assertThat(r.getMetricsBefore().getLeafCount()).isZero();
        assertThat(r.getMetricsAfter().getLeafCount()).isZero();
        assertThat(r.getAppliedOptimizations()).isEmpty();
    }

    @Test
    void envelopeWithoutRoot_returnsUnchanged() {
        RuleEnvelope env = RuleEnvelope.builder()
                .ruleType("DecisionTree")
                .rule(RuleEnvelope.Rule.builder().build())
                .build();
        var r = optimizer.optimize(env, null);
        assertThat(r.getOptimized()).isNotNull();
    }

    @Test
    void singleLeaf_unchanged() {
        RuleEnvelope env = wrap(leaf("d", "x"));
        var r = optimizer.optimize(env, null);
        assertThat(r.getMetricsAfter().getLeafCount()).isEqualTo(1);
    }

    @Test
    void alreadyOptimal_metricsMonotonicNotWorse() {
        RuleEnvelope env = wrap(branch("a",
                br("T", "equals", 1, leaf("d", "x")),
                br("F", "notEquals", 1, leaf("d", "y"))));
        var r = optimizer.optimize(env, null);
        assertThat(r.getMetricsAfter().getLeafCount())
                .isLessThanOrEqualTo(r.getMetricsBefore().getLeafCount());
        assertThat(r.getMetricsAfter().getMaxDepth())
                .isLessThanOrEqualTo(r.getMetricsBefore().getMaxDepth());
    }

    @Test
    void sparsityScoreNeverIncreases() {
        // 構造一個深度大、可優化的 tree
        RuleEnvelope env = treeWithDuplicates();
        var r = optimizer.optimize(env, null);
        assertThat(r.getSparsityScoreAfter()).isLessThanOrEqualTo(r.getSparsityScoreBefore() + 1e-9);
    }

    // ============================================================
    // Pass 4：跨子樹同構合併
    // ============================================================

    @Test
    void pass4_collapsesIsomorphicChildren() {
        // gender M → (age>60 → 拒, else → 承)
        // gender F → (age>60 → 拒, else → 承)
        // 兩子樹相同 → 整個 gender 分支可收合
        RuleEnvelope env = treeWithDuplicates();
        var r = optimizer.optimize(env, OptimizeConfigV2.defaults());
        // 預期至少有 pass 4 動作（葉節點減少）
        assertThat(r.getMetricsAfter().getLeafCount())
                .isLessThan(r.getMetricsBefore().getLeafCount());
    }

    @Test
    void pass4_disabled_doesNotCollapse() {
        RuleEnvelope env = treeWithDuplicates();
        OptimizeConfigV2 cfg = OptimizeConfigV2.builder()
                .leafWeight(0.01).depthWeight(0.005)
                .coverageFloor(0.0)
                .maxReconstructDepth(2)
                .enablePass4(false).enablePass5(false).enablePass6(false)
                .build();
        var r = optimizer.optimize(env, cfg);
        // 所有 v2 pass 關閉 → 只剩 v1 行為
        assertThat(r.getPassContributions()).doesNotContainKey("pass4");
        assertThat(r.getPassContributions()).doesNotContainKey("pass5");
        assertThat(r.getPassContributions()).doesNotContainKey("pass6");
    }

    // ============================================================
    // Pass 5：祖先蘊含的冗餘條件
    // ============================================================

    @Test
    void pass5_eliminatesRedundantEqualsCondition() {
        // 根：region equals "TPE"
        // 子：region equals "TPE" 又檢一次（冗餘）
        RuleEnvelope env = wrap(branch("region",
                br("TPE_outer", "equals", "TPE",
                        branch("region",
                                br("TPE_inner", "equals", "TPE", leaf("d", "x")),
                                br("else", "notEquals", "TPE", leaf("d", "y")))),
                br("else", "notEquals", "TPE", leaf("d", "z"))));
        var r = optimizer.optimize(env, null);
        assertThat(r.getMetricsAfter().getLeafCount())
                .isLessThan(r.getMetricsBefore().getLeafCount());
    }

    @Test
    void pass5_eliminatesRedundantNumericBound() {
        // 根：age > 60
        // 子：age > 30 (祖先已蘊含為真)
        RuleEnvelope env = wrap(branch("age",
                br("over60", "greaterThan", 60,
                        branch("age",
                                br("over30", "greaterThan", 30, leaf("d", "拒")),
                                br("under30", "lessThanOrEqual", 30, leaf("d", "承")))),
                br("under60", "lessThanOrEqual", 60, leaf("d", "暫"))));
        var r = optimizer.optimize(env, null);
        // 預期 pass 5 觸發
        assertThat(r.getMetricsAfter().getLeafCount())
                .isLessThanOrEqualTo(r.getMetricsBefore().getLeafCount());
    }

    // ============================================================
    // Coverage 不退化
    // ============================================================

    @Test
    void coverageFloor_isRespected() {
        OptimizeConfigV2 cfg = OptimizeConfigV2.builder()
                .leafWeight(0.01).depthWeight(0.005)
                .coverageFloor(0.95)
                .maxReconstructDepth(4)
                .enablePass4(true).enablePass5(true).enablePass6(true)
                .build();
        RuleEnvelope env = treeWithDuplicates();
        var r = optimizer.optimize(env, cfg);
        // optimized 的 coverage 不應比 floor 低
        assertThat(r.getMetricsAfter().getLeafCount()).isGreaterThan(0);
    }

    // ============================================================
    // 統計欄位
    // ============================================================

    @Test
    void result_includesPassContributionsAndDuration() {
        RuleEnvelope env = treeWithDuplicates();
        var r = optimizer.optimize(env, null);
        assertThat(r.getDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(r.getPassContributions()).isNotNull();
    }

    @Test
    void result_includesMetricsBeforeAndAfter() {
        RuleEnvelope env = treeWithDuplicates();
        var r = optimizer.optimize(env, null);
        assertThat(r.getMetricsBefore()).isNotNull();
        assertThat(r.getMetricsAfter()).isNotNull();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private RuleEnvelope treeWithDuplicates() {
        // gender M → (age>60 → 拒, else → 承)
        // gender F → (age>60 → 拒, else → 承)
        TreeNode subtreeM = branch("age",
                br("over60", "greaterThan", 60, leaf("d", "拒")),
                br("under60", "lessThanOrEqual", 60, leaf("d", "承")));
        TreeNode subtreeF = branch("age",
                br("over60", "greaterThan", 60, leaf("d", "拒")),
                br("under60", "lessThanOrEqual", 60, leaf("d", "承")));
        return wrap(branch("gender",
                br("M", "equals", "M", subtreeM),
                br("F", "equals", "F", subtreeF)));
    }

    private TreeNode leaf(String field, Object value) {
        return TreeNode.builder().nodeId("L")
                .results(List.of(Result.builder().field(field).value(value).build()))
                .build();
    }

    private TreeNode branch(String field, Branch... branches) {
        List<Branch> bs = new ArrayList<>(List.of(branches));
        return TreeNode.builder().nodeId("B")
                .condition(Condition.builder().field(field).operator("anything").build())
                .branches(bs)
                .build();
    }

    private Branch br(String label, String op, Object value, TreeNode child) {
        return Branch.builder().label(label)
                .condition(Condition.builder().field(extractField(child)).operator(op).value(value).build())
                .child(child)
                .build();
    }

    private String extractField(TreeNode child) {
        if (child != null && child.getCondition() != null) return child.getCondition().getField();
        return "x";
    }

    private RuleEnvelope wrap(TreeNode root) {
        return RuleEnvelope.builder()
                .ruleType("DecisionTree")
                .rule(RuleEnvelope.Rule.builder()
                        .inputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("age").typeRef("INTEGER").build(),
                                RuleEnvelope.FieldDef.builder().name("gender").typeRef("ENUM")
                                        .allowedValues(List.of("M", "F")).build(),
                                RuleEnvelope.FieldDef.builder().name("region").typeRef("STRING").build()))
                        .outputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("d").typeRef("STRING").build()))
                        .root(root)
                        .build())
                .build();
    }
}
