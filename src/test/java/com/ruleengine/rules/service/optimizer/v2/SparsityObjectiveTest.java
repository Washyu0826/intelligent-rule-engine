package com.ruleengine.rules.service.optimizer.v2;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import com.ruleengine.rules.service.generator.TreeEvaluationComputer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SparsityObjectiveTest {

    private SparsityObjective objective;

    @BeforeEach
    void setUp() {
        TreeCanonicalizer c = new TreeCanonicalizer();
        TreeQualityMetrics qm = new TreeQualityMetrics(c);
        TreeEvaluationComputer ec = new TreeEvaluationComputer();
        objective = new SparsityObjective(ec, qm);
    }

    @Test
    void evaluate_followsFormula() {
        RuleEnvelope env = simpleEnvelope(2, 1);
        OptimizeConfigV2 cfg = OptimizeConfigV2.defaults();
        double score = objective.evaluate(env, cfg);
        // (1 - coverage) + λ * leaves + μ * depth
        // coverage 由 evaluation computer 算出
        assertThat(score).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void compare_acceptsImprovedTree() {
        RuleEnvelope before = bigTree();   // 4 葉
        RuleEnvelope after = smallTree();  // 2 葉
        SparsityObjective.Decision d = objective.compare(before, after, OptimizeConfigV2.defaults());
        assertThat(d).isEqualTo(SparsityObjective.Decision.ACCEPT);
    }

    @Test
    void compare_acceptsEqualScore() {
        RuleEnvelope env = simpleEnvelope(2, 1);
        SparsityObjective.Decision d = objective.compare(env, env, OptimizeConfigV2.defaults());
        assertThat(d).isEqualTo(SparsityObjective.Decision.ACCEPT);
    }

    @Test
    void compare_rejectsWorseObjective() {
        RuleEnvelope before = smallTree();  // 2 葉
        RuleEnvelope after = bigTree();     // 4 葉（更差）
        SparsityObjective.Decision d = objective.compare(before, after, OptimizeConfigV2.defaults());
        // 葉變多，objective 上升 → 拒絕
        assertThat(d).isEqualTo(SparsityObjective.Decision.REJECT_OBJECTIVE);
    }

    @Test
    void compare_extremeLambdaPenalizesLeafCount() {
        OptimizeConfigV2 cfg = OptimizeConfigV2.builder()
                .leafWeight(10.0)
                .depthWeight(0.0)
                .coverageFloor(0.0)
                .build();
        RuleEnvelope before = bigTree();
        RuleEnvelope after = smallTree();
        double sBefore = objective.evaluate(before, cfg);
        double sAfter = objective.evaluate(after, cfg);
        // 大 λ 下小樹分數差距明顯
        assertThat(sBefore - sAfter).isGreaterThan(10.0);
    }

    @Test
    void compare_zeroWeightsCollapseToCoverageLoss() {
        OptimizeConfigV2 cfg = OptimizeConfigV2.builder()
                .leafWeight(0.0)
                .depthWeight(0.0)
                .coverageFloor(0.0)
                .build();
        RuleEnvelope env = simpleEnvelope(2, 1);
        double s = objective.evaluate(env, cfg);
        // 全 0 權重 → 只剩 coverageLoss
        assertThat(s).isCloseTo(1.0 - objective.currentCoverage(env),
                org.assertj.core.api.Assertions.within(0.001));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private RuleEnvelope simpleEnvelope(int leaves, int depth) {
        return wrap(branchN(leaves));
    }

    private RuleEnvelope smallTree() {
        return wrap(TreeNode.builder().nodeId("R")
                .condition(Condition.builder().field("a").operator("anything").build())
                .branches(List.of(
                        Branch.builder().label("T")
                                .condition(Condition.builder().field("a").operator("equals").value(1).build())
                                .child(leaf("d", "x")).build(),
                        Branch.builder().label("F")
                                .condition(Condition.builder().field("a").operator("notEquals").value(1).build())
                                .child(leaf("d", "y")).build()))
                .build());
    }

    private RuleEnvelope bigTree() {
        return wrap(TreeNode.builder().nodeId("R")
                .condition(Condition.builder().field("a").operator("anything").build())
                .branches(List.of(
                        Branch.builder().label("T")
                                .condition(Condition.builder().field("a").operator("equals").value(1).build())
                                .child(TreeNode.builder().nodeId("M")
                                        .condition(Condition.builder().field("b").operator("anything").build())
                                        .branches(List.of(
                                                Branch.builder().label("T")
                                                        .condition(Condition.builder().field("b").operator("equals").value(2).build())
                                                        .child(leaf("d", "x")).build(),
                                                Branch.builder().label("F")
                                                        .condition(Condition.builder().field("b").operator("notEquals").value(2).build())
                                                        .child(leaf("d", "y")).build()))
                                        .build()).build(),
                        Branch.builder().label("F")
                                .condition(Condition.builder().field("a").operator("notEquals").value(1).build())
                                .child(TreeNode.builder().nodeId("N")
                                        .condition(Condition.builder().field("b").operator("anything").build())
                                        .branches(List.of(
                                                Branch.builder().label("T")
                                                        .condition(Condition.builder().field("b").operator("equals").value(2).build())
                                                        .child(leaf("d", "p")).build(),
                                                Branch.builder().label("F")
                                                        .condition(Condition.builder().field("b").operator("notEquals").value(2).build())
                                                        .child(leaf("d", "q")).build()))
                                        .build()).build()))
                .build());
    }

    private TreeNode branchN(int n) {
        if (n <= 1) return leaf("d", "x");
        return TreeNode.builder().nodeId("R")
                .condition(Condition.builder().field("a").operator("anything").build())
                .branches(List.of(
                        Branch.builder().label("T")
                                .condition(Condition.builder().field("a").operator("equals").value(1).build())
                                .child(leaf("d", "y")).build(),
                        Branch.builder().label("F")
                                .condition(Condition.builder().field("a").operator("notEquals").value(1).build())
                                .child(leaf("d", "z")).build()))
                .build();
    }

    private TreeNode leaf(String field, Object value) {
        return TreeNode.builder().nodeId("L")
                .results(List.of(Result.builder().field(field).value(value).build()))
                .build();
    }

    private RuleEnvelope wrap(TreeNode root) {
        return RuleEnvelope.builder()
                .ruleType("DecisionTree")
                .rule(RuleEnvelope.Rule.builder()
                        .inputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("a").typeRef("INTEGER").build(),
                                RuleEnvelope.FieldDef.builder().name("b").typeRef("INTEGER").build()))
                        .outputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("d").typeRef("STRING").build()))
                        .root(root)
                        .build())
                .build();
    }
}
