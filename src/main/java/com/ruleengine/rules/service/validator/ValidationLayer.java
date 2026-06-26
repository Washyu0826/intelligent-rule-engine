package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 驗證層介面。
 *
 * DecisionTable 驗證流程分為四層，每層實作此介面。
 * 協調者（DecisionTableValidator）依序執行每層，並匯總所有錯誤。
 */
public interface ValidationLayer {

    /**
     * 執行此層的驗證。
     *
     * @param envelope 完整的 RuleEnvelope JSON
     * @param context  驗證上下文（跨層共享的解析結果）
     * @return 此層發現的錯誤列表
     */
    List<ValidationError> validate(JsonNode envelope, ValidationContext context);
}
