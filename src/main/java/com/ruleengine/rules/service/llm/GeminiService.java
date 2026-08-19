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
 * Gemini LLM 服務 — 將自然語言規則描述轉為 RuleEnvelope JSON。
 *
 * 僅在 rules.llm.enabled=true 時啟用。
 *
 * v1.3.0 變更：
 * - Prompt v2：5 步驟工作流程，強制窮舉笛卡爾積所有條件組合
 * - LLM Retry Level A：JSON parse 失敗自動 retry（最多 3 次）
 * - 環境變數：API Key 改由 ${GEMINI_API_KEY} 注入，啟動時檢查
 */
@Service
@Slf4j
public class GeminiService implements LlmProvider {

    /** JSON parse retry 最大次數 */
    private static final int MAX_RETRY = 3;

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 原本與 ClaudeService 共用同一個 rules.llm.api-key —— 兩家不可能同時設定，
    // 且 .env / k8s 注入的 GEMINI_API_KEY 從未被任何地方讀取。改為獨立命名空間。
    @Value("${rules.llm.gemini.api-key:${rules.llm.api-key:}}")
    private String apiKey;

    @Value("${rules.llm.gemini.model:${rules.llm.model:gemini-2.0-flash}}")
    private String model;

    @Value("${rules.llm.timeout-seconds:60}")
    private int timeoutSeconds;

    public GeminiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 啟動時初始化 RestTemplate 並檢查 API Key。
     * 在 @PostConstruct 初始化以使用 @Value 注入的 timeoutSeconds。
     */
    @PostConstruct
    void init() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutSeconds * 1000 / 3, 15_000));
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.restTemplate = new RestTemplate(factory);
        log.info("RestTemplate 已初始化（connectTimeout={}ms, readTimeout={}ms）",
                Math.min(timeoutSeconds * 1000 / 3, 15_000), timeoutSeconds * 1000);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("⚠️  GEMINI_API_KEY 未設定！自然語言生成功能將無法使用。"
                    + "請設定環境變數 GEMINI_API_KEY 或在 application-local.yml 中配置 rules.llm.api-key");
        } else {
            log.info("Gemini API Key 已載入（長度={}）", apiKey.length());
        }
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getProviderName() {
        return "Gemini (" + model + ")";
    }

    /**
     * 將自然語言描述轉為 RuleEnvelope JSON 字串。
     * <p>
     * 內建 Level A Self-Repair（Q5 第一階段）：
     * 若 Gemini 回傳的 JSON 無法 parse，會把錯誤訊息塞回 prompt 再呼叫一次，最多 retry 3 次。
     *
     * @param description 自然語言規則描述
     * @return RuleEnvelope JSON 字串，失敗時回傳 null
     */
    /** 當前請求的生成模式（thread-local，每次呼叫重設） */
    private final ThreadLocal<String> currentMode = ThreadLocal.withInitial(() -> "normal");

    @Override
    public String generateRuleJson(String description) {
        return generateRuleJson(description, "normal");
    }

    @Override
    public String generateRuleJson(String description, String mode) {
        return generateRuleJson(description, mode, "DecisionTable");
    }

    @Override
    public String generateRuleJson(String description, String mode, String ruleType) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Gemini API Key 未設定，無法生成規則");
            return null;
        }

        String resolvedMode = mode != null ? mode : "normal";
        String resolvedType = ruleType != null ? ruleType : "DecisionTable";
        currentMode.set(resolvedMode);
        try {
            return doGenerateRuleJson(description, resolvedMode, resolvedType);
        } finally {
            currentMode.remove();
        }
    }

    public String doGenerateRuleJson(String description, String mode, String ruleType) {
        log.info("生成模式：{}, 規則型態：{}", currentMode.get(), ruleType);

        // 根據 ruleType 選擇正確的 prompt
        String prompt = "DecisionTree".equalsIgnoreCase(ruleType)
                ? buildTreePrompt(description)
                : buildPrompt(description);

        // === 首次呼叫 ===
        String rawJson = callGeminiApi(prompt, 0);
        if (rawJson == null) {
            return null;
        }

        // === Level A Retry：JSON parse 失敗時自動修正 ===
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            String parseError = tryParseJson(rawJson);
            if (parseError == null) {
                // parse 成功
                log.info("JSON parse 成功（attempt={}）", attempt == 1 ? "首次" : attempt);
                return rawJson;
            }

            log.warn("JSON parse 失敗（retry {}/{}）| 原因：{}", attempt, MAX_RETRY, parseError);

            if (attempt == MAX_RETRY) {
                log.error("已達最大 retry 次數（{}），放棄生成", MAX_RETRY);
                return null;
            }

            // 建構修正 prompt，把錯誤訊息塞回去
            String repairPrompt = buildRepairPrompt(prompt, rawJson, parseError);
            rawJson = callGeminiApi(repairPrompt, attempt);
            if (rawJson == null) {
                return null;
            }
        }

        // 最後一次 retry 的結果也要檢查
        String finalError = tryParseJson(rawJson);
        if (finalError != null) {
            log.error("最終 retry 後 JSON 仍無法 parse：{}", finalError);
            return null;
        }
        return rawJson;
    }

    // ========================================================================
    // Level B Self-Repair：驗證錯誤 + 覆蓋缺口回饋修復
    // ========================================================================

    /**
     * 根據驗證錯誤和覆蓋缺口修復已生成的 RuleEnvelope JSON。
     * <p>
     * 這是 Level B Self-Repair — 比 Level A（JSON parse 修復）更高層次：
     * 接收語意層級的錯誤（INVALID_ENUM_VALUE、INCONSISTENT_TABLE 等）和覆蓋缺口，
     * 讓 LLM 修正具體的規則邏輯。
     */
    @Override
    public String repairRuleJson(String originalDescription, String currentJson, String issues) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String repairPrompt = buildSemanticRepairPrompt(originalDescription, currentJson, issues);
        log.info("Level B Self-Repair | issues.length={}", issues.length());

        String rawJson = callGeminiApi(repairPrompt, 0);
        if (rawJson == null) return null;

        // 快速結構檢查
        String parseError = tryParseJson(rawJson);
        if (parseError != null) {
            log.warn("Level B repair 回傳的 JSON 結構有誤：{}", parseError);
            return null;
        }

        return rawJson;
    }

    /**
     * 建構語意修復 prompt — 包含具體的驗證錯誤和覆蓋缺口。
     */
    private String buildSemanticRepairPrompt(String originalDescription, String currentJson, String issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 角色\n");
        sb.append("你是金控集團的資深業務規則分析師。你之前已經生成了一份 DecisionTable，但經過系統驗證後發現了一些問題。\n");
        sb.append("請根據以下問題清單修正規則，輸出修正後的完整 RuleEnvelope JSON。\n\n");

        sb.append("# 需要修復的問題\n");
        sb.append(issues);
        sb.append("\n\n");

        sb.append("# 修復要求\n");
        sb.append("1. 只修正有問題的規則，不要動其他正確的規則\n");
        sb.append("2. 如果有覆蓋缺口（gap），新增規則來覆蓋缺失的條件組合\n");
        sb.append("3. 如果有衝突（INCONSISTENT_TABLE），調整 conditions 讓規則互斥\n");
        sb.append("4. 如果有型別錯誤（TYPE_MISMATCH），修正 value 的型別\n");
        sb.append("5. 如果有 ENUM 值錯誤（INVALID_ENUM_VALUE），使用 allowedValues 內的值\n");
        sb.append("6. 如果有缺少結果（MISSING_RESULTS），補齊所有 output 欄位的 value\n");
        sb.append("7. 新增的 ruleId 接續現有最大值遞增\n");
        sb.append("8. 只輸出純 JSON，不要 markdown 標記\n\n");

        sb.append("# 原始業務描述\n");
        sb.append(originalDescription).append("\n\n");

        // 附上當前 JSON（如果不太大）
        if (currentJson != null && currentJson.length() < 12000) {
            sb.append("# 當前的 RuleEnvelope JSON（請以此為基礎修正）\n");
            sb.append(currentJson).append("\n");
        }

        return sb.toString();
    }

    // ========================================================================
    // Gemini API 呼叫
    // ========================================================================

    /**
     * 呼叫 Gemini API 並提取生成文字。
     *
     * @param prompt  完整 prompt
     * @param attempt 當前 attempt（0=首次, 1~3=retry）
     * @return 生成的文字，失敗回 null
     */
    private String callGeminiApi(String prompt, int attempt) {
        String url = String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                model, apiKey);

        try {
            Map<String, Object> generationConfig = new java.util.HashMap<>();
            generationConfig.put("temperature", 0.1);
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("maxOutputTokens", 65536);

            String mode = currentMode.get();
            log.info("Gemini mode={}", mode);

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", prompt)))
                    ),
                    "generationConfig", generationConfig
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("呼叫 Gemini API | model={} | attempt={} | prompt.length={}",
                    model, attempt, prompt.length());
            long startMs = System.currentTimeMillis();

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            long durationMs = System.currentTimeMillis() - startMs;
            log.info("Gemini API 回應 | status={} | durationMs={} | attempt={}",
                    response.getStatusCode(), durationMs, attempt);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Gemini API 回傳非 200：{}", response.getStatusCode());
                return null;
            }

            JsonNode responseJson = objectMapper.readTree(response.getBody());
            JsonNode candidates = responseJson.path("candidates");
            if (candidates.isEmpty()) {
                log.error("Gemini API 回傳無 candidates");
                return null;
            }

            // 檢查 finishReason — 截斷偵測
            String finishReason = candidates.get(0).path("finishReason").asText("");
            if ("MAX_TOKENS".equals(finishReason)) {
                log.warn("Gemini 回應被截斷（finishReason=MAX_TOKENS）");
            }

            String generatedText = candidates.get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            log.info("Gemini 生成完成 | output.length={} | attempt={}", generatedText.length(), attempt);
            return generatedText;

        } catch (Exception e) {
            log.error("Gemini API 呼叫失敗（attempt={}）：{}", attempt, e.getMessage(), e);
            return null;
        }
    }

    // ========================================================================
    // JSON Parse 檢查（Level A Self-Repair）
    // ========================================================================

    /**
     * 嘗試 parse JSON 並做基本結構檢查。
     *
     * @return 錯誤訊息，null 表示成功
     */
    private String tryParseJson(String json) {
        if (json == null || json.isBlank()) {
            return "回傳內容為空";
        }
        try {
            JsonNode root = objectMapper.readTree(json);

            // 基本結構檢查：必須有 ruleType + rule
            if (!root.has("ruleType")) {
                return "缺少頂層欄位 'ruleType'";
            }
            if (!root.has("rule")) {
                return "缺少頂層欄位 'rule'";
            }
            JsonNode rule = root.get("rule");
            if (!rule.has("inputs") || !rule.get("inputs").isArray()) {
                return "rule.inputs 缺少或不是陣列";
            }
            if (!rule.has("outputs") || !rule.get("outputs").isArray()) {
                return "rule.outputs 缺少或不是陣列";
            }
            String ruleType = root.get("ruleType").asText("");
            if ("DecisionTree".equalsIgnoreCase(ruleType)) {
                if (!rule.has("root") || !rule.get("root").isObject()) {
                    return "rule.root 缺少或不是物件";
                }
            } else {
                if (!rule.has("rules") || !rule.get("rules").isArray()) {
                    return "rule.rules 缺少或不是陣列";
                }
                if (rule.get("rules").isEmpty()) {
                    return "rule.rules 陣列為空（必須至少有 1 條規則）";
                }
            }
            return null; // 成功
        } catch (Exception e) {
            return "JSON parse 失敗：" + e.getMessage();
        }
    }

    // ========================================================================
    // Repair Prompt（Level A）
    // ========================================================================

    /**
     * 建構修正 prompt：把上一次的錯誤和原始 prompt 組合。
     */
    private String buildRepairPrompt(String originalPrompt, String brokenJson, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("【修正指示】\n");
        sb.append("上一次生成的 JSON 有以下錯誤：").append(error).append("\n");
        sb.append("請修正並重新輸出完整 JSON。注意：\n");
        sb.append("1. 只輸出純 JSON，不要包含 markdown 程式碼區塊標記\n");
        sb.append("2. 確保 JSON 結構完整（所有括號都正確關閉）\n");
        sb.append("3. 必須包含 ruleType、reason、evaluation、rule 四個頂層欄位\n");
        sb.append("4. rule 內必須有 hitPolicy、inputs、outputs、rules\n\n");

        // 如果上次的 JSON 不太長，附上讓 LLM 參考修正
        if (brokenJson != null && brokenJson.length() < 8000) {
            sb.append("上一次的（有錯誤的）輸出如下，請以此為基礎修正：\n");
            sb.append(brokenJson).append("\n\n");
        }

        sb.append("====================================\n");
        sb.append("以下是原始要求，請根據此要求重新生成正確的完整 JSON：\n\n");
        sb.append(originalPrompt);

        return sb.toString();
    }

    // ========================================================================
    // Prompt v2（Q1 — 5 步驟工作流程 + 完整 Schema + 範例）
    // ========================================================================

    /**
     * 建構 Prompt v2 — 5 步驟工作流程 + 完整 RuleEnvelope 規格 + Few-Shot 範例。
     * <p>
     * v1.3.0 重大改版：
     * - 明確的 step-by-step 思考流程
     * - 內嵌 mini example 讓 LLM 理解期望的「窮舉」行為
     * - 強化 reason 品質要求
     * - 目標：同樣輸入從 7 條規則提升到 15+ 條
     */
    /**
     * 建構 DecisionTree 專用 Prompt — 階層式條件分支。
     */
    public String buildTreePrompt(String description) {
        return """
                # 角色
                你是金控集團的資深業務規則分析師，專精決策樹（DecisionTree）設計。
                你的任務是將自然語言業務規則描述轉換為完整的 DecisionTree RuleEnvelope JSON。

                # 核心原則
                ★ **階層原則**：條件按重要性/依賴性分層，最重要的判斷放在樹的根部
                ★ **完整原則**：每個分支節點的所有分支都必須有對應子節點
                ★ **互斥原則**：各分支條件互斥不衝突
                ★ **N-ary 統一結構**：所有分支節點一律使用 `branches` 陣列，禁止使用 trueBranch/falseBranch

                # DecisionTree RuleEnvelope JSON Schema（n-ary，唯一格式）
                ```json
                {
                  "ruleType": "DecisionTree",
                  "reason": "（中文 >= 50 字）說明條件的層級關係和決策邏輯",
                  "evaluation": {
                    "completeness": "COMPLETE",
                    "totalScenarios": <葉節點數量>,
                    "coverageRate": 1.0,
                    "conflictDetection": "NO_CONFLICT",
                    "recommendedStrategy": "FIRST"
                  },
                  "rule": {
                    "inputs":  [ { "name": "fieldName", "typeRef": "型別", "allowedValues": ["值（ENUM必填）"] } ],
                    "outputs": [ { "name": "fieldName", "typeRef": "型別", "allowedValues": ["值（ENUM必填）"] } ],
                    "root": <Node>
                  }
                }
                ```

                # Node 結構
                - 分支節點：{ "nodeId": "Nxx", "condition": <Condition>, "branches": [<Branch>, ...] }
                - 葉節點：  { "nodeId": "Nxx", "results": [ { "field": "<output 名>", "value": <值> } ] }
                - Branch： { "label": "TRUE|FALSE|具體 ENUM 值", "condition": <Condition>, "child": <Node> }
                  · 二元（boolean / 數值閾值）：TRUE 分支 condition 與父完全相同；FALSE 分支用相反 operator（equals→notEquals、lessThanOrEqual→greaterThan）
                  · n-ary（ENUM 多值）：每個 branch 各自 equals 到具體 allowedValue
                - Condition：{ "field": "...", "operator": "...", "value": ... }

                # 極簡範例
                ```json
                {
                  "nodeId": "N01",
                  "condition": { "field": "age", "operator": "lessThan", "value": 18 },
                  "branches": [
                    { "label": "TRUE",  "condition": { "field": "age", "operator": "lessThan", "value": 18 },
                      "child": { "nodeId": "N02", "results": [ { "field": "decision", "value": "拒保" } ] } },
                    { "label": "FALSE", "condition": { "field": "age", "operator": "greaterThanOrEqual", "value": 18 },
                      "child": { "nodeId": "N03", "results": [ { "field": "decision", "value": "承保" } ] } }
                  ]
                }
                ```

                # 節點規則
                - 分支節點：必須有 condition + branches（不可有 results）
                - 葉節點：必須有 results（不可有 condition/branches）
                - nodeId 格式：N01, N02... 按先序遍歷遞增
                - 每個葉節點的 results 必須涵蓋所有 output 欄位

                # typeRef / operator 規格
                typeRef: INTEGER / DECIMAL / BOOLEAN / STRING / ENUM / DATE
                operator: equals / notEquals / greaterThan / greaterThanOrEqual / lessThan / lessThanOrEqual / between / in / notIn / isNull / isNotNull / anything

                # 嚴格忠實規則（最重要）
                - inputs / outputs 欄位名稱必須由 description 萃取，禁止杜撰未提及的欄位
                - condition 的 value（數值門檻、ENUM 列舉、boolean）必須完全對應 description；description 寫「30 萬」就是 300000，「700 分」就是 700，**不可改成其他數字**
                - results 的 value 必須對應 description；description 寫「拒賠」就是 "拒賠"，不要改成 "REJECT" 或其他字
                - 若 description 有歧義或未明示，採最接近字面意思的解讀，不要憑經驗造資料

                # 格式嚴格要求
                1. ENUM 的 inputs/outputs 必須附 allowedValues
                2. BOOLEAN 的 value 是 JSON boolean（true/false）
                3. INTEGER 的 value 是 JSON number
                4. 只輸出純 JSON、UTF-8、不要 markdown 圍欄、不要任何解釋文字

                ====================================
                # 請轉換以下規則描述為 DecisionTree

                """ + PromptGuard.systemGuardSection() + PromptGuard.wrap(description);
    }

    private String buildPrompt(String description) {
        return """
                # 角色
                你是金控集團的資深業務規則分析師，專精 DMN（Decision Model and Notation）決策表設計。
                你的任務是將自然語言業務規則描述轉換為 **完整、無遺漏** 的 RuleEnvelope JSON。

                # 核心原則
                ★ **窮舉原則**：必須覆蓋所有條件組合，不可只寫幾條「代表性」規則
                ★ **互斥原則**：FIRST 策略下，任意輸入只能命中恰好一條規則，規則之間不可重疊
                ★ **完整原則**：coverageRate = 1.0，任何合法輸入都必須命中至少一條規則

                # 你的工作流程 — 嚴格按照 5 步驟執行

                ## 第 1 步：解析條件維度
                仔細閱讀描述，識別所有 input 欄位。對每個欄位確定：
                - 欄位英文名（camelCase）
                - typeRef（INTEGER / DECIMAL / BOOLEAN / STRING / ENUM / DATE）
                - 所有可能的取值分段
                  - 數值型：根據描述中的閾值切分成**互斥且完整**的區間
                  - BOOLEAN：true / false（2 種）
                  - ENUM：列出所有 allowedValues

                示範：描述提到「年齡 18~35 標準、36~50 加費、51~65 高費、65以上拒保」
                → age: INTEGER, 分 4 段: [18,35], [36,50], [51,65], greaterThan 65

                ## 第 2 步：計算笛卡爾積
                將每個維度的取值數量相乘 → 得到理論最大規則數。
                例如：4(年齡) × 2(高血壓) × 2(糖尿病) × 3(BMI區間) = 48 種組合。
                **你生成的規則數必須接近此數字。** 只有在多個組合的「所有 output 欄位完全相同」時，
                才可以用 anything 合併——但合併後的覆蓋範圍仍需等於原始組合數。

                ## 第 3 步：逐一指定結果
                針對每個組合，根據業務邏輯指定所有 output 欄位的值：
                - 風險越高 → 費率加高 / 拒保
                - 風險越低 → 標準承保 / 優惠
                - 每條 rule 的 results 必須涵蓋所有 output 欄位，一個都不能少

                ## 第 4 步：自我檢查
                回頭逐一檢驗：
                1. 是否每個可能的輸入組合都恰好命中一條規則？
                2. 是否有兩條規則的 conditions 存在交集（重疊）？
                3. 規則總數是否接近笛卡爾積？若遠少於笛卡爾積，說明遺漏了組合。
                4. 所有 ruleId 是否從 R01 連續遞增？

                ## 第 5 步：組裝 JSON

                # RuleEnvelope JSON Schema

                ```json
                {
                  "ruleType": "DecisionTable",
                  "reason": "（中文 >= 80 字）本規則集包含 N 個條件欄位：{列出每個欄位名稱與中文說明}，以及 M 個結果欄位：{列出每個欄位名稱與中文說明}。條件維度的笛卡爾積共 K 種組合，生成 L 條規則（其中 X 條使用 anything 合併了結果相同的組合）。採用 FIRST 命中策略，因為每個輸入組合只需對應唯一決策結果。",
                  "evaluation": {
                    "completeness": "COMPLETE",
                    "totalScenarios": <規則條數>,
                    "coverageRate": 1.0,
                    "conflictDetection": "NO_CONFLICT",
                    "recommendedStrategy": "FIRST"
                  },
                  "rule": {
                    "hitPolicy": "FIRST",
                    "inputs": [
                      { "name": "fieldName", "typeRef": "型別", "allowedValues": ["值（ENUM必填）"] }
                    ],
                    "outputs": [
                      { "name": "fieldName", "typeRef": "型別", "allowedValues": ["值（ENUM必填）"] }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01",
                        "priority": 1,
                        "conditions": [
                          { "field": "fieldName", "operator": "操作符", "value": <值> }
                        ],
                        "results": [
                          { "field": "fieldName", "value": <值> }
                        ]
                      }
                    ]
                  }
                }
                ```

                # typeRef 型別定義
                | typeRef | 說明 | value 格式 |
                |---------|------|-----------|
                | INTEGER | 整數 | 42 |
                | DECIMAL | 小數 | 3.14 |
                | BOOLEAN | 布林 | true / false（不是字串） |
                | STRING  | 文字 | "text" |
                | ENUM    | 列舉 | "value"，inputs/outputs 必附 allowedValues |
                | DATE    | 日期 | "yyyy-MM-dd" |

                # operator 運算子定義
                | operator | 適用型別 | value 格式 | 說明 |
                |----------|---------|-----------|------|
                | equals | 全部 | 單一值 | 等於 |
                | notEquals | 全部 | 單一值 | 不等於 |
                | greaterThan | INTEGER,DECIMAL,DATE | 單一值 | 大於 |
                | greaterThanOrEqual | INTEGER,DECIMAL,DATE | 單一值 | 大於等於 |
                | lessThan | INTEGER,DECIMAL,DATE | 單一值 | 小於 |
                | lessThanOrEqual | INTEGER,DECIMAL,DATE | 單一值 | 小於等於 |
                | between | INTEGER,DECIMAL,DATE | [min, max]（含兩端） | 區間 |
                | in | STRING,ENUM | ["a","b"] | 在集合中 |
                | notIn | STRING,ENUM | ["a","b"] | 不在集合中 |
                | isNull | 全部 | 不需 value | 為空 |
                | isNotNull | 全部 | 不需 value | 不為空 |
                | anything | 全部 | 不需 value | 任意值（萬用） |

                # 格式嚴格要求
                1. ENUM 型別的 inputs/outputs 必須附 allowedValues 陣列
                2. BOOLEAN 的 value 是 JSON boolean（true/false），不是字串
                3. INTEGER 的 value 是 JSON number（42），不是字串
                4. ruleId 格式：R01, R02, R03... 連續遞增，不可跳號
                5. 每條 rule 的 conditions 必須涵蓋所有 inputs 欄位
                6. 每條 rule 的 results 必須涵蓋所有 outputs 欄位
                7. 只輸出純 JSON，不要任何 markdown 標記或額外文字

                # ★ 邊界值規則（極重要）
                切分數值區間時，相鄰區間的邊界必須無縫銜接，不可有缺口：
                - 正確：between [0,29] + greaterThanOrEqual 30（30 被第二段覆蓋）
                - 正確：lessThanOrEqual 29 + between [30,100]（29 和 30 都被覆蓋）
                - 錯誤：lessThan 30 + greaterThan 30（30 本身沒被覆蓋！）
                - 錯誤：between [0,30] + greaterThan 30（如果描述說「超過30」應含30，用 greaterThanOrEqual）
                優先使用 between + greaterThanOrEqual / lessThanOrEqual 組合，確保每個數值都恰好被一條規則覆蓋。

                # Few-Shot 範例

                描述：「根據會員等級（金/銀/銅）和消費金額（<1000 / 1000~5000 / >5000）決定折扣率」

                分析：
                - 維度 1: memberLevel (ENUM) → 金/銀/銅 = 3 種
                - 維度 2: purchaseAmount (INTEGER) → <1000 / 1000~5000 / >5000 = 3 段
                - 笛卡爾積 = 3 × 3 = 9 條規則

                生成 9 條規則（R01~R09），每條涵蓋一個唯一的 (memberLevel, purchaseAmount) 組合。
                不可只寫 3 條「金=20%, 銀=10%, 銅=5%」就交差——那遺漏了消費金額維度。

                ====================================
                # 請轉換以下規則描述

                """ + PromptGuard.systemGuardSection() + PromptGuard.wrap(description);
    }
}
