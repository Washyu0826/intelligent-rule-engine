package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DecisionTree Evaluation 計算器（Phase 3 + N-ary 支援）。
 *
 * 計算 DecisionTree 特有的品質指標：
 *   - totalScenarios：葉節點數量（= 決策路徑數）
 *   - treeDepth：樹的最大深度
 *   - completeness：所有分支都有葉節點 → COMPLETE
 *   - coverageRate：基於路徑完整性（每個分支節點的所有分支都存在 → 1.0）
 *   - conflictDetection：DecisionTree 天生互斥，固定 NO_CONFLICT
 *   - recommendedStrategy：固定 FIRST
 *
 * 支援 N-ary branches 格式和向後相容的 trueBranch/falseBranch 格式。
 */
@Component
@Slf4j
public class TreeEvaluationComputer {

    /**
     * 計算 DecisionTree 的 Evaluation 指標（就地修改 envelope）。
     */
    public void computeEvaluation(RuleEnvelope envelope) {
        Rule rule = envelope.getRule();
        if (rule == null || rule.getRoot() == null) {
            envelope.setEvaluation(emptyEvaluation());
            return;
        }

        TreeNode root = rule.getRoot();
        TreeStats stats = new TreeStats();
        collectStats(root, 1, stats);

        // 覆蓋率：完整二元樹 = 1.0，有缺失分支則按比例扣減
        double coverageRate = stats.totalBranches > 0
                ? (double) stats.completeBranches / stats.totalBranches
                : (stats.leafCount > 0 ? 1.0 : 0.0);

        String completeness = (coverageRate >= 1.0 && stats.leafCount > 0) ? "COMPLETE" : "INCOMPLETE";

        Evaluation eval = Evaluation.builder()
                .completeness(completeness)
                .totalScenarios(stats.leafCount)
                .coverageRate(Math.round(coverageRate * 10000.0) / 10000.0)
                .conflictDetection("NO_CONFLICT")
                .recommendedStrategy("FIRST")
                .build();

        envelope.setEvaluation(eval);

        log.info("TreeEvaluation: depth={}, leafCount={}, branchNodes={}, coverage={}, completeness={}",
                stats.maxDepth, stats.leafCount, stats.branchNodeCount, coverageRate, completeness);
    }

    /**
     * 取得樹的統計資訊（供外部使用）。
     */
    public TreeStats getStats(TreeNode root) {
        TreeStats stats = new TreeStats();
        if (root != null) {
            collectStats(root, 1, stats);
        }
        return stats;
    }

    private void collectStats(TreeNode node, int depth, TreeStats stats) {
        if (node == null) return;

        stats.maxDepth = Math.max(stats.maxDepth, depth);

        boolean hasBranches = node.getBranches() != null && !node.getBranches().isEmpty();
        boolean hasLegacyBranches = node.getTrueBranch() != null || node.getFalseBranch() != null;
        boolean hasCondition = node.getCondition() != null;
        boolean hasResults = node.getResults() != null && !node.getResults().isEmpty();

        if (hasBranches) {
            // N-ary 分支節點
            stats.branchNodeCount++;
            int branchCount = node.getBranches().size();
            stats.totalBranches += branchCount;
            for (Branch branch : node.getBranches()) {
                if (branch.getChild() != null) {
                    stats.completeBranches++;
                    collectStats(branch.getChild(), depth + 1, stats);
                }
            }
        } else if (hasCondition || hasLegacyBranches) {
            // 向後相容：二元分支節點
            stats.branchNodeCount++;
            stats.totalBranches += 2;
            if (node.getTrueBranch() != null) {
                stats.completeBranches++;
                collectStats(node.getTrueBranch(), depth + 1, stats);
            }
            if (node.getFalseBranch() != null) {
                stats.completeBranches++;
                collectStats(node.getFalseBranch(), depth + 1, stats);
            }
        } else if (hasResults) {
            // 葉節點
            stats.leafCount++;
        }
    }

    private Evaluation emptyEvaluation() {
        return Evaluation.builder()
                .completeness("INCOMPLETE")
                .totalScenarios(0)
                .coverageRate(0.0)
                .conflictDetection("NO_CONFLICT")
                .recommendedStrategy("FIRST")
                .build();
    }

    // ================================================================
    // 統計資料結構
    // ================================================================

    public static class TreeStats {
        public int maxDepth = 0;
        public int leafCount = 0;
        public int branchNodeCount = 0;
        public int totalBranches = 0;     // 應有的分支總數
        public int completeBranches = 0;  // 實際存在的分支數

        public int totalNodeCount() {
            return branchNodeCount + leafCount;
        }
    }
}
