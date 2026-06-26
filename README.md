# Rules MCP Server

**規則生成與建議服務** — 自然語言 → 標準化 RuleEnvelope JSON（支援 DecisionTable / DecisionTree）

某金融集團 實習專案 | Washyu0826 | 技術團隊 | 需求方: 

> **目前版本：v3.16.0** · Backend 78 classes / 648+ tests / 0 failures · Frontend `tsc -b` + `vite build` 綠 · 詳見 [CHANGELOG](./CHANGELOG.md)

---

## 當前狀態（v3.16.0）

Phase 1（v1.0.0）交付了「Generate → Validate 最小閉環」的承諾，之後陸續擴展為完整產品；v3.14 加入集團 Group 格式轉換與執行 mock，v3.15 完成主管三研究方向（輸入前置閘門 / 樹路徑視覺化 / 規則 diff），v3.16 完成效能實測優化（JMH 185x）與 LLM 端點全面非同步化（thread starvation 修復）：

| 類別 | 功能 |
|------|------|
| **REST API（~28 端點）** | 生成/分析：`/tools/generate`（含 SSE streaming）、`/validate`、`/recommend`、`/analyze`、`/test-run`、`/convert`、`/optimize`、`/optimize/v2`、`/suggest`、`/preflight`、`/lookup`、`/scenario-expand`、`/evaluate`、`/explain`、`/narrate`、`/providers`、`/audit`、`/audit/stats`、`/glossary*`、`/health`；**比對**：`/diff-tree`、`/diff-table`、`/diff-rules`；**樹視覺化**：`/tree-paths`；**Group 整合**：`/export/group-json`、`/export/group-xlsx`、`/execute` |
| **MCP Tools（11 個）** | generate / validate / recommend / analyze / test-run / suggest_input_completeness / lookup_rule / convert / optimize / narrate_rule_overview / optimize_rule_tree_v2 |
| **LLM Provider（4 家）** | Claude / Gemini / OpenAI / Ollama 運行時切換；三層 fallback（LLM → offline scenario → stub）；Claude prompt caching；Level A / B self-repair |
| **規則型態** | DecisionTable（FIRST / MULTI）、DecisionTree（含 v2 sparsity 6-pass 優化）、雙向轉換；ScoreCard Validator stub |
| **分析 / 比對** | DmnAnalyzer（gaps / overlaps / simplification）、TreeAnalyzer、QualityScorer、LlmJudgeEvaluator、TreeOptimizerV2（GOSDT 風格 sparsity）；**規則 diff**：TreeDiffService（Zhang-Shasha 樹編輯距離）、RuleDiffService（行為超矩形差 + 結構簽章對齊） |
| **輸入閘門 / 樹視覺化** | PreflightService（生成前純符號預檢，off/warn/block 三模式，0 token）；TreePathService（每條 root→leaf 展開成白話規則） |
| **Group 整合** | RuleEnvelope ↔ Group 樹 JSON / 欄位定義 XLSX 匯出（SPI `RuleEngineAdapter`）；MockGroupEngine in-process 執行（`/tools/execute`） |
| **前端** | React 19 SPA：輸入頁（NL/JSON 雙模式 + PreflightBanner）、多分頁儀表板、What-If Simulator、Rule History、Impact Analysis、Business Report 列印、Template Library、DecisionTree 視覺化 + 決策路徑表、三種 Diff 檢視、Engine Execution 分頁、RuleNarrative、SparsityPanel；**ErrorBoundary** 防單一元件崩潰整頁 |
| **產品化** | API Key auth、Security headers、Rate limit（含 diff 端點）、Audit log、OpenAPI / Swagger UI、CI pipeline、**前端打包進單一 jar**（`build-frontend.ps1`）、Docker / docker-compose、Kubernetes manifests（12 份 + Kustomization） |
| **品質保證（v3.6-v3.10）** | 四層幻覺防線：符號 grounding（v3.8 AWS pattern）→ LLM-as-Judge 跨 provider（v3.6 arxiv surveys）→ 結構化 witness（v3.7 IEEE 2024）→ 每條規則 rationale（v3.9 reason+verify）→ composite confidence score（v3.10 0-100 + tier） |
| **業務化（v3.12-v3.13）** | 整張規則表業務語言敘事（5 區塊，禁用技術詞）、DecisionTree sparsity-aware 6-pass 優化（GOSDT 風格，不退化為硬性保證） |
| **雲端部署** | 設計：[`docs/deployment-architecture.md`](./docs/deployment-architecture.md)（11 章金管會合規藍圖）／ 操作：[`docs/deployment-playbook.md`](./docs/deployment-playbook.md)（GCP 選型、LiteLLM Gateway、合規對應、Spring K8s 生產設定）／ Manifests：[`k8s/`](./k8s/)（11 份 + Kustomization） |

版本演進重點：
- **v1.0.0**：Phase 1 最小閉環（DecisionTable generate + validate）
- **v1.2.0**：Gemini LLM 整合（自然語言 → JSON）
- **v1.4.0**：DmnAnalyzer 幾何分析 + React SPA
- **v2.0.0**：大重構（God class 拆解、4 層 validation chain、AOP metrics、LlmProvider 抽象）
- **v2.1.0**：Self-Repair Pipeline Level B
- **v2.2.0**：延遲優化 / JSON Schema constrained output / Two-Pass cartesian product
- **v3.0.0**：產品化（suggest / lookup / security / OpenAPI / DecisionTree 正式）
- **v3.2.0 – v3.3.0**：What-If、模板庫、商用級 Dashboard、列印報告
- **v3.4.0**：Multi-LLM runtime 切換 + DecisionTree 點擊詳情
- **v3.5.0 – v3.5.1**：Rule History、Impact Analysis、Business Report、最終清理
- **v3.6.0**：LLM-as-Judge bias mitigation（cross-provider、self-consistency、verbosity diagnostic）— 基於 arxiv 2411.15594 / 2412.05579 / 2512.16041
- **v3.7.0**：結構化 witness 反例（`ValidationError.witness`）+ MCP schema 同步 — 基於 IEEE 2024 "Tabular Requirements SMT-based Verification"
- **v3.8.0**：Symbolic Grounding Guard（`GenerateResponse.groundingCheck`）— 純符號幻覺偵測，AWS Automated Reasoning 2024 pattern
- **v3.9.0**：Per-Rule Rationale Service（`POST /tools/explain` + `RuleRow.rationale`）— 為 BA 補上中文業務解釋，reason+verify pattern 2025
- **v3.10.0**：Composite Confidence Score（`GenerateResponse.confidence`）— 聚合五因子成 0-100 分 + tier，BA 用一個數字判斷是否可 production
- **v3.10.1**：前端 UI 補完 — ConfidenceBar / GroundingPanel / QualityToolbar + RuleTable rationale banner
- **v3.11.0**：OpenAI / GPT Provider 整合（第 4 家 LLM；無 key 時自動 disabled）
- **v3.12.0**：BusinessNarrativeService — 整張規則表的業務語言敘事（summary / highlights / coverage / exceptions / actuarialNote 5 區塊），prompt 禁用技術詞，給非工程使用者
- **v3.13.0**：DecisionTree v2 sparsity-aware 6-pass pipeline — 在 v1 之上加 3 個 pass（同構子樹合併、冗餘條件消除、局部 ID3 重建），每 pass 後以 sparsity objective 比較，不退化（coverage 不破地板、葉節點數單調不增）為硬性保證
- **docs + k8s**：金融業生產級雲端部署藍圖（11 章設計文件 + K8s manifests，金管會 AI 六原則 mapping）
- **v3.14.0**：集團 Group 格式整合 — RuleEnvelope `extensions{}` 加性擴充、Group 樹 JSON / 欄位定義 XLSX 雙向轉換（SPI `RuleEngineAdapter`）、MockGroupEngine in-process 執行（`/tools/execute`）、領域字彙表（glossary）
- **v3.15.0**：主管三研究方向 — ①輸入前置閘門（PreflightService，生成前純符號預檢、0 token、off/warn/block）②決策樹路徑視覺化（TreePathService，root→leaf 白話化）③規則 diff（TreeDiffService Zhang-Shasha 樹編輯距離 + RuleDiffService 行為超矩形差 / 結構簽章對齊）；前端打包進單一 jar
- **v3.15.1**：交付後穩健性 — 前端 ErrorBoundary、TreeDiff/TreePath 規模與循環防呆、diff 端點限流 + 集中式 429、SemanticTreeDiff comparable 友善訊息
- **v3.16.0**：效能實測優化 + LLM 端點全面非同步化 — 重疊偵測掃描線剪枝（JMH 實測 1000 條 1320ms→7.1ms，185x）、`/generate` `/test-run` `/explain` `/narrate` `/evaluate` 改 DeferredResult + 專用有界池（WireMock A/B 壓測 /health 6987ms→126ms）、expandCartesian 截斷透明化（`truncatedRuleIds`）、DmnAnalyzer/HyperRectangle 補單元測試、前端 fetch 分級 timeout

---

## 專案定位

本專案是規則引擎的**前置生成服務**，負責將自然語言規則轉為標準化 JSON，供規則引擎使用。核心定位為「生成 / 驗證 / 分析 / 比對 / 匯出」；自 v3.14 起另含 **MockGroupEngine** 作為 in-process 執行示範（`/tools/execute`），真實集團引擎整合仍為後續範圍。
正式支援 `DecisionTable` 與 `DecisionTree`（含 v2 sparsity 優化）；`ScoreCard` 為 stub。

```
BA / 業務端           Rules MCP Server              規則引擎（現有）
自然語言描述  ──►  /generate  /validate  ──►  RuleEnvelope JSON  ──►  執行規則
                   /recommend (Ph.2)                                   (out of scope)
```

---

## Phase 1 交付範圍（2/26 - 3/13）

Phase 1 承諾：**Generate 的輸出可被 validate 通過（最小閉環）**

| 功能 | 狀態 | 說明 |
|------|------|------|
| `POST /tools/generate` | ✅ | 結構化 JSON → RuleEnvelope（UC-01） |
| `POST /tools/validate` | ✅ | 11 個錯誤碼驗證（UC-02） |
| `POST /tools/recommend` | ✅ Beta | 啟發式分類（UC-03，回傳可推薦但本階段正式輸出仍以 DecisionTable 為主） |
| MCP Tools | ✅ | 目前提供 generate / validate / recommend / analyze / test-run |
| MCP Resources | ✅ | 目前提供 `rules://server-info`、`rules://examples/decision-table`、`rules://schemas/*`（含 validate / analyze / recommend / test-run） |
| 測試案例 | ✅ | 10 筆（§5 TC01-TC10） |
| 測試 fixtures | ✅ | 10 個 JSON 覆蓋所有 11 個錯誤碼 |

---

## 快速開始

### SSE 模式（正式環境）

```bash
mvn spring-boot:run
# http://localhost:8080
```

### STDIO 模式（Claude Desktop 本地測試）

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=stdio
```

`claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "rules-mcp-server": {
      "command": "java",
      "args": ["-jar", "target/rules-mcp-server-1.0.0-SNAPSHOT.jar", "--spring.profiles.active=stdio"]
    }
  }
}
```

### 測試

```bash
mvn test
```

### 本地敏感設定

`src/main/resources/application-local.yml` 已改為安全樣板，請只在本機填值，不要提交實際金鑰。
建議優先使用環境變數：

```bash
set GEMINI_API_KEY=your-key
```

---

## RuleEnvelope 輸出格式（設計文件 §3.1）

```json
{
  "ruleType": "DecisionTable",
  "reason": "條件為並列比對，無先後依賴",
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
      { "name": "gender", "typeRef": "ENUM", "allowedValues": ["male","female"] }
    ],
    "outputs": [
      { "name": "decision", "typeRef": "ENUM", "allowedValues": ["承保","拒保"] }
    ],
    "rules": [
      {
        "ruleId": "R01", "priority": 1,
        "conditions": [
          { "field": "age", "operator": "between", "value": [18, 35] },
          { "field": "gender", "operator": "anything" }
        ],
        "results": [
          { "field": "decision", "value": "承保" }
        ]
      }
    ]
  },
  "schemaVersion": "1.0.0",
  "promptVersion": "p1.0.0"
}
```

---

## 11 個驗證錯誤碼（設計文件 §4 UC-02）

| 錯誤碼 | 說明 | 範例 |
|--------|------|------|
| `MISSING_FIELD` | 缺少必要欄位 | 缺少 hitPolicy |
| `UNKNOWN_OPERATOR` | 不支援的 operator | `"operator": "like"` |
| `TYPE_MISMATCH` | value 與 typeRef 不匹配 | BOOLEAN 值 `"yes"` |
| `UNKNOWN_FIELD` | 引用未定義的欄位 | condition 用了 inputs 裡沒有的 bmi |
| `DUPLICATE_ID` | ruleId 重複 | 兩個 R01 |
| `ENUM_VALUE_MISSING` | ENUM 缺 allowedValues | `typeRef: "ENUM"` 沒給值清單 |
| `INVALID_ENUM_VALUE` | 值不在 allowedValues | `"拒絕"` 不在 `["承保","拒保"]` |
| `INCONSISTENT_TABLE` | FIRST 策略下條件重疊 | R01 與 R02 可同時命中（附觸發範例） |
| `INVALID_MULTI` | MULTI hitPolicy 問題 | MULTI 但只有 1 條規則 |
| `MISSING_BRANCH` | Tree 缺分支（保留給後續階段） | — |
| `MISSING_RESULTS` | 規則缺 output 結果 | 定義了 3 個 output 但 results 只給 2 個 |

---

## 12 個 Operator（設計文件 §3.3）

| operator | 適用 typeRef | value 格式 |
|----------|-------------|-----------|
| `equals` / `notEquals` | 全部 | 單一值 |
| `greaterThan` / `greaterThanOrEqual` | INTEGER / DECIMAL / DATE | 單一值 |
| `lessThan` / `lessThanOrEqual` | INTEGER / DECIMAL / DATE | 單一值 |
| `between` | INTEGER / DECIMAL / DATE | `[min, max]`（含兩端） |
| `in` / `notIn` | STRING / ENUM | `["a", "b"]` |
| `isNull` / `isNotNull` | 全部 | 不需要 value |
| `anything` | 全部 | 不需要 value |

---

## 6 個 typeRef（設計文件 §3.2）

| typeRef | Java 型別 | 驗證規則 |
|---------|----------|---------|
| `INTEGER` | Integer/Long | 不能有小數點 |
| `DECIMAL` | Double/BigDecimal | 允許小數 |
| `BOOLEAN` | Boolean | 只接受 true/false |
| `STRING` | String | 任意文字 |
| `ENUM` | String | value 必須在 allowedValues 內 |
| `DATE` | LocalDate | 格式 yyyy-MM-dd |

---

## 架構（計畫書 §6.2 Strategy Pattern + Registry）

```
RuleTypeRegistry
  ├── DecisionTableGenerator  +  DecisionTableValidator   ← 本階段正式支援
  ├── DecisionTreeGenerator   +  DecisionTreeValidator    ← 後續階段 stub
  └── (未來) ScoreCardGenerator + ScoreCardValidator      ← 後續規劃

核心流程：Generate → Validate → Analyze → Response
新增規則型態：只需新增 Generator + Validator → 自動註冊，不改核心流程
```

---

## 可觀測性

- **Actuator**: `GET /actuator/health`
- **Prometheus**: `GET /actuator/prometheus`
- **Metrics**: `rules.generate.total`, `rules.generate.success`, `rules.generate.fail`, `rules.validate.total`
- **Structured Log**: 每次呼叫記錄 `traceId`, `schemaVersion`, `promptVersion`, `valid`, `errorCount`, `errorCodes`

---

## 版本策略

| 版本欄位 | 用途 | 當前值 |
|---------|------|-------|
| `schemaVersion` | 控制 RuleEnvelope 輸出格式 | `1.0.0` |
| `promptVersion` | 控制生成策略 / prompt template | `p3.13.0` |

每次 generate 輸出都帶有兩個版本號，可完整追溯「此 JSON 是由哪個版本的規格和策略產生」。

---

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

## Phase 進度

| Phase | 時間 | 重點 | 狀態 |
|-------|------|------|------|
| Phase 2 | 3/14 - 3/31 | REST API 完善 + Recommend Beta + Demo | ✅ |
| Phase 3 | 4/01 - 4/30 | DecisionTree 生成 + 驗證 + Test Harness | ✅ |
| Phase 4 | 5/01 - 5/29 | 進階驗證（v3.6-v3.10 品質防線、v3.12 業務敘事、v3.13 sparsity 優化）+ 雲端部署藍圖 + K8s manifests | 進行中 |
| Phase 5 | 5/30 - 6/30 | 結案交付 + 使用手冊 + 結訓分享 + Cluster 實際部署驗證 | 規劃中 |
