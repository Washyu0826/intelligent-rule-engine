package com.ruleengine.rules.service.optimizer.v2;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * TreeQualityMetrics — 對 DecisionTree 計算結構性品質指標。
 *
 * <p>作為 v2 optimizer 的 before/after 比較基礎，亦用於 sparsity objective。
 *
 * <p>指標定義：
 * <ul>
 *   <li><b>leafCount</b>：葉節點數（越少越稀疏）</li>
 *   <li><b>branchNodeCount</b>：分支節點數</li>
 *   <li><b>maxDepth</b>：從根到任一葉的最大深度（根深度 0）</li>
 *   <li><b>avgPathLength</b>：各葉路徑長度的算術平均</li>
 *   <li><b>balanceIndex</b>：0..1，越接近 1 越平衡。定義為 `minLeafDepth / maxLeafDepth`（無葉時 1.0）</li>
 *   <li><b>uniqueSubtrees</b>：distinct canonical hash 的分支節點數</li>
 *   <li><b>duplicationRatio</b>：1 − (uniqueSubtrees / branchNodeCount)，越高表示重複子樹越多</li>
 *   <li><b>sparsityScore</b>：由 {@link SparsityObjective} 填入（此類別不直接計算）</li>
 * </ul>
 */
@Component
public class TreeQualityMetrics {

    private final TreeCanonicalizer canonicalizer;

    public TreeQualityMetrics(TreeCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    // ============================================================
    // 公開 API
    // ============================================================

    /**
     * 對整個 envelope 計算品質指標。僅處理 DecisionTree 型態。
     */
    public Metrics compute(RuleEnvelope envelope) {
        if (envelope == null || envelope.getRule() == null || envelope.getRule().getRoot() == null) {
            return Metrics.empty();
        }
        return compute(envelope.getRule().getRoot());
    }

    /**
     * 對單一子樹計算品質指標。
     */
    public Metrics compute(TreeNode root) {
        if (root == null) return Metrics.empty();

        Collector c = new Collector();
        traverse(root, 0, c);

        int leaves = c.leafDepths.size();
        int branches = c.branchCount;
        int maxDepth = c.leafDepths.isEmpty() ? 0 :
                Collections.max(c.leafDepths);
        int minDepth = c.leafDepths.isEmpty() ? 0 :
                Collections.min(c.leafDepths);
        double avgPath = leaves == 0 ? 0.0 :
                c.leafDepths.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double balance = maxDepth == 0 ? 1.0 : (double) minDepth / maxDepth;

        // 子樹去重統計
        Map<String, List<TreeNode>> groups = canonicalizer.groupByHash(root);
        int uniqueSubtrees = groups.size();
        double dupRatio = branches == 0 ? 0.0 :
                1.0 - ((double) uniqueSubtrees / branches);

        return Metrics.builder()
                .leafCount(leaves)
                .branchNodeCount(branches)
                .maxDepth(maxDepth)
                .avgPathLength(round2(avgPath))
                .balanceIndex(round2(balance))
                .uniqueSubtrees(uniqueSubtrees)
                .duplicationRatio(round2(dupRatio))
                .build();
    }

    // ============================================================
    // 遞迴收集
    // ============================================================

    private void traverse(TreeNode node, int depth, Collector c) {
        if (node == null) return;
        if (canonicalizer.isLeaf(node)) {
            c.leafDepths.add(depth);
            return;
        }
        c.branchCount++;

        if (node.getBranches() != null) {
            for (Branch b : node.getBranches()) {
                traverse(b.getChild(), depth + 1, c);
            }
        }
        if (node.getTrueBranch() != null) traverse(node.getTrueBranch(), depth + 1, c);
        if (node.getFalseBranch() != null) traverse(node.getFalseBranch(), depth + 1, c);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ============================================================
    // 內部結構
    // ============================================================

    private static class Collector {
        final List<Integer> leafDepths = new ArrayList<>();
        int branchCount = 0;
    }

    // ============================================================
    // 對外 DTO
    // ============================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Metrics {
        private int leafCount;
        private int branchNodeCount;
        private int maxDepth;
        private double avgPathLength;
        /** 0..1，越平衡越高 */
        private double balanceIndex;
        private int uniqueSubtrees;
        /** 0..1，越高表示重複子樹越多（Pass 4 可獲利空間越大） */
        private double duplicationRatio;
        /** 由 SparsityObjective 填入；此類別不計算 */
        private Double sparsityScore;

        public int totalNodeCount() {
            return leafCount + branchNodeCount;
        }

        public static Metrics empty() {
            return Metrics.builder()
                    .leafCount(0).branchNodeCount(0).maxDepth(0)
                    .avgPathLength(0.0).balanceIndex(1.0)
                    .uniqueSubtrees(0).duplicationRatio(0.0)
                    .build();
        }
    }
}
