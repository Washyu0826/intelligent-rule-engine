package com.ruleengine.rules.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Claude LLM 服務 — 透過 Anthropic Messages API 將自然語言規則描述轉為 RuleEnvelope JSON。
 *
 * 僅在 rules.llm.provider=claude 時啟用。
 */
@Service
@Slf4j
public class ClaudeService implements LlmProvider {

    // review 修復輪：原為寫死常數 —— WireMock 攔不到、Claude 路徑完全不可整合測試。
    // 改可配置：正式環境不設定即用官方端點，測試指向 mock。
    @Value("${rules.llm.claude.base-url:https://api.anthropic.com/v1/messages}")
    private String apiUrl;
    private static final String API_VERSION = "2023-06-01";
    /** Level A: JSON parse retry 最大次數 */
    private static final int MAX_RETRY = 3;

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LlmGenerationCache generationCache;

    // 各 provider 用自己的命名空間，與 model 的解析鏈一致；
    // 保留 rules.llm.api-key 作為既有部署的相容 fallback（勿再新增使用）。
    @Value("${rules.llm.claude.api-key:${rules.llm.api-key:}}")
    private String apiKey;

    @Value("${rules.llm.claude.model:${rules.llm.model:claude-sonnet-4-6}}")
    private String model;

    @Value("${rules.llm.timeout-seconds:120}")
    private int timeoutSeconds;

    public ClaudeService(ObjectMapper objectMapper, LlmGenerationCache generationCache) {
        this.objectMapper = objectMapper;
        this.generationCache = generationCache;
    }

    @PostConstruct
    void init() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutSeconds * 1000 / 3, 30_000));
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.restTemplate = new RestTemplate(factory);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("CLAUDE_API_KEY 未設定！自然語言生成功能將無法使用。");
        } else {
            log.info("ClaudeService 已初始化 | model={} | timeout={}s | keyLen={}",
                    model, timeoutSeconds, apiKey.length());
        }
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getProviderName() {
        return "Claude (" + model + ")";
    }

    @Override
    public String generateRuleJson(String description) {
        return generateRuleJson(description, "normal", "DecisionTable");
    }

    @Override
    public String generateRuleJson(String description, String mode) {
        return generateRuleJson(description, mode, "DecisionTable");
    }

    @Override
    public String generateRuleJson(String description, String mode, String ruleType) {
        if (!isAvailable()) {
            log.error("Claude API Key 未設定，無法生成規則");
            return null;
        }
        String resolvedMode = mode != null ? mode : "normal";
        String resolvedType = ruleType != null ? ruleType : "DecisionTable";
        // 經獨立的 LlmGenerationCache bean 取快取 —— 直接把 @Cacheable 標在下面的
        // doGenerateRuleJson 上會因為同類別內部呼叫而被 proxy 略過（v3.16.3 前的實況）。
        return generationCache.getOrGenerate(
                LlmGenerationCache.key("claude", description, resolvedMode, resolvedType),
                () -> doGenerateRuleJson(description, resolvedMode, resolvedType));
    }

    public String doGenerateRuleJson(String description, String mode, String ruleType) {
        log.info("生成模式：{}, 規則型態：{}", mode, ruleType);

        // 註：此處原本讀取 DescriptionDimensionParser 的 static ThreadLocal 來注入維度區塊。
        // 該 ThreadLocal 只由 OllamaService 寫入，Claude 路徑永遠拿不到自己的解析結果 ——
        // 唯一會非 null 的情況是同一條池執行緒上「前一個」請求殘留的維度，
        // 亦即把別人的描述解析結果送進本次 prompt。維度預解析是小模型補償機制
        // （見 LlmProvider.usesDimensionPreparse），Claude 不需要，故整段移除。
        String systemPrompt = PromptGuard.systemGuardSection() + buildSystemPrompt(ruleType);
        String userPrompt = PromptGuard.wrap(description);

        // === 首次呼叫 ===
        String rawJson = callClaudeApi(systemPrompt, userPrompt, 0);
        if (rawJson == null) return null;
        rawJson = stripMarkdown(rawJson);

        // === Level A Retry: JSON parse 失敗時自動修正 ===
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            String parseError = tryParseJson(rawJson);
            if (parseError == null) {
                log.info("JSON parse 成功（attempt={}）", attempt == 1 ? "首次" : attempt);
                return rawJson;
            }

            log.warn("JSON parse 失敗（retry {}/{}）| 原因：{}", attempt, MAX_RETRY, parseError);

            if (attempt == MAX_RETRY) {
                log.error("已達最大 retry 次數（{}），放棄生成", MAX_RETRY);
                return null;
            }

            // 建構修正 prompt，把錯誤訊息塞回去
            String repairPrompt = "前次生成的 JSON 有錯誤：" + parseError
                    + "\n\n請修正後重新輸出完整的 RuleEnvelope JSON。只輸出純 JSON，不要 markdown。"
                    + "\n\n前次輸出（有錯誤）：\n" + (rawJson.length() > 8000 ? rawJson.substring(0, 8000) + "..." : rawJson)
                    + "\n\n原始需求：\n" + description;
            rawJson = callClaudeApi(systemPrompt, repairPrompt, attempt);
            if (rawJson == null) return null;
            rawJson = stripMarkdown(rawJson);
        }

        // 最後一次 retry 的結果也要檢查
        String finalError = tryParseJson(rawJson);
        if (finalError != null) {
            log.error("最終 retry 後 JSON 仍無法 parse：{}", finalError);
            return null;
        }
        return rawJson;
    }

    @Override
    public String repairRuleJson(String originalDescription, String currentJson, String issues) {
        if (!isAvailable()) return null;

        String systemPrompt = "你是資深業務規則分析師。請根據問題清單修正 RuleEnvelope JSON。只輸出純 JSON，不要 markdown 標記。";
        String userPrompt = "# 需要修復的問題\n" + issues
                + "\n\n# 原始業務描述\n" + originalDescription
                + (currentJson != null && currentJson.length() < 12000
                    ? "\n\n# 當前 JSON（請修正）\n" + currentJson : "");

        log.info("Level B Self-Repair | issues.length={}", issues.length());
        String rawJson = callClaudeApi(systemPrompt, userPrompt, 0);
        if (rawJson == null) return null;

        rawJson = stripMarkdown(rawJson);
        String parseError = tryParseJson(rawJson);
        if (parseError != null) {
            log.warn("Level B repair JSON 結構有誤：{}", parseError);
            return null;
        }
        return rawJson;
    }

    // ========================================================================
    // Anthropic Messages API 呼叫
    // ========================================================================

    private String callClaudeApi(String systemPrompt, String userPrompt, int attempt) {
        try {
            // Prompt Caching: 系統提示詞用 cache_control 標記，避免重複傳輸
            Map<String, Object> systemBlock = new java.util.LinkedHashMap<>();
            systemBlock.put("type", "text");
            systemBlock.put("text", systemPrompt);
            systemBlock.put("cache_control", Map.of("type", "ephemeral"));

            Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 64000);
            requestBody.put("temperature", 0.1);
            requestBody.put("system", List.of(systemBlock));
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", userPrompt)
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", API_VERSION);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("呼叫 Claude API | model={} | attempt={} | prompt.length={}",
                    model, attempt, userPrompt.length());
            long startMs = System.currentTimeMillis();

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class);

            long durationMs = System.currentTimeMillis() - startMs;
            log.info("Claude API 回應 | status={} | durationMs={} | attempt={}",
                    response.getStatusCode(), durationMs, attempt);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Claude API 回傳非 200：{}", response.getStatusCode());
                return null;
            }

            JsonNode responseJson = objectMapper.readTree(response.getBody());
            JsonNode content = responseJson.path("content");
            if (content.isEmpty() || !content.isArray()) {
                log.error("Claude API 回傳無 content");
                return null;
            }

            // 取出第一個 text block
            String generatedText = null;
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    generatedText = block.path("text").asText();
                    break;
                }
            }

            if (generatedText == null || generatedText.isBlank()) {
                log.error("Claude API 回傳空內容");
                return null;
            }

            // Token 計量 + Prompt Cache 命中統計
            JsonNode usage = responseJson.path("usage");
            if (!usage.isMissingNode()) {
                int inputTokens = usage.path("input_tokens").asInt(0);
                int outputTokens = usage.path("output_tokens").asInt(0);
                int cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
                int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
                log.info("Claude 生成完成 | output.length={} | attempt={} | tokens(in={}, out={}, cache_create={}, cache_read={})",
                        generatedText.length(), attempt, inputTokens, outputTokens, cacheCreation, cacheRead);
                if (cacheRead > 0) {
                    log.info("Prompt Cache 命中！節省 {} input tokens（約 {}% 成本）",
                            cacheRead, Math.round(cacheRead * 100.0 / (inputTokens + cacheRead)));
                }
            } else {
                log.info("Claude 生成完成 | output.length={} | attempt={}", generatedText.length(), attempt);
            }
            return generatedText;

        } catch (Exception e) {
            log.error("Claude API 呼叫失敗（attempt={}）：{}", attempt, e.getMessage(), e);
            return null;
        }
    }

    // ========================================================================
    // JSON Parse & Cleanup
    // ========================================================================

    private String tryParseJson(String json) {
        if (json == null || json.isBlank()) return "回傳內容為空";
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.has("ruleType")) return "缺少頂層欄位 'ruleType'";
            if (!root.has("rule")) return "缺少頂層欄位 'rule'";
            JsonNode rule = root.get("rule");
            if (!rule.has("inputs") || !rule.get("inputs").isArray()) return "rule.inputs 缺少或不是陣列";
            if (!rule.has("outputs") || !rule.get("outputs").isArray()) return "rule.outputs 缺少或不是陣列";

            String ruleType = root.get("ruleType").asText("");
            if ("DecisionTree".equalsIgnoreCase(ruleType)) {
                if (!rule.has("root") || !rule.get("root").isObject()) return "rule.root 缺少或不是物件";
            } else {
                if (!rule.has("rules") || !rule.get("rules").isArray()) return "rule.rules 缺少或不是陣列";
                if (rule.get("rules").isEmpty()) return "rule.rules 陣列為空";
            }
            return null;
        } catch (Exception e) {
            return "JSON parse 失敗：" + e.getMessage();
        }
    }

    private String stripMarkdown(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("```json")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) s = s.substring(start, end + 1);
        return s;
    }

    // ========================================================================
    // System Prompt
    // ========================================================================

    private String buildSystemPrompt(String ruleType) {
        if ("DecisionTree".equalsIgnoreCase(ruleType)) {
            return TREE_SYSTEM_PROMPT;
        }
        return TABLE_SYSTEM_PROMPT;
    }

    private static final String TABLE_SYSTEM_PROMPT = """
            你是資深業務規則分析師，專精 DMN 決策表設計。
            將自然語言業務規則描述轉換為完整的 RuleEnvelope JSON。
            RuleEnvelope 是 engine-neutral 的規則治理中介模型，供業務審查、測試與後續 adapter 轉換；
            不要宣稱輸出可直接部署到特定集團規則引擎。

            ★★★ 核心原則（必須遵守）★★★
            1. 先判斷建模策略，不要一律把正式 rules 展開成笛卡爾積。
            2. 若業務語意是分類、定價、核保決策等單一結果，使用 FIRST，正式 rules 可覆蓋完整情境。
            3. 若業務語意是多個獨立檢核、錯誤訊息可同時回傳、或使用者要求 MULTI，使用 MULTI，只輸出原子錯誤規則。
            4. MULTI 檢核表中 PASS 不需要列為 rule；沒有命中任何錯誤 rule 即代表 PASS。
            5. 情境展開是分析/測試用途，不等於正式 rule rows。
            6. 每條 rule 的 results 必須涵蓋所有 outputs 欄位
            7. 若描述提到下游或集團規則引擎，只表達為後續 exporter/adapter 轉換，不要在 reason 中宣稱已符合該引擎 schema。

            ★★★ 五步驟工作流程 ★★★
            Step 1（解析）：從描述中識別所有 input 維度及其取值區間，以及所有 output 欄位
            Step 2（策略）：判斷應使用 FIRST 還是 MULTI
            Step 3（規則）：FIRST 可列完整情境；MULTI 只列獨立原子規則，不列 PASS
            Step 4（自檢）：確認正式 rules 與業務語意一致，情境展開交由系統分析工具處理
            Step 5（輸出）：輸出純 JSON，不要 markdown 標記（不要 ```json）

            JSON Schema：
            以下範例使用 MULTI；若業務語意是單一結果分類，可改用 FIRST。
            {
              "ruleType": "DecisionTable",
              "reason": "（用中文解釋為何選此型態，>= 80 字）",
              "evaluation": {
                "completeness": "COMPLETE",
                "totalScenarios": N,
                "coverageRate": 1.0,
                "conflictDetection": "NO_CONFLICT",
                "recommendedStrategy": "MULTI"
              },
              "rule": {
                "hitPolicy": "MULTI",
                "inputs": [{ "name": "fieldName", "typeRef": "型別", "allowedValues": ["ENUM必填"] }],
                "outputs": [{ "name": "fieldName", "typeRef": "型別", "allowedValues": ["ENUM必填"] }],
                "rules": [{
                  "ruleId": "R01", "priority": 1,
                  "conditions": [{ "field": "f", "operator": "op", "value": v }],
                  "results": [{ "field": "f", "value": v }]
                }]
              }
            }

            ★★★ 型別與運算子 ★★★
            typeRef: INTEGER / DECIMAL / BOOLEAN / STRING / ENUM / DATE
            operator: equals / notEquals / greaterThan / greaterThanOrEqual / lessThan / lessThanOrEqual / between([min,max]) / in / notIn / isNull / isNotNull / anything

            ★★★ 格式要求（違反會導致驗證失敗）★★★
            - ENUM 的 inputs/outputs 定義必須附 allowedValues
            - BOOLEAN value 必須是 JSON boolean（true/false），不是字串
            - INTEGER/DECIMAL value 必須是 JSON number，不是字串
            - ruleId: R01, R02... 連續遞增，不可跳號
            - 每條 rule 的 conditions 必須涵蓋所有 inputs（不可省略）
            - 每條 rule 的 results 必須涵蓋所有 outputs（不可省略！）
            - 「拒保」或「不適用」時，數值欄位（DECIMAL/INTEGER）用 0，字串欄位用 "N/A"
            - 不要輸出空字串 "" 作為 result value，改用 0 或 "N/A"
            - 只輸出純 JSON，不要 markdown 標記（不要 ```json ... ```）

            ★★★ 邊界處理範例 ★★★
            - 年齡區間 18-35 用：{"field":"ageGroup","operator":"equals","value":"18-35"}（ENUM）
              或 {"field":"age","operator":"between","value":[18,35]}（INTEGER）
            - 性別用 ENUM + allowedValues: ["M","F"]
            - 是否有高血壓用 BOOLEAN: {"field":"hasHypertension","operator":"equals","value":true}
            - 拒保時保費係數：{"field":"premiumFactor","value":0}（不是空字串！）
            """;

    private static final String TREE_SYSTEM_PROMPT = """
            你是資深業務規則分析師，專精決策樹設計。將自然語言描述轉換為 DecisionTree RuleEnvelope JSON。
            RuleEnvelope 是 engine-neutral 的規則治理中介模型，供業務審查、測試與後續 adapter 轉換；
            不要宣稱輸出可直接部署到特定集團規則引擎。

            輸出 schema（必須完整含頂層 ruleType / rule，禁止簡化或省略欄位）：
            {
              "ruleType": "DecisionTree",
              "rule": {
                "inputs":  [ { "name": "<欄位>", "typeRef": "INTEGER|DECIMAL|BOOLEAN|STRING|ENUM|DATE", "allowedValues": [...] } ],
                "outputs": [ { "name": "<結果欄位>", "typeRef": "...", "allowedValues": [...] } ],
                "root":    <Node>
              }
            }

            Node 結構（n-ary tree，不是 binary；不要用 trueBranch/falseBranch）：
            - 分支節點：{ "nodeId": "Nxx", "condition": <Condition>, "branches": [<Branch>, ...] }
            - 葉節點：  { "nodeId": "Nxx", "results": [ { "field": "<output 名>", "value": <值> } ] }
            - Branch： { "label": "TRUE|FALSE|具體 ENUM 值", "condition": <Condition>, "child": <Node> }
              · TRUE 分支：condition 與父節點完全相同（equals/lessThanOrEqual 等）
              · FALSE 分支：condition 用相反 operator（equals→notEquals、lessThanOrEqual→greaterThan）
              · n-ary（ENUM 多分支）：每個 branch 各自 equals 到具體 allowedValue
            - Condition：{ "field": "...", "operator": "equals|notEquals|lessThan|lessThanOrEqual|greaterThan|greaterThanOrEqual|between|in|notIn|isNull|isNotNull|anything", "value": ... }
            - nodeId 用 N01, N02... 先序遞增

            極簡範例：
            {
              "ruleType": "DecisionTree",
              "rule": {
                "inputs":  [ { "name": "age", "typeRef": "INTEGER" } ],
                "outputs": [ { "name": "decision", "typeRef": "ENUM", "allowedValues": ["承保","拒保"] } ],
                "root": {
                  "nodeId": "N01",
                  "condition": { "field": "age", "operator": "lessThan", "value": 18 },
                  "branches": [
                    { "label": "TRUE",  "condition": { "field": "age", "operator": "lessThan", "value": 18 },
                      "child": { "nodeId": "N02", "results": [ { "field": "decision", "value": "拒保" } ] } },
                    { "label": "FALSE", "condition": { "field": "age", "operator": "greaterThanOrEqual", "value": 18 },
                      "child": { "nodeId": "N03", "results": [ { "field": "decision", "value": "承保" } ] } }
                  ]
                }
              }
            }

            **嚴格忠實規則**（最重要）：
            - inputs / outputs 欄位名稱必須由 description 萃取，禁止杜撰未提及的欄位
            - condition 的 value（數值門檻、ENUM 列舉、boolean）必須完全對應 description；description 寫「30 萬」就是 300000，寫「700 分」就是 700，**不可改成其他數字**
            - results 的 value 也必須對應 description；description 寫「拒賠」就是 "拒賠"，不要改成 "REJECT" 或其他字
            - 若 description 有歧義或未明示，採最接近字面意思的解讀，不要憑經驗造資料

            其他要求：
            - 涵蓋 description 描述的所有路徑（缺一條會被 validator 抓出）
            - 葉節點 results 必須包含所有 outputs 欄位
            - 只輸出純 JSON、UTF-8、不要 ```json``` 圍欄、不要任何解釋文字
            """;
}
