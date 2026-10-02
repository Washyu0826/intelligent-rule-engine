# 專案結構

> 從舊版 README 搬移而來，部分內容為早期版本描述，以實際目錄為準。

## 專案結構

```
src/main/java/com/ruleengine/rules/
├── RulesMcpServerApplication.java    # 啟動入口
├── config/
│   ├── McpConfig.java                # MCP ToolCallbackProvider 註冊
│   └── GlobalExceptionHandler.java   # 全域例外處理
├── controller/
│   └── ToolsController.java          # REST API（/tools/generate, /validate, /recommend, /analyze, /test-run）
├── domain/
│   ├── RuleType.java                 # 規則型態 enum
│   ├── dto/ToolDtos.java             # 所有 Request/Response + 11 ErrorCodes + 12 Operators + 6 TypeRefs
│   └── envelope/RuleEnvelope.java    # 統一輸出格式（ruleType + evaluation + rule）
├── mcp/
│   ├── RulesMcpToolService.java      # MCP Tools（generate, validate, recommend, analyze, test-run）
│   ├── RulesMcpInfoToolService.java  # MCP 相容資訊工具（server info, examples）
│   └── RulesMcpResourceProvider.java # MCP 正式 resources（server-info, examples, response schemas）
├── registry/
│   └── RuleTypeRegistry.java         # 型態註冊表（自動掃描 Spring Bean）
└── service/
    ├── RuleService.java              # 核心編排（Generate → Validate 閉環）
    ├── generator/                    # 各規則型態生成器 + Self-Repair Pipeline
    ├── validator/                    # 4 層驗證 chain（11 錯誤碼）
    ├── analyzer/                     # DmnAnalyzer / TreeAnalyzer 幾何分析
    ├── recommender/                  # 啟發式分類
    ├── converter/                    # DecisionTable ↔ DecisionTree 雙向轉換
    ├── optimizer/                    # v1：3-pass 葉合併 / 死碼 / 單子節點摺疊
    ├── optimizer/v2/                 # v3.13：6-pass GOSDT-style sparsity 優化
    ├── narrative/                    # v3.12：BusinessNarrativeService
    ├── evaluator/                    # v3.6-v3.10 品質防線（Judge / Grounding / Confidence / Explain）
    ├── llm/                          # LlmProvider 抽象 + Registry（Claude / Gemini / OpenAI / Ollama）
    └── audit/                        # 不可變稽核 log（cloud profile 寫 Kafka）

docs/
├── decision-tree-v2.md               # v3.13 sparsity 設計文件（416 行）
├── deployment-architecture.md        # 雲端部署藍圖（11 章金管會合規）
└── ...                               # 其他 demo / 報告

k8s/                                  # Kubernetes manifests（11 份 + Kustomization）

src/test/
├── java/.../
│   ├── DecisionTableValidatorTest.java       # 25+ 測試（覆蓋所有 11 錯誤碼）
│   ├── RuleRecommenderTest.java              # 分類準確率 ≥ 80% 驗證
│   └── GenerateValidateClosedLoopTest.java   # Phase 1 閉環驗收
└── resources/
    ├── application.yml                       # 測試環境設定
    ├── testcases/test-cases-v1.json          # 10 筆測試案例（§5 TC01-TC10）
    └── fixtures/                             # 錯誤碼測試 fixtures
        ├── tc01-valid-first.json             # ✅ 合法 FIRST
        ├── tc02-valid-multi.json             # ✅ 合法 MULTI
        ├── tc07-invalid-enum.json            # ❌ INVALID_ENUM_VALUE
        ├── tc08-conflict.json                # ❌ INCONSISTENT_TABLE
        ├── tc09-missing-field.json           # ❌ MISSING_FIELD
        ├── unknown-operator.json             # ❌ UNKNOWN_OPERATOR
        ├── type-mismatch-op.json             # ❌ TYPE_MISMATCH (operator)
        ├── type-mismatch-boolean.json        # ❌ TYPE_MISMATCH (value)
        ├── between-min-gt-max.json           # ❌ TYPE_MISMATCH (between)
        ├── unknown-field.json                # ❌ UNKNOWN_FIELD
        ├── duplicate-id.json                 # ❌ DUPLICATE_ID
        ├── enum-value-missing.json           # ❌ ENUM_VALUE_MISSING
        └── missing-results.json              # ❌ MISSING_RESULTS
```

---
