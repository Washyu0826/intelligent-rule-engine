package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes.*;

/**
 * Layer 1 — 結構完整性驗證。
 *
 * 檢查 RuleEnvelope 的頂層結構是否完整：
 *   - ruleType 是否存在且值正確
 *   - rule 物件是否存在
 *   - rule 內的 hitPolicy, inputs, outputs, rules 是否存在
 *   - 基本的結構一致性（inputs/outputs 空 vs rules 非空）
 */
@Component
@Order(1)
public class StructureValidator implements ValidationLayer {

    @Override
    public List<ValidationError> validate(JsonNode envelope, ValidationContext context) {
        List<ValidationError> errors = new ArrayList<>();

        // null 輸入
        if (envelope == null || envelope.isNull()) {
            errors.add(err(MISSING_FIELD, "輸入為 null，無法驗證"));
            context.setShouldContinue(false);
            return errors;
        }

        // ruleType
        requireField(envelope, "ruleType", errors);
        requireField(envelope, "rule", errors);

        // ruleType 值合法性
        if (envelope.has("ruleType") && !envelope.get("ruleType").isNull()) {
            String rt = envelope.get("ruleType").asText();
            if (!"DecisionTable".equals(rt)) {
                errors.add(err(TYPE_MISMATCH,
                        "ruleType 值 \"" + rt + "\" 不正確。此 validator 只接受 \"DecisionTable\""));
            }
        }

        // rule 節點
        JsonNode ruleNode = envelope.get("rule");
        if (ruleNode == null || ruleNode.isNull()) {
            context.setShouldContinue(false);
            return errors;
        }
        context.setRuleNode(ruleNode);

        // rule 內的必要欄位
        requireField(ruleNode, "hitPolicy", errors);
        requireField(ruleNode, "inputs", errors);
        requireField(ruleNode, "outputs", errors);
        requireField(ruleNode, "rules", errors);

        JsonNode inputsNode = ruleNode.get("inputs");
        JsonNode outputsNode = ruleNode.get("outputs");
        JsonNode rulesNode = ruleNode.get("rules");

        // 任一核心節點缺失就停止後續層
        if (inputsNode == null || !inputsNode.isArray()
                || outputsNode == null || !outputsNode.isArray()
                || rulesNode == null || !rulesNode.isArray()) {
            context.setShouldContinue(false);
            return errors;
        }

        // hitPolicy
        String hitPolicy = ruleNode.get("hitPolicy") != null ? ruleNode.get("hitPolicy").asText() : "";
        context.setHitPolicy(hitPolicy);

        // inputs 空但 rules 不為空 → 邏輯矛盾
        if (inputsNode.isEmpty() && !rulesNode.isEmpty()) {
            errors.add(err(INCONSISTENT_TABLE,
                    "inputs 為空陣列但有 " + rulesNode.size() + " 條規則。"
                            + "沒有定義條件欄位就無法建立有意義的規則"));
        }
        // outputs 空但 rules 不為空
        if (outputsNode.isEmpty() && !rulesNode.isEmpty()) {
            errors.add(err(INCONSISTENT_TABLE,
                    "outputs 為空陣列但有 " + rulesNode.size() + " 條規則。"
                            + "沒有定義結果欄位就無法產出規則結果"));
        }

        return errors;
    }

    private void requireField(JsonNode node, String field, List<ValidationError> errors) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            errors.add(err(MISSING_FIELD, "缺少必要欄位 \"" + field + "\""));
        }
    }

    private ValidationError err(String code, String message) {
        return ValidationError.builder().code(code).message(message).build();
    }
}
