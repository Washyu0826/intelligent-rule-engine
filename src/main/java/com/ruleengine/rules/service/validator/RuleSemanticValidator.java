package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.service.validator.ValidationContext.ConditionInfo;
import com.ruleengine.rules.service.validator.ValidationContext.ParsedRule;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes.*;

/**
 * Layer 3 — 逐條規則語義驗證。
 *
 * 驗證每條規則的 conditions 和 results：
 *   - MISSING_FIELD: ruleId, conditions, results 缺失
 *   - DUPLICATE_ID: ruleId 重複
 *   - UNKNOWN_FIELD: 引用未定義的欄位
 *   - UNKNOWN_OPERATOR: 不支援的 operator
 *   - TYPE_MISMATCH: operator/typeRef 不相容、value 型別不正確
 *   - INVALID_ENUM_VALUE: ENUM 值不在 allowedValues 中
 *   - MISSING_RESULTS: 缺少 output 結果
 *
 * 同時填入 parsedRules 和 referencedInputs 供 Layer 4 使用。
 */
@Component
@Order(3)
public class RuleSemanticValidator implements ValidationLayer {

    // Operator 與 typeRef 的相容矩陣
    private static final Set<String> NUMERIC_DATE_OPERATORS = Set.of(
            Operators.GREATER_THAN, Operators.GREATER_THAN_OR_EQUAL,
            Operators.LESS_THAN, Operators.LESS_THAN_OR_EQUAL,
            Operators.BETWEEN
    );
    // v3.14: 加入 TIMESTAMP，否則 TIMESTAMP 欄位用 greaterThan / between 等 operator 會誤報 TYPE_MISMATCH。
    private static final Set<String> NUMERIC_DATE_TYPES = Set.of(
            TypeRefs.INTEGER, TypeRefs.DECIMAL, TypeRefs.DATE, TypeRefs.TIMESTAMP
    );
    private static final Set<String> IN_OPERATORS = Set.of(Operators.IN, Operators.NOT_IN);
    private static final Set<String> IN_TYPES = Set.of(TypeRefs.STRING, TypeRefs.ENUM);
    private static final Set<String> NO_VALUE_OPERATORS = Set.of(
            Operators.IS_NULL, Operators.IS_NOT_NULL, Operators.ANYTHING
    );

    @Override
    public List<ValidationError> validate(JsonNode envelope, ValidationContext context) {
        List<ValidationError> errors = new ArrayList<>();

        if (!context.isShouldContinue()) return errors;

        // v3.14: 區域變數（VARIABLE typeRef）僅供 outputs，不可出現於 inputs。
        // 對應 Group xlsx 規格 sheet「使用說明」: 8.variable - 區域變數 (條件欄位不開放)。
        // 不論 envelope 是否帶 extensions block，此規則皆生效。
        for (Map.Entry<String, String> e : context.getInputTypes().entrySet()) {
            if (TypeRefs.VARIABLE.equals(e.getValue())) {
                errors.add(err(INVALID_VARIABLE_IN_CONDITION,
                        "input field '" + e.getKey()
                                + "' uses typeRef=VARIABLE which is reserved for output fields"
                                + " (Group xlsx 區域變數)"));
            }
        }

        JsonNode rulesNode = context.getRuleNode().get("rules");
        Set<String> seenRuleIds = new LinkedHashSet<>();

        for (int idx = 0; idx < rulesNode.size(); idx++) {
            JsonNode row = rulesNode.get(idx);
            String ruleId = row.has("ruleId") ? row.get("ruleId").asText() : "(未命名 rules[" + idx + "])";

            // MISSING_FIELD: ruleId
            if (!row.has("ruleId") || row.get("ruleId").asText().isBlank()) {
                errors.add(err(MISSING_FIELD, "rules[" + idx + "] 缺少 ruleId"));
            }

            // DUPLICATE_ID
            if (!seenRuleIds.add(ruleId) && row.has("ruleId")) {
                errors.add(err(DUPLICATE_ID, "ruleId \"" + ruleId + "\" 重複出現"));
            }

            // conditions 驗證
            Map<String, ConditionInfo> condMap = new LinkedHashMap<>();
            if (!row.has("conditions") || !row.get("conditions").isArray()) {
                errors.add(err(MISSING_FIELD, ruleId + " 缺少 conditions 陣列"));
            } else {
                Set<String> condFieldsInThisRule = new HashSet<>();
                for (JsonNode cond : row.get("conditions")) {
                    String condField = cond.has("field") ? cond.get("field").asText() : null;
                    if (condField != null && !condFieldsInThisRule.add(condField)) {
                        errors.add(err(INCONSISTENT_TABLE,
                                ruleId + " 的 conditions 中欄位 \"" + condField + "\" 出現了多次。"
                                        + "同一條規則中，每個欄位只應出現一次 condition"));
                    }
                    ConditionInfo ci = validateCondition(cond, ruleId, context, errors);
                    if (ci != null) {
                        condMap.put(ci.field(), ci);
                        context.getReferencedInputs().add(ci.field());
                    }
                }
            }

            // results 驗證
            if (!row.has("results") || !row.get("results").isArray()) {
                errors.add(err(MISSING_RESULTS, ruleId + " 缺少 results 陣列"));
            } else {
                validateResults(row.get("results"), ruleId, context, errors);
            }

            context.getParsedRules().add(new ParsedRule(ruleId, condMap));
        }

        return errors;
    }

    // ================================================================
    // 單一 Condition 驗證
    // ================================================================

    private ConditionInfo validateCondition(JsonNode cond, String ruleId,
                                             ValidationContext context,
                                             List<ValidationError> errors) {
        Map<String, String> inputTypes = context.getInputTypes();
        Map<String, List<String>> inputAllowed = context.getInputAllowed();

        // field 必填
        if (!cond.has("field") || cond.get("field").asText().isBlank()) {
            errors.add(err(MISSING_FIELD, ruleId + " 有一個 condition 缺少 field"));
            return null;
        }
        String field = cond.get("field").asText();

        // operator 必填
        if (!cond.has("operator") || cond.get("operator").asText().isBlank()) {
            errors.add(err(MISSING_FIELD, ruleId + " condition[" + field + "] 缺少 operator"));
            return null;
        }
        String operator = cond.get("operator").asText();
        JsonNode value = cond.get("value");
        JsonNode valueRefNode = cond.get("valueRef");
        boolean hasValueRef = valueRefNode != null && !valueRefNode.isNull()
                && !valueRefNode.asText().isBlank();
        String valueRef = hasValueRef ? valueRefNode.asText().trim() : null;

        // UNKNOWN_FIELD
        if (!inputTypes.containsKey(field)) {
            errors.add(err(UNKNOWN_FIELD,
                    ruleId + " condition 引用了未定義的 input 欄位 \"" + field + "\"。"
                            + "已定義的 inputs：" + inputTypes.keySet()));
            return null;
        }

        // UNKNOWN_OPERATOR
        if (!Operators.ALL.contains(operator)) {
            errors.add(err(UNKNOWN_OPERATOR,
                    ruleId + " condition[" + field + "] 使用了不支援的 operator \"" + operator + "\"。"
                            + "支援的 operator：" + Operators.ALL));
            return new ConditionInfo(field, operator, value);
        }

        String typeRef = inputTypes.get(field);

        // TYPE_MISMATCH: operator 與 typeRef 相容性
        if (NUMERIC_DATE_OPERATORS.contains(operator) && !NUMERIC_DATE_TYPES.contains(typeRef)) {
            errors.add(err(TYPE_MISMATCH,
                    ruleId + " condition[" + field + "] operator \"" + operator
                            + "\" 僅適用 INTEGER/DECIMAL/DATE/TIMESTAMP，但此欄位 typeRef=" + typeRef));
        }
        if (IN_OPERATORS.contains(operator) && !IN_TYPES.contains(typeRef)) {
            errors.add(err(TYPE_MISMATCH,
                    ruleId + " condition[" + field + "] operator \"" + operator
                            + "\" 僅適用 STRING/ENUM，但此欄位 typeRef=" + typeRef));
        }

        // v3.12: valueRef 與 value 互斥
        if (hasValueRef && value != null && !value.isNull()) {
            errors.add(err(INCONSISTENT_TABLE,
                    ruleId + " condition[" + field + "] 同時提供了 value 與 valueRef，僅能擇一"));
        }

        // v3.12: valueRef 不可用於不需要值的 operator
        if (NO_VALUE_OPERATORS.contains(operator) && hasValueRef) {
            errors.add(err(INCONSISTENT_TABLE,
                    ruleId + " condition[" + field + "] operator \"" + operator
                            + "\" 不需要值，不應使用 valueRef"));
        }

        // v3.12: valueRef 格式與型別檢查
        if (hasValueRef && !NO_VALUE_OPERATORS.contains(operator)) {
            validateValueRef(ruleId, field, valueRef, typeRef, inputTypes, errors);
            // v3.14: 跨欄位 valueRef 也算「引用」該 input，避免 ConsistencyValidator 誤報 INCONSISTENT_TABLE
            if (!valueRef.startsWith("$") && inputTypes.containsKey(valueRef)) {
                context.getReferencedInputs().add(valueRef);
            }
        }

        // TYPE_MISMATCH: value 型態檢查
        if (!NO_VALUE_OPERATORS.contains(operator)) {
            if ((value == null || value.isNull()) && !hasValueRef) {
                errors.add(err(TYPE_MISMATCH,
                        ruleId + " condition[" + field + "] operator \"" + operator
                                + "\" 需要 value 或 valueRef 但兩者皆未提供"));
            } else if (value != null && !value.isNull()) {
                validateValueType(ruleId, field, operator, value, typeRef, errors);
            }
        } else if (value != null && !value.isNull()) {
            errors.add(err(INCONSISTENT_TABLE,
                    ruleId + " condition[" + field + "] operator \"" + operator
                            + "\" 不需要 value，但提供了 value=" + value.asText()
                            + "。此 value 會被忽略，建議移除以避免混淆"));
        }

        // INVALID_ENUM_VALUE
        if (TypeRefs.ENUM.equals(typeRef) && inputAllowed.containsKey(field) && value != null) {
            checkEnumValue(ruleId, "conditions", field, value, operator, inputAllowed.get(field), errors);
        }

        return new ConditionInfo(field, operator, value);
    }

    // ================================================================
    // valueRef 驗證（v3.12 跨欄位 / 相對日期）
    // ================================================================

    private static final java.util.regex.Pattern VALUE_REF_RELATIVE_DATE =
            java.util.regex.Pattern.compile("^\\$today(\\s*[+-]\\s*\\d+\\s*[dmyDMY])?$");

    private void validateValueRef(String ruleId, String field, String valueRef,
                                   String typeRef, Map<String, String> inputTypes,
                                   List<ValidationError> errors) {
        String label = ruleId + " condition[" + field + "].valueRef";
        if (valueRef == null || valueRef.isBlank()) {
            errors.add(err(MISSING_FIELD, label + " 不可為空"));
            return;
        }
        // $today / $today±Nd / $today±Nm / $today±Ny → 必須對 DATE 或 TIMESTAMP 欄位使用
        if (valueRef.startsWith("$")) {
            if (!VALUE_REF_RELATIVE_DATE.matcher(valueRef).matches()) {
                errors.add(err(TYPE_MISMATCH,
                        label + " 相對日期格式必須是 \"$today\" 或 \"$today±N[d|m|y]\"，收到：" + valueRef));
                return;
            }
            // v3.14: TIMESTAMP 也支援 $today 家族
            if (!TypeRefs.DATE.equals(typeRef) && !TypeRefs.TIMESTAMP.equals(typeRef)) {
                errors.add(err(TYPE_MISMATCH,
                        label + " 相對日期僅適用於 typeRef=DATE 或 TIMESTAMP，目前欄位 typeRef=" + typeRef));
            }
            return;
        }
        // 跨欄位參照：必須指向已定義的 input
        if (!inputTypes.containsKey(valueRef)) {
            errors.add(err(UNKNOWN_FIELD,
                    label + " 引用了未定義的 input 欄位 \"" + valueRef + "\"。"
                            + "已定義的 inputs：" + inputTypes.keySet()));
            return;
        }
        // 跨欄位型別必須相容（同型 or 兩者皆數值類）
        String refType = inputTypes.get(valueRef);
        if (!isCompatibleType(typeRef, refType)) {
            errors.add(err(TYPE_MISMATCH,
                    label + " 欄位 typeRef=" + typeRef + " 與被參照欄位 \"" + valueRef
                            + "\" typeRef=" + refType + " 不相容"));
        }
    }

    private boolean isCompatibleType(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        boolean aNum = TypeRefs.INTEGER.equals(a) || TypeRefs.DECIMAL.equals(a);
        boolean bNum = TypeRefs.INTEGER.equals(b) || TypeRefs.DECIMAL.equals(b);
        return aNum && bNum;
    }

    // ================================================================
    // value 型態深度驗證
    // ================================================================

    private void validateValueType(String ruleId, String field, String operator,
                                    JsonNode value, String typeRef, List<ValidationError> errors) {
        String label = ruleId + " condition[" + field + "]";

        // between: value 必須是 [min, max]
        if (Operators.BETWEEN.equals(operator)) {
            if (!value.isArray() || value.size() != 2) {
                errors.add(err(TYPE_MISMATCH,
                        label + " operator=between 的 value 必須是 [min, max] 陣列（長度 2）"));
                return;
            }
            validateSingleValueType(label + ".value[0]", value.get(0), typeRef, errors);
            validateSingleValueType(label + ".value[1]", value.get(1), typeRef, errors);

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
                        label + " operator=" + operator + " 的 value 必須是陣列（如 [\"a\",\"b\"]）"));
                return;
            }
            if (value.isEmpty()) {
                errors.add(err(TYPE_MISMATCH,
                        label + " operator=" + operator + " 的 value 是空陣列，至少需要一個元素"));
                return;
            }
            for (int i = 0; i < value.size(); i++) {
                validateSingleValueType(label + ".value[" + i + "]", value.get(i), typeRef, errors);
            }
            return;
        }

        // 單一值
        validateSingleValueType(label, value, typeRef, errors);
    }

    private void validateSingleValueType(String label, JsonNode value, String typeRef,
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
            // v3.14: TIMESTAMP — 接受 ISO-8601 Instant / OffsetDateTime / LocalDateTime 字串
            case TypeRefs.TIMESTAMP -> {
                if (!value.isTextual()) {
                    errors.add(err(TYPE_MISMATCH, label + " typeRef=TIMESTAMP 但 value 不是字串"));
                } else if (!isParsableTimestamp(value.asText())) {
                    errors.add(err(TYPE_MISMATCH,
                            label + " typeRef=TIMESTAMP 但 value \"" + value.asText()
                                    + "\" 不是 ISO-8601 timestamp（如 2026-01-31T08:00:00Z）"));
                }
            }
            // v3.14: VARIABLE — 不做型別檢查（區域變數本質上 opaque）
            case TypeRefs.VARIABLE -> { /* no-op */ }
            // STRING / ENUM: 任何值都接受
        }
    }

    /**
     * v3.14：TIMESTAMP 容忍三種 ISO-8601 表示：
     *   - Instant：2026-01-31T08:00:00Z
     *   - OffsetDateTime：2026-01-31T08:00:00+08:00
     *   - LocalDateTime：2026-01-31T08:00:00
     */
    private boolean isParsableTimestamp(String raw) {
        try { Instant.parse(raw); return true; } catch (DateTimeParseException ignored) { /* try next */ }
        try { OffsetDateTime.parse(raw); return true; } catch (DateTimeParseException ignored) { /* try next */ }
        try { LocalDateTime.parse(raw); return true; } catch (DateTimeParseException ignored) { /* no */ }
        return false;
    }

    // ================================================================
    // ENUM value 白名單驗證
    // ================================================================

    private void checkEnumValue(String ruleId, String section, String field,
                                 JsonNode value, String operator,
                                 List<String> allowed, List<ValidationError> errors) {
        String label = ruleId + " " + section + "." + field;

        if (Operators.IN.equals(operator) || Operators.NOT_IN.equals(operator)) {
            if (value.isArray()) {
                for (JsonNode v : value) {
                    if (!allowed.contains(v.asText())) {
                        errors.add(err(INVALID_ENUM_VALUE,
                                label + " 值 \"" + v.asText() + "\" 不在 allowedValues " + allowed + " 內"));
                    }
                }
            }
        } else if (Operators.EQUALS.equals(operator) || Operators.NOT_EQUALS.equals(operator)) {
            if (value.isTextual() && !allowed.contains(value.asText())) {
                errors.add(err(INVALID_ENUM_VALUE,
                        label + " 值 \"" + value.asText() + "\" 不在 allowedValues " + allowed + " 內"));
            }
        }
    }

    // ================================================================
    // Results 驗證
    // ================================================================

    private void validateResults(JsonNode resultsNode, String ruleId,
                                  ValidationContext context,
                                  List<ValidationError> errors) {
        Map<String, String> outputTypes = context.getOutputTypes();
        Map<String, List<String>> outputAllowed = context.getOutputAllowed();
        Set<String> coveredOutputs = new HashSet<>();

        for (JsonNode res : resultsNode) {
            if (!res.has("field") || res.get("field").asText().isBlank()) {
                errors.add(err(MISSING_FIELD, ruleId + " 有一個 result 缺少 field"));
                continue;
            }
            String field = res.get("field").asText();

            // UNKNOWN_FIELD
            if (!outputTypes.containsKey(field)) {
                errors.add(err(UNKNOWN_FIELD,
                        ruleId + " result 引用了未定義的 output 欄位 \"" + field + "\"。"
                                + "已定義的 outputs：" + outputTypes.keySet()));
                continue;
            }

            coveredOutputs.add(field);

            // result 必須有 value 欄位
            if (!res.has("value") || res.get("value").isNull()) {
                errors.add(err(MISSING_RESULTS,
                        ruleId + " results[" + field + "] 缺少 value 欄位。"
                                + "每個 result 必須提供具體的結果值"));
                continue;
            }

            // INVALID_ENUM_VALUE
            if (outputAllowed.containsKey(field) && res.has("value")) {
                String val = res.get("value").asText();
                List<String> allowed = outputAllowed.get(field);
                if (!allowed.contains(val)) {
                    errors.add(err(INVALID_ENUM_VALUE,
                            ruleId + " results." + field + " 值 \"" + val
                                    + "\" 不在 allowedValues " + allowed + " 內"));
                }
            }

            // result value 的 typeRef 驗證
            if (res.has("value") && outputTypes.containsKey(field)) {
                String typeRef = outputTypes.get(field);
                JsonNode val = res.get("value");
                String label = ruleId + " results[" + field + "]";
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
        }

        // MISSING_RESULTS：每個 output 欄位都必須在 results 中出現
        for (String outputName : outputTypes.keySet()) {
            if (!coveredOutputs.contains(outputName)) {
                errors.add(err(MISSING_RESULTS,
                        ruleId + " 缺少 output 欄位 \"" + outputName + "\" 的結果值"));
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private boolean isNumericType(String typeRef) {
        return TypeRefs.INTEGER.equals(typeRef) || TypeRefs.DECIMAL.equals(typeRef);
    }

    private ValidationError err(String code, String message) {
        return ValidationError.builder().code(code).message(message).build();
    }
}
