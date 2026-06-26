package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes.*;

/**
 * DecisionTree 驗證器 — Phase 3 完整實作 + N-ary 分支支援。
 *
 * 驗證分三階段：
 *   1. 頂層結構檢查（ruleType, rule, root, inputs, outputs）
 *   2. 欄位定義檢查（name, typeRef, ENUM allowedValues）
 *   3. 遞迴樹節點驗證
 *      - 分支節點（N-ary）：branches 陣列，每個 branch 有 label + condition + child
 *      - 分支節點（二元）：向後相容 trueBranch + falseBranch
 *      - 葉節點：必須有非空 results
 *      - condition 驗證：UNKNOWN_FIELD, UNKNOWN_OPERATOR, TYPE_MISMATCH, INVALID_ENUM_VALUE
 *      - results 驗證：UNKNOWN_FIELD, MISSING_RESULTS, INVALID_ENUM_VALUE, TYPE_MISMATCH
 *      - 深度限制（rules.validation.max-tree-depth）
 *      - nodeId 重複偵測（DUPLICATE_ID）
 *
 * 負責的錯誤碼：
 * - MISSING_FIELD：缺少必要欄位
 * - MISSING_BRANCH：分支節點缺少子節點（branches 少於 2 個或 trueBranch/falseBranch 缺失）
 * - MISSING_RESULTS：葉節點缺少 results
 * - UNKNOWN_FIELD：condition/result 引用了不在 inputs/outputs 中的欄位
 * - UNKNOWN_OPERATOR：不支援的 operator
 * - TYPE_MISMATCH：operator/typeRef 不相容、value 型別錯誤
 * - DUPLICATE_ID：nodeId 重複
 * - ENUM_VALUE_MISSING：ENUM 型別缺少 allowedValues
 * - INVALID_ENUM_VALUE：ENUM 值不在 allowedValues 中
 */
@Component
@Slf4j
public class DecisionTreeValidator implements RuleValidator {

    @Value("${rules.validation.max-tree-depth:10}")
    private int maxTreeDepth;

    // Operator 與 typeRef 相容矩陣（與 RuleSemanticValidator 一致）
    private static final Set<String> NUMERIC_DATE_OPERATORS = Set.of(
            Operators.GREATER_THAN, Operators.GREATER_THAN_OR_EQUAL,
            Operators.LESS_THAN, Operators.LESS_THAN_OR_EQUAL,
            Operators.BETWEEN
    );
    private static final Set<String> NUMERIC_DATE_TYPES = Set.of(
            TypeRefs.INTEGER, TypeRefs.DECIMAL, TypeRefs.DATE
    );
    private static final Set<String> IN_OPERATORS = Set.of(Operators.IN, Operators.NOT_IN);
    private static final Set<String> IN_TYPES = Set.of(TypeRefs.STRING, TypeRefs.ENUM);
    private static final Set<String> NO_VALUE_OPERATORS = Set.of(
            Operators.IS_NULL, Operators.IS_NOT_NULL, Operators.ANYTHING
    );

    @Override
    public RuleType supportedType() {
        return RuleType.DECISION_TREE;
    }

    @Override
    public List<ValidationError> validate(JsonNode envelope) {
        List<ValidationError> errors = new ArrayList<>();

        // ================================================================
        // Phase 1: 頂層結構檢查
        // ================================================================

        if (envelope == null || envelope.isNull()) {
            errors.add(err(MISSING_FIELD, "輸入為 null，無法驗證"));
            return errors;
        }

        if (!envelope.has("ruleType")) {
            errors.add(err(MISSING_FIELD, "缺少必要欄位 \"ruleType\""));
        } else {
            String rt = envelope.get("ruleType").asText();
            if (!"DecisionTree".equals(rt)) {
                errors.add(err(TYPE_MISMATCH,
                        "ruleType 值 \"" + rt + "\" 不正確。此 validator 只接受 \"DecisionTree\""));
            }
        }

        if (!envelope.has("rule") || envelope.get("rule").isNull()) {
            errors.add(err(MISSING_FIELD, "缺少必要欄位 \"rule\""));
            return errors;
        }

        JsonNode rule = envelope.get("rule");

        // DecisionTree 必須有 root
        if (!rule.has("root") || rule.get("root").isNull()) {
            errors.add(err(MISSING_FIELD, "DecisionTree 缺少 \"root\" 節點"));
            return errors;
        }

        // inputs 必須存在
        if (!rule.has("inputs") || !rule.get("inputs").isArray()) {
            errors.add(err(MISSING_FIELD, "缺少必要欄位 \"inputs\""));
            return errors;
        }

        // outputs 必須存在
        if (!rule.has("outputs") || !rule.get("outputs").isArray()) {
            errors.add(err(MISSING_FIELD, "缺少必要欄位 \"outputs\""));
            return errors;
        }

        // ================================================================
        // Phase 2: 欄位定義檢查
        // ================================================================

        Map<String, String> inputTypes = new LinkedHashMap<>();
        Map<String, List<String>> inputAllowed = new LinkedHashMap<>();
        Map<String, String> outputTypes = new LinkedHashMap<>();
        Map<String, List<String>> outputAllowed = new LinkedHashMap<>();

        parseFieldDefs(rule.get("inputs"), "inputs", inputTypes, inputAllowed, errors);
        parseFieldDefs(rule.get("outputs"), "outputs", outputTypes, outputAllowed, errors);

        // inputs 和 outputs 名稱不重複
        Set<String> overlapping = new LinkedHashSet<>(inputTypes.keySet());
        overlapping.retainAll(outputTypes.keySet());
        for (String name : overlapping) {
            errors.add(err(INCONSISTENT_TABLE,
                    "欄位名稱 \"" + name + "\" 同時出現在 inputs 和 outputs 中"));
        }

        if (inputTypes.isEmpty()) {
            errors.add(err(MISSING_FIELD, "inputs 為空陣列，至少需要定義一個輸入欄位"));
        }
        if (outputTypes.isEmpty()) {
            errors.add(err(MISSING_FIELD, "outputs 為空陣列，至少需要定義一個輸出欄位"));
        }

        // ================================================================
        // Phase 3: 遞迴樹節點驗證
        // ================================================================

        Set<String> seenNodeIds = new HashSet<>();
        TreeValidationContext ctx = new TreeValidationContext(
                inputTypes, inputAllowed, outputTypes, outputAllowed, seenNodeIds);

        validateNode(rule.get("root"), "/rule/root", 1, ctx, errors);

        log.info("DecisionTree 驗證完成：{} 個錯誤", errors.size());
        return errors;
    }

    @Override
    public List<ValidationError> checkConsistency(JsonNode payload) {
        return validate(payload);
    }

    // ================================================================
    // 遞迴驗證 TreeNode
    // ================================================================

    private void validateNode(JsonNode node, String path, int depth,
                               TreeValidationContext ctx, List<ValidationError> errors) {
        if (node == null || node.isNull()) {
            errors.add(err(MISSING_BRANCH, path + " 節點為空"));
            return;
        }

        // 深度限制
        if (depth > maxTreeDepth) {
            errors.add(err(TYPE_MISMATCH,
                    path + " 樹深度 " + depth + " 超過最大限制 " + maxTreeDepth));
            return;
        }

        // nodeId 重複偵測
        if (node.has("nodeId") && !node.get("nodeId").isNull()) {
            String nodeId = node.get("nodeId").asText();
            if (!nodeId.isBlank() && !ctx.seenNodeIds.add(nodeId)) {
                errors.add(err(DUPLICATE_ID, "nodeId \"" + nodeId + "\" 重複出現（路徑：" + path + "）"));
            }
        }

        boolean hasCondition = node.has("condition") && !node.get("condition").isNull();
        boolean hasBranches = node.has("branches") && node.get("branches").isArray()
                && !node.get("branches").isEmpty();
        boolean hasTrueBranch = node.has("trueBranch") && !node.get("trueBranch").isNull();
        boolean hasFalseBranch = node.has("falseBranch") && !node.get("falseBranch").isNull();
        boolean hasResults = node.has("results") && !node.get("results").isNull();

        // 判斷是否為分支節點
        boolean isBranchNode = hasCondition || hasBranches || hasTrueBranch || hasFalseBranch;

        if (!isBranchNode && !hasResults) {
            errors.add(err(MISSING_BRANCH,
                    path + " 無法識別為分支節點或葉節點（需要 condition/branches 或 results）"));
            return;
        }

        if (isBranchNode) {
            // === 分支節點驗證 ===

            // 驗證節點層級的 condition（若存在）
            if (hasCondition) {
                validateCondition(node.get("condition"), path, ctx, errors);
            }

            // 優先使用 N-ary branches 格式
            if (hasBranches) {
                JsonNode branches = node.get("branches");
                if (branches.size() < 2) {
                    errors.add(err(MISSING_BRANCH,
                            path + " branches 至少需要 2 個分支，目前只有 " + branches.size() + " 個"));
                }
                for (int i = 0; i < branches.size(); i++) {
                    JsonNode branch = branches.get(i);
                    String branchPath = path + "/branches[" + i + "]";

                    // 驗證分支的 condition（若存在）
                    if (branch.has("condition") && !branch.get("condition").isNull()) {
                        validateCondition(branch.get("condition"), branchPath, ctx, errors);
                    }

                    // 驗證分支的 child
                    if (!branch.has("child") || branch.get("child").isNull()) {
                        String label = branch.has("label") ? branch.get("label").asText() : "branch[" + i + "]";
                        errors.add(err(MISSING_BRANCH,
                                branchPath + " (label=\"" + label + "\") 缺少 child 子節點"));
                    } else {
                        validateNode(branch.get("child"), branchPath + "/child", depth + 1, ctx, errors);
                    }
                }
            } else {
                // 向後相容：二元 trueBranch/falseBranch 格式
                if (!hasTrueBranch) {
                    errors.add(err(MISSING_BRANCH, path + " 缺少 trueBranch"));
                } else {
                    validateNode(node.get("trueBranch"), path + "/trueBranch", depth + 1, ctx, errors);
                }

                if (!hasFalseBranch) {
                    errors.add(err(MISSING_BRANCH, path + " 缺少 falseBranch"));
                } else {
                    validateNode(node.get("falseBranch"), path + "/falseBranch", depth + 1, ctx, errors);
                }
            }

            // 有分支同時有 results 是不正確的
            if (hasResults) {
                errors.add(err(INCONSISTENT_TABLE,
                        path + " 同時有 condition/branches 和 results。分支節點不應包含 results，只有葉節點才有 results"));
            }
        }

        if (!isBranchNode && hasResults) {
            // === 葉節點驗證 ===
            JsonNode results = node.get("results");
            if (!results.isArray() || results.isEmpty()) {
                errors.add(err(MISSING_RESULTS, path + " 葉節點的 results 為空"));
            } else {
                validateResults(results, path, ctx, errors);
            }
        }
    }

    // ================================================================
    // Condition 驗證
    // ================================================================

    private void validateCondition(JsonNode cond, String path,
                                    TreeValidationContext ctx, List<ValidationError> errors) {
        String condPath = path + "/condition";

        // field 必填
        if (!cond.has("field") || cond.get("field").asText().isBlank()) {
            errors.add(err(MISSING_FIELD, condPath + " 缺少 field"));
            return;
        }
        String field = cond.get("field").asText();

        // operator 必填
        if (!cond.has("operator") || cond.get("operator").asText().isBlank()) {
            errors.add(err(MISSING_FIELD, condPath + "[" + field + "] 缺少 operator"));
            return;
        }
        String operator = cond.get("operator").asText();
        JsonNode value = cond.get("value");

        // UNKNOWN_FIELD
        if (!ctx.inputTypes.containsKey(field)) {
            errors.add(err(UNKNOWN_FIELD,
                    condPath + " 引用了未定義的 input 欄位 \"" + field + "\"。"
                            + "已定義的 inputs：" + ctx.inputTypes.keySet()));
            return;
        }

        // UNKNOWN_OPERATOR
        if (!Operators.ALL.contains(operator)) {
            errors.add(err(UNKNOWN_OPERATOR,
                    condPath + "[" + field + "] 使用了不支援的 operator \"" + operator + "\""));
            return;
        }

        String typeRef = ctx.inputTypes.get(field);

        // TYPE_MISMATCH: operator 與 typeRef 相容性
        if (NUMERIC_DATE_OPERATORS.contains(operator) && !NUMERIC_DATE_TYPES.contains(typeRef)) {
            errors.add(err(TYPE_MISMATCH,
                    condPath + "[" + field + "] operator \"" + operator
                            + "\" 僅適用 INTEGER/DECIMAL/DATE，但此欄位 typeRef=" + typeRef));
        }
        if (IN_OPERATORS.contains(operator) && !IN_TYPES.contains(typeRef)) {
            errors.add(err(TYPE_MISMATCH,
                    condPath + "[" + field + "] operator \"" + operator
                            + "\" 僅適用 STRING/ENUM，但此欄位 typeRef=" + typeRef));
        }

        // value 型態檢查
        if (!NO_VALUE_OPERATORS.contains(operator)) {
            if (value == null || value.isNull()) {
                errors.add(err(TYPE_MISMATCH,
                        condPath + "[" + field + "] operator \"" + operator + "\" 需要 value 但未提供"));
            } else {
                validateValueType(condPath + "[" + field + "]", operator, value, typeRef, errors);
            }
        }

        // INVALID_ENUM_VALUE
        if (TypeRefs.ENUM.equals(typeRef) && ctx.inputAllowed.containsKey(field) && value != null && !value.isNull()) {
            checkEnumValue(condPath, field, value, operator, ctx.inputAllowed.get(field), errors);
        }
    }

    // ================================================================
    // Results 驗證
    // ================================================================

    private void validateResults(JsonNode resultsNode, String path,
                                  TreeValidationContext ctx, List<ValidationError> errors) {
        String resultsPath = path + "/results";
        Set<String> coveredOutputs = new HashSet<>();

        for (int i = 0; i < resultsNode.size(); i++) {
            JsonNode res = resultsNode.get(i);

            if (!res.has("field") || res.get("field").asText().isBlank()) {
                errors.add(err(MISSING_FIELD, resultsPath + "[" + i + "] 缺少 field"));
                continue;
            }
            String field = res.get("field").asText();

            // UNKNOWN_FIELD
            if (!ctx.outputTypes.containsKey(field)) {
                errors.add(err(UNKNOWN_FIELD,
                        resultsPath + " 引用了未定義的 output 欄位 \"" + field + "\"。"
                                + "已定義的 outputs：" + ctx.outputTypes.keySet()));
                continue;
            }

            coveredOutputs.add(field);

            // value 必填
            if (!res.has("value") || res.get("value").isNull()) {
                errors.add(err(MISSING_RESULTS,
                        resultsPath + "[" + field + "] 缺少 value 欄位"));
                continue;
            }

            // INVALID_ENUM_VALUE
            if (ctx.outputAllowed.containsKey(field)) {
                String val = res.get("value").asText();
                List<String> allowed = ctx.outputAllowed.get(field);
                if (!allowed.contains(val)) {
                    errors.add(err(INVALID_ENUM_VALUE,
                            resultsPath + "." + field + " 值 \"" + val
                                    + "\" 不在 allowedValues " + allowed + " 內"));
                }
            }

            // result value 的 typeRef 驗證
            String typeRef = ctx.outputTypes.get(field);
            JsonNode val = res.get("value");
            String label = resultsPath + "[" + field + "]";
            switch (typeRef) {
                case TypeRefs.INTEGER -> {
                    if (val.isNumber() && val.asDouble() != Math.floor(val.asDouble())) {
                        errors.add(err(TYPE_MISMATCH, label + " typeRef=INTEGER 但 value 有小數點"));
                    }
                }
                case TypeRefs.DECIMAL -> {
                    if (!val.isNumber()) {
                        errors.add(err(TYPE_MISMATCH, label + " typeRef=DECIMAL 但 value 不是數值"));
                    }
                }
                case TypeRefs.BOOLEAN -> {
                    if (!val.isBoolean()) {
                        errors.add(err(TYPE_MISMATCH, label + " typeRef=BOOLEAN 但 value 不是 true/false"));
                    }
                }
            }
        }

        // MISSING_RESULTS：每個 output 欄位都必須在 results 中出現
        for (String outputName : ctx.outputTypes.keySet()) {
            if (!coveredOutputs.contains(outputName)) {
                errors.add(err(MISSING_RESULTS,
                        path + " 葉節點缺少 output 欄位 \"" + outputName + "\" 的結果值"));
            }
        }
    }

    // ================================================================
    // value 型態驗證
    // ================================================================

    private void validateValueType(String label, String operator,
                                    JsonNode value, String typeRef, List<ValidationError> errors) {
        // between: value 必須是 [min, max]
        if (Operators.BETWEEN.equals(operator)) {
            if (!value.isArray() || value.size() != 2) {
                errors.add(err(TYPE_MISMATCH,
                        label + " operator=between 的 value 必須是 [min, max] 陣列（長度 2）"));
                return;
            }
            validateSingleValue(label + ".value[0]", value.get(0), typeRef, errors);
            validateSingleValue(label + ".value[1]", value.get(1), typeRef, errors);
            if (isNumericType(typeRef) && value.get(0).isNumber() && value.get(1).isNumber()) {
                if (value.get(0).asDouble() > value.get(1).asDouble()) {
                    errors.add(err(TYPE_MISMATCH,
                            label + " between 的 min(" + value.get(0) + ") > max(" + value.get(1) + ")"));
                }
            }
            return;
        }

        // in / notIn: value 必須是非空陣列
        if (IN_OPERATORS.contains(operator)) {
            if (!value.isArray()) {
                errors.add(err(TYPE_MISMATCH,
                        label + " operator=" + operator + " 的 value 必須是陣列"));
                return;
            }
            if (value.isEmpty()) {
                errors.add(err(TYPE_MISMATCH,
                        label + " operator=" + operator + " 的 value 是空陣列"));
                return;
            }
            for (int i = 0; i < value.size(); i++) {
                validateSingleValue(label + ".value[" + i + "]", value.get(i), typeRef, errors);
            }
            return;
        }

        // 單一值
        validateSingleValue(label, value, typeRef, errors);
    }

    private void validateSingleValue(String label, JsonNode value, String typeRef,
                                      List<ValidationError> errors) {
        switch (typeRef) {
            case TypeRefs.INTEGER -> {
                if (!value.isInt() && !value.isLong()) {
                    if (value.isNumber() && value.asDouble() != Math.floor(value.asDouble())) {
                        errors.add(err(TYPE_MISMATCH, label + " typeRef=INTEGER 但 value 有小數點"));
                    } else if (!value.isNumber()) {
                        errors.add(err(TYPE_MISMATCH, label + " typeRef=INTEGER 但 value 不是整數"));
                    }
                }
            }
            case TypeRefs.DECIMAL -> {
                if (!value.isNumber()) {
                    errors.add(err(TYPE_MISMATCH, label + " typeRef=DECIMAL 但 value 不是數值"));
                }
            }
            case TypeRefs.BOOLEAN -> {
                if (!value.isBoolean()) {
                    errors.add(err(TYPE_MISMATCH,
                            label + " typeRef=BOOLEAN 但 value 不是 true/false（收到：" + value + "）"));
                }
            }
            case TypeRefs.DATE -> {
                if (!value.isTextual()) {
                    errors.add(err(TYPE_MISMATCH, label + " typeRef=DATE 但 value 不是字串"));
                } else {
                    try {
                        LocalDate.parse(value.asText());
                    } catch (DateTimeParseException e) {
                        errors.add(err(TYPE_MISMATCH,
                                label + " typeRef=DATE 但 value \"" + value.asText()
                                        + "\" 不符合 yyyy-MM-dd 格式"));
                    }
                }
            }
        }
    }

    // ================================================================
    // ENUM 值驗證
    // ================================================================

    private void checkEnumValue(String path, String field, JsonNode value, String operator,
                                 List<String> allowed, List<ValidationError> errors) {
        if (Operators.IN.equals(operator) || Operators.NOT_IN.equals(operator)) {
            if (value.isArray()) {
                for (JsonNode v : value) {
                    if (!allowed.contains(v.asText())) {
                        errors.add(err(INVALID_ENUM_VALUE,
                                path + "." + field + " 值 \"" + v.asText()
                                        + "\" 不在 allowedValues " + allowed + " 內"));
                    }
                }
            }
        } else if (Operators.EQUALS.equals(operator) || Operators.NOT_EQUALS.equals(operator)) {
            if (value.isTextual() && !allowed.contains(value.asText())) {
                errors.add(err(INVALID_ENUM_VALUE,
                        path + "." + field + " 值 \"" + value.asText()
                                + "\" 不在 allowedValues " + allowed + " 內"));
            }
        }
    }

    // ================================================================
    // 欄位定義解析（與 FieldDefinitionValidator 邏輯一致）
    // ================================================================

    private void parseFieldDefs(JsonNode fieldsNode, String section,
                                 Map<String, String> typeMap,
                                 Map<String, List<String>> allowedMap,
                                 List<ValidationError> errors) {
        Set<String> seenNames = new HashSet<>();

        for (int i = 0; i < fieldsNode.size(); i++) {
            JsonNode field = fieldsNode.get(i);
            String label = section + "[" + i + "]";

            if (!field.has("name") || field.get("name").asText().isBlank()) {
                errors.add(err(MISSING_FIELD, label + " 缺少 name"));
                continue;
            }
            String name = field.get("name").asText();
            label = section + "[" + name + "]";

            if (!seenNames.add(name)) {
                errors.add(err(DUPLICATE_ID,
                        section + " 中欄位名稱 \"" + name + "\" 重複出現"));
                continue;
            }

            if (!field.has("typeRef") || field.get("typeRef").asText().isBlank()) {
                errors.add(err(MISSING_FIELD, label + " 缺少 typeRef"));
                continue;
            }
            String typeRef = field.get("typeRef").asText();

            if (!TypeRefs.ALL.contains(typeRef)) {
                errors.add(err(TYPE_MISMATCH,
                        label + " typeRef \"" + typeRef + "\" 不是合法型別。"
                                + "合法型別：INTEGER, DECIMAL, BOOLEAN, STRING, ENUM, DATE"));
                continue;
            }

            typeMap.put(name, typeRef);

            if (TypeRefs.ENUM.equals(typeRef)) {
                if (!field.has("allowedValues")
                        || !field.get("allowedValues").isArray()
                        || field.get("allowedValues").isEmpty()) {
                    errors.add(err(ENUM_VALUE_MISSING,
                            label + " typeRef=ENUM 但缺少 allowedValues"));
                } else {
                    allowedMap.put(name, toStringList(field.get("allowedValues")));
                }
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private record TreeValidationContext(
            Map<String, String> inputTypes,
            Map<String, List<String>> inputAllowed,
            Map<String, String> outputTypes,
            Map<String, List<String>> outputAllowed,
            Set<String> seenNodeIds
    ) {}

    private List<String> toStringList(JsonNode arrayNode) {
        return StreamSupport.stream(arrayNode.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toList());
    }

    private boolean isNumericType(String typeRef) {
        return TypeRefs.INTEGER.equals(typeRef) || TypeRefs.DECIMAL.equals(typeRef);
    }

    private ValidationError err(String code, String message) {
        return ValidationError.builder().code(code).message(message).build();
    }
}
