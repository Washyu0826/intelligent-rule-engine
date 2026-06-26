package com.ruleengine.rules.mcp;

import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.RuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MCP Tools（業務規則治理與 RuleEnvelope 中介模型）
 *
 * 以 Spring AI @Tool annotation 暴露 MCP 工具。
 * tool 的 description 是 AI 判斷何時呼叫、以及如何生成正確 JSON 的關鍵。
 *
 * 設計原則：
 * - description 要精確描述 RuleEnvelope 結構（設計文件 §3）
 * - AI 可直接傳入自然語言或完整 JSON，server 會做正規化、驗證與分析
 * - RuleEnvelope 是 engine-neutral 中介模型；正式導入下游引擎需由 exporter/adapter 轉換
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RulesMcpToolService {

    private final RuleService ruleService;
    private final ObjectMapper objectMapper;
    private final com.ruleengine.rules.service.optimizer.TreeOptimizer treeOptimizer;
    private final com.ruleengine.rules.service.InputSuggestionService inputSuggestionService;
    private final com.ruleengine.rules.service.RuleLookupService ruleLookupService;
    private final com.ruleengine.rules.service.narrative.BusinessNarrativeService businessNarrativeService;
    private final com.ruleengine.rules.service.optimizer.v2.TreeOptimizerV2 treeOptimizerV2;
    /** AsyncExecutorConfig 提供；同型別兩顆 bean 依建構子參數名對應 */
    private final java.util.concurrent.ExecutorService llmExecutor;
    private final com.ruleengine.rules.config.LlmTimeoutPolicy llmTimeoutPolicy;

    // ========================================================================
    //  Tool 1: generate_rule_payload（UC-01）
    // ========================================================================
    @Tool(description = """
            將業務規格轉換為結構化的 RuleEnvelope JSON。
            RuleEnvelope 是 engine-neutral 規則治理中介模型，供審查、測試、情境展開與後續 exporter/adapter 轉換；
            不代表已經是任何特定集團規則引擎的部署格式。
            支援兩種規則型態：DecisionTable（平行條件組合）和 DecisionTree（階層條件分支）。

            【使用方式】
            你（AI）需要先把使用者的自然語言規則描述轉為以下 JSON 格式，
            然後將完整 JSON 字串作為 description 參數傳入。
            本工具會做正規化、欄位白名單檢查、evaluation 計算、以及自動驗證。

            ===== DecisionTable 格式 =====
            {
              "ruleType": "DecisionTable",
              "reason": "用中文說明為何選用此規則型態",
              "evaluation": {
                "completeness": "COMPLETE",
                "totalScenarios": 18,
                "coverageRate": 1.0,
                "conflictDetection": "NO_CONFLICT",
                "recommendedStrategy": "FIRST"
              },
              "rule": {
                "hitPolicy": "FIRST",
                "inputs": [
                  { "name": "age", "typeRef": "INTEGER" },
                  { "name": "gender", "typeRef": "ENUM", "allowedValues": ["male","female"] },
                  { "name": "hypertension", "typeRef": "BOOLEAN" }
                ],
                "outputs": [
                  { "name": "decision", "typeRef": "ENUM", "allowedValues": ["承保","人工評估","拒保"] },
                  { "name": "premium_rate", "typeRef": "DECIMAL" },
                  { "name": "remark", "typeRef": "STRING" }
                ],
                "rules": [
                  {
                    "ruleId": "R01",
                    "priority": 1,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [18, 35] },
                      { "field": "hypertension", "operator": "equals", "value": false }
                    ],
                    "results": [
                      { "field": "decision", "value": "承保" },
                      { "field": "premium_rate", "value": 1.0 }
                    ]
                  }
                ]
              }
            }

            【typeRef 型別（6 種）】
            INTEGER - 整數 | DECIMAL - 小數 | BOOLEAN - true/false
            STRING - 文字 | ENUM - 列舉（必附 allowedValues）| DATE - yyyy-MM-dd

            【operator 運算子（12 種）】
            equals / notEquals - 全部型別
            greaterThan / greaterThanOrEqual / lessThan / lessThanOrEqual - INTEGER,DECIMAL,DATE
            between - [min,max] 含兩端 - INTEGER,DECIMAL,DATE
            in / notIn - ["a","b"] - STRING,ENUM
            isNull / isNotNull - 全部（不需 value）
            anything - 全部（不需 value，任意值匹配）

            【hitPolicy】
            FIRST - 條件互斥，命中第一條即停（預設）
            MULTI - 條件可重疊，多條可同時命中

            ===== DecisionTree 格式（Phase 3 新增；n-ary 統一結構）=====
            適用於「先看 A，再看 B」的階層式條件判斷。所有分支一律使用 branches 陣列。
            {
              "ruleType": "DecisionTree",
              "reason": "條件有層級依賴，先看年齡再看健康狀態",
              "rule": {
                "inputs": [
                  { "name": "age", "typeRef": "INTEGER" },
                  { "name": "has_diabetes", "typeRef": "BOOLEAN" }
                ],
                "outputs": [
                  { "name": "decision", "typeRef": "ENUM", "allowedValues": ["承保","拒保","人工評估"] }
                ],
                "root": {
                  "nodeId": "N01",
                  "condition": { "field": "age", "operator": "greaterThan", "value": 60 },
                  "branches": [
                    {
                      "label": "TRUE",
                      "condition": { "field": "age", "operator": "greaterThan", "value": 60 },
                      "child": { "nodeId": "N02", "results": [{ "field": "decision", "value": "拒保" }] }
                    },
                    {
                      "label": "FALSE",
                      "condition": { "field": "age", "operator": "lessThanOrEqual", "value": 60 },
                      "child": {
                        "nodeId": "N03",
                        "condition": { "field": "has_diabetes", "operator": "equals", "value": true },
                        "branches": [
                          { "label": "TRUE",  "condition": { "field": "has_diabetes", "operator": "equals", "value": true },
                            "child": { "nodeId": "N04", "results": [{ "field": "decision", "value": "人工評估" }] } },
                          { "label": "FALSE", "condition": { "field": "has_diabetes", "operator": "notEquals", "value": true },
                            "child": { "nodeId": "N05", "results": [{ "field": "decision", "value": "承保" }] } }
                        ]
                      }
                    }
                  ]
                }
              }
            }

            【DecisionTree 節點規則】
            - 分支節點：必須有 condition + branches 陣列
            - Branch：{ label, condition, child } — 二元用 TRUE/FALSE，n-ary 用具體 ENUM 值
            - 葉節點：必須有 results（涵蓋所有 output 欄位）
            - nodeId 格式：N01, N02... 不可重複
            - DecisionTree 不需要 hitPolicy

            【重要規則】
            1. ENUM typeRef 必須附 allowedValues
            2. BOOLEAN 的 value 必須是 true/false（不是字串）
            3. INTEGER 的 value 必須是整數
            4. DecisionTable: ruleId R01, R02... 遞增；DecisionTree: nodeId N01, N02... 不重複
            5. DecisionTable 必須窮舉所有合理條件組合；DecisionTree 每個分支必須完整
            """)
    public String generateRulePayload(
            @ToolParam(description = "RuleEnvelope JSON 字串（由你根據使用者描述先轉好），或自然語言描述")
            String description,
            @ToolParam(description = "規則型態：DecisionTable 或 DecisionTree")
            String ruleType,
            @ToolParam(description = "允許的欄位白名單 JSON 陣列（選填），如 [\"age\",\"gender\"]")
            String allowedFieldsJson
    ) {
        log.info("MCP generate_rule_payload | ruleType={}", ruleType);

        List<String> allowedFields = parseStringList(allowedFieldsJson);

        RuleEnvelope result = ruleService.generate(GenerateRequest.builder()
                .description(description)
                .ruleType(ruleType != null ? ruleType : "DecisionTable")
                .allowedFields(allowedFields)
                .build());

        return toJson(result);
    }

    // ========================================================================
    //  Tool 2: validate_rule_schema（UC-02）
    // ========================================================================
    @Tool(description = """
            驗證 RuleEnvelope JSON 的正確性。執行 11 個錯誤碼檢查：

            MISSING_FIELD        - 缺少必要欄位（ruleType, hitPolicy, inputs, outputs, rules 等）
            UNKNOWN_OPERATOR     - condition 使用了不支援的 operator
            TYPE_MISMATCH        - value 型態與 typeRef 不匹配（如 BOOLEAN 欄位給了字串）
            UNKNOWN_FIELD        - condition/result 引用了不在 inputs/outputs 中的欄位
            DUPLICATE_ID         - ruleId 重複
            ENUM_VALUE_MISSING   - ENUM 型別缺少 allowedValues
            INVALID_ENUM_VALUE   - result 值不在 ENUM 的 allowedValues 內
            INCONSISTENT_TABLE   - FIRST 策略下規則條件重疊可同時命中（含觸發條件範例）
            INVALID_MULTI        - MULTI hitPolicy 相關錯誤
            MISSING_BRANCH       - DecisionTree 非葉節點缺分支
            MISSING_RESULTS      - DecisionTree 葉節點缺 results

            回傳格式：{ "valid": true/false, "errors": [{ "code": "...", "message": "..." }] }
            errors 中的 message 為中文白話描述，INCONSISTENT_TABLE 會附上可同時觸發的條件範例。
            """)
    public String validateRuleSchema(
            @ToolParam(description = "要驗證的 RuleEnvelope JSON 字串")
            String ruleJsonStr,
            @ToolParam(description = "規則型態：本階段請使用 DecisionTable（選填，JSON 中有 ruleType 可不填）")
            String ruleType
    ) {
        log.info("MCP validate_rule_schema | ruleType={}", ruleType);

        JsonNode ruleJson;
        try {
            ruleJson = objectMapper.readTree(ruleJsonStr);
        } catch (Exception e) {
            return toJson(ValidateResponse.builder()
                    .valid(false)
                    .errors(List.of(ValidationError.builder()
                            .code("INVALID_JSON")
                            .message("無法解析 JSON：" + e.getMessage())
                            .build()))
                    .build());
        }

        ValidateResponse result = ruleService.validate(ValidateRequest.builder()
                .ruleJson(ruleJson)
                .ruleType(ruleType)
                .build());

        return toJson(result);
    }

    // ========================================================================
    //  Tool 3: recommend_rule_type（UC-03）
    // ========================================================================
    @Tool(description = """
            根據自然語言規則描述推薦最適合的規則型態。

            回傳格式：
            {
              "recommendedRuleType": "DecisionTable",
              "reason": "...",
              "confidence": 0.92,
              "alternatives": [...]
            }

            注意：
            - recommender 仍可能回傳 DecisionTree / ScoreCard 作為概念性建議
            - 但本階段正式可生成與驗證的型態只有 DecisionTable
            - 若要進入實際 generate 流程，請以 DecisionTable 為準
            """)
    public String recommendRuleType(
            @ToolParam(description = "自然語言規則描述")
            String description
    ) {
        log.info("MCP recommend_rule_type");

        RecommendResponse result = ruleService.recommend(RecommendRequest.builder()
                .description(description)
                .build());

        return toJson(result);
    }

    // ========================================================================
    //  Tool 4: analyze_rule_payload
    // ========================================================================
    @Tool(description = """
            分析 RuleEnvelope JSON 的覆蓋率、缺口、重疊與可簡化規則。

            回傳格式：
            {
              "coverageRate": 0.87,
              "gaps": [...],
              "overlaps": [...],
              "simplifications": [...]
            }

            適用情境：
            - 檢查 DecisionTable 是否有 coverage gap
            - 找出 FIRST hitPolicy 下的條件重疊
            - 找出可合併或可簡化的規則
            """)
    public String analyzeRulePayload(
            @ToolParam(description = "要分析的 RuleEnvelope JSON 字串")
            String ruleJsonStr,
            @ToolParam(description = "規則型態：本階段請使用 DecisionTable（選填，JSON 中有 ruleType 可不填）")
            String ruleType
    ) {
        log.info("MCP analyze_rule_payload | ruleType={}", ruleType);

        JsonNode ruleJson;
        try {
            ruleJson = objectMapper.readTree(ruleJsonStr);
        } catch (Exception e) {
            return toJson(java.util.Map.of(
                    "error", "INVALID_JSON",
                    "message", "無法解析 JSON：" + e.getMessage()
            ));
        }

        AnalyzeResponse result = ruleService.analyze(AnalyzeRequest.builder()
                .ruleJson(ruleJson)
                .ruleType(ruleType)
                .build());

        return toJson(result);
    }

    // ========================================================================
    //  Tool 5: test_run_rules
    // ========================================================================
    @Tool(description = """
            批次執行規則生成與驗證測試。

            請傳入 testCases 的 JSON 陣列字串，例如：
            [
              {
                "id": "TC01",
                "description": "...",
                "expectedRuleType": "DecisionTable",
                "expectValid": true
              }
            ]

            回傳總數、通過率、錯誤分布與逐筆結果。
            適合用來做 regression smoke test 或 prompt 調整後的快速驗收。
            """)
    public String testRunRules(
            @ToolParam(description = "測試案例 JSON 陣列字串")
            String testCasesJson
    ) {
        log.info("MCP test_run_rules");

        List<TestCase> testCases;
        try {
            testCases = objectMapper.readValue(
                    testCasesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TestCase.class)
            );
        } catch (Exception e) {
            return toJson(java.util.Map.of(
                    "error", "INVALID_JSON",
                    "message", "無法解析 testCases JSON：" + e.getMessage()
            ));
        }

        // 與 REST /test-run 同一逾時政策：經 llmExecutor 跑、逾時 cancel(true) 中止剩餘 case，
        // 避免 MCP 客戶端早已放棄而 server 執行緒繼續燒 token（REST 入口在 v3.16.1 修過的同一問題）
        TestRunRequest req = TestRunRequest.builder().testCases(testCases).build();
        long timeoutMs = llmTimeoutPolicy.asyncTimeoutMs(testCases.size());
        java.util.concurrent.Future<TestRunResponse> future;
        try {
            future = llmExecutor.submit(() -> ruleService.testRun(req));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return toJson(java.util.Map.of(
                    "error", "BUSY",
                    "message", "LLM 工作池滿載，請稍後重試"
            ));
        }
        try {
            return toJson(future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return toJson(java.util.Map.of(
                    "error", "TIMEOUT",
                    "message", "批次測試逾時（" + timeoutMs / 1000 + " 秒），已中止剩餘 case；請減少 case 數或分批執行"
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return toJson(java.util.Map.of("error", "INTERRUPTED", "message", "批次測試被中斷"));
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return toJson(java.util.Map.of(
                    "error", "RUNTIME_ERROR",
                    "message", String.valueOf(cause.getMessage())
            ));
        }
    }

    // ========================================================================
    //  Tool 6: convert_rule_type（Phase 3 — 雙向轉換）
    // ========================================================================
    @Tool(description = """
            將 RuleEnvelope 在 DecisionTable 和 DecisionTree 之間雙向轉換。

            支援的轉換：
            - DecisionTree → DecisionTable：將樹的所有路徑展平為表格規則
            - DecisionTable → DecisionTree：使用 ID3 資訊增益演算法建構最優決策樹

            限制：
            - MULTI hitPolicy 的 DecisionTable 無法轉為 DecisionTree
            - 轉換後建議重新呼叫 validate 驗證結果

            回傳格式：完整的 RuleEnvelope JSON（ruleType 已更新為目標型態）
            """)
    public String convertRuleType(
            @ToolParam(description = "原始 RuleEnvelope JSON 字串")
            String ruleJsonStr,
            @ToolParam(description = "目標規則型態：DecisionTable 或 DecisionTree")
            String targetType
    ) {
        log.info("MCP convert_rule_type | targetType={}", targetType);

        JsonNode ruleJson;
        try {
            ruleJson = objectMapper.readTree(ruleJsonStr);
        } catch (Exception e) {
            return toJson(java.util.Map.of(
                    "error", "INVALID_JSON",
                    "message", "無法解析 JSON：" + e.getMessage()
            ));
        }

        try {
            var result = ruleService.convert(ruleJson, targetType);
            return toJson(result);
        } catch (Exception e) {
            return toJson(java.util.Map.of(
                    "error", "CONVERSION_FAILED",
                    "message", e.getMessage()
            ));
        }
    }

    // ========================================================================
    //  Tool 7: optimize_rule_tree（DecisionTree 優化）
    // ========================================================================
    @Tool(description = """
            優化 DecisionTree 的 RuleEnvelope JSON。
            執行三個優化 Pass：
            1. 合併語意相同的兄弟葉節點
            2. 移除條件矛盾的死碼分支（aggressive 模式）
            3. 摺疊只有一個子節點的分支
            回傳優化後的 RuleEnvelope 與優化統計。
            """)
    public String optimizeRuleTree(
            @ToolParam(description = "DecisionTree 的 RuleEnvelope JSON 字串")
            String ruleJsonStr,
            @ToolParam(description = "是否啟用積極優化（含移除死碼分支），預設 false")
            String aggressiveStr
    ) {
        boolean aggressive = "true".equalsIgnoreCase(aggressiveStr);
        log.info("MCP optimize_rule_tree | aggressive={}", aggressive);

        try {
            var envelope = objectMapper.readValue(ruleJsonStr,
                    com.ruleengine.rules.domain.envelope.RuleEnvelope.class);
            var result = treeOptimizer.optimize(envelope, aggressive);
            return toJson(java.util.Map.of(
                    "optimized", result.getOptimized(),
                    "nodesRemoved", result.getNodesRemoved(),
                    "depthReduction", result.getDepthReduction(),
                    "appliedOptimizations", result.getAppliedOptimizations()
            ));
        } catch (Exception e) {
            return toJson(java.util.Map.of(
                    "error", "OPTIMIZE_FAILED",
                    "message", e.getMessage()
            ));
        }
    }

    // ========================================================================
    //  Tool 8: suggest_input_completeness（輸入建議）
    // ========================================================================
    @Tool(description = """
            分析使用者的自然語言規則描述，提供輸入建議。
            回傳已偵測的維度、建議補充的缺失維度、描述品質分數、預估規則數量。
            用途：在使用者送出 generate 前，先檢查描述是否完整。
            """)
    public String suggestInputCompleteness(
            @ToolParam(description = "使用者的自然語言規則描述")
            String description
    ) {
        log.info("MCP suggest_input_completeness | description.length={}", description.length());
        try {
            var result = inputSuggestionService.analyze(description);
            return toJson(result);
        } catch (Exception e) {
            return toJson(java.util.Map.of("error", "SUGGEST_FAILED", "message", e.getMessage()));
        }
    }

    // ========================================================================
    //  Tool 9: lookup_rule（規則查詢）
    // ========================================================================
    @Tool(description = """
            給定一組輸入值，在已生成的 RuleEnvelope 中查找匹配的規則。
            支援 DecisionTable（FIRST/MULTI hit policy）和 DecisionTree 遍歷。
            回傳匹配的規則 ID、優先級、輸出結果，以及評估路徑。
            """)
    public String lookupRule(
            @ToolParam(description = "完整的 RuleEnvelope JSON 字串")
            String envelopeJsonStr,
            @ToolParam(description = "輸入值的 JSON 物件字串，例如 {\"age\":25,\"gender\":\"M\"}")
            String inputValuesJsonStr
    ) {
        log.info("MCP lookup_rule");
        try {
            var envelope = objectMapper.readValue(envelopeJsonStr, RuleEnvelope.class);
            @SuppressWarnings("unchecked")
            var inputValues = objectMapper.readValue(inputValuesJsonStr, java.util.Map.class);
            var result = ruleLookupService.lookup(envelope, inputValues);
            return toJson(result);
        } catch (Exception e) {
            return toJson(java.util.Map.of("error", "LOOKUP_FAILED", "message", e.getMessage()));
        }
    }

    // ========================================================================
    //  Tool 10: narrate_rule_overview（整體業務敘事，v3.12）
    // ========================================================================
    @Tool(description = """
            為整張 RuleEnvelope 規則表產出一段「業務語言」的整體敘事，
            專為非工程使用者（保險業務／精算師）設計。

            回傳 JSON：
            {
              "summary": "一段 50-120 字的整體敘事（業務口吻）",
              "highlights": ["2-4 個關鍵要點"],
              "coverage": "涵蓋範圍一句話",
              "exceptions": ["0-3 個特殊例外"],
              "actuarialNote": "給精算師的一句話提醒（覆蓋 gap、假設、費率影響）"
            }

            用詞規範：禁用 operator/typeRef/hitPolicy/FIRST/ENUM 等技術詞；
            使用「超過／不超過／屬於／落在 X 至 Y」這類業務語言。

            適用情境：
            - 生成規則後想給業務主管看 3 秒版摘要
            - 精算師需要快速掌握一張表的命中邏輯
            - Demo 時的「人話版」說明
            """)
    public String narrateRuleOverview(
            @ToolParam(description = "原始自然語言描述（可為空字串）")
            String description,
            @ToolParam(description = "完整的 RuleEnvelope JSON 字串")
            String envelopeJsonStr,
            @ToolParam(description = "LLM Provider 選擇（claude / ollama / gemini / openai；可空）")
            String providerName
    ) {
        log.info("MCP narrate_rule_overview | provider={}", providerName);
        try {
            RuleEnvelope envelope = objectMapper.readValue(envelopeJsonStr, RuleEnvelope.class);
            var result = businessNarrativeService.narrate(description, envelope, providerName);
            return toJson(result);
        } catch (Exception e) {
            return toJson(java.util.Map.of("error", "NARRATE_FAILED", "message", e.getMessage()));
        }
    }

    // ========================================================================
    //  Tool 11: optimize_rule_tree_v2（DecisionTree sparsity-aware 優化，v3.13）
    // ========================================================================
    @Tool(description = """
            DecisionTree 進階 sparsity 優化（6-pass 管線）。

            在 v1 三段（葉合併 / 死碼移除 / 單子節點摺疊）之上，再跑：
            - 深度同構子樹合併（所有分支走向同樹時收合）
            - 祖先蘊含的冗餘條件消除
            - 局部 ID3 重建（葉數 ≤ K 的子樹）

            每 pass 後以 sparsity objective 比較，不退化（coverage 不降破地板、
            objective 不上升）為硬保證。

            回傳：optimized envelope + before/after 度量 + sparsityScore + 各 pass 貢獻。
            適合在生成後做為「最終打磨」步驟，特別針對 LLM 產出的有冗餘樹。
            """)
    public String optimizeRuleTreeV2(
            @ToolParam(description = "DecisionTree 的 RuleEnvelope JSON 字串")
            String ruleJsonStr,
            @ToolParam(description = "可選 OptimizeConfigV2 JSON（可空，使用預設）")
            String configJsonStr
    ) {
        log.info("MCP optimize_rule_tree_v2");
        try {
            RuleEnvelope envelope = objectMapper.readValue(ruleJsonStr, RuleEnvelope.class);
            com.ruleengine.rules.service.optimizer.v2.OptimizeConfigV2 cfg = null;
            if (configJsonStr != null && !configJsonStr.isBlank()) {
                cfg = objectMapper.readValue(configJsonStr,
                        com.ruleengine.rules.service.optimizer.v2.OptimizeConfigV2.class);
            }
            var result = treeOptimizerV2.optimize(envelope, cfg);
            return toJson(java.util.Map.of(
                    "optimized", result.getOptimized(),
                    "metricsBefore", result.getMetricsBefore(),
                    "metricsAfter", result.getMetricsAfter(),
                    "sparsityScoreBefore", result.getSparsityScoreBefore(),
                    "sparsityScoreAfter", result.getSparsityScoreAfter(),
                    "appliedOptimizations", result.getAppliedOptimizations(),
                    "passContributions", result.getPassContributions(),
                    "durationMs", result.getDurationMs()
            ));
        } catch (Exception e) {
            return toJson(java.util.Map.of(
                    "error", "OPTIMIZE_V2_FAILED",
                    "message", e.getMessage()));
        }
    }

    // ========================================================================
    //  Helpers
    // ========================================================================
    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("無法解析 allowedFieldsJson: {}", json);
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失敗", e);
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
