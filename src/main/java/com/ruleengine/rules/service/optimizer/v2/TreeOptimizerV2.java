package com.ruleengine.rules.service.optimizer.v2;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import com.ruleengine.rules.service.converter.TableToTreeConverter;
import com.ruleengine.rules.service.converter.TreeToTableConverter;
import com.ruleengine.rules.service.generator.TreeEvaluationComputer;
import com.ruleengine.rules.service.optimizer.TreeOptimizer;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TreeOptimizerV2 — sparsity-aware DecisionTree 優化管線（6-pass pipeline）。
 *
 * <p>核心設計：委派 Pass 1-3 給 v1 {@link TreeOptimizer}，在其上再跑 3 個新 pass：
 * <ul>
 *   <li><b>Pass 4 — DeduplicateIsomorphicSubtrees</b>：若分支節點的「所有子樹」canonical hash 相同，
 *       則可收合為單一子樹（是 v1 Pass 1 的深度延伸版）</li>
 *   <li><b>Pass 5 — EliminateRedundantConditions</b>：祖先條件已蘊含（implies-true）的子條件，
 *       將該分支無條件收為 matching child</li>
 *   <li><b>Pass 6 — LocalReconstruction</b>：對小子樹（葉數 ≤ maxReconstructDepth²）先 flatten 後
 *       重新用 ID3 重建，若 objective 更優則採用</li>
 * </ul>
 *
 * <p>所有 pass 都：
 * <ul>
 *   <li>deep-copy 當前 envelope，動完後用 {@link SparsityObjective#compare} 決定接受或 rollback</li>
 *   <li>記錄實際動作（{@code appliedOptimizations} 中文描述）與節點減量（{@code passContributions}）</li>
 *   <li>永不降低 coverage 到 {@code coverageFloor} 以下</li>
 * </ul>
 *
 * <p>文獻依據：GOSDT (ICML 2020) 的 sparsity objective，subtree isomorphism canonical form。
 */
@Service
@Slf4j
public class TreeOptimizerV2 {

    private final TreeOptimizer v1Optimizer;
    private final TreeCanonicalizer canonicalizer;
    private final TreeQualityMetrics qualityMetrics;
    private final SparsityObjective objective;
    private final TreeEvaluationComputer evaluationComputer;
    private final TableToTreeConverter tableToTreeConverter;
    private final TreeToTableConverter treeToTableConverter;

    public TreeOptimizerV2(TreeOptimizer v1Optimizer,
                           TreeCanonicalizer canonicalizer,
                           TreeQualityMetrics qualityMetrics,
                           SparsityObjective objective,
                           TreeEvaluationComputer evaluationComputer,
                           @Autowired(required = false) @Lazy TableToTreeConverter tableToTreeConverter,
                           @Autowired(required = false) @Lazy TreeToTableConverter treeToTableConverter) {
        this.v1Optimizer = v1Optimizer;
        this.canonicalizer = canonicalizer;
        this.qualityMetrics = qualityMetrics;
        this.objective = objective;
        this.evaluationComputer = evaluationComputer;
        this.tableToTreeConverter = tableToTreeConverter;
        this.treeToTableConverter = treeToTableConverter;
    }

    // ============================================================
    // 公開 API
    // ============================================================

    /**
     * 對 envelope 執行 v2 6-pass pipeline。
     *
     * @param envelope DecisionTree 型 envelope（非 tree 會直接回傳無變動）
     * @param config   可 null，用 {@link OptimizeConfigV2#defaults()}
     */
    public OptimizeResultV2 optimize(RuleEnvelope envelope, OptimizeConfigV2 config) {
        long startMs = System.currentTimeMillis();
        if (envelope == null || envelope.getRule() == null || envelope.getRule().getRoot() == null) {
            return OptimizeResultV2.builder()
                    .optimized(envelope)
                    .metricsBefore(TreeQualityMetrics.Metrics.empty())
                    .metricsAfter(TreeQualityMetrics.Metrics.empty())
                    .sparsityScoreBefore(0.0)
                    .sparsityScoreAfter(0.0)
                    .appliedOptimizations(List.of())
                    .passContributions(Map.of())
                    .durationMs(System.currentTimeMillis() - startMs)
                    .build();
        }

        OptimizeConfigV2 cfg = config == null ? OptimizeConfigV2.defaults() : config.withDefaults();

        // ===== 初始度量 =====
        TreeQualityMetrics.Metrics before = qualityMetrics.compute(envelope);
        double scoreBefore = objective.evaluate(envelope, cfg);

        List<String> applied = new ArrayList<>();
        Map<String, Integer> passContribs = new LinkedHashMap<>();

        // ===== Pass 1-3：委派 v1 =====
        RuleEnvelope current = runV1(envelope, applied, passContribs);

        // ===== Pass 4：深度同構子樹合併 =====
        if (Boolean.TRUE.equals(cfg.getEnablePass4())) {
            current = tryPass(current, cfg, "pass4",
                    "深度同構子樹合併（所有分支走向同樹時直接收合）",
                    this::pass4DedupIsomorphicSubtrees,
                    applied, passContribs);
        }

        // ===== Pass 5：祖先蘊含的冗餘條件消除 =====
        if (Boolean.TRUE.equals(cfg.getEnablePass5())) {
            current = tryPass(current, cfg, "pass5",
                    "消除祖先蘊含的冗餘條件",
                    this::pass5EliminateRedundantConditions,
                    applied, passContribs);
        }

        // ===== Pass 6：局部重構（葉數少的子樹用 ID3 重建） =====
        if (Boolean.TRUE.equals(cfg.getEnablePass6())
                && tableToTreeConverter != null && treeToTableConverter != null) {
            current = tryPass(current, cfg, "pass6",
                    "局部重構（ID3 重建小子樹）",
                    env -> pass6LocalReconstruction(env, cfg),
                    applied, passContribs);
        }

        // ===== Re-normalize & 重算 evaluation =====
        reassignNodeIds(current.getRule().getRoot(), new AtomicInteger(1));
        try {
            evaluationComputer.computeEvaluation(current);
        } catch (Exception e) {
            log.warn("TreeOptimizerV2: 最終 computeEvaluation 失敗（non-fatal）: {}", e.getMessage());
        }

        // ===== 終局度量 =====
        TreeQualityMetrics.Metrics after = qualityMetrics.compute(current);
        double scoreAfter = objective.evaluate(current, cfg);

        log.info("TreeOptimizerV2: {} leaves/{} depth → {}/{} | score {} → {} | passes={} | {}ms",
                before.getLeafCount(), before.getMaxDepth(),
                after.getLeafCount(), after.getMaxDepth(),
                scoreBefore, scoreAfter, applied.size(),
                System.currentTimeMillis() - startMs);

        return OptimizeResultV2.builder()
                .optimized(current)
                .metricsBefore(before)
                .metricsAfter(after)
                .sparsityScoreBefore(scoreBefore)
                .sparsityScoreAfter(scoreAfter)
                .appliedOptimizations(applied)
                .passContributions(passContribs)
                .durationMs(System.currentTimeMillis() - startMs)
                .build();
    }

    // ============================================================
    // Pass 1-3 委派 v1
    // ============================================================

    private RuleEnvelope runV1(RuleEnvelope envelope, List<String> applied,
                               Map<String, Integer> passContribs) {
        TreeOptimizer.OptimizeResult r = v1Optimizer.optimize(envelope, true);
        if (r.getNodesRemoved() > 0 && r.getOptimized() != null) {
            applied.addAll(r.getAppliedOptimizations());
            passContribs.put("pass1-3", r.getNodesRemoved());
            return r.getOptimized();
        }
        // 若 v1 沒動東西，也採用其 optimized（等同於正規化）
        return r.getOptimized() != null ? r.getOptimized() : envelope;
    }

    // ============================================================
    // Pass 4：深度同構子樹合併
    //
    // 若一個分支節點的所有子樹 canonical hash 完全相同 → 該分支決策不影響結果，
    // 可直接替換為任一子樹（複本）。v1 Pass 1 只處理葉層；本 pass 延伸至深層。
    // ============================================================

    RuleEnvelope pass4DedupIsomorphicSubtrees(RuleEnvelope envelope) {
        RuleEnvelope copy = deepCopyEnvelope(envelope);
        TreeNode newRoot = dedupBottomUp(copy.getRule().getRoot());
        copy.getRule().setRoot(newRoot);
        return copy;
    }

    private TreeNode dedupBottomUp(TreeNode node) {
        if (node == null || canonicalizer.isLeaf(node)) return node;
        if (node.getBranches() != null) {
            // 先遞迴處理子節點
            for (Branch b : node.getBranches()) {
                b.setChild(dedupBottomUp(b.getChild()));
            }
            // 檢查所有子節點 hash 是否一致
            if (!node.getBranches().isEmpty()) {
                String firstHash = canonicalizer.canonicalHash(node.getBranches().get(0).getChild());
                boolean allSame = node.getBranches().stream()
                        .allMatch(b -> firstHash.equals(canonicalizer.canonicalHash(b.getChild())));
                if (allSame && firstHash != null) {
                    // 收合：回傳第一個子節點的複本（保留原 nodeId 以維持 ID 唯一）
                    TreeNode collapsed = deepCopyNode(node.getBranches().get(0).getChild());
                    collapsed.setNodeId(node.getNodeId());
                    return collapsed;
                }
            }
        }
        return node;
    }

    // ============================================================
    // Pass 5：祖先蘊含的冗餘條件消除
    //
    // 若祖先已決定「field op value」為真，且當前節點檢查的條件被其蘊含，
    // 則當前檢查是多餘的 tautology → 收合為對應子節點。
    //
    // 實作：沿路徑累積 PathConstraint（簡化為每欄位的 interval + equals set），
    // 對每個分支 condition 評估是否 always-true / always-false。
    // ============================================================

    RuleEnvelope pass5EliminateRedundantConditions(RuleEnvelope envelope) {
        RuleEnvelope copy = deepCopyEnvelope(envelope);
        PathConstraintMap empty = new PathConstraintMap();
        TreeNode newRoot = eliminateRedundant(copy.getRule().getRoot(), empty);
        copy.getRule().setRoot(newRoot);
        return copy;
    }

    private TreeNode eliminateRedundant(TreeNode node, PathConstraintMap path) {
        if (node == null || canonicalizer.isLeaf(node)) return node;
        if (node.getBranches() == null || node.getBranches().isEmpty()) return node;

        // 先處理：該節點是否全部 branches 的條件都已被祖先蘊含
        List<Branch> liveBranches = new ArrayList<>();
        Branch alwaysTrueBranch = null;
        for (Branch b : node.getBranches()) {
            ImplicationResult impl = path.implies(b.getCondition());
            if (impl == ImplicationResult.ALWAYS_FALSE) {
                // 死枝（v1 Pass 2 已處理部分情況；這裡補）
                continue;
            }
            if (impl == ImplicationResult.ALWAYS_TRUE && alwaysTrueBranch == null) {
                alwaysTrueBranch = b;
            }
            liveBranches.add(b);
        }

        if (alwaysTrueBranch != null) {
            // 祖先已蘊含此分支為真 → 直接跳到該 child，繼續往下處理
            TreeNode child = alwaysTrueBranch.getChild();
            if (child == null) return node;   // 保守
            // 保留原 nodeId 作為被收合節點的 ID
            TreeNode newChild = deepCopyNode(child);
            newChild.setNodeId(node.getNodeId());
            PathConstraintMap extended = path.extend(alwaysTrueBranch.getCondition());
            return eliminateRedundant(newChild, extended);
        }

        // 否則：遞迴處理每個 live branch
        node.setBranches(liveBranches);
        for (Branch b : node.getBranches()) {
            PathConstraintMap extended = path.extend(b.getCondition());
            b.setChild(eliminateRedundant(b.getChild(), extended));
        }
        return node;
    }

    // ============================================================
    // Pass 6：局部重構（對小子樹 flatten → ID3 重建）
    //
    // 找葉數 ≤ maxReconstructDepth² 的子樹，用 TreeToTable + TableToTree 重建，
    // 若 objective 改善則採用。
    // ============================================================

    RuleEnvelope pass6LocalReconstruction(RuleEnvelope envelope, OptimizeConfigV2 cfg) {
        RuleEnvelope copy = deepCopyEnvelope(envelope);
        int maxLeaves = cfg.getMaxReconstructDepth() * cfg.getMaxReconstructDepth();
        TreeNode reconstructed = reconstructSmallSubtrees(copy.getRule().getRoot(),
                maxLeaves, copy, cfg);
        copy.getRule().setRoot(reconstructed);
        return copy;
    }

    private TreeNode reconstructSmallSubtrees(TreeNode node, int maxLeaves,
                                               RuleEnvelope host, OptimizeConfigV2 cfg) {
        if (node == null || canonicalizer.isLeaf(node)) return node;

        // 優先嘗試用此節點為根重構
        int leafCount = countLeaves(node);
        if (leafCount >= 2 && leafCount <= maxLeaves) {
            TreeNode candidate = tryReconstructSubtree(node, host, cfg);
            if (candidate != null) return candidate;
        }

        // 否則往下遞迴
        if (node.getBranches() != null) {
            for (Branch b : node.getBranches()) {
                b.setChild(reconstructSmallSubtrees(b.getChild(), maxLeaves, host, cfg));
            }
        }
        return node;
    }

    private TreeNode tryReconstructSubtree(TreeNode subtreeRoot, RuleEnvelope host,
                                            OptimizeConfigV2 cfg) {
        try {
            // 組一個「只含此子樹」的臨時 envelope 來跑 flatten → rebuild
            RuleEnvelope sub = RuleEnvelope.builder()
                    .ruleType("DecisionTree")
                    .rule(RuleEnvelope.Rule.builder()
                            .inputs(host.getRule().getInputs())
                            .outputs(host.getRule().getOutputs())
                            .root(deepCopyNode(subtreeRoot))
                            .build())
                    .build();

            RuleEnvelope asTable = treeToTableConverter.convert(sub);
            if (asTable == null || asTable.getRule() == null
                    || asTable.getRule().getRules() == null
                    || asTable.getRule().getRules().isEmpty()) {
                return null;
            }
            RuleEnvelope backToTree = tableToTreeConverter.convert(asTable);
            if (backToTree == null || backToTree.getRule() == null
                    || backToTree.getRule().getRoot() == null) {
                return null;
            }

            TreeNode newSubtree = backToTree.getRule().getRoot();
            // 比較 objective：僅以局部（leafCount + depth）估算
            TreeQualityMetrics.Metrics mOld = qualityMetrics.compute(subtreeRoot);
            TreeQualityMetrics.Metrics mNew = qualityMetrics.compute(newSubtree);
            double sOld = cfg.getLeafWeight() * mOld.getLeafCount()
                    + cfg.getDepthWeight() * mOld.getMaxDepth();
            double sNew = cfg.getLeafWeight() * mNew.getLeafCount()
                    + cfg.getDepthWeight() * mNew.getMaxDepth();
            if (sNew + 1e-9 < sOld) {
                newSubtree.setNodeId(subtreeRoot.getNodeId());
                return newSubtree;
            }
            return null;
        } catch (Exception e) {
            log.debug("Pass 6 reconstructSubtree 失敗（non-fatal）: {}", e.getMessage());
            return null;
        }
    }

    // ============================================================
    // Try a pass with objective-based accept/rollback
    // ============================================================

    private RuleEnvelope tryPass(RuleEnvelope current, OptimizeConfigV2 cfg,
                                  String passName, String description,
                                  java.util.function.Function<RuleEnvelope, RuleEnvelope> op,
                                  List<String> applied,
                                  Map<String, Integer> passContribs) {
        int leavesBefore = qualityMetrics.compute(current).getLeafCount();
        try {
            RuleEnvelope candidate = op.apply(current);
            SparsityObjective.Decision d = objective.compare(current, candidate, cfg);
            if (d == SparsityObjective.Decision.ACCEPT) {
                int leavesAfter = qualityMetrics.compute(candidate).getLeafCount();
                int removed = leavesBefore - leavesAfter;
                if (removed > 0) {
                    applied.add(description + "（葉節點減少 " + removed + "）");
                    passContribs.put(passName, removed);
                }
                return candidate;
            }
            log.debug("TreeOptimizerV2: {} 被拒絕（{}）", passName, d);
        } catch (Exception e) {
            log.warn("TreeOptimizerV2: {} 失敗（non-fatal）: {}", passName, e.getMessage());
        }
        return current;
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private RuleEnvelope deepCopyEnvelope(RuleEnvelope src) {
        return RuleEnvelope.builder()
                .ruleType(src.getRuleType())
                .reason(src.getReason())
                .schemaVersion(src.getSchemaVersion())
                .promptVersion(src.getPromptVersion())
                .versionId(src.getVersionId())
                .previousVersionId(src.getPreviousVersionId())
                .evaluation(src.getEvaluation())  // 淺拷貝即可（不會被本類別修改）
                .rule(RuleEnvelope.Rule.builder()
                        .hitPolicy(src.getRule().getHitPolicy())
                        .inputs(src.getRule().getInputs())     // 共用引用即可
                        .outputs(src.getRule().getOutputs())
                        .root(deepCopyNode(src.getRule().getRoot()))
                        .build())
                .build();
    }

    private TreeNode deepCopyNode(TreeNode src) {
        if (src == null) return null;
        TreeNode.TreeNodeBuilder b = TreeNode.builder()
                .nodeId(src.getNodeId())
                .condition(copyCondition(src.getCondition()))
                .results(src.getResults() == null ? null : new ArrayList<>(src.getResults()));
        if (src.getBranches() != null) {
            List<Branch> bs = new ArrayList<>();
            for (Branch br : src.getBranches()) {
                bs.add(Branch.builder()
                        .label(br.getLabel())
                        .condition(copyCondition(br.getCondition()))
                        .child(deepCopyNode(br.getChild()))
                        .build());
            }
            b.branches(bs);
        }
        return b.build();
    }

    private Condition copyCondition(Condition c) {
        if (c == null) return null;
        return Condition.builder().field(c.getField()).operator(c.getOperator()).value(c.getValue()).build();
    }

    private void reassignNodeIds(TreeNode node, AtomicInteger counter) {
        if (node == null) return;
        node.setNodeId(String.format("N%02d", counter.getAndIncrement()));
        if (node.getBranches() != null) {
            for (Branch b : node.getBranches()) {
                reassignNodeIds(b.getChild(), counter);
            }
        }
    }

    private int countLeaves(TreeNode node) {
        if (node == null) return 0;
        if (canonicalizer.isLeaf(node)) return 1;
        int total = 0;
        if (node.getBranches() != null) {
            for (Branch b : node.getBranches()) {
                total += countLeaves(b.getChild());
            }
        }
        return total;
    }

    // ============================================================
    // Path Constraint for Pass 5
    // ============================================================

    /** 累積一條路徑上，各欄位已知的約束。 */
    static class PathConstraintMap {
        /** 每個欄位的已知資訊；不可變替換式更新以避免副作用 */
        final Map<String, FieldConstraint> byField;

        PathConstraintMap() { this.byField = Map.of(); }

        PathConstraintMap(Map<String, FieldConstraint> m) { this.byField = m; }

        PathConstraintMap extend(Condition c) {
            if (c == null || c.getField() == null || c.getOperator() == null) return this;
            FieldConstraint existing = byField.getOrDefault(c.getField(), new FieldConstraint());
            FieldConstraint merged = existing.merge(c);
            Map<String, FieldConstraint> next = new LinkedHashMap<>(byField);
            next.put(c.getField(), merged);
            return new PathConstraintMap(next);
        }

        /**
         * 判斷此 condition 是否被當前 path constraint 蘊含。
         */
        ImplicationResult implies(Condition c) {
            if (c == null || c.getField() == null || c.getOperator() == null) {
                return ImplicationResult.UNKNOWN;
            }
            FieldConstraint fc = byField.get(c.getField());
            if (fc == null) return ImplicationResult.UNKNOWN;
            return fc.test(c);
        }
    }

    enum ImplicationResult { ALWAYS_TRUE, ALWAYS_FALSE, UNKNOWN }

    /**
     * 單一欄位的累積約束：equals 值、排除值、數值 interval。
     */
    static class FieldConstraint {
        String equalsValue;             // 若已知確切值
        Set<String> excluded = new HashSet<>();
        Set<String> inSet;              // 若 in [...]；null 表示未限定
        Double lowerBound;              // > 或 ≥
        boolean lowerInclusive;
        Double upperBound;              // < 或 ≤
        boolean upperInclusive;

        FieldConstraint merge(Condition c) {
            FieldConstraint out = copy();
            Object v = c.getValue();
            switch (c.getOperator()) {
                case "equals" -> out.equalsValue = String.valueOf(v);
                case "notEquals" -> out.excluded.add(String.valueOf(v));
                case "in" -> {
                    if (v instanceof Collection<?> coll) {
                        Set<String> ns = new HashSet<>();
                        coll.forEach(x -> ns.add(String.valueOf(x)));
                        out.inSet = ns;
                    }
                }
                case "notIn" -> {
                    if (v instanceof Collection<?> coll) {
                        coll.forEach(x -> out.excluded.add(String.valueOf(x)));
                    }
                }
                case "greaterThan" -> {
                    Double d = toDouble(v);
                    if (d != null && (out.lowerBound == null || d > out.lowerBound)) {
                        out.lowerBound = d;
                        out.lowerInclusive = false;
                    }
                }
                case "greaterThanOrEqual" -> {
                    Double d = toDouble(v);
                    if (d != null && (out.lowerBound == null || d > out.lowerBound)) {
                        out.lowerBound = d;
                        out.lowerInclusive = true;
                    }
                }
                case "lessThan" -> {
                    Double d = toDouble(v);
                    if (d != null && (out.upperBound == null || d < out.upperBound)) {
                        out.upperBound = d;
                        out.upperInclusive = false;
                    }
                }
                case "lessThanOrEqual" -> {
                    Double d = toDouble(v);
                    if (d != null && (out.upperBound == null || d < out.upperBound)) {
                        out.upperBound = d;
                        out.upperInclusive = true;
                    }
                }
                case "between" -> {
                    if (v instanceof List<?> list && list.size() == 2) {
                        Double a = toDouble(list.get(0));
                        Double b = toDouble(list.get(1));
                        if (a != null && (out.lowerBound == null || a > out.lowerBound)) {
                            out.lowerBound = a; out.lowerInclusive = true;
                        }
                        if (b != null && (out.upperBound == null || b < out.upperBound)) {
                            out.upperBound = b; out.upperInclusive = true;
                        }
                    }
                }
                default -> { /* anything / isNull / isNotNull: 不累積 */ }
            }
            return out;
        }

        private FieldConstraint copy() {
            FieldConstraint x = new FieldConstraint();
            x.equalsValue = equalsValue;
            x.excluded = new HashSet<>(excluded);
            x.inSet = inSet == null ? null : new HashSet<>(inSet);
            x.lowerBound = lowerBound;
            x.lowerInclusive = lowerInclusive;
            x.upperBound = upperBound;
            x.upperInclusive = upperInclusive;
            return x;
        }

        /** 測試 condition 是否被本 constraint 蘊含。 */
        ImplicationResult test(Condition c) {
            Object v = c.getValue();
            String op = c.getOperator();

            // 處理非值型 operators
            if ("anything".equals(op)) return ImplicationResult.ALWAYS_TRUE;
            if ("isNull".equals(op) || "isNotNull".equals(op)) return ImplicationResult.UNKNOWN;

            // equals 值已知的情境
            if (equalsValue != null) {
                String want = String.valueOf(v);
                switch (op) {
                    case "equals": return want.equals(equalsValue)
                            ? ImplicationResult.ALWAYS_TRUE : ImplicationResult.ALWAYS_FALSE;
                    case "notEquals": return want.equals(equalsValue)
                            ? ImplicationResult.ALWAYS_FALSE : ImplicationResult.ALWAYS_TRUE;
                    case "in":
                        if (v instanceof Collection<?> coll) {
                            boolean has = coll.stream()
                                    .map(String::valueOf).anyMatch(equalsValue::equals);
                            return has ? ImplicationResult.ALWAYS_TRUE : ImplicationResult.ALWAYS_FALSE;
                        }
                        return ImplicationResult.UNKNOWN;
                    case "notIn":
                        if (v instanceof Collection<?> coll) {
                            boolean has = coll.stream()
                                    .map(String::valueOf).anyMatch(equalsValue::equals);
                            return has ? ImplicationResult.ALWAYS_FALSE : ImplicationResult.ALWAYS_TRUE;
                        }
                        return ImplicationResult.UNKNOWN;
                }
            }

            // 數值 interval 類比較
            Double target = toDouble(v);
            if (target != null && (lowerBound != null || upperBound != null)) {
                switch (op) {
                    case "greaterThan":
                        // ancestor 下界 ≥ target (嚴格) → always true
                        if (lowerBound != null && (lowerBound > target
                                || (lowerBound == target && !lowerInclusive))) {
                            // lower > target，代表所有值都 > lower > target
                            if (lowerInclusive ? lowerBound > target : lowerBound >= target) {
                                return ImplicationResult.ALWAYS_TRUE;
                            }
                        }
                        // ancestor 上界 ≤ target → always false
                        if (upperBound != null && upperBound <= target) {
                            if (upperInclusive ? upperBound <= target : upperBound <= target) {
                                return ImplicationResult.ALWAYS_FALSE;
                            }
                        }
                        break;
                    case "greaterThanOrEqual":
                        if (lowerBound != null && lowerBound >= target) {
                            return ImplicationResult.ALWAYS_TRUE;
                        }
                        if (upperBound != null && upperBound < target) {
                            return ImplicationResult.ALWAYS_FALSE;
                        }
                        break;
                    case "lessThan":
                        if (upperBound != null && upperBound < target) {
                            return ImplicationResult.ALWAYS_TRUE;
                        }
                        if (lowerBound != null && lowerBound >= target) {
                            return ImplicationResult.ALWAYS_FALSE;
                        }
                        break;
                    case "lessThanOrEqual":
                        if (upperBound != null && upperBound <= target) {
                            return ImplicationResult.ALWAYS_TRUE;
                        }
                        if (lowerBound != null && lowerBound > target) {
                            return ImplicationResult.ALWAYS_FALSE;
                        }
                        break;
                }
            }

            // inSet 子集合 test
            if (inSet != null && "in".equals(op) && v instanceof Collection<?> coll) {
                Set<String> incoming = new HashSet<>();
                coll.forEach(x -> incoming.add(String.valueOf(x)));
                if (incoming.containsAll(inSet)) return ImplicationResult.ALWAYS_TRUE;
            }

            // excluded 已包含該值 → notEquals / equals 的結論
            String cvs = v == null ? null : String.valueOf(v);
            if (cvs != null && excluded.contains(cvs)) {
                if ("equals".equals(op)) return ImplicationResult.ALWAYS_FALSE;
                if ("notEquals".equals(op)) return ImplicationResult.ALWAYS_TRUE;
            }

            return ImplicationResult.UNKNOWN;
        }
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { return null; }
    }

    // ============================================================
    // OptimizeResultV2
    // ============================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OptimizeResultV2 {
        private RuleEnvelope optimized;
        private TreeQualityMetrics.Metrics metricsBefore;
        private TreeQualityMetrics.Metrics metricsAfter;
        private double sparsityScoreBefore;
        private double sparsityScoreAfter;
        private List<String> appliedOptimizations;
        private Map<String, Integer> passContributions;
        private long durationMs;
    }
}
