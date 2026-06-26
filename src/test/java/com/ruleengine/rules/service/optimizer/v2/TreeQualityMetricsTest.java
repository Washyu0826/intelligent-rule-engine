package com.ruleengine.rules.service.optimizer.v2;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeQualityMetricsTest {

    private TreeQualityMetrics metrics;

    @BeforeEach
    void setUp() { metrics = new TreeQualityMetrics(new TreeCanonicalizer()); }

    @Test
    void empty_envelopeReturnsEmptyMetrics() {
        TreeQualityMetrics.Metrics m = metrics.compute((RuleEnvelope) null);
        assertThat(m.getLeafCount()).isZero();
        assertThat(m.getMaxDepth()).isZero();
        assertThat(m.getBranchNodeCount()).isZero();
        assertThat(m.getBalanceIndex()).isEqualTo(1.0);
    }

    @Test
    void singleLeaf_count1Depth0() {
        TreeNode leaf = leaf("d", "x");
        TreeQualityMetrics.Metrics m = metrics.compute(leaf);
        assertThat(m.getLeafCount()).isEqualTo(1);
        assertThat(m.getMaxDepth()).isZero();
        assertThat(m.getBranchNodeCount()).isZero();
        assertThat(m.totalNodeCount()).isEqualTo(1);
    }

    @Test
    void balancedBinaryTree_balanceIndex1() {
        TreeNode tree = branch("age",
                br("T", leaf("d", "a")),
                br("F", leaf("d", "b")));
        TreeQualityMetrics.Metrics m = metrics.compute(tree);
        assertThat(m.getLeafCount()).isEqualTo(2);
        assertThat(m.getMaxDepth()).isEqualTo(1);
        assertThat(m.getBalanceIndex()).isEqualTo(1.0);
    }

    @Test
    void unbalancedTree_balanceIndexLessThan1() {
        // 一邊深、一邊淺的不平衡樹
        TreeNode deep = branch("a",
                br("T", branch("b",
                        br("T", leaf("d", "x1")),
                        br("F", leaf("d", "x2")))),
                br("F", leaf("d", "y")));
        TreeQualityMetrics.Metrics m = metrics.compute(deep);
        assertThat(m.getMaxDepth()).isEqualTo(2);
        assertThat(m.getBalanceIndex()).isLessThan(1.0);
        assertThat(m.getBalanceIndex()).isCloseTo(0.5, org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    void duplicateSubtrees_duplicationRatioGreaterThan0() {
        // gender M → (age>60 → 拒/承), gender F → (age>60 → 拒/承) — 兩子樹相同
        TreeNode dup = branch("gender",
                br("M", branch("age",
                        br("T", leaf("d", "拒")),
                        br("F", leaf("d", "承")))),
                br("F", branch("age",
                        br("T", leaf("d", "拒")),
                        br("F", leaf("d", "承")))));
        TreeQualityMetrics.Metrics m = metrics.compute(dup);
        assertThat(m.getBranchNodeCount()).isEqualTo(3);   // root + 2 sub-roots
        assertThat(m.getUniqueSubtrees()).isLessThan(m.getBranchNodeCount());
        assertThat(m.getDuplicationRatio()).isGreaterThan(0.0);
    }

    @Test
    void avgPathLength_calculated() {
        TreeNode tree = branch("a",
                br("T", branch("b",
                        br("T", leaf("d", "x")),
                        br("F", leaf("d", "y")))),
                br("F", leaf("d", "z")));
        TreeQualityMetrics.Metrics m = metrics.compute(tree);
        // 葉深度: 2, 2, 1 → 平均 5/3 ≈ 1.67
        assertThat(m.getAvgPathLength()).isCloseTo(1.67,
                org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    void totalNodeCount_sumsLeafAndBranch() {
        TreeNode tree = branch("a",
                br("T", leaf("d", "x")),
                br("F", leaf("d", "y")));
        TreeQualityMetrics.Metrics m = metrics.compute(tree);
        assertThat(m.totalNodeCount()).isEqualTo(3);  // 1 branch + 2 leaves
    }

    @Test
    void wrappedInEnvelope_computesCorrectly() {
        RuleEnvelope env = RuleEnvelope.builder()
                .ruleType("DecisionTree")
                .rule(RuleEnvelope.Rule.builder()
                        .root(branch("a",
                                br("T", leaf("d", "x")),
                                br("F", leaf("d", "y"))))
                        .build())
                .build();
        TreeQualityMetrics.Metrics m = metrics.compute(env);
        assertThat(m.getLeafCount()).isEqualTo(2);
        assertThat(m.getMaxDepth()).isEqualTo(1);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private TreeNode leaf(String field, Object value) {
        return TreeNode.builder().nodeId("L")
                .results(List.of(Result.builder().field(field).value(value).build()))
                .build();
    }

    private TreeNode branch(String field, Branch... branches) {
        return TreeNode.builder().nodeId("B")
                .condition(Condition.builder().field(field).operator("anything").build())
                .branches(List.of(branches))
                .build();
    }

    private Branch br(String label, TreeNode child) {
        return Branch.builder().label(label)
                .condition(Condition.builder().field("x").operator("equals").value(label).build())
                .child(child)
                .build();
    }
}
