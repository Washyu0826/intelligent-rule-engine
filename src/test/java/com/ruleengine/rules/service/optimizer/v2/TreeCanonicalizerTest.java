package com.ruleengine.rules.service.optimizer.v2;

import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.13 — TreeCanonicalizer 測試。
 *
 * 重點驗證：
 *  - 葉節點 hash 對 results 順序不敏感
 *  - 分支 hash 對兄弟順序不敏感
 *  - 各種值正規化：boolean string ↔ boolean、數字 string ↔ number、between 排序
 *  - 不同結構 → 不同 hash
 *  - groupByHash 正確分群
 *  - null / 邊界輸入 graceful
 */
class TreeCanonicalizerTest {

    private TreeCanonicalizer c;

    @BeforeEach void setUp() { c = new TreeCanonicalizer(); }

    // ============================================================
    // Leaf hash
    // ============================================================

    @Test
    void leaf_resultsOrderDoesNotAffectHash() {
        TreeNode a = leaf(List.of(
                Result.builder().field("decision").value("承保").build(),
                Result.builder().field("rate").value(1.0).build()));
        TreeNode b = leaf(List.of(
                Result.builder().field("rate").value(1.0).build(),
                Result.builder().field("decision").value("承保").build()));
        assertThat(c.canonicalHash(a)).isEqualTo(c.canonicalHash(b));
    }

    @Test
    void leaf_differentValuesProduceDifferentHash() {
        TreeNode a = leaf(List.of(Result.builder().field("d").value("X").build()));
        TreeNode b = leaf(List.of(Result.builder().field("d").value("Y").build()));
        assertThat(c.canonicalHash(a)).isNotEqualTo(c.canonicalHash(b));
    }

    // ============================================================
    // Value normalization
    // ============================================================

    @Test
    void leaf_booleanAsStringSameAsBoolean() {
        TreeNode a = leaf(List.of(Result.builder().field("flag").value(true).build()));
        TreeNode b = leaf(List.of(Result.builder().field("flag").value("true").build()));
        assertThat(c.canonicalHash(a)).isEqualTo(c.canonicalHash(b));
    }

    @Test
    void leaf_integerAsStringSameAsInteger() {
        TreeNode a = leaf(List.of(Result.builder().field("score").value(60).build()));
        TreeNode b = leaf(List.of(Result.builder().field("score").value("60").build()));
        assertThat(c.canonicalHash(a)).isEqualTo(c.canonicalHash(b));
    }

    @Test
    void condition_betweenOrderIndependent() {
        Condition a = Condition.builder().field("age").operator("between")
                .value(List.of(20, 30)).build();
        Condition b = Condition.builder().field("age").operator("between")
                .value(List.of(30, 20)).build();
        assertThat(c.canonicalCondition(a)).isEqualTo(c.canonicalCondition(b));
    }

    @Test
    void condition_inOrderIndependent() {
        Condition a = Condition.builder().field("region").operator("in")
                .value(List.of("A", "B", "C")).build();
        Condition b = Condition.builder().field("region").operator("in")
                .value(List.of("C", "A", "B")).build();
        assertThat(c.canonicalCondition(a)).isEqualTo(c.canonicalCondition(b));
    }

    // ============================================================
    // Branch order
    // ============================================================

    @Test
    void branchNode_siblingOrderDoesNotAffectHash() {
        TreeNode a = branch("age",
                br("TRUE", "greaterThan", 60, leaf("decision", "拒")),
                br("FALSE", "lessThanOrEqual", 60, leaf("decision", "承")));
        TreeNode b = branch("age",
                br("FALSE", "lessThanOrEqual", 60, leaf("decision", "承")),
                br("TRUE", "greaterThan", 60, leaf("decision", "拒")));
        assertThat(c.canonicalHash(a)).isEqualTo(c.canonicalHash(b));
    }

    @Test
    void branchNode_differentConditionDifferentHash() {
        TreeNode a = branch("age",
                br("TRUE", "greaterThan", 60, leaf("d", "x")),
                br("FALSE", "lessThanOrEqual", 60, leaf("d", "y")));
        TreeNode b = branch("age",
                br("TRUE", "greaterThan", 50, leaf("d", "x")),
                br("FALSE", "lessThanOrEqual", 50, leaf("d", "y")));
        assertThat(c.canonicalHash(a)).isNotEqualTo(c.canonicalHash(b));
    }

    // ============================================================
    // groupByHash
    // ============================================================

    @Test
    void groupByHash_findsDuplicateBranchSubtrees() {
        // 子樹 X：age > 60 → 拒, else → 承
        TreeNode subtreeX1 = branch("age",
                br("TRUE", "greaterThan", 60, leaf("d", "拒")),
                br("FALSE", "lessThanOrEqual", 60, leaf("d", "承")));
        TreeNode subtreeX2 = branch("age",
                br("TRUE", "greaterThan", 60, leaf("d", "拒")),
                br("FALSE", "lessThanOrEqual", 60, leaf("d", "承")));
        // 根分支：gender M → X1, gender F → X2
        TreeNode root = branch("gender",
                br("M", "equals", "M", subtreeX1),
                br("F", "equals", "F", subtreeX2));

        Map<String, List<TreeNode>> groups = c.groupByHash(root);
        // 應有 2 distinct hash：root 自己 + X 的 hash（X 出現 2 次）
        boolean hasDuplicateGroup = groups.values().stream().anyMatch(l -> l.size() == 2);
        assertThat(hasDuplicateGroup).isTrue();
    }

    @Test
    void groupByHash_distinctSubtreesProduceDistinctEntries() {
        TreeNode root = branch("age",
                br("TRUE", "greaterThan", 60, leaf("d", "x")),
                br("FALSE", "lessThanOrEqual", 60, leaf("d", "y")));
        Map<String, List<TreeNode>> groups = c.groupByHash(root);
        assertThat(groups).hasSize(1);  // 只有根 branch（葉不收集）
    }

    // ============================================================
    // Edge cases
    // ============================================================

    @Test
    void canonicalHash_nullNodeReturnsStableValue() {
        String h = c.canonicalHash(null);
        assertThat(h).isNotNull().isNotEmpty();
        assertThat(c.canonicalHash(null)).isEqualTo(h);  // 穩定
    }

    @Test
    void semanticEqual_handlesNull() {
        assertThat(c.semanticEqual(null, null)).isTrue();
        assertThat(c.semanticEqual(leaf("d", "x"), null)).isFalse();
    }

    @Test
    void canonicalHash_isLeaf_recognizesLeafNode() {
        assertThat(c.isLeaf(leaf("d", "x"))).isTrue();
        assertThat(c.isLeaf(branch("age",
                br("T", "greaterThan", 60, leaf("d", "y"))))).isFalse();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private TreeNode leaf(String field, Object value) {
        return leaf(List.of(Result.builder().field(field).value(value).build()));
    }

    private TreeNode leaf(List<Result> results) {
        return TreeNode.builder()
                .nodeId("L")
                .results(results)
                .build();
    }

    private TreeNode branch(String field, Branch... branches) {
        return TreeNode.builder()
                .nodeId("B")
                .condition(Condition.builder().field(field).operator("anything").build())
                .branches(List.of(branches))
                .build();
    }

    private Branch br(String label, String op, Object value, TreeNode child) {
        return Branch.builder()
                .label(label)
                .condition(Condition.builder().field("age").operator(op).value(value).build())
                .child(child)
                .build();
    }
}
