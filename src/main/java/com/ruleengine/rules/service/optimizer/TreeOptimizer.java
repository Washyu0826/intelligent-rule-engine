package com.ruleengine.rules.service.optimizer;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * DecisionTree 優化器。
 *
 * 三個優化 Pass：
 * 1. mergeIdenticalSiblings — 所有 sibling leaf 結果相同時，合併為單一 leaf
 * 2. removeDeadBranches — 移除條件矛盾的分支（永遠無法到達）
 * 3. collapseSingleChildBranches — 若分支節點只剩一個 child，將 child 提升
 *
 * 每個 Pass 都是遞迴的，從葉節點往根節點方向優化（bottom-up）。
 */
@Component
@Slf4j
public class TreeOptimizer {

    /**
     * 優化 DecisionTree RuleEnvelope。
     * 回傳新的 OptimizeResult，包含優化後的 envelope 和統計資訊。
     */
    public OptimizeResult optimize(RuleEnvelope envelope, boolean aggressive) {
        if (envelope == null || envelope.getRule() == null || envelope.getRule().getRoot() == null) {
            return OptimizeResult.builder()
                    .optimized(envelope)
                    .nodesRemoved(0)
                    .depthReduction(0)
                    .appliedOptimizations(List.of())
                    .build();
        }

        TreeNode original = envelope.getRule().getRoot();
        int originalNodes = countNodes(original);
        int originalDepth = getDepth(original);

        List<String> applied = new ArrayList<>();

        // Deep copy the tree for optimization
        TreeNode root = deepCopy(original);

        // Pass 1: Merge identical siblings
        TreeNode afterMerge = mergeIdenticalSiblings(root);
        int afterMergeNodes = countNodes(afterMerge);
        if (afterMergeNodes < originalNodes) {
            applied.add("合併語意相同的兄弟葉節點（移除 " + (originalNodes - afterMergeNodes) + " 個節點）");
        }
        root = afterMerge;

        // Pass 2: Remove dead branches (only in aggressive mode)
        if (aggressive) {
            TreeNode afterDead = removeDeadBranches(root, new ArrayList<>());
            int afterDeadNodes = countNodes(afterDead);
            if (afterDeadNodes < countNodes(root)) {
                applied.add("移除死碼分支（移除 " + (countNodes(root) - afterDeadNodes) + " 個節點）");
            }
            root = afterDead;
        }

        // Pass 3: Collapse single-child branches
        TreeNode afterCollapse = collapseSingleChildBranches(root);
        int afterCollapseNodes = countNodes(afterCollapse);
        if (afterCollapseNodes < countNodes(root)) {
            applied.add("摺疊單子節點分支（移除 " + (countNodes(root) - afterCollapseNodes) + " 個節點）");
        }
        root = afterCollapse;

        // Re-assign nodeIds
        reassignNodeIds(root, new AtomicInteger(1));

        int finalNodes = countNodes(root);
        int finalDepth = getDepth(root);

        // Build optimized envelope
        RuleEnvelope optimized = RuleEnvelope.builder()
                .ruleType(envelope.getRuleType())
                .reason(envelope.getReason())
                .schemaVersion(envelope.getSchemaVersion())
                .promptVersion(envelope.getPromptVersion())
                .evaluation(envelope.getEvaluation())
                .rule(Rule.builder()
                        .inputs(envelope.getRule().getInputs())
                        .outputs(envelope.getRule().getOutputs())
                        .root(root)
                        .build())
                .build();

        log.info("TreeOptimizer: {} → {} nodes (removed {}), depth {} → {} (reduced {}), {} optimizations",
                originalNodes, finalNodes, originalNodes - finalNodes,
                originalDepth, finalDepth, originalDepth - finalDepth,
                applied.size());

        return OptimizeResult.builder()
                .optimized(optimized)
                .nodesRemoved(originalNodes - finalNodes)
                .depthReduction(originalDepth - finalDepth)
                .appliedOptimizations(applied)
                .build();
    }

    // ================================================================
    // Pass 1: Merge identical sibling leaves
    // ================================================================

    /**
     * 若一個分支節點的所有 children 都是 leaf 且 results 語意相同，
     * 則將此分支節點替換為單一 leaf。
     */
    private TreeNode mergeIdenticalSiblings(TreeNode node) {
        if (node == null) return null;

        // 葉節點不需優化
        if (isLeaf(node)) return node;

        // 先遞迴優化子節點
        if (node.getBranches() != null && !node.getBranches().isEmpty()) {
            for (Branch branch : node.getBranches()) {
                branch.setChild(mergeIdenticalSiblings(branch.getChild()));
            }

            // 檢查：所有子節點都是 leaf 且結果語意相同？
            List<TreeNode> children = node.getBranches().stream()
                    .map(Branch::getChild)
                    .filter(Objects::nonNull)
                    .toList();

            if (!children.isEmpty() && children.stream().allMatch(this::isLeaf)) {
                Map<String, String> firstResult = extractResultMap(children.get(0));
                boolean allSame = children.stream().allMatch(c -> extractResultMap(c).equals(firstResult));

                if (allSame) {
                    // 合併：用第一個 leaf 的 results 取代整個分支節點
                    return TreeNode.builder()
                            .nodeId(node.getNodeId())
                            .results(new ArrayList<>(children.get(0).getResults()))
                            .build();
                }
            }
        }

        return node;
    }

    // ================================================================
    // Pass 2: Remove dead branches
    // ================================================================

    /**
     * 偵測並移除條件矛盾的分支。
     * 使用路徑上已有的條件 bounds 來判斷是否矛盾。
     */
    private TreeNode removeDeadBranches(TreeNode node, List<ConditionBound> pathBounds) {
        if (node == null || isLeaf(node)) return node;

        if (node.getBranches() != null && !node.getBranches().isEmpty()) {
            List<Branch> validBranches = new ArrayList<>();

            for (Branch branch : node.getBranches()) {
                Condition cond = branch.getCondition();
                boolean isDead = false;

                if (cond != null && cond.getField() != null && cond.getOperator() != null) {
                    for (ConditionBound bound : pathBounds) {
                        if (cond.getField().equals(bound.field) && isContradictory(bound, cond)) {
                            isDead = true;
                            log.debug("TreeOptimizer: 移除死碼分支 {} (條件矛盾)", branch.getLabel());
                            break;
                        }
                    }
                }

                if (!isDead) {
                    // 加入 bound 並遞迴
                    List<ConditionBound> childBounds = new ArrayList<>(pathBounds);
                    if (cond != null && cond.getField() != null) {
                        childBounds.add(new ConditionBound(cond.getField(), cond.getOperator(), cond.getValue()));
                    }
                    branch.setChild(removeDeadBranches(branch.getChild(), childBounds));
                    validBranches.add(branch);
                }
            }

            node.setBranches(validBranches);
        }

        return node;
    }

    private boolean isContradictory(ConditionBound bound, Condition newCond) {
        String newOp = newCond.getOperator();
        Object newVal = newCond.getValue();
        if (bound.value == null || newVal == null) return false;

        // equals X + equals Y (X != Y)
        if ("equals".equals(bound.operator) && "equals".equals(newOp)) {
            return !Objects.equals(String.valueOf(bound.value), String.valueOf(newVal));
        }

        // greaterThan X + lessThan Y (Y <= X)
        if ("greaterThan".equals(bound.operator) && "lessThan".equals(newOp)) {
            return toDouble(newVal) <= toDouble(bound.value);
        }

        // lessThan X + greaterThan Y (Y >= X)
        if ("lessThan".equals(bound.operator) && "greaterThan".equals(newOp)) {
            return toDouble(newVal) >= toDouble(bound.value);
        }

        return false;
    }

    // ================================================================
    // Pass 3: Collapse single-child branches
    // ================================================================

    /**
     * 若分支節點只有一個 child，將 child 提升取代此節點。
     */
    private TreeNode collapseSingleChildBranches(TreeNode node) {
        if (node == null || isLeaf(node)) return node;

        if (node.getBranches() != null) {
            // 先遞迴
            for (Branch branch : node.getBranches()) {
                branch.setChild(collapseSingleChildBranches(branch.getChild()));
            }

            // 移除 child 為 null 的分支
            node.setBranches(node.getBranches().stream()
                    .filter(b -> b.getChild() != null)
                    .collect(Collectors.toList()));

            // 只剩一個分支 → 提升
            if (node.getBranches().size() == 1) {
                return node.getBranches().get(0).getChild();
            }

            // 沒有分支 → 變成空葉節點
            if (node.getBranches().isEmpty()) {
                return null;
            }
        }

        return node;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private boolean isLeaf(TreeNode node) {
        return node != null
                && node.getResults() != null && !node.getResults().isEmpty()
                && (node.getBranches() == null || node.getBranches().isEmpty())
                && node.getCondition() == null;
    }

    private Map<String, String> extractResultMap(TreeNode leaf) {
        Map<String, String> map = new TreeMap<>();
        if (leaf.getResults() != null) {
            for (Result r : leaf.getResults()) {
                map.put(r.getField(), String.valueOf(r.getValue()));
            }
        }
        return map;
    }

    private TreeNode deepCopy(TreeNode node) {
        if (node == null) return null;

        TreeNode.TreeNodeBuilder builder = TreeNode.builder()
                .nodeId(node.getNodeId())
                .condition(node.getCondition() != null
                        ? Condition.builder()
                                .field(node.getCondition().getField())
                                .operator(node.getCondition().getOperator())
                                .value(node.getCondition().getValue())
                                .build()
                        : null)
                .results(node.getResults() != null ? new ArrayList<>(node.getResults()) : null);

        if (node.getBranches() != null) {
            List<Branch> branchesCopy = new ArrayList<>();
            for (Branch branch : node.getBranches()) {
                branchesCopy.add(Branch.builder()
                        .label(branch.getLabel())
                        .condition(branch.getCondition() != null
                                ? Condition.builder()
                                        .field(branch.getCondition().getField())
                                        .operator(branch.getCondition().getOperator())
                                        .value(branch.getCondition().getValue())
                                        .build()
                                : null)
                        .child(deepCopy(branch.getChild()))
                        .build());
            }
            builder.branches(branchesCopy);
        }

        return builder.build();
    }

    private void reassignNodeIds(TreeNode node, AtomicInteger counter) {
        if (node == null) return;
        node.setNodeId(String.format("N%02d", counter.getAndIncrement()));
        if (node.getBranches() != null) {
            for (Branch branch : node.getBranches()) {
                reassignNodeIds(branch.getChild(), counter);
            }
        }
    }

    private int countNodes(TreeNode node) {
        if (node == null) return 0;
        int count = 1;
        if (node.getBranches() != null) {
            for (Branch branch : node.getBranches()) {
                count += countNodes(branch.getChild());
            }
        }
        return count;
    }

    private int getDepth(TreeNode node) {
        if (node == null) return 0;
        int maxChild = 0;
        if (node.getBranches() != null) {
            for (Branch branch : node.getBranches()) {
                maxChild = Math.max(maxChild, getDepth(branch.getChild()));
            }
        }
        return 1 + maxChild;
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException e) { return 0.0; }
    }

    // ================================================================
    // Data structures
    // ================================================================

    private record ConditionBound(String field, String operator, Object value) {}

    @Data @Builder
    public static class OptimizeResult {
        private RuleEnvelope optimized;
        private int nodesRemoved;
        private int depthReduction;
        private List<String> appliedOptimizations;
    }
}
