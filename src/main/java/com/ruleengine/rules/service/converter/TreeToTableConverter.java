package com.ruleengine.rules.service.converter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DecisionTree → DecisionTable 轉換器（N-ary 支援）。
 *
 * 演算法：DFS 遍歷樹的所有 root-to-leaf 路徑，
 * 每條路徑的累積 conditions + leaf results = 一條 DecisionTable rule。
 *
 * N-ary 支援：
 * - branches 格式：每個分支帶有自己的 condition，直接加入路徑
 * - 向後相容：trueBranch/falseBranch 使用條件否定邏輯
 *
 * 複雜度：O(L * D) 其中 L = 葉節點數, D = 最大深度
 */
@Component
@Slf4j
public class TreeToTableConverter {

    /**
     * 將 DecisionTree 的 RuleEnvelope 轉換為 DecisionTable 的 RuleEnvelope。
     */
    public RuleEnvelope convert(RuleEnvelope treeEnvelope) {
        if (treeEnvelope == null || treeEnvelope.getRule() == null
                || treeEnvelope.getRule().getRoot() == null) {
            throw new IllegalArgumentException("無效的 DecisionTree：root 為空");
        }

        Rule treeRule = treeEnvelope.getRule();
        List<FieldDef> inputs = treeRule.getInputs() != null ? treeRule.getInputs() : List.of();
        List<FieldDef> outputs = treeRule.getOutputs() != null ? treeRule.getOutputs() : List.of();

        // DFS 收集所有路徑
        List<RuleRow> tableRules = new ArrayList<>();
        List<Condition> pathConditions = new ArrayList<>();
        flattenPaths(treeRule.getRoot(), pathConditions, tableRules, inputs);

        // 填充缺少的 input 欄位為 anything
        Set<String> inputNames = new LinkedHashSet<>();
        for (FieldDef fd : inputs) {
            inputNames.add(fd.getName());
        }
        for (RuleRow row : tableRules) {
            fillMissingConditions(row, inputNames);
        }

        log.info("TreeToTable 轉換完成：{} 條路徑 → {} 條 DecisionTable rules",
                tableRules.size(), tableRules.size());

        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .reason("由 DecisionTree 自動轉換。原樹有 " + tableRules.size() + " 條路徑，"
                        + "每條路徑對應一條 DecisionTable 規則。採用 FIRST 命中策略。")
                .schemaVersion(treeEnvelope.getSchemaVersion())
                .promptVersion(treeEnvelope.getPromptVersion())
                .rule(Rule.builder()
                        .hitPolicy("FIRST")
                        .inputs(new ArrayList<>(inputs))
                        .outputs(new ArrayList<>(outputs))
                        .rules(tableRules)
                        .build())
                .build();
    }

    // ================================================================
    // DFS 遍歷
    // ================================================================

    private void flattenPaths(TreeNode node, List<Condition> pathConditions,
                               List<RuleRow> rules, List<FieldDef> inputs) {
        if (node == null) return;

        boolean hasBranches = node.getBranches() != null && !node.getBranches().isEmpty();
        boolean isLeaf = node.getResults() != null && !node.getResults().isEmpty()
                && node.getCondition() == null && !hasBranches;

        // 葉節點 → 產生一條 rule
        if (isLeaf) {
            String ruleId = String.format("R%02d", rules.size() + 1);
            rules.add(RuleRow.builder()
                    .ruleId(ruleId)
                    .priority(rules.size() + 1)
                    .conditions(new ArrayList<>(pathConditions))
                    .results(new ArrayList<>(node.getResults()))
                    .build());
            return;
        }

        if (hasBranches) {
            // === N-ary branches：每個分支帶有自己的 condition ===
            for (Branch branch : node.getBranches()) {
                Condition branchCond = branch.getCondition();
                if (branchCond != null) {
                    pathConditions.add(branchCond);
                }
                flattenPaths(branch.getChild(), pathConditions, rules, inputs);
                if (branchCond != null) {
                    pathConditions.remove(pathConditions.size() - 1);
                }
            }
        } else if (node.getCondition() != null) {
            // === 向後相容：二元 trueBranch/falseBranch ===
            Condition cond = node.getCondition();

            // trueBranch：使用原始條件
            pathConditions.add(cond);
            flattenPaths(node.getTrueBranch(), pathConditions, rules, inputs);
            pathConditions.remove(pathConditions.size() - 1);

            // falseBranch：使用否定條件
            List<Condition> negatedConditions = negateCondition(cond);
            if (negatedConditions.size() == 1) {
                pathConditions.add(negatedConditions.get(0));
                flattenPaths(node.getFalseBranch(), pathConditions, rules, inputs);
                pathConditions.remove(pathConditions.size() - 1);
            } else {
                for (Condition neg : negatedConditions) {
                    pathConditions.add(neg);
                    flattenPaths(node.getFalseBranch(), pathConditions, rules, inputs);
                    pathConditions.remove(pathConditions.size() - 1);
                }
            }
        }
    }

    // ================================================================
    // 條件否定
    // ================================================================

    /**
     * 產生條件的邏輯否定（回傳 List，因為 between 否定會拆成兩個條件）。
     *
     * equals → [notEquals]
     * greaterThan → [lessThanOrEqual]
     * between [a,b] → [lessThan a, greaterThan b]（兩條路徑）
     * anything → [anything]（無法否定，保持原樣）
     */
    private List<Condition> negateCondition(Condition cond) {
        String op = cond.getOperator();

        // between [a,b] 否定 = (< a) OR (> b) → 分裂成兩個條件
        if ("between".equals(op) && cond.getValue() instanceof List<?> range && range.size() == 2) {
            return List.of(
                    Condition.builder()
                            .field(cond.getField())
                            .operator("lessThan")
                            .value(range.get(0))
                            .build(),
                    Condition.builder()
                            .field(cond.getField())
                            .operator("greaterThan")
                            .value(range.get(1))
                            .build()
            );
        }

        // anything 無法否定（代表「任何值」，否定 = 空集 → 不產生規則）
        if ("anything".equals(op)) {
            return List.of(); // 空集：falseBranch 永遠不會觸發
        }

        // 一般否定：回傳單一條件
        String negatedOp = switch (op) {
            case "equals" -> "notEquals";
            case "notEquals" -> "equals";
            case "greaterThan" -> "lessThanOrEqual";
            case "greaterThanOrEqual" -> "lessThan";
            case "lessThan" -> "greaterThanOrEqual";
            case "lessThanOrEqual" -> "greaterThan";
            case "in" -> "notIn";
            case "notIn" -> "in";
            case "isNull" -> "isNotNull";
            case "isNotNull" -> "isNull";
            default -> op;
        };

        return List.of(Condition.builder()
                .field(cond.getField())
                .operator(negatedOp)
                .value(cond.getValue())
                .build());
    }

    // ================================================================
    // 填充缺少的條件
    // ================================================================

    private void fillMissingConditions(RuleRow row, Set<String> inputNames) {
        Set<String> coveredFields = new HashSet<>();
        for (Condition c : row.getConditions()) {
            coveredFields.add(c.getField());
        }

        for (String inputName : inputNames) {
            if (!coveredFields.contains(inputName)) {
                row.getConditions().add(Condition.builder()
                        .field(inputName)
                        .operator("anything")
                        .build());
            }
        }
    }
}
