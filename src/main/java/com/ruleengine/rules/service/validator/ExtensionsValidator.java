package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes.*;

/**
 * Layer 5 — extensions{} 區塊驗證（v3.14）。
 *
 * 對 envelope.extensions 內的五個 sub-list 進行語義檢查：
 *   - INVALID_GLOBAL_GUARD       — globalGuards[].condition 引用未定義欄位等
 *   - INVALID_FIELD_OR           — fieldOr[].fields 數量、未定義欄位、appliesToRuleIds 不存在
 *   - INVALID_RULE_STATUS_REF    — ruleStatus[].ruleId 不存在、重複、status 非法
 *   - INVALID_FOOTNOTE_REF       — footnotes[].appliesToRuleId 不存在、text 空白
 *   - MALFORMED_GROUPING         — groupings[].memberRuleIds 空、未存在 ruleId、重複 groupId、level≤0
 *
 * 設計重點：
 *   - 若 envelope.extensions 不存在或 5 個 sub-list 都為 null，本層回傳空 list（零成本短路）。
 *   - 仰賴 Layer 2 (FieldDefinitionValidator) 填入的 inputTypes / outputTypes，
 *     以及 Layer 3 (RuleSemanticValidator) 填入的 parsedRules — 故必須排在 @Order(5)。
 *
 * INVALID_VARIABLE_IN_CONDITION 不在此層，而是由 RuleSemanticValidator (Layer 3) 處理，
 * 因為它檢查的是核心 inputs/outputs typeRef 語義，與 extensions block 無關。
 */
@Component
@Order(5)
@RequiredArgsConstructor
@Slf4j
public class ExtensionsValidator implements ValidationLayer {

    @Override
    public List<ValidationError> validate(JsonNode envelope, ValidationContext context) {
        List<ValidationError> errors = new ArrayList<>();

        if (!context.isShouldContinue()) return errors;
        if (envelope == null || envelope.get("extensions") == null
                || envelope.get("extensions").isNull()) {
            return errors;
        }
        JsonNode ext = envelope.get("extensions");

        Set<String> parsedRuleIds = new HashSet<>();
        for (ValidationContext.ParsedRule pr : context.getParsedRules()) {
            if (pr.ruleId() != null) parsedRuleIds.add(pr.ruleId());
        }

        validateGlobalGuards(ext.get("globalGuards"), context, errors);
        validateFieldOr(ext.get("fieldOr"), context, parsedRuleIds, errors);
        validateGroupings(ext.get("groupings"), parsedRuleIds, errors);
        validateFootnotes(ext.get("footnotes"), parsedRuleIds, errors);
        validateRuleStatus(ext.get("ruleStatus"), parsedRuleIds, errors);

        return errors;
    }

    // ================================================================
    // GlobalGuard
    // ================================================================

    private void validateGlobalGuards(JsonNode guards, ValidationContext context,
                                       List<ValidationError> errors) {
        if (guards == null || !guards.isArray()) return;
        for (int i = 0; i < guards.size(); i++) {
            JsonNode g = guards.get(i);
            String label = guardLabel(g, i);

            JsonNode cond = g.get("condition");
            if (cond == null || cond.isNull()) {
                errors.add(err(INVALID_GLOBAL_GUARD, label + " 缺少 condition"));
                continue;
            }
            // condition.field 必須在 inputs 中
            JsonNode fNode = cond.get("field");
            String f = fNode != null && fNode.isTextual() ? fNode.asText() : null;
            if (f == null || f.isBlank()) {
                errors.add(err(INVALID_GLOBAL_GUARD, label + " condition 缺少 field"));
            } else if (!context.getInputTypes().containsKey(f)) {
                errors.add(err(INVALID_GLOBAL_GUARD,
                        label + " condition.field \"" + f + "\" 不在 inputs 中。"
                                + "已定義的 inputs：" + context.getInputTypes().keySet()));
            }
            // operator 合法性
            JsonNode opNode = cond.get("operator");
            if (opNode == null || opNode.isNull()) {
                errors.add(err(INVALID_GLOBAL_GUARD, label + " condition 缺少 operator"));
            } else {
                String op = opNode.asText();
                if (!Operators.ALL.contains(op)) {
                    errors.add(err(INVALID_GLOBAL_GUARD,
                            label + " condition.operator \"" + op + "\" 不在合法 operator 集合內"));
                }
            }
            // onFailure[].field 必須屬於 outputs
            JsonNode onFailure = g.get("onFailure");
            if (onFailure != null && onFailure.isArray()) {
                for (int j = 0; j < onFailure.size(); j++) {
                    JsonNode r = onFailure.get(j);
                    JsonNode rf = r.get("field");
                    String rfStr = rf != null && rf.isTextual() ? rf.asText() : null;
                    if (rfStr == null || rfStr.isBlank()) {
                        errors.add(err(INVALID_GLOBAL_GUARD,
                                label + " onFailure[" + j + "] 缺少 field"));
                    } else if (!context.getOutputTypes().containsKey(rfStr)) {
                        errors.add(err(INVALID_GLOBAL_GUARD,
                                label + " onFailure[" + j + "].field \"" + rfStr + "\" 不在 outputs 中。"
                                        + "已定義的 outputs：" + context.getOutputTypes().keySet()));
                    }
                }
            }
        }
    }

    private String guardLabel(JsonNode g, int i) {
        JsonNode id = g.get("guardId");
        return "globalGuards[" + (id != null && id.isTextual() ? id.asText() : i) + "]";
    }

    // ================================================================
    // FieldOrSpec
    // ================================================================

    private void validateFieldOr(JsonNode fieldOr, ValidationContext context,
                                  Set<String> parsedRuleIds, List<ValidationError> errors) {
        if (fieldOr == null || !fieldOr.isArray()) return;
        for (int i = 0; i < fieldOr.size(); i++) {
            JsonNode spec = fieldOr.get(i);
            JsonNode idNode = spec.get("orId");
            String label = "fieldOr[" + (idNode != null && idNode.isTextual() ? idNode.asText() : i) + "]";

            JsonNode fields = spec.get("fields");
            if (fields == null || !fields.isArray() || fields.size() < 2) {
                errors.add(err(INVALID_FIELD_OR,
                        label + " fields 至少需要 2 個欄位（目前 "
                                + (fields == null ? 0 : fields.size()) + " 個）"));
                continue;
            }
            for (int j = 0; j < fields.size(); j++) {
                String fn = fields.get(j).asText();
                if (!context.getInputTypes().containsKey(fn)) {
                    errors.add(err(INVALID_FIELD_OR,
                            label + " fields[" + j + "] \"" + fn + "\" 不在 inputs 中"));
                }
            }
            // appliesToRuleIds 必須對應到實際 rule
            JsonNode applies = spec.get("appliesToRuleIds");
            if (applies != null && applies.isArray()) {
                for (int j = 0; j < applies.size(); j++) {
                    String rid = applies.get(j).asText();
                    if (!parsedRuleIds.contains(rid)) {
                        errors.add(err(INVALID_FIELD_OR,
                                label + " appliesToRuleIds[" + j + "] \"" + rid
                                        + "\" 不在 rules[] 中"));
                    }
                }
            }
        }
    }

    // ================================================================
    // RuleGrouping
    // ================================================================

    private void validateGroupings(JsonNode groupings, Set<String> parsedRuleIds,
                                    List<ValidationError> errors) {
        if (groupings == null || !groupings.isArray()) return;
        Set<String> seenGroupIds = new LinkedHashSet<>();
        for (int i = 0; i < groupings.size(); i++) {
            JsonNode g = groupings.get(i);
            JsonNode idNode = g.get("groupId");
            String gid = idNode != null && idNode.isTextual() ? idNode.asText() : null;
            String label = "groupings[" + (gid != null ? gid : i) + "]";

            // 重複 groupId
            if (gid != null && !seenGroupIds.add(gid)) {
                errors.add(err(MALFORMED_GROUPING, label + " groupId \"" + gid + "\" 重複"));
            }
            // memberRuleIds 必須非空
            JsonNode members = g.get("memberRuleIds");
            if (members == null || !members.isArray() || members.isEmpty()) {
                errors.add(err(MALFORMED_GROUPING, label + " memberRuleIds 不可為空"));
            } else {
                for (int j = 0; j < members.size(); j++) {
                    String rid = members.get(j).asText();
                    if (!parsedRuleIds.contains(rid)) {
                        errors.add(err(MALFORMED_GROUPING,
                                label + " memberRuleIds[" + j + "] \"" + rid + "\" 不在 rules[] 中"));
                    }
                }
            }
            // level > 0
            JsonNode level = g.get("level");
            if (level != null && level.isNumber() && level.asInt() <= 0) {
                errors.add(err(MALFORMED_GROUPING,
                        label + " level 必須 > 0（收到：" + level.asInt() + "）"));
            }
        }
    }

    // ================================================================
    // Footnote
    // ================================================================

    private void validateFootnotes(JsonNode footnotes, Set<String> parsedRuleIds,
                                    List<ValidationError> errors) {
        if (footnotes == null || !footnotes.isArray()) return;
        for (int i = 0; i < footnotes.size(); i++) {
            JsonNode fn = footnotes.get(i);
            String label = "footnotes[" + i + "]";
            JsonNode textNode = fn.get("text");
            if (textNode == null || !textNode.isTextual() || textNode.asText().isBlank()) {
                errors.add(err(INVALID_FOOTNOTE_REF, label + " text 不可為空白"));
            }
            JsonNode appliesTo = fn.get("appliesToRuleId");
            if (appliesTo != null && appliesTo.isTextual() && !appliesTo.asText().isBlank()) {
                String rid = appliesTo.asText();
                if (!parsedRuleIds.contains(rid)) {
                    errors.add(err(INVALID_FOOTNOTE_REF,
                            label + " appliesToRuleId \"" + rid + "\" 不在 rules[] 中"));
                }
            }
        }
    }

    // ================================================================
    // RuleStatus
    // ================================================================

    private static final Set<String> LIFECYCLE_VALUES = Set.of("ACTIVE", "DRAFT", "RETIRED");

    private void validateRuleStatus(JsonNode statuses, Set<String> parsedRuleIds,
                                     List<ValidationError> errors) {
        if (statuses == null || !statuses.isArray()) return;
        Set<String> seenRuleIds = new LinkedHashSet<>();
        for (int i = 0; i < statuses.size(); i++) {
            JsonNode s = statuses.get(i);
            String label = "ruleStatus[" + i + "]";

            JsonNode ridNode = s.get("ruleId");
            String rid = ridNode != null && ridNode.isTextual() ? ridNode.asText() : null;
            if (rid == null || rid.isBlank()) {
                errors.add(err(INVALID_RULE_STATUS_REF, label + " 缺少 ruleId"));
            } else {
                if (!seenRuleIds.add(rid)) {
                    errors.add(err(INVALID_RULE_STATUS_REF,
                            label + " ruleId \"" + rid + "\" 重複"));
                }
                if (!parsedRuleIds.contains(rid)) {
                    errors.add(err(INVALID_RULE_STATUS_REF,
                            label + " ruleId \"" + rid + "\" 不在 rules[] 中"));
                }
            }
            JsonNode statusNode = s.get("status");
            if (statusNode != null && statusNode.isTextual()) {
                String st = statusNode.asText();
                if (!LIFECYCLE_VALUES.contains(st)) {
                    errors.add(err(INVALID_RULE_STATUS_REF,
                            label + " status \"" + st + "\" 不在合法值 " + LIFECYCLE_VALUES + " 內"));
                }
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private ValidationError err(String code, String message) {
        return ValidationError.builder().code(code).message(message).build();
    }
}
