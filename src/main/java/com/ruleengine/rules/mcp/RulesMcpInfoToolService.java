package com.ruleengine.rules.mcp;

import com.ruleengine.rules.registry.RuleTypeRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Informational MCP tools for server metadata and examples.
 *
 * <p>The current phase keeps these compatibility tools while formal MCP resources
 * are introduced for discovery and read flows.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RulesMcpInfoToolService {

    private static final String DECISION_TABLE = "DecisionTable";
    private static final String UNSUPPORTED_PHASE_ERROR = "UNSUPPORTED_RULE_TYPE_IN_THIS_PHASE";

    private final RuleTypeRegistry registry;
    private final ObjectMapper objectMapper;

    @Value("${rules.schema-version:1.0.0}")
    private String schemaVersion;

    @Value("${rules.prompt-version:p1.0.0}")
    private String promptVersion;

    @Tool(description = """
            Return server metadata, supported rule types, and schema guidance.
            The server provides a business-facing rules governance and handoff workflow.
            RuleEnvelope is an engine-neutral intermediate model; downstream engines require exporter/adapter conversion.
            This phase keeps compatibility info tools and also exposes formal MCP resources.
            Officially supported rule types: DecisionTable and DecisionTree (convertible in both directions).
            ScoreCard is a stub only and must not be used for formal output.
            """)
    public String getServerInfo() {
        log.info("MCP Info Tool: getServerInfo");
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("serverName", "Rules MCP Server");
            info.put("phase", "Phase 3 - DecisionTable + DecisionTree");
            info.put("mcpMode", "tools-and-resources");
            info.put("positioning", "Business rules governance and handoff service; RuleEnvelope is an engine-neutral intermediate model.");
            info.put("downstreamContract", "Use exporter/adapter to convert RuleEnvelope into the target group rules engine schema, operators, field codes, and deployment package.");
            info.put("resourceCapabilityEnabled", true);
            info.put("schemaVersion", schemaVersion);
            info.put("promptVersion", promptVersion);
            info.put("officialSupportedTypes", List.of(
                    Map.of(
                            "code", DECISION_TABLE,
                            "label", "Decision Table",
                            "description", "Flat condition-combination rules with hitPolicy."
                    ),
                    Map.of(
                            "code", "DecisionTree",
                            "label", "Decision Tree",
                            "description", "Hierarchical condition-branch rules with tree structure (Phase 3)."
                    )
            ));
            info.put("plannedTypes", registry.getTypeDescriptions().stream()
                    .filter(type -> !DECISION_TABLE.equals(type.get("code")))
                    .toList());
            info.put("notes", List.of(
                    "This server exposes both formal MCP resources and compatibility info tools.",
                    "RuleEnvelope is for review, validation, scenario analysis, and adapter handoff; it is not a target-engine deployment artifact by itself.",
                    "Preferred resources: rules://server-info, rules://examples/decision-table, and rules://schemas/*.",
                    "Response schemas are available for validate, analyze, recommend, and test-run MCP flows.",
                    "ScoreCard is intentionally excluded from formal support; it is a stub type.",
                    "DecisionTable and DecisionTree are both stable generation and validation paths.",
                    "Rule approval and activation are not exposed as tools: this service uses maker-checker governance, "
                            + "so approve / activate / retire must be performed by a human with CHECKER or ADMIN rights."
            ));
            info.put("errorCodes", Map.ofEntries(
                    Map.entry("MISSING_FIELD", "Required field is missing."),
                    Map.entry("UNKNOWN_OPERATOR", "Operator is not supported."),
                    Map.entry("TYPE_MISMATCH", "Value type does not match the declared field type."),
                    Map.entry("UNKNOWN_FIELD", "Condition or result field is not declared in inputs or outputs."),
                    Map.entry("DUPLICATE_ID", "ruleId must be unique."),
                    Map.entry("ENUM_VALUE_MISSING", "ENUM field must declare allowedValues."),
                    Map.entry("INVALID_ENUM_VALUE", "ENUM value is outside allowedValues."),
                    Map.entry("INCONSISTENT_TABLE", "FIRST hit policy contains overlapping rows with conflicting outputs."),
                    Map.entry("INVALID_MULTI", "MULTI hit policy has invalid row composition."),
                    Map.entry("MISSING_BRANCH", "Reserved for DecisionTree validation in a later phase."),
                    Map.entry("MISSING_RESULTS", "A rule is missing one or more required outputs.")
            ));
            info.put("typeRefSpec", List.of(
                    Map.of("typeRef", "INTEGER", "javaType", "Integer/Long", "rule", "Whole numbers."),
                    Map.of("typeRef", "DECIMAL", "javaType", "Double/BigDecimal", "rule", "Decimal numbers."),
                    Map.of("typeRef", "BOOLEAN", "javaType", "Boolean", "rule", "true or false."),
                    Map.of("typeRef", "STRING", "javaType", "String", "rule", "Free-form text."),
                    Map.of("typeRef", "ENUM", "javaType", "String", "rule", "Value must be included in allowedValues."),
                    Map.of("typeRef", "DATE", "javaType", "LocalDate", "rule", "Format yyyy-MM-dd.")
            ));
            info.put("operatorSpec", List.of(
                    Map.of("operator", "equals", "applicableTypes", "ALL", "valueFormat", "single value"),
                    Map.of("operator", "notEquals", "applicableTypes", "ALL", "valueFormat", "single value"),
                    Map.of("operator", "greaterThan", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "single value"),
                    Map.of("operator", "greaterThanOrEqual", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "single value"),
                    Map.of("operator", "lessThan", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "single value"),
                    Map.of("operator", "lessThanOrEqual", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "single value"),
                    Map.of("operator", "between", "applicableTypes", "INTEGER/DECIMAL/DATE", "valueFormat", "[min, max]"),
                    Map.of("operator", "in", "applicableTypes", "STRING/ENUM", "valueFormat", "[\"a\",\"b\",...]"),
                    Map.of("operator", "notIn", "applicableTypes", "STRING/ENUM", "valueFormat", "[\"a\",\"b\",...]"),
                    Map.of("operator", "isNull", "applicableTypes", "ALL", "valueFormat", "no value"),
                    Map.of("operator", "isNotNull", "applicableTypes", "ALL", "valueFormat", "no value"),
                    Map.of("operator", "anything", "applicableTypes", "ALL", "valueFormat", "no value")
            ));
            return objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            return toErrorJson(e.getMessage());
        }
    }

    @Tool(description = """
            Return a RuleEnvelope JSON example for an officially supported rule type.
            Formal examples are available for DecisionTable and DecisionTree.
            ScoreCard is a stub type and has no formal example.
            """)
    public String getRuleTypeExample(
            @ToolParam(description = "Rule type: DecisionTable or DecisionTree.") String ruleType
    ) {
        log.info("MCP Info Tool: getRuleTypeExample, type={}", ruleType);

        if (ruleType == null || ruleType.isBlank()) {
            return toErrorJson("ruleType is required.");
        }

        String normalizedRuleType = ruleType.trim().replace("_", "");
        if ("decisiontable".equalsIgnoreCase(normalizedRuleType)) {
            return DECISION_TABLE_EXAMPLE;
        }
        if ("decisiontree".equalsIgnoreCase(normalizedRuleType)) {
            return DECISION_TREE_EXAMPLE;
        }
        if ("scorecard".equalsIgnoreCase(normalizedRuleType)) {
            return unsupportedRuleTypeResponse(ruleType);
        }
        return toErrorJson("Unsupported ruleType: " + ruleType);
    }

    private String unsupportedRuleTypeResponse(String ruleType) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "error", UNSUPPORTED_PHASE_ERROR,
                    "ruleType", ruleType,
                    "message", "ScoreCard is a stub type. Officially supported: DecisionTable and DecisionTree."
            ));
        } catch (Exception e) {
            return toErrorJson(e.getMessage());
        }
    }

    private String toErrorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception ignored) {
            return "{\"error\":\"serialization_failure\"}";
        }
    }

    private static final String DECISION_TREE_EXAMPLE = """
            {
              "ruleType": "DecisionTree",
              "reason": "條件有層級依賴：先判斷年齡是否超過 60 歲，再根據是否有理賠紀錄決定核保結果。",
              "evaluation": {
                "completeness": "COMPLETE",
                "totalScenarios": 4,
                "coverageRate": 1.0,
                "conflictDetection": "NO_CONFLICT",
                "recommendedStrategy": "FIRST"
              },
              "rule": {
                "inputs": [
                  { "name": "age", "typeRef": "INTEGER" },
                  { "name": "has_claim_history", "typeRef": "BOOLEAN" },
                  { "name": "risk_level", "typeRef": "ENUM", "allowedValues": ["LOW", "MEDIUM", "HIGH"] }
                ],
                "outputs": [
                  { "name": "decision", "typeRef": "ENUM", "allowedValues": ["APPROVE", "REVIEW", "REJECT"] },
                  { "name": "remark", "typeRef": "STRING" }
                ],
                "root": {
                  "nodeId": "N01",
                  "condition": { "field": "age", "operator": "greaterThan", "value": 60 },
                  "branches": [
                    {
                      "label": "TRUE",
                      "condition": { "field": "age", "operator": "greaterThan", "value": 60 },
                      "child": {
                        "nodeId": "N02",
                        "condition": { "field": "has_claim_history", "operator": "equals", "value": true },
                        "branches": [
                          {
                            "label": "TRUE",
                            "condition": { "field": "has_claim_history", "operator": "equals", "value": true },
                            "child": {
                              "nodeId": "N03",
                              "results": [
                                { "field": "decision", "value": "REJECT" },
                                { "field": "remark", "value": "age > 60 with claim history" }
                              ]
                            }
                          },
                          {
                            "label": "FALSE",
                            "condition": { "field": "has_claim_history", "operator": "notEquals", "value": true },
                            "child": {
                              "nodeId": "N04",
                              "results": [
                                { "field": "decision", "value": "REVIEW" },
                                { "field": "remark", "value": "age > 60 without claim history, manual review" }
                              ]
                            }
                          }
                        ]
                      }
                    },
                    {
                      "label": "FALSE",
                      "condition": { "field": "age", "operator": "lessThanOrEqual", "value": 60 },
                      "child": {
                        "nodeId": "N05",
                        "condition": { "field": "risk_level", "operator": "equals", "value": "HIGH" },
                        "branches": [
                          {
                            "label": "TRUE",
                            "condition": { "field": "risk_level", "operator": "equals", "value": "HIGH" },
                            "child": {
                              "nodeId": "N06",
                              "results": [
                                { "field": "decision", "value": "REVIEW" },
                                { "field": "remark", "value": "age <= 60 but high risk" }
                              ]
                            }
                          },
                          {
                            "label": "FALSE",
                            "condition": { "field": "risk_level", "operator": "notEquals", "value": "HIGH" },
                            "child": {
                              "nodeId": "N07",
                              "results": [
                                { "field": "decision", "value": "APPROVE" },
                                { "field": "remark", "value": "standard approval" }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
            """;

    private static final String DECISION_TABLE_EXAMPLE = """
            {
              "ruleType": "DecisionTable",
              "reason": "DecisionTable is the only formally supported rule type in this phase.",
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
                  { "name": "risk_level", "typeRef": "ENUM", "allowedValues": ["LOW", "MEDIUM", "HIGH"] },
                  { "name": "has_claim_history", "typeRef": "BOOLEAN" }
                ],
                "outputs": [
                  { "name": "decision", "typeRef": "ENUM", "allowedValues": ["APPROVE", "REVIEW", "REJECT"] },
                  { "name": "premium_rate", "typeRef": "DECIMAL" },
                  { "name": "remark", "typeRef": "STRING" }
                ],
                "rules": [
                  {
                    "ruleId": "R01",
                    "priority": 1,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [18, 35] },
                      { "field": "risk_level", "operator": "equals", "value": "LOW" },
                      { "field": "has_claim_history", "operator": "equals", "value": false }
                    ],
                    "results": [
                      { "field": "decision", "value": "APPROVE" },
                      { "field": "premium_rate", "value": 1.0 },
                      { "field": "remark", "value": "standard case" }
                    ]
                  },
                  {
                    "ruleId": "R02",
                    "priority": 2,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [18, 35] },
                      { "field": "risk_level", "operator": "equals", "value": "MEDIUM" },
                      { "field": "has_claim_history", "operator": "equals", "value": false }
                    ],
                    "results": [
                      { "field": "decision", "value": "REVIEW" },
                      { "field": "premium_rate", "value": 1.25 },
                      { "field": "remark", "value": "manual review recommended" }
                    ]
                  },
                  {
                    "ruleId": "R03",
                    "priority": 3,
                    "conditions": [
                      { "field": "age", "operator": "between", "value": [18, 35] },
                      { "field": "risk_level", "operator": "equals", "value": "HIGH" },
                      { "field": "has_claim_history", "operator": "anything" }
                    ],
                    "results": [
                      { "field": "decision", "value": "REJECT" },
                      { "field": "premium_rate", "value": 0.0 },
                      { "field": "remark", "value": "risk too high" }
                    ]
                  }
                ]
              }
            }
            """;
}
