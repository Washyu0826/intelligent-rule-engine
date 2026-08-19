package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.RuleType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 規則生成器介面（計畫書 §6.2 Strategy Pattern）
 * 各規則型態實作 generate(description) → payload
 */
public interface RuleGenerator {

    /** 此 generator 負責的規則型態 */
    RuleType supportedType();

    /**
     * 從自然語言描述生成規則 payload JSON
     *
     * @param description    自然語言規則描述
     * @param allowedFields  允許的欄位白名單（null = 不限制）
     * @return payload JsonNode（不含 envelope，只有 payload 部分）
     */
    JsonNode generate(String description, List<String> allowedFields);

    /**
     * 從自然語言描述生成規則 payload JSON（指定生成模式）
     *
     * @param description    自然語言規則描述
     * @param allowedFields  允許的欄位白名單（null = 不限制）
     * @param mode           生成模式：fast / normal / deep
     * @return payload JsonNode
     */
    default JsonNode generate(String description, List<String> allowedFields, String mode) {
        return generate(description, allowedFields);
    }

    /**
     * 從自然語言描述生成規則 payload JSON（指定生成模式與 LLM provider）。
     *
     * <p>review 修復輪：修復「前端選了 provider、服務層丟棄」的參數斷層 ——
     * 介面用 default 方法向後相容（不支援 per-request provider 的 generator
     * 自動忽略該參數，如 ScoreCardGenerator stub）。</p>
     *
     * @param providerName claude / gemini / ollama / openai；null 或未知名稱用系統預設
     */
    default JsonNode generate(String description, List<String> allowedFields, String mode, String providerName) {
        return generate(description, allowedFields, mode);
    }
}
