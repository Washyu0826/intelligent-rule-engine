package com.ruleengine.rules.service.converter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * DecisionTable → DecisionTree 轉換器（N-ary 支援）。
 *
 * 演算法：基於 ID3 (Iterative Dichotomiser 3) 的資訊增益分裂。
 *
 * N-ary 擴充：
 * - ENUM/STRING 欄位若有多個 allowedValues，產生 N-way 分支（每個值一個分支）
 * - BOOLEAN/數值欄位仍使用二元分裂
 * - 輸出使用 branches 格式
 *
 * 限制：
 * - 不支援 MULTI hitPolicy（因為 tree 天生互斥）
 * - 連續型欄位（INTEGER/DECIMAL）使用規則中的分裂點
 *
 * 複雜度：O(n * m * log m) 其中 n = 規則數, m = 欄位數
 */
@Component
@Slf4j
public class TableToTreeConverter {

    /**
     * 將 DecisionTable 的 RuleEnvelope 轉換為 DecisionTree。
     *
     * @throws IllegalArgumentException 若 hitPolicy 為 MULTI
     */
    public RuleEnvelope convert(RuleEnvelope tableEnvelope) {
        if (tableEnvelope == null || tableEnvelope.getRule() == null) {
            throw new IllegalArgumentException("無效的 DecisionTable");
        }

        Rule tableRule = tableEnvelope.getRule();

        // MULTI hitPolicy 不支援轉換
        if ("MULTI".equalsIgnoreCase(tableRule.getHitPolicy())) {
            throw new IllegalArgumentException(
                    "MULTI hitPolicy 的 DecisionTable 無法轉換為 DecisionTree，"
                            + "因為 DecisionTree 天生互斥，不支援多條命中");
        }

        List<FieldDef> inputs = tableRule.getInputs() != null ? tableRule.getInputs() : List.of();
        List<FieldDef> outputs = tableRule.getOutputs() != null ? tableRule.getOutputs() : List.of();
        List<RuleRow> rules = tableRule.getRules() != null ? tableRule.getRules() : List.of();

        if (rules.isEmpty()) {
            throw new IllegalArgumentException("DecisionTable 沒有規則，無法轉換");
        }

        // 建構決策樹
        AtomicInteger nodeCounter = new AtomicInteger(1);
        Set<String> usedFields = new HashSet<>();
        TreeNode root = buildTree(rules, inputs, usedFields, nodeCounter, 0);

        log.info("TableToTree 轉換完成：{} 條規則 → 深度 {} 的 DecisionTree",
                rules.size(), getDepth(root));

        return RuleEnvelope.builder()
                .ruleType("DecisionTree")
                .reason("由 DecisionTable 自動轉換（ID3 資訊增益分裂）。"
                        + "原表有 " + rules.size() + " 條規則，"
                        + inputs.size() + " 個輸入欄位。")
                .schemaVersion(tableEnvelope.getSchemaVersion())
                .promptVersion(tableEnvelope.getPromptVersion())
                .rule(Rule.builder()
                        .inputs(new ArrayList<>(inputs))
                        .outputs(new ArrayList<>(outputs))
                        .root(root)
                        .build())
                .build();
    }

    // ================================================================
    // 遞迴建構
    // ================================================================

    private TreeNode buildTree(List<RuleRow> rules, List<FieldDef> inputs,
                                Set<String> usedFields, AtomicInteger counter, int depth) {
        String nodeId = String.format("N%02d", counter.getAndIncrement());

        // 基礎情況 1：無規則 → 空葉節點
        if (rules.isEmpty()) {
            return TreeNode.builder()
                    .nodeId(nodeId)
                    .results(List.of(Result.builder().field("_error").value("無匹配規則").build()))
                    .build();
        }

        // 基礎情況 2：所有規則結果相同 → 葉節點
        if (allSameResult(rules)) {
            return TreeNode.builder()
                    .nodeId(nodeId)
                    .results(new ArrayList<>(rules.get(0).getResults()))
                    .build();
        }

        // 基礎情況 3：只剩 1 條規則 → 葉節點
        if (rules.size() == 1) {
            return TreeNode.builder()
                    .nodeId(nodeId)
                    .results(new ArrayList<>(rules.get(0).getResults()))
                    .build();
        }

        // 基礎情況 4：深度限制
        if (depth >= 10) {
            return TreeNode.builder()
                    .nodeId(nodeId)
                    .results(new ArrayList<>(rules.get(0).getResults()))
                    .build();
        }

        // 嘗試 N-way split（ENUM/STRING 欄位）
        MultiWaySplitCandidate multiWayBest = selectBestMultiWaySplit(rules, inputs, usedFields);
        // 嘗試 binary split（所有欄位）
        BinarySplitCandidate binaryBest = selectBestBinarySplit(rules, inputs, usedFields);

        // 選擇增益較高的方式
        double multiWayGain = multiWayBest != null ? multiWayBest.gain : -1.0;
        double binaryGain = binaryBest != null ? binaryBest.gain : -1.0;

        Set<String> nextUsed = new HashSet<>(usedFields);

        if (multiWayBest != null && multiWayGain >= binaryGain && multiWayBest.groups.size() >= 2) {
            // === N-way split ===
            nextUsed.add(multiWayBest.field);
            Condition rootCond = Condition.builder()
                    .field(multiWayBest.field)
                    .operator("equals")
                    .build();

            List<Branch> branches = new ArrayList<>();
            for (SplitGroup group : multiWayBest.groups) {
                TreeNode child = buildTree(group.rules, inputs, nextUsed, counter, depth + 1);
                branches.add(Branch.builder()
                        .label(String.valueOf(group.value))
                        .condition(Condition.builder()
                                .field(multiWayBest.field)
                                .operator("equals")
                                .value(group.value)
                                .build())
                        .child(child)
                        .build());
            }

            return TreeNode.builder()
                    .nodeId(nodeId)
                    .condition(rootCond)
                    .branches(branches)
                    .build();
        }

        if (binaryBest != null) {
            // === Binary split（輸出為 branches 格式） ===
            nextUsed.add(binaryBest.field);

            TreeNode trueChild = buildTree(binaryBest.trueRules, inputs, nextUsed, counter, depth + 1);
            TreeNode falseChild = buildTree(binaryBest.falseRules, inputs, nextUsed, counter, depth + 1);

            Condition negated = negateCondition(binaryBest.condition);

            List<Branch> branches = new ArrayList<>();
            branches.add(Branch.builder()
                    .label("TRUE")
                    .condition(binaryBest.condition)
                    .child(trueChild)
                    .build());
            branches.add(Branch.builder()
                    .label("FALSE")
                    .condition(negated)
                    .child(falseChild)
                    .build());

            return TreeNode.builder()
                    .nodeId(nodeId)
                    .condition(binaryBest.condition)
                    .branches(branches)
                    .build();
        }

        // 無法分裂 → 使用最常見的結果
        return TreeNode.builder()
                .nodeId(nodeId)
                .results(new ArrayList<>(mostCommonResult(rules)))
                .build();
    }

    // ================================================================
    // N-way split 選擇（ENUM/STRING 欄位）
    // ================================================================

    private MultiWaySplitCandidate selectBestMultiWaySplit(List<RuleRow> rules, List<FieldDef> inputs,
                                                            Set<String> usedFields) {
        MultiWaySplitCandidate best = null;
        double bestGain = -1.0;

        for (FieldDef field : inputs) {
            if (usedFields.contains(field.getName())) continue;
            String typeRef = field.getTypeRef();

            // 只對 ENUM/STRING 欄位嘗試 N-way split
            if (!"ENUM".equals(typeRef) && !"STRING".equals(typeRef)) continue;

            // 收集該欄位在規則中出現的所有不同值
            Set<String> distinctValues = new LinkedHashSet<>();
            if (field.getAllowedValues() != null) {
                distinctValues.addAll(field.getAllowedValues());
            }
            for (RuleRow rule : rules) {
                if (rule.getConditions() == null) continue;
                for (Condition c : rule.getConditions()) {
                    if (field.getName().equals(c.getField()) && c.getValue() != null) {
                        if ("equals".equals(c.getOperator())) {
                            distinctValues.add(String.valueOf(c.getValue()));
                        } else if ("in".equals(c.getOperator()) && c.getValue() instanceof List<?> list) {
                            list.forEach(v -> distinctValues.add(String.valueOf(v)));
                        }
                    }
                }
            }

            if (distinctValues.size() < 2) continue;

            // 建立 N-way 分組
            List<SplitGroup> groups = new ArrayList<>();
            List<RuleRow> unmatched = new ArrayList<>(rules);

            for (String value : distinctValues) {
                List<RuleRow> matched = new ArrayList<>();
                List<RuleRow> remaining = new ArrayList<>();
                for (RuleRow rule : unmatched) {
                    if (ruleMatchesValue(rule, field.getName(), value)) {
                        matched.add(rule);
                    } else {
                        remaining.add(rule);
                    }
                }
                if (!matched.isEmpty()) {
                    groups.add(new SplitGroup(value, matched));
                }
                // anything 規則會出現在多個分組中，不從 unmatched 中移除
            }

            // 將 anything 規則加入每個分組
            List<RuleRow> anythingRules = rules.stream()
                    .filter(r -> hasAnythingCondition(r, field.getName()))
                    .toList();
            for (SplitGroup group : groups) {
                for (RuleRow ar : anythingRules) {
                    if (!group.rules.contains(ar)) {
                        group.rules.add(ar);
                    }
                }
            }

            // 過濾空分組
            groups = groups.stream().filter(g -> !g.rules.isEmpty()).collect(Collectors.toList());

            if (groups.size() < 2) continue;

            // 計算 N-way 資訊增益
            double gain = informationGainMultiWay(rules, groups);
            if (gain > bestGain) {
                bestGain = gain;
                best = new MultiWaySplitCandidate(field.getName(), groups, gain);
            }
        }

        return best;
    }

    private boolean ruleMatchesValue(RuleRow rule, String fieldName, String value) {
        if (rule.getConditions() == null) return false;
        for (Condition c : rule.getConditions()) {
            if (!fieldName.equals(c.getField())) continue;
            if ("anything".equals(c.getOperator())) return true;
            if ("equals".equals(c.getOperator())) {
                return Objects.equals(String.valueOf(c.getValue()), value);
            }
            if ("in".equals(c.getOperator()) && c.getValue() instanceof List<?> list) {
                return list.stream().anyMatch(v -> Objects.equals(String.valueOf(v), value));
            }
        }
        return false;
    }

    private boolean hasAnythingCondition(RuleRow rule, String fieldName) {
        if (rule.getConditions() == null) return false;
        return rule.getConditions().stream()
                .anyMatch(c -> fieldName.equals(c.getField()) && "anything".equals(c.getOperator()));
    }

    // ================================================================
    // Binary split 選擇（所有欄位）
    // ================================================================

    private BinarySplitCandidate selectBestBinarySplit(List<RuleRow> rules, List<FieldDef> inputs,
                                                        Set<String> usedFields) {
        BinarySplitCandidate best = null;
        double bestGain = -1.0;

        for (FieldDef field : inputs) {
            if (usedFields.contains(field.getName())) continue;

            List<BinarySplitCandidate> candidates = generateBinarySplitCandidates(rules, field);

            for (BinarySplitCandidate candidate : candidates) {
                if (candidate.gain > bestGain) {
                    bestGain = candidate.gain;
                    best = candidate;
                }
            }
        }

        return best;
    }

    private List<BinarySplitCandidate> generateBinarySplitCandidates(List<RuleRow> rules, FieldDef field) {
        List<BinarySplitCandidate> candidates = new ArrayList<>();
        String fieldName = field.getName();
        String typeRef = field.getTypeRef();

        // 收集所有出現的值
        Set<Object> values = new LinkedHashSet<>();
        for (RuleRow rule : rules) {
            if (rule.getConditions() == null) continue;
            for (Condition c : rule.getConditions()) {
                if (fieldName.equals(c.getField()) && c.getValue() != null
                        && !"anything".equals(c.getOperator())) {
                    if ("equals".equals(c.getOperator())) {
                        values.add(c.getValue());
                    } else if ("between".equals(c.getOperator()) && c.getValue() instanceof List<?> range) {
                        if (range.size() == 2) {
                            values.add(range.get(0));
                            values.add(range.get(1));
                        }
                    } else if ("greaterThan".equals(c.getOperator())
                            || "greaterThanOrEqual".equals(c.getOperator())
                            || "lessThan".equals(c.getOperator())
                            || "lessThanOrEqual".equals(c.getOperator())) {
                        values.add(c.getValue());
                    }
                }
            }
        }

        if (values.isEmpty()) return candidates;

        for (Object splitValue : values) {
            String operator;
            if ("BOOLEAN".equals(typeRef)) {
                operator = "equals";
            } else if ("ENUM".equals(typeRef) || "STRING".equals(typeRef)) {
                operator = "equals";
            } else {
                operator = "greaterThan";
            }

            Condition splitCondition = Condition.builder()
                    .field(fieldName)
                    .operator(operator)
                    .value(splitValue)
                    .build();

            List<RuleRow> trueRules = new ArrayList<>();
            List<RuleRow> falseRules = new ArrayList<>();
            splitRulesBinary(rules, fieldName, operator, splitValue, trueRules, falseRules);

            if (!trueRules.isEmpty() && !falseRules.isEmpty()) {
                double gain = informationGain(rules, trueRules, falseRules);
                candidates.add(new BinarySplitCandidate(fieldName, splitCondition, trueRules, falseRules, gain));
            }
        }

        return candidates;
    }

    private void splitRulesBinary(List<RuleRow> rules, String field, String operator, Object splitValue,
                                   List<RuleRow> trueRules, List<RuleRow> falseRules) {
        for (RuleRow rule : rules) {
            boolean matchesTrue = ruleMatchesBinarySplit(rule, field, operator, splitValue);
            if (matchesTrue) {
                trueRules.add(rule);
            } else {
                falseRules.add(rule);
            }
        }
    }

    private boolean ruleMatchesBinarySplit(RuleRow rule, String field, String operator, Object splitValue) {
        if (rule.getConditions() == null) return false;

        for (Condition c : rule.getConditions()) {
            if (!field.equals(c.getField())) continue;
            if ("anything".equals(c.getOperator())) return true;

            if ("equals".equals(operator)) {
                if ("equals".equals(c.getOperator())) {
                    return Objects.equals(String.valueOf(c.getValue()), String.valueOf(splitValue));
                }
            } else if ("greaterThan".equals(operator)) {
                double splitNum = toDouble(splitValue);
                if ("greaterThan".equals(c.getOperator()) || "greaterThanOrEqual".equals(c.getOperator())) {
                    return toDouble(c.getValue()) >= splitNum;
                }
                if ("between".equals(c.getOperator()) && c.getValue() instanceof List<?> range) {
                    if (range.size() == 2) {
                        return toDouble(range.get(0)) > splitNum;
                    }
                }
                if ("lessThan".equals(c.getOperator()) || "lessThanOrEqual".equals(c.getOperator())) {
                    return false;
                }
            }
        }
        return false;
    }

    // ================================================================
    // 資訊理論
    // ================================================================

    private double informationGain(List<RuleRow> parent, List<RuleRow> trueChild, List<RuleRow> falseChild) {
        double parentEntropy = entropy(parent);
        double trueWeight = (double) trueChild.size() / parent.size();
        double falseWeight = (double) falseChild.size() / parent.size();
        double childEntropy = trueWeight * entropy(trueChild) + falseWeight * entropy(falseChild);
        return parentEntropy - childEntropy;
    }

    private double informationGainMultiWay(List<RuleRow> parent, List<SplitGroup> groups) {
        double parentEntropy = entropy(parent);
        double childEntropy = 0.0;
        int totalSize = parent.size();
        for (SplitGroup group : groups) {
            double weight = (double) group.rules.size() / totalSize;
            childEntropy += weight * entropy(group.rules);
        }
        return parentEntropy - childEntropy;
    }

    private double entropy(List<RuleRow> rules) {
        if (rules.isEmpty()) return 0.0;

        Map<String, Integer> resultCounts = new HashMap<>();
        for (RuleRow rule : rules) {
            String key = rule.getResults() != null ? rule.getResults().toString() : "null";
            resultCounts.merge(key, 1, Integer::sum);
        }

        double entropy = 0.0;
        for (int count : resultCounts.values()) {
            double p = (double) count / rules.size();
            if (p > 0) {
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }

    // ================================================================
    // Condition 取反（binary split 用）
    // ================================================================

    private Condition negateCondition(Condition cond) {
        if (cond == null) return null;
        String op = cond.getOperator();
        if (op == null) return Condition.builder().field(cond.getField()).build();

        String negated = switch (op) {
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

        return Condition.builder()
                .field(cond.getField())
                .operator(negated)
                .value(cond.getValue())
                .build();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private boolean allSameResult(List<RuleRow> rules) {
        if (rules.size() <= 1) return true;
        String first = rules.get(0).getResults() != null ? rules.get(0).getResults().toString() : "";
        return rules.stream().allMatch(r ->
                (r.getResults() != null ? r.getResults().toString() : "").equals(first));
    }

    private List<Result> mostCommonResult(List<RuleRow> rules) {
        Map<String, List<Result>> resultMap = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RuleRow rule : rules) {
            String key = rule.getResults() != null ? rule.getResults().toString() : "null";
            resultMap.putIfAbsent(key, rule.getResults());
            counts.merge(key, 1, Integer::sum);
        }
        String maxKey = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        return maxKey != null && resultMap.containsKey(maxKey)
                ? resultMap.get(maxKey)
                : rules.get(0).getResults();
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int getDepth(TreeNode node) {
        if (node == null) return 0;
        int maxChildDepth = 0;
        // N-ary branches
        if (node.getBranches() != null && !node.getBranches().isEmpty()) {
            for (Branch branch : node.getBranches()) {
                maxChildDepth = Math.max(maxChildDepth, getDepth(branch.getChild()));
            }
        }
        // 向後相容
        maxChildDepth = Math.max(maxChildDepth, getDepth(node.getTrueBranch()));
        maxChildDepth = Math.max(maxChildDepth, getDepth(node.getFalseBranch()));
        return 1 + maxChildDepth;
    }

    // ================================================================
    // Data Records
    // ================================================================

    private record SplitGroup(Object value, List<RuleRow> rules) {}

    private record MultiWaySplitCandidate(String field, List<SplitGroup> groups, double gain) {}

    private record BinarySplitCandidate(
            String field,
            Condition condition,
            List<RuleRow> trueRules,
            List<RuleRow> falseRules,
            double gain
    ) {}
}
