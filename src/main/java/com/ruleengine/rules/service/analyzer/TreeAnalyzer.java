package com.ruleengine.rules.service.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.StreamSupport;

/**
 * DecisionTree 分析引擎（Phase 3 + N-ary 分支支援）。
 *
 * 分析項目：
 * 1. 路徑枚舉：列出所有 root-to-leaf 路徑
 * 2. 深度分析：最大深度、平均深度
 * 3. 缺口偵測：缺失的分支 → gaps（加權：1/N per branch at each level）
 * 4. 簡化建議：sibling 葉節點結果語意相同 → 可合併
 * 5. Dead Code 偵測：永遠不會到達的分支
 * 6. 加權覆蓋率：(1/N) * sum(branchCoverage)
 *
 * 同時支援 N-ary branches 格式和向後相容的 trueBranch/falseBranch 格式。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TreeAnalyzer {

    private final ObjectMapper objectMapper;

    @Cacheable(value = "treeAnalysis", key = "#ruleJson.toString().hashCode()")
    public AnalysisResult analyze(JsonNode ruleJson) {
        log.info("開始 DecisionTree 分析...");

        try {
            JsonNode ruleNode = resolveRuleNode(ruleJson);
            if (ruleNode == null || !ruleNode.has("root")) {
                return emptyResult("無法找到 root 節點");
            }

            List<String> inputNames = new ArrayList<>();
            if (ruleNode.has("inputs") && ruleNode.get("inputs").isArray()) {
                for (JsonNode inp : ruleNode.get("inputs")) {
                    if (inp.has("name")) inputNames.add(inp.get("name").asText());
                }
            }

            JsonNode root = ruleNode.get("root");
            List<PathInfo> paths = new ArrayList<>();
            List<AnalysisResult.GapInfo> gaps = new ArrayList<>();
            List<AnalysisResult.SimplificationHint> simplifications = new ArrayList<>();
            int[] depthStats = {0, 0}; // [maxDepth, totalLeafDepth]

            // 1. 遍歷樹：收集路徑和缺口
            traverseTree(root, new ArrayList<>(), paths, gaps, depthStats, 1);

            // 2. 語意簡化偵測（用 normalized JSON 比較）
            detectSimplifications(root, simplifications);

            // 3. Dead code 偵測
            List<AnalysisResult.SimplificationHint> deadCode = new ArrayList<>();
            detectDeadCode(root, new ArrayList<>(), deadCode);
            simplifications.addAll(deadCode);

            // 4. 加權覆蓋率：root 缺失 = 50% 空間，leaf 缺失 = 1/2^depth
            double coverageRate = computeWeightedCoverage(root, 1, depthStats[0]);

            String summary = buildSummary(paths, depthStats[0], coverageRate, gaps, simplifications);

            AnalysisResult result = AnalysisResult.builder()
                    .coverageRate(Math.round(coverageRate * 10000.0) / 10000.0)
                    .gaps(gaps)
                    .overlaps(List.of())
                    .simplifications(simplifications)
                    .summary(summary)
                    .totalRules(paths.size())
                    .totalDimensions(inputNames.size())
                    .build();

            log.info("TreeAnalysis 完成 | paths={} | depth={} | gaps={} | simplifications={} | coverage={:.1f}%",
                    paths.size(), depthStats[0], gaps.size(), simplifications.size(), coverageRate * 100);

            return result;

        } catch (Exception e) {
            log.error("TreeAnalysis ERROR: {}", e.getMessage(), e);
            return emptyResult("分析失敗：" + e.getMessage());
        }
    }

    // ================================================================
    // 1. 樹遍歷 — 路徑枚舉 + 缺口偵測
    // ================================================================

    private void traverseTree(JsonNode node, List<String> pathConditions,
                               List<PathInfo> paths, List<AnalysisResult.GapInfo> gaps,
                               int[] depthStats, int depth) {
        if (node == null || node.isNull()) return;

        depthStats[0] = Math.max(depthStats[0], depth);

        boolean hasBranches = node.has("branches") && node.get("branches").isArray()
                && !node.get("branches").isEmpty();
        boolean hasCondition = node.has("condition") && !node.get("condition").isNull();
        boolean hasResults = node.has("results") && !node.get("results").isNull();

        if (hasBranches) {
            // === N-ary branches 格式 ===
            JsonNode branches = node.get("branches");
            int branchCount = branches.size();

            for (int i = 0; i < branchCount; i++) {
                JsonNode branch = branches.get(i);
                String label = branch.has("label") ? branch.get("label").asText() : "branch[" + i + "]";
                String condDesc = branch.has("condition") && !branch.get("condition").isNull()
                        ? formatConditionDesc(branch.get("condition")) : label;

                if (!branch.has("child") || branch.get("child").isNull()) {
                    // 缺失的分支 → gap
                    Map<String, String> gapConds = new LinkedHashMap<>();
                    gapConds.put("branch", label + " (" + condDesc + ")");
                    if (!pathConditions.isEmpty()) {
                        gapConds.put("path", String.join(" → ", pathConditions));
                    }
                    double weight = 1.0 / Math.pow(branchCount, depth);
                    gaps.add(AnalysisResult.GapInfo.builder()
                            .conditions(gapConds)
                            .message("缺少分支 \"" + label + "\"：當 " + condDesc + " 時無對應處理（影響約 "
                                    + String.format("%.1f%%", weight * 100) + " 輸入空間）")
                            .volumeRatio(weight)
                            .build());
                } else {
                    List<String> branchPath = new ArrayList<>(pathConditions);
                    branchPath.add(condDesc + " [" + label + "]");
                    traverseTree(branch.get("child"), branchPath, paths, gaps, depthStats, depth + 1);
                }
            }
        } else if (hasCondition) {
            // === 向後相容：二元 trueBranch/falseBranch ===
            String condDesc = formatConditionDesc(node.get("condition"));
            String field = node.get("condition").has("field")
                    ? node.get("condition").get("field").asText() : "?";

            // trueBranch
            if (!node.has("trueBranch") || node.get("trueBranch").isNull()) {
                Map<String, String> gapConds = new LinkedHashMap<>();
                gapConds.put(field, condDesc + " = true");
                if (!pathConditions.isEmpty()) {
                    gapConds.put("path", String.join(" → ", pathConditions));
                }
                double weight = 1.0 / Math.pow(2, depth);
                gaps.add(AnalysisResult.GapInfo.builder()
                        .conditions(gapConds)
                        .message("缺少 trueBranch：當 " + condDesc + " 為 true 時無對應處理（影響約 "
                                + String.format("%.1f%%", weight * 100) + " 輸入空間）")
                        .volumeRatio(weight)
                        .build());
            } else {
                List<String> truePath = new ArrayList<>(pathConditions);
                truePath.add(condDesc + " = TRUE");
                traverseTree(node.get("trueBranch"), truePath, paths, gaps, depthStats, depth + 1);
            }

            // falseBranch
            if (!node.has("falseBranch") || node.get("falseBranch").isNull()) {
                Map<String, String> gapConds = new LinkedHashMap<>();
                gapConds.put(field, condDesc + " = false");
                double weight = 1.0 / Math.pow(2, depth);
                gaps.add(AnalysisResult.GapInfo.builder()
                        .conditions(gapConds)
                        .message("缺少 falseBranch：當 " + condDesc + " 為 false 時無對應處理（影響約 "
                                + String.format("%.1f%%", weight * 100) + " 輸入空間）")
                        .volumeRatio(weight)
                        .build());
            } else {
                List<String> falsePath = new ArrayList<>(pathConditions);
                falsePath.add(condDesc + " = FALSE");
                traverseTree(node.get("falseBranch"), falsePath, paths, gaps, depthStats, depth + 1);
            }
        }

        if (!hasBranches && !hasCondition && hasResults) {
            String nodeId = node.has("nodeId") ? node.get("nodeId").asText() : "leaf";
            paths.add(new PathInfo(nodeId, new ArrayList<>(pathConditions), depth));
            depthStats[1] += depth;
        }
    }

    // ================================================================
    // 2. 語意簡化偵測 — 用 normalized JSON 比較
    // ================================================================

    private void detectSimplifications(JsonNode node, List<AnalysisResult.SimplificationHint> hints) {
        if (node == null || node.isNull()) return;

        boolean hasBranches = node.has("branches") && node.get("branches").isArray()
                && !node.get("branches").isEmpty();

        if (hasBranches) {
            // === N-ary：檢查所有 sibling leaf 是否語意相同 ===
            JsonNode branches = node.get("branches");
            List<JsonNode> leafChildren = new ArrayList<>();
            List<String> leafIds = new ArrayList<>();

            for (JsonNode branch : branches) {
                JsonNode child = branch.has("child") ? branch.get("child") : null;
                if (child != null && isLeaf(child)) {
                    leafChildren.add(child);
                    leafIds.add(child.has("nodeId") ? child.get("nodeId").asText() : "leaf");
                }
            }

            // 如果所有子節點都是 leaf 且結果語意相同 → 可合併
            if (leafChildren.size() == branches.size() && leafChildren.size() >= 2) {
                boolean allSame = true;
                for (int i = 1; i < leafChildren.size(); i++) {
                    if (!resultsSemanticEqual(leafChildren.get(0), leafChildren.get(i))) {
                        allSame = false;
                        break;
                    }
                }
                if (allSame) {
                    String field = node.has("condition") && node.get("condition").has("field")
                            ? node.get("condition").get("field").asText() : "?";
                    hints.add(AnalysisResult.SimplificationHint.builder()
                            .ruleIds(leafIds)
                            .suggestion("節點 " + String.join(", ", leafIds) + " 的 results 語意相同，"
                                    + "可移除 " + field + " 的分支判斷，直接用葉節點取代")
                            .build());
                }
            }

            // 遞迴子節點
            for (JsonNode branch : branches) {
                if (branch.has("child")) {
                    detectSimplifications(branch.get("child"), hints);
                }
            }
        } else if (node.has("condition")) {
            // === 向後相容：二元 ===
            JsonNode trueBranch = node.has("trueBranch") ? node.get("trueBranch") : null;
            JsonNode falseBranch = node.has("falseBranch") ? node.get("falseBranch") : null;

            if (trueBranch != null && falseBranch != null
                    && isLeaf(trueBranch) && isLeaf(falseBranch)) {
                if (resultsSemanticEqual(trueBranch, falseBranch)) {
                    String trueId = trueBranch.has("nodeId") ? trueBranch.get("nodeId").asText() : "true-leaf";
                    String falseId = falseBranch.has("nodeId") ? falseBranch.get("nodeId").asText() : "false-leaf";
                    String field = node.get("condition").has("field")
                            ? node.get("condition").get("field").asText() : "?";
                    hints.add(AnalysisResult.SimplificationHint.builder()
                            .ruleIds(List.of(trueId, falseId))
                            .suggestion("節點 " + trueId + " 和 " + falseId + " 的 results 語意相同，"
                                    + "可移除 " + field + " 的分支判斷，直接用葉節點取代")
                            .build());
                }
            }

            if (trueBranch != null) detectSimplifications(trueBranch, hints);
            if (falseBranch != null) detectSimplifications(falseBranch, hints);
        }
    }

    /**
     * 語意比較兩個葉節點的 results — 按 field 排序後逐一比較 value，
     * 避免 JSON field 順序不同導致誤判。
     */
    private boolean resultsSemanticEqual(JsonNode leafA, JsonNode leafB) {
        JsonNode resultsA = leafA.get("results");
        JsonNode resultsB = leafB.get("results");
        if (resultsA == null || resultsB == null) return false;
        if (resultsA.size() != resultsB.size()) return false;

        // 按 field 排序後比較
        Map<String, String> mapA = extractResultMap(resultsA);
        Map<String, String> mapB = extractResultMap(resultsB);
        return mapA.equals(mapB);
    }

    private Map<String, String> extractResultMap(JsonNode resultsNode) {
        Map<String, String> map = new TreeMap<>(); // TreeMap 保證排序
        if (resultsNode.isArray()) {
            for (JsonNode r : resultsNode) {
                String field = r.has("field") ? r.get("field").asText() : "";
                String value = r.has("value") ? r.get("value").asText() : "";
                map.put(field, value);
            }
        }
        return map;
    }

    // ================================================================
    // 3. Dead Code 偵測
    // ================================================================

    /**
     * 偵測永遠不會到達的分支（條件矛盾）。
     *
     * 例如：if (a > 10) { if (a < 5) { ... } }
     * 內層 a < 5 永遠為 false（因為外層已確認 a > 10）。
     *
     * 簡化版：只偵測同欄位的直接矛盾（同一條路徑上重複引用相同欄位且範圍不相容）。
     */
    private void detectDeadCode(JsonNode node, List<ConditionBound> pathBounds,
                                 List<AnalysisResult.SimplificationHint> deadCode) {
        if (node == null || node.isNull()) return;

        boolean hasBranches = node.has("branches") && node.get("branches").isArray()
                && !node.get("branches").isEmpty();

        // 檢查節點層級的 condition
        if (node.has("condition") && !node.get("condition").isNull()) {
            JsonNode cond = node.get("condition");
            String field = cond.has("field") ? cond.get("field").asText() : null;
            String operator = cond.has("operator") ? cond.get("operator").asText() : null;
            String nodeId = node.has("nodeId") ? node.get("nodeId").asText() : "?";

            if (field != null && operator != null) {
                for (ConditionBound bound : pathBounds) {
                    if (field.equals(bound.field) && isContradictory(bound, operator, cond)) {
                        deadCode.add(AnalysisResult.SimplificationHint.builder()
                                .ruleIds(List.of(nodeId))
                                .suggestion("Dead Code：節點 " + nodeId + " 的條件 " + field + " " + operator
                                        + " 與上層條件 " + bound.field + " " + bound.operator
                                        + " 矛盾，此分支永遠不會觸發")
                                .build());
                        return;
                    }
                }
            }
        }

        if (hasBranches) {
            // === N-ary branches ===
            for (JsonNode branch : node.get("branches")) {
                JsonNode branchCond = branch.has("condition") ? branch.get("condition") : null;
                List<ConditionBound> branchBounds = new ArrayList<>(pathBounds);

                if (branchCond != null && !branchCond.isNull()) {
                    String bField = branchCond.has("field") ? branchCond.get("field").asText() : null;
                    String bOp = branchCond.has("operator") ? branchCond.get("operator").asText() : null;
                    if (bField != null && bOp != null) {
                        branchBounds.add(new ConditionBound(bField, bOp, branchCond.get("value"), true));
                    }
                }

                if (branch.has("child") && !branch.get("child").isNull()) {
                    detectDeadCode(branch.get("child"), branchBounds, deadCode);
                }
            }
        } else {
            // === 向後相容：二元 ===
            JsonNode cond = node.has("condition") ? node.get("condition") : null;
            if (cond != null && !cond.isNull()) {
                String field = cond.has("field") ? cond.get("field").asText() : null;
                String operator = cond.has("operator") ? cond.get("operator").asText() : null;

                if (field != null && operator != null) {
                    if (node.has("trueBranch") && !node.get("trueBranch").isNull()) {
                        List<ConditionBound> trueBounds = new ArrayList<>(pathBounds);
                        trueBounds.add(new ConditionBound(field, operator, cond.get("value"), true));
                        detectDeadCode(node.get("trueBranch"), trueBounds, deadCode);
                    }
                    if (node.has("falseBranch") && !node.get("falseBranch").isNull()) {
                        List<ConditionBound> falseBounds = new ArrayList<>(pathBounds);
                        falseBounds.add(new ConditionBound(field, operator, cond.get("value"), false));
                        detectDeadCode(node.get("falseBranch"), falseBounds, deadCode);
                    }
                }
            }
        }
    }

    /**
     * 檢查新條件是否與已有的 bound 矛盾。
     *
     * 簡化實作：只偵測明顯矛盾：
     * - 上層 field > X (true), 下層 field < Y 且 Y <= X → 矛盾
     * - 上層 field equals X (true), 下層 field equals Y 且 X != Y → 矛盾
     */
    private boolean isContradictory(ConditionBound bound, String newOperator, JsonNode newCond) {
        JsonNode newValue = newCond.get("value");
        if (bound.value == null || newValue == null) return false;

        // 同欄位 equals + equals，值不同
        if (bound.isTrue && "equals".equals(bound.operator) && "equals".equals(newOperator)) {
            return !bound.value.equals(newValue);
        }

        // 上層 greaterThan X (true) + 下層 lessThan Y，Y <= X
        if (bound.isTrue && "greaterThan".equals(bound.operator) && "lessThan".equals(newOperator)) {
            if (bound.value.isNumber() && newValue.isNumber()) {
                return newValue.asDouble() <= bound.value.asDouble();
            }
        }

        // 上層 lessThan X (true) + 下層 greaterThan Y，Y >= X
        if (bound.isTrue && "lessThan".equals(bound.operator) && "greaterThan".equals(newOperator)) {
            if (bound.value.isNumber() && newValue.isNumber()) {
                return newValue.asDouble() >= bound.value.asDouble();
            }
        }

        // 上層 greaterThan X (false → lessThanOrEqual X) + 下層 greaterThan Y，Y > X
        if (!bound.isTrue && "greaterThan".equals(bound.operator) && "greaterThan".equals(newOperator)) {
            if (bound.value.isNumber() && newValue.isNumber()) {
                return newValue.asDouble() >= bound.value.asDouble();
            }
        }

        return false;
    }

    // ================================================================
    // 4. 加權覆蓋率
    // ================================================================

    /**
     * 計算加權覆蓋率：越靠近 root 的缺失分支，影響越大。
     *
     * N-ary 分支節點：每個分支權重 = 1/N
     * 二元分支節點：每個分支權重 = 1/2（與舊版一致）
     *
     * 例如：3-way 分支缺一個 → 損失 1/3
     *       二元 root 缺 falseBranch → 損失 50%
     */
    private double computeWeightedCoverage(JsonNode node, int depth, int maxDepth) {
        if (node == null || node.isNull()) return 0.0;

        boolean hasBranches = node.has("branches") && node.get("branches").isArray()
                && !node.get("branches").isEmpty();
        boolean hasCondition = node.has("condition") && !node.get("condition").isNull();

        // 葉節點 = 完全覆蓋
        if (!hasBranches && !hasCondition) return 1.0;

        if (hasBranches) {
            // === N-ary 加權覆蓋 ===
            JsonNode branches = node.get("branches");
            int n = branches.size();
            if (n == 0) return 0.0;
            double branchWeight = 1.0 / n;
            double total = 0.0;

            for (JsonNode branch : branches) {
                if (branch.has("child") && !branch.get("child").isNull()) {
                    total += branchWeight * computeWeightedCoverage(branch.get("child"), depth + 1, maxDepth);
                }
                // 缺失 child → 貢獻 0
            }
            return total;
        }

        // === 向後相容：二元覆蓋 ===
        double trueWeight;
        double falseWeight;

        if (node.has("trueBranch") && !node.get("trueBranch").isNull()) {
            trueWeight = 0.5 * computeWeightedCoverage(node.get("trueBranch"), depth + 1, maxDepth);
        } else {
            trueWeight = 0.0;
        }

        if (node.has("falseBranch") && !node.get("falseBranch").isNull()) {
            falseWeight = 0.5 * computeWeightedCoverage(node.get("falseBranch"), depth + 1, maxDepth);
        } else {
            falseWeight = 0.0;
        }

        return trueWeight + falseWeight;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private String formatConditionDesc(JsonNode cond) {
        String field = cond.has("field") ? cond.get("field").asText() : "?";
        String operator = cond.has("operator") ? cond.get("operator").asText() : "?";
        String value = cond.has("value") ? cond.get("value").toString() : "";
        return field + " " + operator + " " + value;
    }

    private String buildSummary(List<PathInfo> paths, int maxDepth, double coverageRate,
                                 List<AnalysisResult.GapInfo> gaps,
                                 List<AnalysisResult.SimplificationHint> simplifications) {
        return String.format(
                "DecisionTree 共 %d 條決策路徑，最大深度 %d，加權覆蓋率 %.1f%%。%s%s",
                paths.size(), maxDepth, coverageRate * 100,
                gaps.isEmpty() ? "無缺口。" : "有 " + gaps.size() + " 個缺口。",
                simplifications.isEmpty() ? "" : "有 " + simplifications.size() + " 個可優化處。");
    }

    private JsonNode resolveRuleNode(JsonNode ruleJson) {
        if (ruleJson.has("rule")) return ruleJson.get("rule");
        if (ruleJson.has("root")) return ruleJson;
        return null;
    }

    private boolean isLeaf(JsonNode node) {
        if (node == null || node.isNull()) return false;
        boolean hasBranches = node.has("branches") && node.get("branches").isArray()
                && !node.get("branches").isEmpty();
        return !node.has("condition") && !hasBranches && node.has("results");
    }

    private AnalysisResult emptyResult(String reason) {
        return AnalysisResult.builder()
                .coverageRate(0.0)
                .gaps(List.of())
                .overlaps(List.of())
                .simplifications(List.of())
                .summary(reason)
                .totalRules(0)
                .totalDimensions(0)
                .build();
    }

    private record PathInfo(String leafNodeId, List<String> conditions, int depth) {}

    private record ConditionBound(String field, String operator, JsonNode value, boolean isTrue) {}
}
