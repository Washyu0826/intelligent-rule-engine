package com.ruleengine.rules.service.llm;

/**
 * LLM 服務抽象介面。
 *
 * 將自然語言規則描述轉為 RuleEnvelope JSON 字串。
 * 不同的 LLM 提供者（Gemini、OpenAI、Claude 等）實作此介面。
 *
 * v2.0.0: 從 GeminiService 抽象而來，支援多 LLM 切換。
 */
public interface LlmProvider {

    /**
     * 將自然語言描述轉為 RuleEnvelope JSON 字串。
     *
     * @param description 自然語言規則描述
     * @return RuleEnvelope JSON 字串，失敗時回傳 null
     */
    String generateRuleJson(String description);

    /**
     * 將自然語言描述轉為 RuleEnvelope JSON 字串（指定生成模式）。
     *
     * @param description 自然語言規則描述
     * @param mode        生成模式：fast / normal / deep
     * @return RuleEnvelope JSON 字串，失敗時回傳 null
     */
    default String generateRuleJson(String description, String mode) {
        return generateRuleJson(description); // 預設忽略 mode
    }

    /**
     * 將自然語言描述轉為 RuleEnvelope JSON 字串（指定生成模式和規則型態）。
     *
     * @param description 自然語言規則描述
     * @param mode        生成模式：fast / normal / deep
     * @param ruleType    規則型態：DecisionTable / DecisionTree
     * @return RuleEnvelope JSON 字串，失敗時回傳 null
     */
    default String generateRuleJson(String description, String mode, String ruleType) {
        return generateRuleJson(description, mode); // 預設忽略 ruleType
    }

    /**
     * 根據驗證錯誤和覆蓋缺口修復已生成的 RuleEnvelope JSON。
     *
     * @param originalDescription 原始自然語言描述
     * @param currentJson         當前（有問題的）RuleEnvelope JSON
     * @param issues              需要修復的問題描述（驗證錯誤 + 覆蓋缺口）
     * @return 修復後的 RuleEnvelope JSON 字串，失敗時回傳 null
     */
    default String repairRuleJson(String originalDescription, String currentJson, String issues) {
        return null; // 預設不支援，子類別可覆寫
    }

    /**
     * 串流生成 RuleEnvelope JSON，逐 token 回傳。
     *
     * @param description 自然語言規則描述
     * @param ruleType    規則型態
     * @param tokenConsumer 每收到一段文字時呼叫
     * @return 完整的 JSON 字串，失敗時回傳 null
     */
    default String generateRuleJsonStreaming(String description, String ruleType,
                                             java.util.function.Consumer<String> tokenConsumer) {
        // 預設 fallback：不支援 streaming 的 provider 直接呼叫非 streaming 版本
        String result = generateRuleJson(description, "normal", ruleType);
        if (result != null && tokenConsumer != null) {
            tokenConsumer.accept(result);
        }
        return result;
    }

    /**
     * 帶自訂 JSON Schema 的通用 LLM 呼叫（Two-Pass 生成用）。
     *
     * @param prompt 提示詞
     * @param jsonSchema JSON Schema Map（Ollama format 參數），null 時用預設 RuleEnvelope schema
     * @return JSON 字串，失敗時回傳 null
     */
    default String callWithSchema(String prompt, java.util.Map<String, Object> jsonSchema) {
        return generateRuleJson(prompt); // 預設 fallback
    }

    /**
     * 規則型態二選一（決策表／決策樹／評分卡）。只在啟發式信心不足時由 RuleRecommender 呼叫。
     *
     * @param prompt 已組好的分類提示詞，要求模型只回 {"ruleType":..., "reason":...}
     * @return 模型回傳的 JSON 文字；不支援或失敗時回傳 null
     */
    default String classifyRuleType(String prompt) {
        return null;
    }

    /**
     * 檢查此 LLM 提供者是否可用。
     *
     * @return true 若服務可用（API key 已設定、服務連線等）
     */
    boolean isAvailable();

    /**
     * 取得提供者名稱（用於日誌和偵錯）。
     */
    String getProviderName();

    /**
     * 此 provider 是否採用「描述維度預解析」補償機制。
     *
     * <p>
     * {@link DescriptionDimensionParser} 的用途是補償小模型無法從複雜中文描述中
     * 正確識別維度的問題（見該類別 javadoc），因此只有本地小模型 provider 需要。
     * 回傳 {@code true} 時，generator 會用同一份描述獨立解析出的維度執行後處理
     * （維度擴展 + Two-Pass 笛卡爾積填充）。
     * </p>
     *
     * <p>
     * <b>設計註記：</b>此旗標取代了原本以 {@code static ThreadLocal} 在 provider 與
     * generator 之間隱性傳遞維度的做法 —— 該做法在執行緒池（v3.16 起 LLM 工作跑在
     * 共用 {@code llmExecutor}）上會把前一個請求的解析結果洩漏給下一個請求。
     * </p>
     */
    default boolean usesDimensionPreparse() {
        return false;
    }
}
