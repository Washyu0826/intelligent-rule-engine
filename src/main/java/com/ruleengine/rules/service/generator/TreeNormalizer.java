package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * DecisionTree 正規化服務（Phase 3 + N-ary 擴充）。
 *
 * 負責修正 AI 生成的 DecisionTree 常見問題：
 *   - 二元格式轉換（trueBranch/falseBranch → branches）
 *   - 補版本號（schemaVersion / promptVersion）
 *   - 確保 ruleType = "DecisionTree"
 *   - typeRef alias 修正（int → INTEGER 等）
 *   - operator alias 修正（eq → equals 等）
 *   - 自動補 nodeId（N01, N02, ...）
 *   - BOOLEAN 字串修正（"true" → true）
 *   - INTEGER/DECIMAL 字串轉數值
 *   - ENUM allowedValues 自動推斷（從樹的所有葉節點 results + 所有 condition values）
 *   - 確保所有 List 可變
 */
@Component
@Slf4j
public class TreeNormalizer {

    private final EnvelopeNormalizer envelopeNormalizer;

    public TreeNormalizer(EnvelopeNormalizer envelopeNormalizer) {
        this.envelopeNormalizer = envelopeNormalizer;
    }

    /**
     * 對 DecisionTree RuleEnvelope 進行完整正規化（就地修改）。
     */
    public void normalize(RuleEnvelope envelope, String schemaVersion, String promptVersion) {
        // 補版本資訊
        if (envelope.getSchemaVersion() == null) envelope.setSchemaVersion(schemaVersion);
        if (envelope.getPromptVersion() == null) envelope.setPromptVersion(promptVersion);
        if (envelope.getRuleType() == null) envelope.setRuleType("DecisionTree");

        Rule rule = envelope.getRule();
        if (rule == null) return;

        // 確保 inputs/outputs 可變
        rule.setInputs(ensureMutableList(rule.getInputs()));
        rule.setOutputs(ensureMutableList(rule.getOutputs()));

        // 正規化欄位定義
        normalizeFieldDefs(rule.getInputs());
        normalizeFieldDefs(rule.getOutputs());

        // 二元格式轉 N-ary branches（必須在 normalizeNode 之前）
        TreeNode root = rule.getRoot();
        if (root != null) {
            normalizeBranches(root);
        }

        // 正規化樹節點
        if (root != null) {
            AtomicInteger nodeCounter = new AtomicInteger(1);
            normalizeNode(root, rule.getInputs(), rule.getOutputs(), nodeCounter);
        }

        // 自動推斷 ENUM allowedValues
        autoInferEnumAllowedValues(rule);
    }

    // ================================================================
    // 欄位定義正規化
    // ================================================================

    private void normalizeFieldDefs(List<FieldDef> fields) {
        for (FieldDef fd : fields) {
            if (fd.getTypeRef() != null) {
                fd.setTypeRef(envelopeNormalizer.normalizeTypeRef(fd.getTypeRef()));
            }
            if (fd.getAllowedValues() != null) {
                fd.setAllowedValues(ensureMutableList(fd.getAllowedValues()));
            }
        }
    }

    // ================================================================
    // 二元 → N-ary 轉換（向後相容）
    // ================================================================

    /**
     * 遞迴將 trueBranch/falseBranch 轉為 branches 陣列。
     * 若已有 branches 則不重複轉換。
     */
    private void normalizeBranches(TreeNode node) {
        if (node == null) return;

        // 已有 branches 則直接遞迴子節點
        if (node.getBranches() != null && !node.getBranches().isEmpty()) {
            for (Branch branch : node.getBranches()) {
                normalizeBranches(branch.getChild());
            }
            return;
        }

        // 將 trueBranch/falseBranch 轉為 branches
        if (node.getTrueBranch() != null || node.getFalseBranch() != null) {
            List<Branch> branches = new ArrayList<>();
            Condition cond = node.getCondition();

            if (node.getTrueBranch() != null) {
                branches.add(Branch.builder()
                        .label("TRUE")
                        .condition(cond)
                        .child(node.getTrueBranch())
                        .build());
            }
            if (node.getFalseBranch() != null) {
                Condition negated = cond != null ? negateCondition(cond) : null;
                branches.add(Branch.builder()
                        .label("FALSE")
                        .condition(negated)
                        .child(node.getFalseBranch())
                        .build());
            }
            node.setBranches(branches);
            node.setTrueBranch(null);
            node.setFalseBranch(null);
            log.debug("TreeNorm: 節點 {} 二元格式已轉為 {} 個 branches", node.getNodeId(), branches.size());
        }

        // 遞迴子節點
        if (node.getBranches() != null) {
            for (Branch branch : node.getBranches()) {
                normalizeBranches(branch.getChild());
            }
        }
    }

    /**
     * 條件取反（二元轉換用）。
     */
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
            default -> op; // between, anything 等特殊情況不取反，保留原樣
        };

        return Condition.builder()
                .field(cond.getField())
                .operator(negated)
                .value(cond.getValue())
                .build();
    }

    // ================================================================
    // 遞迴正規化樹節點
    // ================================================================

    private void normalizeNode(TreeNode node, List<FieldDef> inputs, List<FieldDef> outputs,
                                AtomicInteger counter) {
        if (node == null) return;

        // 自動補 nodeId
        if (node.getNodeId() == null || node.getNodeId().isBlank()) {
            node.setNodeId(String.format("N%02d", counter.getAndIncrement()));
        } else {
            counter.getAndIncrement(); // 即使已有 nodeId 也遞增計數
        }

        // 正規化 condition（節點層級的條件）
        Condition cond = node.getCondition();
        if (cond != null) {
            if (cond.getOperator() != null) {
                cond.setOperator(envelopeNormalizer.normalizeOperator(cond.getOperator()));
            }
            repairConditionValue(cond, inputs);
        }

        // 正規化 branches（N-ary 格式）
        if (node.getBranches() != null && !node.getBranches().isEmpty()) {
            for (Branch branch : node.getBranches()) {
                // 正規化分支條件
                Condition branchCond = branch.getCondition();
                if (branchCond != null) {
                    if (branchCond.getOperator() != null) {
                        branchCond.setOperator(envelopeNormalizer.normalizeOperator(branchCond.getOperator()));
                    }
                    repairConditionValue(branchCond, inputs);
                }
                // 遞迴正規化子節點
                normalizeNode(branch.getChild(), inputs, outputs, counter);
            }
        }

        // 向後相容：若仍有 trueBranch/falseBranch（未經 normalizeBranches 處理的邊界情況）
        if (node.getTrueBranch() != null) {
            normalizeNode(node.getTrueBranch(), inputs, outputs, counter);
        }
        if (node.getFalseBranch() != null) {
            normalizeNode(node.getFalseBranch(), inputs, outputs, counter);
        }

        // 正規化 results
        if (node.getResults() != null) {
            node.setResults(ensureMutableList(node.getResults()));
            for (Result res : node.getResults()) {
                repairResultValue(res, outputs);
            }
        }
    }

    // ================================================================
    // 值修復
    // ================================================================

    private void repairConditionValue(Condition cond, List<FieldDef> inputs) {
        // 展開嵌套值（複用 EnvelopeNormalizer 的邏輯）
        if (cond.getValue() != null) {
            cond.setValue(unwrapNestedValue(cond.getValue()));
        }
        if (cond.getField() == null || cond.getValue() == null) return;
        String typeRef = findTypeRef(cond.getField(), inputs);
        if (typeRef == null) return;

        // BOOLEAN 字串修正
        if ("BOOLEAN".equals(typeRef) && cond.getValue() instanceof String strVal) {
            if ("true".equalsIgnoreCase(strVal)) {
                cond.setValue(true);
                log.debug("TreeNorm: {} BOOLEAN '{}' → true", cond.getField(), strVal);
            } else if ("false".equalsIgnoreCase(strVal)) {
                cond.setValue(false);
                log.debug("TreeNorm: {} BOOLEAN '{}' → false", cond.getField(), strVal);
            }
        }

        // 數值字串修正
        if (("INTEGER".equals(typeRef) || "DECIMAL".equals(typeRef)) && cond.getValue() instanceof String strVal) {
            try {
                if ("INTEGER".equals(typeRef)) {
                    cond.setValue(Long.parseLong(strVal.trim()));
                } else {
                    cond.setValue(Double.parseDouble(strVal.trim()));
                }
                log.debug("TreeNorm: {} numeric '{}' → {}", cond.getField(), strVal, cond.getValue());
            } catch (NumberFormatException e) {
                log.warn("TreeNorm: 無法修復 {} 的值 '{}'", cond.getField(), strVal);
            }
        }

        // between 修復
        if (cond.getValue() instanceof List && "between".equals(cond.getOperator())) {
            List<?> listVal = (List<?>) cond.getValue();
            List<Object> fixed = new ArrayList<>();
            for (Object item : listVal) {
                if (item instanceof String s) {
                    try {
                        fixed.add("INTEGER".equals(typeRef) ? Long.parseLong(s.trim()) : Double.parseDouble(s.trim()));
                    } catch (NumberFormatException e) {
                        fixed.add(item);
                    }
                } else {
                    fixed.add(item);
                }
            }
            cond.setValue(fixed);
        }

        // 確保 list value 可變
        if (cond.getValue() instanceof List<?> original) {
            if (!(original instanceof ArrayList)) {
                cond.setValue(new ArrayList<>(original));
            }
        }
    }

    private void repairResultValue(Result res, List<FieldDef> outputs) {
        // 展開嵌套值
        if (res.getValue() != null) {
            res.setValue(unwrapNestedValue(res.getValue()));
        }
        if (res.getField() == null || res.getValue() == null) return;
        String typeRef = findTypeRef(res.getField(), outputs);
        if (typeRef == null) return;

        Object val = res.getValue();
        if (val instanceof String strVal) {
            try {
                if ("INTEGER".equals(typeRef)) {
                    res.setValue(Long.parseLong(strVal.trim()));
                } else if ("DECIMAL".equals(typeRef)) {
                    res.setValue(Double.parseDouble(strVal.trim()));
                } else if ("BOOLEAN".equals(typeRef)) {
                    if ("true".equalsIgnoreCase(strVal)) res.setValue(true);
                    else if ("false".equalsIgnoreCase(strVal)) res.setValue(false);
                }
            } catch (NumberFormatException e) {
                // 留給 validator 報錯
            }
        }
    }

    // ================================================================
    // ENUM allowedValues 自動推斷
    // ================================================================

    private void autoInferEnumAllowedValues(Rule rule) {
        TreeNode root = rule.getRoot();
        if (root == null) return;

        for (FieldDef fd : rule.getInputs()) {
            if ("ENUM".equals(fd.getTypeRef()) && isNullOrEmpty(fd.getAllowedValues())) {
                Set<String> values = new LinkedHashSet<>();
                collectConditionValues(root, fd.getName(), values);
                if (!values.isEmpty()) {
                    fd.setAllowedValues(new ArrayList<>(values));
                    log.info("TreeNorm: input[{}] ENUM allowedValues = {}", fd.getName(), values);
                }
            }
        }

        for (FieldDef fd : rule.getOutputs()) {
            if ("ENUM".equals(fd.getTypeRef()) && isNullOrEmpty(fd.getAllowedValues())) {
                Set<String> values = new LinkedHashSet<>();
                collectResultValues(root, fd.getName(), values);
                if (!values.isEmpty()) {
                    fd.setAllowedValues(new ArrayList<>(values));
                    log.info("TreeNorm: output[{}] ENUM allowedValues = {}", fd.getName(), values);
                }
            }
        }
    }

    private void collectConditionValues(TreeNode node, String fieldName, Set<String> values) {
        if (node == null) return;
        // 收集節點自身的 condition
        Condition cond = node.getCondition();
        if (cond != null && fieldName.equals(cond.getField()) && cond.getValue() != null) {
            if ("equals".equals(cond.getOperator()) || "notEquals".equals(cond.getOperator())) {
                values.add(String.valueOf(cond.getValue()));
            } else if ("in".equals(cond.getOperator()) && cond.getValue() instanceof List) {
                ((List<?>) cond.getValue()).forEach(v -> values.add(String.valueOf(v)));
            }
        }
        // 收集 branches 中的 condition
        if (node.getBranches() != null) {
            for (Branch branch : node.getBranches()) {
                Condition bc = branch.getCondition();
                if (bc != null && fieldName.equals(bc.getField()) && bc.getValue() != null) {
                    if ("equals".equals(bc.getOperator()) || "notEquals".equals(bc.getOperator())) {
                        values.add(String.valueOf(bc.getValue()));
                    } else if ("in".equals(bc.getOperator()) && bc.getValue() instanceof List) {
                        ((List<?>) bc.getValue()).forEach(v -> values.add(String.valueOf(v)));
                    }
                }
                collectConditionValues(branch.getChild(), fieldName, values);
            }
        }
        // 向後相容
        collectConditionValues(node.getTrueBranch(), fieldName, values);
        collectConditionValues(node.getFalseBranch(), fieldName, values);
    }

    private void collectResultValues(TreeNode node, String fieldName, Set<String> values) {
        if (node == null) return;
        if (node.getResults() != null) {
            for (Result r : node.getResults()) {
                if (fieldName.equals(r.getField()) && r.getValue() != null) {
                    values.add(String.valueOf(r.getValue()));
                }
            }
        }
        // 遍歷 branches
        if (node.getBranches() != null) {
            for (Branch branch : node.getBranches()) {
                collectResultValues(branch.getChild(), fieldName, values);
            }
        }
        // 向後相容
        collectResultValues(node.getTrueBranch(), fieldName, values);
        collectResultValues(node.getFalseBranch(), fieldName, values);
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * 展開 LLM 包裝的嵌套值物件。
     * {"type":"integer","value":60} → 60, {"enum":"x"} → "x" 等。
     */
    @SuppressWarnings("unchecked")
    private Object unwrapNestedValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("value")) return unwrapNestedValue(map.get("value"));
            if (map.containsKey("enum")) return map.get("enum");
            if (map.containsKey("string")) return String.valueOf(map.get("string"));
            if (map.containsKey("number")) return map.get("number");
            if (map.containsKey("integer")) return map.get("integer");
            if (map.containsKey("decimal")) return map.get("decimal");
            if (map.containsKey("boolean")) return map.get("boolean");
            // {"typeRef":"STRING","allowedValues":["reject"]} → "reject"
            if (map.containsKey("allowedValues") || map.containsKey("allowedvalues")) {
                Object allowed = map.containsKey("allowedValues") ? map.get("allowedValues") : map.get("allowedvalues");
                if (allowed instanceof List<?> avList && !avList.isEmpty()) {
                    if (avList.size() == 1 && !(avList.get(0) instanceof List)) return avList.get(0);
                    if (avList.size() == 1 && avList.get(0) instanceof List<?> range) return new ArrayList<>(range);
                    return new ArrayList<>(avList);
                }
                return value;
            }
            if (map.size() == 1) return unwrapNestedValue(map.values().iterator().next());
            if (map.size() == 2 && map.containsKey("typeRef")) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!"typeRef".equals(entry.getKey())) return unwrapNestedValue(entry.getValue());
                }
            }
        }
        if (value instanceof List<?> list) {
            List<Object> unwrapped = new ArrayList<>();
            boolean changed = false;
            for (Object item : list) {
                Object result = unwrapNestedValue(item);
                unwrapped.add(result);
                if (result != item) changed = true;
            }
            return changed ? unwrapped : value;
        }
        return value;
    }

    private String findTypeRef(String fieldName, List<FieldDef> fields) {
        return fields.stream()
                .filter(f -> fieldName.equals(f.getName()))
                .map(FieldDef::getTypeRef)
                .findFirst().orElse(null);
    }

    <T> List<T> ensureMutableList(List<T> list) {
        if (list == null) return new ArrayList<>();
        if (list instanceof ArrayList) return list;
        return new ArrayList<>(list);
    }

    private boolean isNullOrEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }
}
