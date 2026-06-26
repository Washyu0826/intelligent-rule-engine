package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.dto.ToolDtos.Severity;
import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.service.generator.ConditionOverlapDetector;
import com.ruleengine.rules.service.validator.ValidationContext.ConditionInfo;
import com.ruleengine.rules.service.validator.ValidationContext.ParsedRule;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes.*;

/**
 * Layer 4 — 跨規則一致性驗證。
 *
 * 檢查規則之間的一致性：
 *   - 未被引用的 input 欄位 (INCONSISTENT_TABLE)
 *   - hitPolicy 合法性
 *   - FIRST hitPolicy 下的衝突偵測 (INCONSISTENT_TABLE)
 *   - MULTI hitPolicy 下的基本合理性 (INVALID_MULTI)
 *
 * v2.0.0: 使用 ConditionOverlapDetector 共用邏輯（數值區間、集合交集）。
 */
@Component
@Order(4)
@RequiredArgsConstructor
public class ConsistencyValidator implements ValidationLayer {

    private final ConditionOverlapDetector overlapDetector;

    @Override
    public List<ValidationError> validate(JsonNode envelope, ValidationContext context) {
        List<ValidationError> errors = new ArrayList<>();

        if (!context.isShouldContinue()) return errors;

        JsonNode rulesNode = context.getRuleNode().get("rules");
        String hitPolicy = context.getHitPolicy();

        // 未被引用的 input 欄位警告
        for (String inputName : context.getInputTypes().keySet()) {
            if (!context.getReferencedInputs().contains(inputName) && !rulesNode.isEmpty()) {
                errors.add(err(INCONSISTENT_TABLE,
                        "input 欄位 \"" + inputName + "\" 已定義但沒有任何規則引用它。"
                                + "請確認此欄位是否需要，或在規則的 conditions 中加入對應條件"));
            }
        }

        // hitPolicy 值合法性檢查
        if (!hitPolicy.isEmpty() && !"FIRST".equals(hitPolicy) && !"MULTI".equals(hitPolicy)) {
            errors.add(err(MISSING_FIELD,
                    "hitPolicy \"" + hitPolicy + "\" 不是合法值。合法值為 FIRST 或 MULTI"));
        }

        // INCONSISTENT_TABLE（僅 FIRST hitPolicy）
        if ("FIRST".equalsIgnoreCase(hitPolicy)) {
            detectConflicts(context.getParsedRules(), rulesNode, context.getInputTypes(), errors);
        }

        // INVALID_MULTI
        if ("MULTI".equalsIgnoreCase(hitPolicy)) {
            if (rulesNode.size() < 2) {
                errors.add(err(INVALID_MULTI,
                        "hitPolicy=MULTI 但只有 " + rulesNode.size() + " 條規則，MULTI 策略需要多條規則才有意義"));
            }
        }

        return errors;
    }

    // ================================================================
    // 衝突偵測
    // ================================================================

    private void detectConflicts(List<ParsedRule> rules, JsonNode rulesNode, Map<String, String> inputTypes,
                                  List<ValidationError> errors) {
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                ParsedRule rA = rules.get(i);
                ParsedRule rB = rules.get(j);

                Map<String, String> overlapExample = findOverlapExample(rA, rB, inputTypes);
                if (overlapExample == null) continue;

                String exampleStr = overlapExample.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", "));

                // v3.15 E1：條件重疊但兩規則「結果相同」→ 只是冗餘（FIRST 下結果一致、無害），
                // 降級為 WARNING（REDUNDANT_RULE，不使 valid=false）；結果不同才是真衝突。
                boolean sameResults = resultsEqual(resultsAt(rulesNode, i), resultsAt(rulesNode, j));
                if (sameResults) {
                    errors.add(ValidationError.builder()
                            .code(REDUNDANT_RULE)
                            .severity(Severity.WARNING)
                            .message(rA.ruleId() + " 與 " + rB.ruleId()
                                    + " 條件重疊且結果相同，屬冗餘規則（建議合併），非衝突。觸發條件範例：" + exampleStr)
                            .witness(overlapExample)
                            .build());
                } else {
                    // v3.7.0：witness 作為 structured field（IEEE 2024 verification pattern）。
                    // v3.15 E3：message 附上數值/日期欄位的重疊區間，witness 取重疊中點（見 findIntersectionValue）。
                    String rangeNote = overlapRangeNote(rA, rB, inputTypes);
                    errors.add(ValidationError.builder()
                            .code(INCONSISTENT_TABLE)
                            .severity(Severity.ERROR)
                            .message(rA.ruleId() + " 與 " + rB.ruleId()
                                    + " 可同時命中但結果不同（衝突）。觸發條件範例：" + exampleStr + rangeNote)
                            .witness(overlapExample)
                            .build());
                }
            }
        }
    }

    /** 取第 idx 條規則的 results 陣列節點（與 parsedRules 同序）。 */
    private JsonNode resultsAt(JsonNode rulesNode, int idx) {
        if (rulesNode == null || !rulesNode.isArray() || idx >= rulesNode.size()) return null;
        return rulesNode.get(idx).get("results");
    }

    /** 兩組 results 是否語義相同（欄位→值，與順序無關）。 */
    private boolean resultsEqual(JsonNode ra, JsonNode rb) {
        return resultsSignature(ra).equals(resultsSignature(rb));
    }

    private Map<String, String> resultsSignature(JsonNode results) {
        Map<String, String> sig = new TreeMap<>();
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                if (r.has("field")) {
                    sig.put(r.get("field").asText(),
                            r.has("value") && !r.get("value").isNull() ? r.get("value").asText() : "");
                }
            }
        }
        return sig;
    }

    /** E3：列出兩規則在數值/日期欄位上的重疊區間，供衝突訊息附註。 */
    private String overlapRangeNote(ParsedRule rA, ParsedRule rB, Map<String, String> inputTypes) {
        List<String> parts = new ArrayList<>();
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(rA.conditions().keySet());
        fields.addAll(rB.conditions().keySet());
        for (String f : fields) {
            String t = inputTypes.getOrDefault(f, TypeRefs.STRING);
            if (!(isNumericType(t) || TypeRefs.DATE.equals(t))) continue;
            ConditionInfo cA = rA.conditions().get(f);
            ConditionInfo cB = rB.conditions().get(f);
            if (cA == null || cB == null) continue;
            double[] ra = overlapDetector.toNumericRangeFromJson(cA.operator(), cA.value());
            double[] rb = overlapDetector.toNumericRangeFromJson(cB.operator(), cB.value());
            if (ra == null || rb == null) continue;
            double[] ov = overlapDetector.rangeIntersection(ra, rb);
            if (ov == null) continue;
            parts.add(f + " " + fmtNum(ov[0], t) + ".." + fmtNum(ov[1], t));
        }
        return parts.isEmpty() ? "" : "（重疊區間：" + String.join("、", parts) + "）";
    }

    private String fmtNum(double v, String typeRef) {
        return TypeRefs.INTEGER.equals(typeRef) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private Map<String, String> findOverlapExample(ParsedRule rA, ParsedRule rB,
                                                     Map<String, String> inputTypes) {
        Map<String, String> example = new LinkedHashMap<>();
        Set<String> allFields = new LinkedHashSet<>();
        allFields.addAll(rA.conditions().keySet());
        allFields.addAll(rB.conditions().keySet());
        allFields.addAll(inputTypes.keySet());

        for (String field : allFields) {
            ConditionInfo cA = rA.conditions().get(field);
            ConditionInfo cB = rB.conditions().get(field);

            String sampleValue = findIntersectionValue(cA, cB, inputTypes.getOrDefault(field, "STRING"));
            if (sampleValue == null) {
                return null; // 此欄位互斥 → 不可能同時命中
            }
            example.put(field, sampleValue);
        }

        return example;
    }

    /**
     * 找出同時滿足兩個條件的一個範例值。
     * 回傳 null 表示兩個條件互斥。
     *
     * 使用 ConditionOverlapDetector 的共用邏輯進行數值區間和集合操作。
     */
    private String findIntersectionValue(ConditionInfo cA, ConditionInfo cB, String typeRef) {
        // 任一方沒有限制此欄位 → 用另一方的值
        if (cA == null || Operators.ANYTHING.equals(cA.operator())) {
            return cB != null ? extractSampleValue(cB) : "any";
        }
        if (cB == null || Operators.ANYTHING.equals(cB.operator())) {
            return extractSampleValue(cA);
        }

        // isNull vs isNotNull → 必定互斥
        if (Operators.IS_NULL.equals(cA.operator()) && Operators.IS_NOT_NULL.equals(cB.operator())) return null;
        if (Operators.IS_NOT_NULL.equals(cA.operator()) && Operators.IS_NULL.equals(cB.operator())) return null;
        if (Operators.IS_NULL.equals(cA.operator()) && Operators.IS_NULL.equals(cB.operator())) return "null";
        if (Operators.IS_NOT_NULL.equals(cA.operator()) && Operators.IS_NOT_NULL.equals(cB.operator())) return "any";

        // equals vs notEquals → 互斥（同一值）
        if (Operators.EQUALS.equals(cA.operator()) && Operators.NOT_EQUALS.equals(cB.operator())) {
            if (cA.value() != null && cB.value() != null && cA.value().asText().equals(cB.value().asText())) return null;
        }
        if (Operators.NOT_EQUALS.equals(cA.operator()) && Operators.EQUALS.equals(cB.operator())) {
            if (cA.value() != null && cB.value() != null && cA.value().asText().equals(cB.value().asText())) return null;
        }

        // 數值型：使用 ConditionOverlapDetector 的共用區間邏輯
        if (isNumericType(typeRef) || TypeRefs.DATE.equals(typeRef)) {
            double[] rA = overlapDetector.toNumericRangeFromJson(cA.operator(), cA.value());
            double[] rB = overlapDetector.toNumericRangeFromJson(cB.operator(), cB.value());
            if (rA != null && rB != null) {
                double[] overlap = overlapDetector.rangeIntersection(rA, rB);
                if (overlap == null) return null; // 互斥
                // E3：回傳重疊區間中點而非邊界值，較能代表「典型」會同時命中的輸入。
                double mid = (overlap[0] + overlap[1]) / 2.0;
                return TypeRefs.INTEGER.equals(typeRef)
                        ? String.valueOf((long) Math.floor(mid))
                        : String.valueOf(mid);
            }
        }

        // 集合型：使用 ConditionOverlapDetector 的共用集合邏輯
        if (TypeRefs.STRING.equals(typeRef) || TypeRefs.ENUM.equals(typeRef)) {
            return intersectSets(cA, cB);
        }

        // BOOLEAN
        if (TypeRefs.BOOLEAN.equals(typeRef)) {
            return intersectBoolean(cA, cB);
        }

        // Fallback
        return extractSampleValue(cA);
    }

    // ========== 集合型交集（STRING / ENUM）==========

    private String intersectSets(ConditionInfo cA, ConditionInfo cB) {
        Set<String> setA = overlapDetector.extractValueSet(cA.operator(), cA.value());
        Set<String> setB = overlapDetector.extractValueSet(cB.operator(), cB.value());

        if (setA == null && setB == null) return "any";
        if (setA == null) return setB.isEmpty() ? null : setB.iterator().next();
        if (setB == null) return setA.isEmpty() ? null : setA.iterator().next();

        // 求交集
        Set<String> intersection = new LinkedHashSet<>(setA);
        intersection.retainAll(setB);
        return intersection.isEmpty() ? null : intersection.iterator().next();
    }

    // ========== BOOLEAN 交集 ==========

    private String intersectBoolean(ConditionInfo cA, ConditionInfo cB) {
        Boolean bA = toBoolValue(cA);
        Boolean bB = toBoolValue(cB);
        if (bA == null && bB == null) return "true";
        if (bA == null) return String.valueOf(bB);
        if (bB == null) return String.valueOf(bA);
        return bA.equals(bB) ? String.valueOf(bA) : null;
    }

    private Boolean toBoolValue(ConditionInfo c) {
        if (c == null || c.value() == null) return null;
        if (Operators.EQUALS.equals(c.operator())) return c.value().asBoolean();
        if (Operators.NOT_EQUALS.equals(c.operator())) return !c.value().asBoolean();
        return null;
    }

    // ========== Helpers ==========

    private String extractSampleValue(ConditionInfo ci) {
        if (ci == null || ci.value() == null || ci.value().isNull()) return "any";
        if (ci.value().isArray()) {
            return ci.value().size() > 0 ? ci.value().get(0).asText() : "any";
        }
        return ci.value().asText();
    }

    private boolean isNumericType(String typeRef) {
        return TypeRefs.INTEGER.equals(typeRef) || TypeRefs.DECIMAL.equals(typeRef);
    }

    /**
     * 一般錯誤 helper（無 witness）。
     * v3.7.0：衝突偵測請勿使用此 helper，改用 inline builder 填 .witness(...)
     * 以符合 IEEE 2024 結構化反例規範。
     */
    private ValidationError err(String code, String message) {
        return ValidationError.builder().code(code).message(message).build();
    }
}
