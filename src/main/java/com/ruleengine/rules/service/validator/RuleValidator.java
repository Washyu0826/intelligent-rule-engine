package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 規則驗證器介面（計畫書 §6.2 Strategy Pattern）
 * 各規則型態實作 validate(payload) → errors[]
 *
 * 驗證分兩層：
 * 1. JSON Schema 驗證（結構正確性）
 * 2. 型態專屬一致性檢查（語義正確性）
 *
 * v2.0.0: 新增 validate(RuleEnvelope) 預設方法，支援型別安全的驗證呼叫。
 */
public interface RuleValidator {

    /** 共用 mapper（ObjectMapper 為執行緒安全，避免每次驗證重複建構） */
    ObjectMapper SHARED_MAPPER = new ObjectMapper();

    /** 此 validator 負責的規則型態 */
    RuleType supportedType();

    /**
     * 驗證規則 payload（JsonNode 版本）。
     *
     * @param payload  規則 JSON（可以是完整 RuleEnvelope 或只是 payload 部分）
     * @return 驗證錯誤列表（空 = 通過）
     */
    List<ValidationError> validate(JsonNode payload);

    /**
     * 驗證規則 payload（型別安全版本）。
     * 預設實作將 RuleEnvelope 轉為 JsonNode 後委派給 validate(JsonNode)。
     *
     * @param envelope RuleEnvelope 型別物件
     * @return 驗證錯誤列表（空 = 通過）
     */
    default List<ValidationError> validate(RuleEnvelope envelope) {
        JsonNode jsonNode = SHARED_MAPPER.valueToTree(envelope);
        return validate(jsonNode);
    }

    /**
     * 型態專屬的一致性檢查（語義層面）
     *
     * @param payload 規則 payload
     * @return 驗證錯誤列表
     */
    List<ValidationError> checkConsistency(JsonNode payload);
}
