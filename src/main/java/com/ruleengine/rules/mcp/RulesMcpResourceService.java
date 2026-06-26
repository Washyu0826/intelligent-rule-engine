package com.ruleengine.rules.mcp;

import com.ruleengine.rules.registry.RuleTypeRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 資訊查詢工具 — 讓 AI 了解此 server 的能力、格式、範例。
 *
 * 設計原則：所有 schema 資訊和範例都內嵌在程式碼中（不依賴外部檔案），
 * 確保 AI 在任何環境下都能取得正確的格式說明。
 */
@Deprecated
@RequiredArgsConstructor
@Slf4j
public class RulesMcpResourceService {

    private final RuleTypeRegistry registry;
    private final ObjectMapper objectMapper;

    @Value("${rules.schema-version:1.0.0}")
    private String schemaVersion;

    @Value("${rules.prompt-version:p1.0.0}")
    private String promptVersion;

    /**
     * 查詢 server 能力、版本、支援型態、錯誤碼。
     */
    @Tool(description = """
            查詢 Rules MCP Server 的完整能力資訊。回傳：
            - serverName / phase / schemaVersion / promptVersion
            - officialSupportedTypes：本階段正式支援的規則型態
            - plannedTypes：後續階段規劃中的規則型態
            - errorCodes：11 個驗證錯誤碼及說明
            - typeRefSpec：6 種欄位型別及驗證規則
            - operatorSpec：12 個 operator 及適用型別
            建議在首次互動時呼叫此工具。
            """)
    public String getServerInfo() {
        log.info("MCP Resource: getServerInfo");
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("serverName", "Rules MCP Server");
            info.put("phase", "Phase 1 - DecisionTable Generate + Validate");
            info.put("schemaVersion", schemaVersion);
            info.put("promptVersion", promptVersion);
            info.put("officialSupportedTypes", List.of(
                    Map.of(
                            "code", "DecisionTable",
                            "label", "決策表",
                            "description", "本階段唯一正式支援的規則型態"
                    )
            ));
            info.put("plannedTypes", registry.getTypeDescriptions().stream()
                    .filter(type -> !"DecisionTable".equals(type.get("code")))
                    .toList());
            info.put("notes", List.of(
                    "MCP 目前以工具（tools）為主，未提供獨立 resource provider。",
                    "DecisionTree 與 ScoreCard 為後續階段規劃，請勿在本階段工作流中依賴。"
            ));

            // 11 個錯誤碼（設計文件 §6.5）
            info.put("errorCodes", Map.ofEntries(
                    Map.entry("MISSING_FIELD", "缺少必要欄位（如 hitPolicy、ruleType 等）"),
                    Map.entry("UNKNOWN_OPERATOR", "使用了不支援的 operator"),
                    Map.entry("TYPE_MISMATCH", "value 型態與 typeRef 不匹配，或 operator 不適用該 typeRef"),
                    Map.entry("UNKNOWN_FIELD", "condition/result 引用了不在 inputs/outputs 中的欄位"),
                    Map.entry("DUPLICATE_ID", "ruleId 重複"),
                    Map.entry("ENUM_VALUE_MISSING", "ENUM typeRef 缺少 allowedValues 定義"),
                    Map.entry("INVALID_ENUM_VALUE", "值不在 allowedValues 內"),
                    Map.entry("INCONSISTENT_TABLE", "FIRST 策略下規則條件重疊可同時命中（附觸發範例）"),
                    Map.entry("INVALID_MULTI", "MULTI hitPolicy 專屬問題"),
                    Map.entry("MISSING_BRANCH", "DecisionTree 非葉節點缺分支（保留給後續階段）"),
                    Map.entry("MISSING_RESULTS", "規則缺少某個 output 欄位的結果值")
            ));

            // 6 種 typeRef（§3.2）
            info.put("typeRefSpec", List.of(
                    Map.of("typeRef", "INTEGER", "javaType", "Integer/Long", "rule", "不能有小數點"),
                    Map.of("typeRef", "DECIMAL", "javaType", "Double/BigDecimal", "rule", "允許小數"),
                    Map.of("typeRef", "BOOLEAN", "javaType", "Boolean", "rule", "值只接受 true/false"),
                    Map.of("typeRef", "STRING", "javaType", "String", "rule", "任意文字"),
                    Map.of("typeRef", "ENUM", "javaType", "String", "rule", "value 必須在 allowedValues 內，allowedValues 為必填"),
                    Map.of("typeRef", "DATE", "javaType", "LocalDate", "rule", "格式 yyyy-MM-dd")
            ));

            // 12 個 operator（§3.3）
            info.put("operatorSpec", List.of(
                    Map.of("operator", "equals", "applicableTypes", "全部", "valueFormat", "單一值"),
                    Map.of("operator", "notEquals", "applicableTypes", "全部", "valueFormat", "單一值"),
                    Map.of("operator", "greaterThan", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "單一值"),
                    Map.of("operator", "greaterThanOrEqual", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "單一值"),
                    Map.of("operator", "lessThan", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "單一值"),
                    Map.of("operator", "lessThanOrEqual", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "單一值"),
                    Map.of("operator", "between", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "[min, max]（含兩端）"),
                    Map.of("operator", "in", "applicableTypes", "STRING/ENUM", "valueFormat", "[\"a\",\"b\",...]"),
                    Map.of("operator", "notIn", "applicableTypes", "STRING/ENUM", "valueFormat", "[\"a\",\"b\",...]"),
                    Map.of("operator", "isNull", "applicableTypes", "全部", "valueFormat", "不需要 value"),
                    Map.of("operator", "isNotNull", "applicableTypes", "全部", "valueFormat", "不需要 value"),
                    Map.of("operator", "anything", "applicableTypes", "全部", "valueFormat", "不需要 value（任意值）")
            ));

            return objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 取得特定規則型態的完整 JSON 範例。
     */
    @Tool(description = """
            取得特定規則型態的完整 RuleEnvelope JSON 範例。
            本階段只正式提供 DecisionTable 範例。
            若傳入非 DecisionTable，會回傳說明訊息，避免誤導 AI 使用尚未正式支援的型態。
            """)
    public String getRuleTypeExample(
            @ToolParam(description = "規則型態：本階段請使用 DecisionTable") String ruleType
    ) {
        log.info("MCP Resource: getRuleTypeExample, type={}", ruleType);

        if (ruleType == null) return "{\"error\": \"請指定 ruleType\"}";

        String example = switch (ruleType.toLowerCase().replace("_", "")) {
            case "decisiontable" -> DECISION_TABLE_EXAMPLE;
            case "decisiontree", "scorecard" -> """
                    {
                      "error": "UNSUPPORTED_RULE_TYPE_IN_THIS_PHASE",
                      "message": "本階段只正式支援 DecisionTable。DecisionTree / ScoreCard 為後續階段規劃。"
                    }
                    """;
            default -> "{\"error\": \"不支援的型態：" + ruleType + "\"}";
        };

        return example;
    }

    // ================================================================
    // 完整範例（對齊設計文件 §3.4）
    // ================================================================

    /**
     * DecisionTable 完整 JSON 範例 — 壽險核保（設計文件 §3.4）
     */
    private static final String DECISION_TABLE_EXAMPLE = """
            {
              "ruleType": "DecisionTable",
              "reason": "條件為並列比對，無先後依賴，適用決策表 FIRST 策略",
              "evaluation": {
                "completeness": "COMPLETE",
                "totalScenarios": 3,
                "coverageRate": 1.0,
                "conflictDetection": "NO_CONFLICT",
                "recommendedStrategy": "FIRST"
              },
              "rule": {
                "hitPolicy": "FIRST",
                "inputs": [
                  { "name": "age", "typeRef": "INTEGER" },
                  { "name": "gender", "typeRef": "ENUM", "allowedValues": ["male","female","any"] },
                  { "name": "hypertension", "typeRef": "BOOLEAN" },
                  { "name": "diabetes", "typeRef": "BOOLEAN" },
                  { "name": "heart_disease", "typeRef": "BOOLEAN" }
                ],
                "outputs": [
                  { "name": "decision", "typeRef": "ENUM", "allowedValues": ["承保","人工評估","拒保"] },
                  { "name": "premium_rate", "typeRef": "DECIMAL" },
                  { "name": "remark", "typeRef": "STRING" }
                ],
                "rules": [
                  {
                    "ruleId": "R01", "priority": 1,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [18, 35] },
                      { "field": "hypertension", "operator": "equals", "value": false },
                      { "field": "diabetes", "operator": "equals", "value": false },
                      { "field": "heart_disease", "operator": "equals", "value": false }
                    ],
                    "results": [
                      { "field": "decision", "value": "承保" },
                      { "field": "premium_rate", "value": 1.0 },
                      { "field": "remark", "value": "標準核保" }
                    ]
                  },
                  {
                    "ruleId": "R02", "priority": 2,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [18, 35] },
                      { "field": "hypertension", "operator": "equals", "value": true },
                      { "field": "diabetes", "operator": "equals", "value": false },
                      { "field": "heart_disease", "operator": "equals", "value": false }
                    ],
                    "results": [
                      { "field": "decision", "value": "人工評估" },
                      { "field": "premium_rate", "value": 1.3 },
                      { "field": "remark", "value": "高血壓加費，需提供近三個月血壓紀錄" }
                    ]
                  },
                  {
                    "ruleId": "R03", "priority": 3,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [18, 35] },
                      { "field": "hypertension", "operator": "anything" },
                      { "field": "diabetes", "operator": "equals", "value": false },
                      { "field": "heart_disease", "operator": "equals", "value": true }
                    ],
                    "results": [
                      { "field": "decision", "value": "拒保" },
                      { "field": "premium_rate", "value": 0 },
                      { "field": "remark", "value": "心臟病史，拒絕承保" }
                    ]
                  }
                ]
              }
            }
            """;

}
