package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DecisionTable 驗證器 — Phase 1 核心交付。
 *
 * 完整實作 11 個錯誤碼（output design v3.0 §UC-02）。
 * 驗證流程分四層，由獨立的 ValidationLayer 實作：
 *   Layer 1 — StructureValidator: 結構完整性（MISSING_FIELD）
 *   Layer 2 — FieldDefinitionValidator: 欄位定義正確性（ENUM_VALUE_MISSING, TypeRef 合法性）
 *   Layer 3 — RuleSemanticValidator: 逐條規則語義驗證（UNKNOWN_FIELD, UNKNOWN_OPERATOR, TYPE_MISMATCH,
 *             DUPLICATE_ID, INVALID_ENUM_VALUE, MISSING_RESULTS）
 *   Layer 4 — ConsistencyValidator: 跨規則一致性（INCONSISTENT_TABLE, INVALID_MULTI）
 *
 * v2.0.0 重構：拆解為 4 個獨立的 ValidationLayer，本類別僅為協調者。
 * MISSING_BRANCH 為 DecisionTree 專屬，此 validator 不產生此錯誤碼。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DecisionTableValidator implements RuleValidator {

    private final List<ValidationLayer> validationLayers;

    @Override
    public RuleType supportedType() {
        return RuleType.DECISION_TABLE;
    }

    @Override
    public List<ValidationError> validate(JsonNode envelope) {
        List<ValidationError> allErrors = new ArrayList<>();
        ValidationContext context = new ValidationContext();

        for (ValidationLayer layer : validationLayers) {
            List<ValidationError> layerErrors = layer.validate(envelope, context);
            allErrors.addAll(layerErrors);

            // 若某層標記不應繼續（例如結構嚴重錯誤），提前終止
            if (!context.isShouldContinue()) {
                break;
            }
        }

        log.info("DecisionTable 驗證完成：{} 個錯誤", allErrors.size());
        return allErrors;
    }

    @Override
    public List<ValidationError> checkConsistency(JsonNode payload) {
        return validate(payload);
    }
}
