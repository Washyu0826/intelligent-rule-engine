package com.ruleengine.rules.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Formal MCP resources exposed in addition to the existing compatibility info tools.
 */
@Component
@RequiredArgsConstructor
public class RulesMcpResourceProvider {

    public static final String SERVER_INFO_URI = "rules://server-info";
    public static final String DECISION_TABLE_EXAMPLE_URI = "rules://examples/decision-table";
    public static final String RULE_ENVELOPE_SCHEMA_URI = "rules://schemas/rule-envelope";
    public static final String VALIDATE_RESPONSE_SCHEMA_URI = "rules://schemas/validate-response";
    public static final String ANALYZE_RESPONSE_SCHEMA_URI = "rules://schemas/analyze-response";
    public static final String RECOMMEND_RESPONSE_SCHEMA_URI = "rules://schemas/recommend-response";
    public static final String TEST_RUN_RESPONSE_SCHEMA_URI = "rules://schemas/test-run-response";
    private static final String JSON_MIME_TYPE = "application/json";

    private final RulesMcpInfoToolService infoToolService;

    public List<McpServerFeatures.SyncResourceSpecification> syncResources() {
        return List.of(
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource(
                                SERVER_INFO_URI,
                                "server-info",
                                "Server metadata and schema guidance for the Rules MCP Server.",
                                JSON_MIME_TYPE,
                                null
                        ),
                        (exchange, request) -> textResult(
                                SERVER_INFO_URI,
                                infoToolService.getServerInfo()
                        )
                ),
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource(
                                DECISION_TABLE_EXAMPLE_URI,
                                "decision-table-example",
                                "Official DecisionTable RuleEnvelope example for the current phase.",
                                JSON_MIME_TYPE,
                                null
                        ),
                        (exchange, request) -> textResult(
                                DECISION_TABLE_EXAMPLE_URI,
                                infoToolService.getRuleTypeExample("DecisionTable")
                        )
                ),
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource(
                                RULE_ENVELOPE_SCHEMA_URI,
                                "rule-envelope-schema",
                                "Schema guide for the RuleEnvelope contract used by DecisionTable generation.",
                                JSON_MIME_TYPE,
                                null
                        ),
                        (exchange, request) -> textResult(
                                RULE_ENVELOPE_SCHEMA_URI,
                                RULE_ENVELOPE_SCHEMA
                        )
                ),
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource(
                                VALIDATE_RESPONSE_SCHEMA_URI,
                                "validate-response-schema",
                                "Schema guide for the validate_rule_schema response.",
                                JSON_MIME_TYPE,
                                null
                        ),
                        (exchange, request) -> textResult(
                                VALIDATE_RESPONSE_SCHEMA_URI,
                                VALIDATE_RESPONSE_SCHEMA
                        )
                ),
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource(
                                ANALYZE_RESPONSE_SCHEMA_URI,
                                "analyze-response-schema",
                                "Schema guide for the analyze_rule_payload response.",
                                JSON_MIME_TYPE,
                                null
                        ),
                        (exchange, request) -> textResult(
                                ANALYZE_RESPONSE_SCHEMA_URI,
                                ANALYZE_RESPONSE_SCHEMA
                        )
                ),
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource(
                                RECOMMEND_RESPONSE_SCHEMA_URI,
                                "recommend-response-schema",
                                "Schema guide for the recommend_rule_type response.",
                                JSON_MIME_TYPE,
                                null
                        ),
                        (exchange, request) -> textResult(
                                RECOMMEND_RESPONSE_SCHEMA_URI,
                                RECOMMEND_RESPONSE_SCHEMA
                        )
                ),
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource(
                                TEST_RUN_RESPONSE_SCHEMA_URI,
                                "test-run-response-schema",
                                "Schema guide for the test_run_rules response.",
                                JSON_MIME_TYPE,
                                null
                        ),
                        (exchange, request) -> textResult(
                                TEST_RUN_RESPONSE_SCHEMA_URI,
                                TEST_RUN_RESPONSE_SCHEMA
                        )
                )
        );
    }

    private McpSchema.ReadResourceResult textResult(String uri, String text) {
        return new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, JSON_MIME_TYPE, text)
        ));
    }

    private static final String RULE_ENVELOPE_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "RuleEnvelope",
              "type": "object",
              "required": ["ruleType", "rule"],
              "properties": {
                "ruleType": {
                  "type": "string",
                  "enum": ["DecisionTable"]
                },
                "reason": {
                  "type": "string"
                },
                "schemaVersion": {
                  "type": "string"
                },
                "promptVersion": {
                  "type": "string"
                },
                "evaluation": {
                  "type": "object",
                  "properties": {
                    "completeness": { "type": "string" },
                    "totalScenarios": { "type": "integer" },
                    "coverageRate": { "type": "number" },
                    "conflictDetection": { "type": "string" },
                    "recommendedStrategy": { "type": "string" },
                    "gaps": { "type": "array" },
                    "overlaps": { "type": "array" },
                    "simplifications": { "type": "array" }
                  }
                },
                "rule": {
                  "type": "object",
                  "required": ["hitPolicy", "inputs", "outputs", "rules"],
                  "properties": {
                    "hitPolicy": {
                      "type": "string",
                      "enum": ["FIRST", "MULTI"]
                    },
                    "inputs": {
                      "type": "array",
                      "items": { "$ref": "#/$defs/fieldDef" }
                    },
                    "outputs": {
                      "type": "array",
                      "items": { "$ref": "#/$defs/fieldDef" }
                    },
                    "rules": {
                      "type": "array",
                      "items": { "$ref": "#/$defs/ruleRow" }
                    }
                  }
                }
              },
              "$defs": {
                "fieldDef": {
                  "type": "object",
                  "required": ["name", "typeRef"],
                  "properties": {
                    "name": { "type": "string" },
                    "typeRef": {
                      "type": "string",
                      "enum": ["INTEGER", "DECIMAL", "BOOLEAN", "STRING", "ENUM", "DATE"]
                    },
                    "allowedValues": {
                      "type": "array",
                      "items": { "type": "string" }
                    }
                  }
                },
                "condition": {
                  "type": "object",
                  "required": ["field", "operator"],
                  "properties": {
                    "field": { "type": "string" },
                    "operator": {
                      "type": "string",
                      "enum": [
                        "equals",
                        "notEquals",
                        "greaterThan",
                        "greaterThanOrEqual",
                        "lessThan",
                        "lessThanOrEqual",
                        "between",
                        "in",
                        "notIn",
                        "isNull",
                        "isNotNull",
                        "anything"
                      ]
                    },
                    "value": {}
                  }
                },
                "result": {
                  "type": "object",
                  "required": ["field"],
                  "properties": {
                    "field": { "type": "string" },
                    "value": {}
                  }
                },
                "ruleRow": {
                  "type": "object",
                  "required": ["ruleId", "conditions", "results"],
                  "properties": {
                    "ruleId": { "type": "string" },
                    "priority": { "type": "integer" },
                    "conditions": {
                      "type": "array",
                      "items": { "$ref": "#/$defs/condition" }
                    },
                    "results": {
                      "type": "array",
                      "items": { "$ref": "#/$defs/result" }
                    }
                  }
                }
              }
            }
            """;

    private static final String VALIDATE_RESPONSE_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "ValidateResponse",
              "type": "object",
              "required": ["valid", "errors"],
              "properties": {
                "valid": {
                  "type": "boolean"
                },
                "errors": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["code", "message"],
                    "properties": {
                      "code": { "type": "string" },
                      "message": { "type": "string" },
                      "witness": {
                        "type": "object",
                        "additionalProperties": { "type": "string" },
                        "description": "Optional structured counter-example that triggers this error (IEEE 2024 witness pattern)."
                      }
                    }
                  }
                }
              }
            }
            """;

    private static final String ANALYZE_RESPONSE_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "AnalyzeResponse",
              "type": "object",
              "required": ["coverageRate", "gaps", "overlaps", "simplifications"],
              "properties": {
                "coverageRate": {
                  "type": "number"
                },
                "gaps": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["conditions", "message"],
                    "properties": {
                      "conditions": {
                        "type": "object",
                        "additionalProperties": { "type": "string" }
                      },
                      "message": { "type": "string" }
                    }
                  }
                },
                "overlaps": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["ruleIds", "intersection", "message"],
                    "properties": {
                      "ruleIds": {
                        "type": "array",
                        "items": { "type": "string" }
                      },
                      "intersection": {
                        "type": "object",
                        "additionalProperties": { "type": "string" }
                      },
                      "message": { "type": "string" }
                    }
                  }
                },
                "simplifications": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["ruleIds", "suggestion"],
                    "properties": {
                      "ruleIds": {
                        "type": "array",
                        "items": { "type": "string" }
                      },
                      "suggestion": { "type": "string" }
                    }
                  }
                }
              }
            }
            """;

    private static final String RECOMMEND_RESPONSE_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "RecommendResponse",
              "type": "object",
              "required": ["recommendedRuleType", "reason", "confidence"],
              "properties": {
                "recommendedRuleType": {
                  "type": "string",
                  "enum": ["DecisionTable", "DecisionTree", "ScoreCard"]
                },
                "reason": {
                  "type": "string"
                },
                "confidence": {
                  "type": "number"
                },
                "alternatives": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["ruleType", "reason", "score"],
                    "properties": {
                      "ruleType": {
                        "type": "string",
                        "enum": ["DecisionTable", "DecisionTree", "ScoreCard"]
                      },
                      "reason": { "type": "string" },
                      "score": { "type": "number" }
                    }
                  }
                }
              }
            }
            """;

    private static final String TEST_RUN_RESPONSE_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "TestRunResponse",
              "type": "object",
              "required": ["total", "passed", "failed", "passRate", "errorBreakdown", "results"],
              "properties": {
                "total": { "type": "integer" },
                "passed": { "type": "integer" },
                "failed": { "type": "integer" },
                "passRate": { "type": "number" },
                "errorBreakdown": {
                  "type": "object",
                  "additionalProperties": { "type": "integer" }
                },
                "results": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": [
                      "id",
                      "passed",
                      "recommendedType",
                      "typeMatchExpected",
                      "jsonParseable",
                      "schemaValid",
                      "durationMs"
                    ],
                    "properties": {
                      "id": { "type": "string" },
                      "passed": { "type": "boolean" },
                      "recommendedType": { "type": "string" },
                      "typeMatchExpected": { "type": "boolean" },
                      "jsonParseable": { "type": "boolean" },
                      "schemaValid": { "type": "boolean" },
                      "errors": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "required": ["code", "message"],
                          "properties": {
                            "code": { "type": "string" },
                            "message": { "type": "string" },
                            "witness": {
                              "type": "object",
                              "additionalProperties": { "type": "string" },
                              "description": "Optional structured counter-example (IEEE 2024 witness pattern)."
                            }
                          }
                        }
                      },
                      "durationMs": { "type": "integer" }
                    }
                  }
                }
              }
            }
            """;
}
