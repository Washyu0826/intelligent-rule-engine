# Changelog

All notable changes to Rules MCP Server will be documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased] — 審核工作台第一批：選型、補缺口、試算

### Added
- **審核工作台**（maker／checker 分流）：樹狀目錄＋兩維標籤、送審必填理由、送審當下的影響報告快照、
  審核單（差異＋影響＋理由）、以中文描述請 AI 提修改建議（只提案、不自動儲存）。
  新端點：`GET /rules/tree`、`PUT /rules/{key}/placement`、`GET /rules/{id}/review-sheet`、
  `POST /rules/{id}/suggest-change`；migration `V5__workbench.sql`。
- **規則型態判定改為結構訊號優先**：多層編號／縮排、列舉維度＋輸出、逐條「若…則拋訊息」、
  「條件 x.y／檢核 x.y」各有加權；信心不足才請預設 LLM 二選一（`LlmProvider.classifyRuleType`）；
  仍判不出就回 `needsClarification` 讓前端先問一題。回應多了 `method` 與一句白話 `reason`。
  前端結果頁新增判定橫幅（信心、理由、一鍵改型重生）與反問對話框；評分卡進入型態選項。
- **缺口補成案例**：影響報告的 `gaps[]` 改為帶 `conditions` 的物件；`POST /rules/{id}/gap-case`
  依缺口區間產生一列規則（決議類 ENUM 填「人工評估」，其餘留空，由驗證標為待填），
  前端走與 AI 建議相同的前後對照→存成新草稿流程。
- **工作台試算**：依規則 inputs 自動產生表單；草稿／送審版以 `/tools/execute` 模擬、不留軌跡，
  生效版以 `/engine/execute` 執行並顯示軌跡編號；顯示命中規則、輸出與耗時。

### Changed
- `POST /rules/{id}/submit` 現在要求 `{"reason": "..."}`，空白回 400。
- `RecommendResponse` 新增 `method`、`needsClarification`、`clarifyingQuestion`、`clarifyOptions`。

---

## [v3.16.3] - 2026-08-18 — 共享狀態修正：ThreadLocal 跨請求污染 + 失效的 LLM 快取

### Fixed — 維度解析結果跨請求洩漏（保密 + 正確性）
- `DescriptionDimensionParser` 的 **static `ThreadLocal<ParsedDimensions>` 已移除**。
  原本它被用來在「LLM 生成」與「generator 後處理」之間隱性傳值，但只有 `OllamaService`
  寫入、只有 `DecisionTableGenerator` 清除 —— `DecisionTreeGenerator` / `ScoreCardGenerator`
  走完後值會殘留在執行緒上。v3.16 起 LLM 工作跑在共用的 `llmExecutor` 池、執行緒高度重用，
  殘留值會被**下一個請求**讀到：`ClaudeService` 會把前一個請求解析出的欄位塞進本次 prompt，
  `DecisionTableGenerator` 會拿它去跑維度擴展 / 笛卡爾積填充，產出不屬於該張表的欄位。
- 改為顯式契約：`LlmProvider.usesDimensionPreparse()`（預設 `false`，`OllamaService` 覆寫為
  `true`——維度預解析本來就是補償本地小模型的機制）。`DecisionTableGenerator` 以方法區域的
  `LlmGenerationTrace` 承接訊號，`parse()` 由呼叫端各自獨立執行（純 regex，典型 <5ms，
  與 `PreflightService` / `SpecLintService` 既有作法一致）。無共享可變狀態。
- `ClaudeService` 移除該段維度注入 —— Claude 路徑從來拿不到自己的解析結果，
  唯一非 null 的情況就是上述污染。

### Fixed — `llmGenerate` 快取從未生效
- `@Cacheable` 原本標在各 provider 的 `doGenerateRuleJson(...)` 上，而該方法只被同類別的
  `generateRuleJson(...)` 以 `this.` 內部呼叫。Spring Cache 預設 proxy 模式下內部呼叫
  不經過 proxy → **註解自始無效，命中率恆為 0**（v3.16.0 調整的「50 筆 / 30 分」
  調的是一個沒有運作的東西）。專案對快取有 0 個測試，因此長期無訊號。
- 新增 `LlmGenerationCache` @Component 作為唯一快取入口，provider 跨 bean 邊界呼叫，
  註解才會作用。Claude / Ollama 已接上（Gemini 先前也沒有實際標註，維持原狀）。
- 快取鍵由 `description.hashCode()`（32-bit，碰撞會回傳**另一個請求的規則 JSON**）
  改為 `provider|mode|ruleType|sha256(description)` —— 消除碰撞，且鍵長度固定，
  不因 50KB 描述而膨脹。
- 加上 `unless = "#result == null"`：provider 失敗回 null 時不進快取，
  避免一次暫時性 API 失敗被固定 30 分鐘。

### Tests
- 新增 `LlmGenerationCacheTest`（7 測試）：快取語意（命中只執行一次 loader、null 不入快取、
  鍵區分度、鍵長度固定）、**端對端 proxy 邊界**（同一描述連打兩次，WireMock 只應收到 1 次
  `/api/chat`）、維度預解析契約（只有 Ollama 宣告、介面預設 false、
  `DescriptionDimensionParser` 不得再有 `ThreadLocal` 靜態欄位）。
- 端對端那條已反向驗證：還原修正前的寫法後失敗於
  `Expected exactly 1 requests ... but received 2`，確認不是空測試。
- `LlmIntegrationTest` 加上 `llmGenerate` 快取清理 —— 快取現在真的會生效，
  測試間必須隔離才有決定性。
- 全套件 **656 測試全綠**（649 → 656）。

---

## [v3.16.2] - 2026-06-11 — 深度 code review 修正：取消機制補全 + 截斷透明化結構化

### Fixed — 非同步取消（v3.16 殭屍工作修復的下半場）
- `asyncLlm` 保留 `Future` 並在 `onTimeout` 呼叫 `cancel(true)`；`RuleService.testRun`
  逐 case 檢查中斷旗標、catch 偵測例外鏈中的 `InterruptedException` 並恢復旗標 —
  客戶端 504 後 worker 於當前 case 邊界停止（原本只防「排隊中」的工作，執行中的照跑到完）。
- 佇列中過期（從未開始執行）的請求改回 **429「伺服器忙碌」**，不再誤導性地回
  504「請簡化輸入」（以 `started` 旗標區分）。
- timeout 公式抽成 `LlmTimeoutPolicy` @Component（REST 與 MCP 共用）：
  `(timeout-seconds + 60s 緩衝) × case 數`，上限改為可設定 `rules.llm.max-async-timeout-seconds`
  （預設 1800）；緩衝隨 case 數縮放（原固定 +60s 被 N case 攤薄）。
- MCP `test_run_rules` 套用同一 policy：經 `llmExecutor` + `Future.get(timeout)`、
  逾時 `cancel(true)` 回結構化 TIMEOUT — 補上 REST 入口修了但 MCP 入口沒修的缺口。

### Fixed — 截斷透明化（v3.16 expandCartesian 修復的結構化收尾）
- `TableDiffResult` 新增 `truncatedRuleIds` 欄位、截斷時 **`approximate=true`** —
  原本截斷只進中文 summary 字串，機器消費者（CI 閘門）看 `approximate==false` 會被騙；
  且截斷實際會產生幻影 LOST/NEW_COVERAGE（誤報），不只漏報。
- `/generate` 路徑補齊：`RuleEnvelope.Evaluation` 加 `truncatedRuleIds`（非空才設定，
  舊回應 JSON 形狀不變）→ `/generate` 與 `/analyze` 的截斷警告一致（原本只有 /analyze 有）。
- 警告文案抽共用 `HyperRectangle.truncationNote()`（DmnAnalyzer / RuleDiffService 不再各寫一份）；
  文案改「可能不完整**或失準**」；ID 列表上限 5 個防 summary/log 膨脹。
- `AnalyzeResponse.truncatedRuleIds` 加 `@Builder.Default`（取代逐建構點 null-guard 與誤導註解）；
  刪除已無呼叫者的 `HyperRectangle.fromJsonRule`（會悄悄丟棄截斷訊號的捷徑）。

### Fixed — 前端
- `TestRunPanel` 原本固定送空 body `{}` → 必然 400，「批次測試」按鈕從未可用；
  改為內建 3 個測試案例（描述對準離線情境關鍵字，無 LLM 金鑰可跑通）走 `api.testRun()`。
- SSE error 事件改 `objectMapper` 序列化 — 原手組 JSON 只跳脫引號，
  訊息含反斜線/換行（Jackson 錯誤、Windows 路徑）會產生非法 JSON，前端錯誤被靜默吞掉。
- `TIMEOUT_LLM` 360s → 430s（後端視窗最長 420s，原本前端先斷線丟棄完成中的結果）；
  `testRun` 客戶端 timeout 鏡像後端公式（430×N+10s，封頂 1810s）。

### Tests
- 截斷測試補 `approximate==true` 與 `truncatedRuleIds` 結構化斷言；
  全套件 **78 類 / 649 測試全綠**；前端 43 測試全過、lint 0 errors。

---

## [v3.16.0] - 2026-06-10 — 效能實測優化 + LLM 端點全面非同步化

### Performance（JMH 實測，基線 commit bc5c24c）
- `ConditionOverlapDetector` 重疊偵測改「profile 預計算 + 掃描線剪枝」：
  findAllOverlaps 1000 條規則 **1320ms → 7.1ms（185x）**；既有 35 個語意測試不變全過，
  輸出順序相容。數據與方法：`docs/面試敘事_效能與併發優化_2026-06-10.md`。
- ObjectMapper 注入共用（controller 4 處 per-call 建構移除）、`/convert` 序列化往返改 `deepCopy()`、
  `RuleValidator` 共用靜態 mapper、`CartesianProductFiller` 預編譯 regex。
- `CacheConfig` 各快取獨立 TTL：`llmGenerate` 50 筆/30 分（原三快取共用 5 分，與註解不符）。

### Changed — LLM 端點全面非同步化（thread starvation 修復）
- 新增 `AsyncExecutorConfig`：`llmExecutor` / `sseExecutor` 專用有界池
  （core=max + allowCoreThreadTimeOut；大小可由 `rules.executor.*` 配置；滿載 AbortPolicy → 429）。
- `/generate`、`/test-run`、`/explain`、`/narrate`、`/evaluate` 改 `DeferredResult` + 共用
  `asyncLlm` helper：LLM 工作移出 Tomcat worker；逾時 = `rules.llm.timeout-seconds`+60s → 504。
  `/test-run` 原本在迴圈內逐 case 同步打 LLM（N×120s 佔住 worker），風險最高。
- WireMock A/B 壓測實證（Tomcat 4 worker、3s 慢 LLM、8 併發）：
  /health 最大延遲 **同步 6987ms → 非同步 126ms**；固化為迴歸測試 `GenerateThreadStarvationTest`。
- SSE `/generate/stream` 改用有界池（原裸 `new Thread()` 無上限）；graceful shutdown
  （`server.shutdown: graceful` + Dockerfile ENTRYPOINT `exec` 讓 SIGTERM 直達 JVM）。

### Fixed — 正確性
- `HyperRectangle.expandCartesian`：IN 笛卡爾積超過 1000 上限原本**靜默截斷**，
  overlap/gap 分析在截斷區漏報且無警告 → 新增 `Expansion`（truncated/totalCombinations）、
  WARN log、`AnalyzeResponse.truncatedRuleIds` 欄位與 summary 警示；long 計算防 int 溢位。
- 前端 `types.ts` `!Boolean(x)` → `!x`（CI eslint no-extra-boolean-cast）。
- SSE 錯誤路徑 `e.getMessage()` 為 null 時 NPE；`/convert`、`/test-run` 驗證行為補正。

### Frontend
- `rulesApi.ts` 全 fetch 加分級 timeout（LLM 360s / 計算 60s / 查詢 10s / SSE 600s 與後端對齊），
  網路錯誤統一轉 `ApiError`；`useRuleGeneration` 移除未接線的 `abortRef`、JSON 解析失敗給友善提示。

### Tests / Tooling
- 新增 `DmnAnalyzerTest`(8)、`HyperRectangleTest`(7)（原兩類零覆蓋）、
  `OverlapDetectorBenchmark`（JMH）、`GenerateThreadStarvationTest`（A/B 壓測）。
- `clean-workspace.ps1`：一鍵清理建置產物（工作區 252MB→11.8MB）。
- 全套件：**78 類 / 648+ 測試全綠**。

### Docs
- `全面深度優化_2026-06-10.md`（13 項修正 + backlog）、
  `docs/面試敘事_效能與併發優化_2026-06-10.md`（問題→量測→取捨→結果）、
  `docs/優化研究_下一輪_2026-06-10.md`（3 路深掃：LLM 管線 >> DmnAnalyzer > TreeOptimizer 降級不做）。

---

## [v3.15.1] - 2026-06-07 — 交付後穩健性與品質強化（§6 backlog）

### Added
- 前端 `ErrorBoundary`：包覆「規則輸入 / 結果分析 / v3.14 預覽」三區，單一元件 render 例外不再整頁白畫面，提供「重試 / 重新整理」復原。
- diff / tree-paths 端點限流：Resilience4j `"diff"` 限流器（60/min、`timeoutDuration=0` 額滿即時拒絕）套用於 `/tools/diff-tree`、`/tools/diff-table`、`/tools/diff-rules`、`/tools/tree-paths`；新增集中式 `RequestNotPermitted → 429`（`RATE_LIMITED` + `Retry-After`）於 `GlobalExceptionHandler`，免去每端點 fallback 樣板。

### Changed（健壯性）
- `TreeDiffService`：節點數上限 `MAX_NODES=200`（防 Zhang-Shasha O(m²n²) hang）+ 深度/循環防呆 `safeNodeCount`（`MAX_DEPTH=200`），超限回 `comparable=false` 友善訊息；`TreeDiffResult` 加 `comparable` 旗標。
- `TreePathService`：`dfs` 加遞迴深度上限 `MAX_DEPTH=500`，防極深/循環樹 StackOverflow。
- 前端 `SemanticTreeDiff`：`comparable=false` 顯示友善訊息（避免誤判「完全相同」）；`types.ts` `TreeDiffResult` 補 `comparable?`。

### Fixed
- `DecisionTreeView` 節點細節面板用到未定義的 `cnField`/`cnOp` → 前端 build 失敗；沿用同檔既有 `fieldLabel` / `getOperatorDisplay` 字典補上。
- `RuleDiffServiceTest.structuralCountsDuplicateSignatures`：text block 共同縮排剝除後結尾實為 `"  ]}}"`，原 `.replace("          ]}}", …)` 未命中導致測資未插入而誤判；改用剝除後字串。

### Deferred（評估後刻意暫緩）
- **PreflightService 單次 parse**：`DescriptionDimensionParser.parse()` 為純函式、重構技術上安全，但需改 3 檔 + 新增依賴只為省 ~5ms 符號解析，收益不抵風險，暫緩。
- **前端打包 Maven profile（`-Pui`）**：已設計 antrun/exec 方案，但目前建置環境無法下載對應 plugin 依賴（離線/憑證限制），無法產出可驗證的工作版本；維持 `build-frontend.ps1` + `mvn package`（讀已 commit 的 `static`）為前端打包流程。

### Tests
- 新增 `GlobalExceptionHandlerTest`（限流 → 429 對映）、`TreeDiffServiceTest` 超大樹拒絕案例；後端 `mvn test` 全綠、前端 `tsc -b` + `vite build` 綠。

---

## [v3.15.0] - 2026-06-05 — 主管三研究方向：輸入前置閘門 / 樹路徑視覺化 / 規則 diff

### Research / 文獻依據
- 樹編輯距離 **Zhang-Shasha (1989)** 用於決策樹語意比對；行為等價概念參考 Calvanese et al. (BPM 2016)。
- 設計文件：`design/input-preflight-gate.md`、`design/tech-research-three-directions.md`。

### Added — 方向① 輸入前置閘門（pre-flight gate，純符號、0 token）
- `PreflightService`：off | warn | block 三模式；block 偵測 ERROR 即短路、不呼叫 LLM。
- `SpecLintService`：矛盾區間 / 重疊區間 / 空值域 / 重複值 / 笛卡爾積爆炸的純符號預檢。
- `POST /tools/preflight`；前端 `PreflightBanner` 紅黃綠燈；`application.yml` 新增 `rules.preflight.*`。

### Added — 方向② 決策樹條件視覺化
- `TreePathService`：DFS 把每條 root→leaf 展開成一句中文白話規則（結構化 steps + narrative）。
- `POST /tools/tree-paths`；前端 `DecisionPathTable` + 樹節點條件主角化（欄位/邊標籤放大、halo）。

### Added — 方向③ 規則 diff（三面）
- `TreeDiffService`：Zhang-Shasha 樹編輯距離，語意層忽略 nodeId 改名與兄弟重排。
- `RuleDiffService.diff`：雙表行為比對（超矩形集合差），回歸(漏覆蓋)/決策改變/新增覆蓋，相鄰差異點合併成可讀區段。
- `RuleDiffService.structuralDiff`：規則列依條件簽章對齊（不靠 ruleId）。
- `POST /tools/diff-tree`、`/tools/diff-table`、`/tools/diff-rules`；前端 `SemanticTreeDiff` / `BehavioralTableDiff` / `StructuralTableDiff` 整合進 `DiffView`。

### Changed
- `ConsistencyValidator`：區分冗餘規則（重疊但結果相同 → `REDUNDANT_RULE`/WARNING，不致 `valid=false`）與真衝突（結果不同 → `INCONSISTENT_TABLE`/ERROR，附重疊區間中點 witness）。
- `ValidationError` 新增 `severity` 欄位（`@JsonInclude(NON_NULL)`，向後相容）；`valid` 僅由 ERROR 級決定。
- `DmnAnalyzer` 暴露 `buildGeometry` / `GeometryModel` / `resolveRule` / `parseInputs`，雙表比對與單表分析共用同一幾何模型（DRY）。

### Fixed
- `InputSuggestionService` 日誌使用非法 SLF4J 佔位符 `{:.2f}`（會漏印 `qualityScore` 並印出字面值），改為 `{}`。

### Build
- 前端 `dist` 打包進 `src/main/resources/static`，單一 jar 啟動即服務完整 UI + API（同源相對路徑，零代理）；重建腳本 `build-frontend.ps1`。

### Tests
- 新增 `PreflightServiceTest` / `SpecLintServiceTest` / `TreePathServiceTest` / `RuleDiffServiceTest` / `TreeDiffServiceTest`；`mvn test` 全綠，前端 `tsc -b` + `vite build` 綠。

### 相容性
- 全部新增為加性：preflight `off` 時行為與導入前位元級相同；`severity` 未設時視為 ERROR（既有錯誤碼行為不變）。

---

## [v3.13.0] - 2026-04-25 — DecisionTree v2 Sparsity-Aware Pipeline

引入 GOSDT 風格的稀疏度感知 pruning 管線，讓 LLM 生成的決策樹在不犧牲 coverage 的前提下，平均葉節點減少、深度降低。本版完全 opt-in，不影響既有 `/tools/optimize`（v1）行為。

### Research / 文獻依據

- **GOSDT** — Lin et al., ICML 2020 / AAAI 2022：sparsity objective `loss + λ·#leaves`
- **LHT (Learning Hyperplane Tree)** — arxiv 2505.04139（2025.05）：oblique trees 列入 v3.14 規劃，本版本不做
- **FIGS** — PNAS 2024：multi-tree 加總策略，列入未來規劃
- **Subtree isomorphism** — Abboud 2017 + Aho-Hopcroft-Ullman canonical form：用於 Pass 4 同構偵測

### Added

- **設計文件 `docs/decision-tree-v2.md`**（416 行）：完整文獻綜述、目標函數推導、6-pass pipeline 設計、不變式、out-of-scope 清單
- **`service/optimizer/v2/TreeCanonicalizer`**：Merkle-like DFS 雜湊，產出子樹語意正規形（SHA-256 前 8 bytes）
  - operator / value / results 規範化（boolean string ↔ boolean、數字 string ↔ number、between 排序）
  - `groupByHash()` 用於偵測跨位置同構子樹
- **`service/optimizer/v2/TreeQualityMetrics`**：結構性度量（leafCount / branchNodeCount / maxDepth / avgPathLength / balanceIndex / uniqueSubtrees / duplicationRatio）
- **`service/optimizer/v2/SparsityObjective`**：GOSDT 風格目標函數
  - `Objective(T) = (1 − coverage) + λ·leaves + μ·depth`
  - `compare(before, after)` 回傳 ACCEPT / REJECT_OBJECTIVE / REJECT_COVERAGE
  - coverage 硬下限保護（預設 0.95）
- **`service/optimizer/v2/OptimizeConfigV2`**：可調參數 bean
  - `leafWeight` (λ, 預設 0.01) / `depthWeight` (μ, 預設 0.005) / `coverageFloor` (0.95)
  - `enablePass4/5/6` 個別開關 / `maxReconstructDepth` (4)
- **`service/optimizer/v2/TreeOptimizerV2`**：6-pass orchestrator
  - Pass 1-3：委派 v1 `TreeOptimizer.optimize()`（不重造）
  - **Pass 4 — DeduplicateIsomorphicSubtrees**：所有 children 同 hash 時收合（v1 Pass 1 的深度延伸）
  - **Pass 5 — EliminateRedundantConditions**：DFS 維護 `PathConstraintMap`，每個 field 累積 equals/in/excluded/數值 interval；child condition 被 ancestor 蘊含 always-true → 收合該分支；蘊含 always-false → 死碼
  - **Pass 6 — LocalReconstruction**：葉數 ≤ K² 的小子樹 flatten 後用 ID3 重建，objective 改善才採用
  - 每 pass 後 `SparsityObjective.compare` 決定接受/rollback；保證 monotonic improvement

### API

- **`POST /tools/optimize/v2`** — REST 端點
  - 請求：`OptimizeRequestV2 { ruleJson, config? }`
  - 回應：`OptimizeResponseV2 { optimized, metricsBefore, metricsAfter, sparsityScoreBefore, sparsityScoreAfter, appliedOptimizations, passContributions, durationMs }`
- **MCP tool `optimize_rule_tree_v2`** — 第 11 個 tool，參數 `ruleJsonStr` + 可選 `configJsonStr`

### Tests

- **`TreeCanonicalizerTest`**（13 tests）— 葉/分支 hash、value 正規化、groupByHash、null 邊界
- **`TreeQualityMetricsTest`**（8 tests）— 空樹/單葉/平衡/不平衡、duplicationRatio、avgPathLength、envelope wrapper
- **`SparsityObjectiveTest`**（6 tests）— objective 公式、accept/reject、極端 λ、equal-score ACCEPT
- **`TreeOptimizerV2Test`**（12 tests）— 6 passes 整合、Pass 4 同構合併、Pass 5 冗餘消除、coverageFloor 保護、邊界、不退化保證
- 全套合計新增 **39 tests**

### 不變式（Invariants，design doc §9.1）

- **A**：`metricsAfter.coverageRate ≥ coverageFloor`（Pass 內部 rollback 保證）
- **B**：`metricsAfter.leafCount ≤ metricsBefore.leafCount`（objective 比較保證）
- **C**：對相同輸入 input，optimized envelope 給出相同 result（純結構重排）
- **D**：`canonicalizer.semanticEqual(before, after)` 對 leaf-result 集合保留

### 不在本版本範圍（v3.14+）

- Oblique tree（LHT）— schema 大改 + 前端視覺化重做
- Causal sanity check — 公平性偵測
- FIGS multi-tree ensemble
- 前端 sparsity 滑桿 UI（即時調 λ/μ 看效果）
- v1 vs v2 diff view

### 相容性

完全 opt-in：v1 `TreeOptimizer` 與 `POST /tools/optimize` 行為不變。前端遷移可由 feature flag 控制。

---

## [v3.12.0] - 2026-04-24 — 業務敘事層（BusinessNarrativeService）

針對非工程使用者（保險業務／精算師）需求，新增整體業務敘事服務。與 v3.9 per-rule rationale 互補：v3.9 為每條規則一句解釋；v3.12 為整張表的口語化摘要。

### Added

- **`service/narrative/BusinessNarrativeService`**：用既有 `LlmProvider` API 產生 summary / highlights / coverage / exceptions / actuarialNote 五區塊；prompt 明文禁止 operator/typeRef 等技術詞；LLM 不可用時 rule-based fallback
- **REST `POST /tools/narrate`** + DTO `NarrateRequest`
- **MCP tool `narrate_rule_overview`**（第 10 個 tool）
- **前端 `RuleNarrative.tsx`**：放在 Overview tab 最上方，五區塊結構化顯示，loading skeleton + fallback 提示
- **`api.narrate()`**（rulesApi）+ 型別 `BusinessNarrative`
- **測試**：`BusinessNarrativeServiceTest` 11 測試（happy path / markdown 圍欄 / LLM unavailable / partial JSON / prompt smoke / 大規則表截斷）

---

## [v3.11.0] - 2026-04-22 — OpenAI / GPT Provider（stub）

為 Provider 切換介面新增 **GPT**，讓使用者可在 Claude / Gemini / Ollama 之外選擇 OpenAI 系列模型。本版刻意以 **最小可用版 stub** 交付：沒 `OPENAI_API_KEY` 時前端顯示為 disabled；有 key 時可完整呼叫 Chat Completions API 生成 RuleEnvelope JSON。

### Added

- **`OpenAiService`**（實作 `LlmProvider`）
  - 呼叫 `POST https://api.openai.com/v1/chat/completions`，`response_format: json_object` 強制 JSON 輸出
  - 預設 model：`gpt-4o-mini`（由 `rules.llm.openai.model` 可調）
  - `isAvailable()` 僅在 `OPENAI_API_KEY` 存在且非 blank 時回 true
  - `getProviderName()` → `"GPT (<model>)"`
  - 記錄 `usage.prompt_tokens / completion_tokens / total_tokens` 至 log
  - 失敗皆 graceful：非 2xx、空 body、缺 `choices[0].message.content`、network exception → 回 null（不拋例外）

- **`OpenAiServiceTest`**（12 tests）
  - isAvailable / getProviderName 合約
  - 無 API key → 直接回 null，不呼叫 RestTemplate
  - happy path：OpenAI 回應格式正確 → 取出 content
  - error branches：非 2xx、null body、missing content、RestClientException、null description

- **`application.yml`**：新增 `rules.llm.openai` 區塊（`api-key: ${OPENAI_API_KEY:}`, `model: gpt-4o-mini`）

- **`LlmProviderRegistry`**：`keyFor()` 識別 `OpenAiService` → `"openai"`

- **前端 `InputPage.tsx`**
  - `PROVIDER_DISPLAY` 加 `openai: 'GPT'` / `gpt: 'GPT'`
  - `PROVIDER_ENV` 加對應 `OPENAI_API_KEY` tooltip（未設時 disabled 並顯示應設定哪個變數）

### Deferred（刻意不做，留待未來版本）

OpenAiService 目前與 `ClaudeService` 不對等，下列機能留作 future work：

| 機能 | ClaudeService | OpenAiService v3.11.0 |
|---|---|---|
| Level A JSON parse retry（最多 3 次） | ✅ | ❌ |
| Level B self-repair（`repairRuleJson`） | ✅ | ❌（用 interface 預設 → null） |
| Spring `@Cacheable` 回應快取 | ✅ | ❌ |
| 3-arg form 支援 mode / ruleType 分流 prompt | ✅ | ❌（用 interface 預設） |
| Prompt caching 命中 log | ✅（Anthropic `cache_control`） | ❌（OpenAI 自動快取 ≥1024 tokens，未讀 `prompt_tokens_details.cached_tokens`）|

理由：stub 先讓 Provider 選擇 UI 完整（Claude / Gemini / GPT / Ollama 四選），實務上 Claude 仍為預設主力。等 GPT 出現真實使用量後再升級。

### Test Coverage

- Backend：**345 → 357 tests**（+12，OpenAiServiceTest 全綠）
- 其他既有測試不變

### Tech Details

- 版本 bump：prompt-version `p3.10.0` → `p3.11.0`、App.tsx `VERSION` → `v3.11.0`
- 新檔案：`OpenAiService.java`、`OpenAiServiceTest.java`
- 修改：`LlmProviderRegistry.java`、`application.yml`、前端 `InputPage.tsx`

---

## [v3.10.1] - 2026-04-22 — Frontend UI 補完

v3.6 – v3.10 累積的後端品質訊號（Judge / Witness / Grounding / Rationale / Confidence）先前只有 TypeScript 型別、沒有 UI 呈現。本版補齊前端視覺化，BA 可直接在 dashboard 看到這些訊號。

### Added — 3 個新前端元件

#### 1. `ConfidenceBar`（最頂端 overview）
- 大字顯示 0-100 總分 + tier 徽章（PRODUCTION_READY / REVIEW_NEEDED / NOT_RECOMMENDED）
- 展開後顯示五因子分解 bar（validation / grounding / coverage / noConflict / ruleCount），每條附色碼
- `actionableWarnings` 以圓點列表條列可行動建議
- tier 決定整體顏色：綠 / 黃 / 紅

#### 2. `GroundingPanel`（overview）
- SVG 圓環動畫顯示 grounding ratio 百分比
- 有嫌疑時預設展開，列出 ungrounded 欄位名與 ENUM 值的 chip 清單
- suspicionLevel 決定顏色（LOW 綠 / MEDIUM 黃 / HIGH 紅）
- 底部附使用提示（allowedFields 白名單可忽略此警示）

#### 3. `QualityToolbar`（overview）
- 「跨 provider 評估」按鈕觸發 `POST /tools/evaluate`，顯示 4 維度百分比 + comment + suggestion + biasWarnings
- 「產生每條規則解釋」按鈕觸發 `POST /tools/explain`，成功後 mutate envelope 回填 rationale，RuleTable 下次 render 可見
- 按鈕 loading / error 狀態都有視覺回饋

### Modified

- **`RuleTable.tsx`**：每條規則展開列頂部新增「規則理由」banner（只在 `rule.rationale` 存在時顯示），accent 色高亮，與既有三欄（條件解說 / 範例輸入 / 衝突規則）銜接
- **`ResultDashboard.tsx`**：overview tab 順序改為 Confidence → Grounding → QualityToolbar → 需求描述 → Evaluation → Validation → RuleHistory
- **`useRuleGeneration.ts`**：把 `groundingCheck`、`confidence` 從 `GenerateResponse` 接出來存入 `GenerationResult`
- **`types.ts::GenerationResult`**：補 `grounding?` / `confidence?` 欄位
- **`rulesApi.ts`**：新增 `evaluate()` / `explain()` 方法
- **`types.ts`**：新增 `JudgeResult` interface

### Test Coverage
- Frontend：38 tests, 0 failures（既有）+ TypeScript `tsc -b` 零錯誤 + `npm run build` 成功
- Backend：345 tests, 0 failures（不變，v3.10 已驗證）
- 3 個新 component 各自為獨立 lazy chunk（gzip 2-4 kB）

### Tech Details

- 版本 bump：App.tsx VERSION v3.10.0 → v3.10.1
- `@JsonInclude(NON_NULL)` 配合 optional `?:` 欄位確保舊 response 不破
- 全部新 UI 在後端回傳 null / undefined 時會優雅隱藏（沒有 `groundingCheck` 時面板不出現）

---

## [v3.10.0] - 2026-04-22

### Added — Composite Confidence Score（Capstone）

研究依據：
- arxiv 2404.15604 "Hybrid LLM/Rule-based Approaches to Business"
- Brain.co 2024 "Executable IF-THEN Logic for LLM Explainability in Regulated Industries"
- WEF 2025-12 Neurosymbolic AI — "auditable workings, real-world outcomes"

#### 1. 新服務 `ConfidenceScorer`
聚合前幾版所有品質訊號成**一個 0-100 分**，讓 BA 用單一數字決定是否可上線：

| 因子 | 權重 | 來源 |
|------|------|------|
| Validation 通過 | 30 | `ValidateResponse.valid`（每個錯誤扣 10%） |
| Grounding ratio | 25 | v3.8 `GroundingReport.groundingRatio` |
| Coverage rate | 20 | `Evaluation.coverageRate` |
| No conflict | 15 | `Evaluation.conflictDetection == NO_CONFLICT` |
| Rule count sanity | 10 | 0 → 0；1 → 0.5；2-200 → 1.0；>200 → 0.6 |

#### 2. Tier 分級
- **≥ 85：PRODUCTION_READY** — 可直接上線
- **70-84：REVIEW_NEEDED** — BA 人工確認
- **< 70：NOT_RECOMMENDED** — 需重新生成或大幅修改

#### 3. Actionable Warnings
每個扣分原因都對應一條具體的中文警告，BA 看到數字同時看到「怎麼改善」：
- 「驗證發現 N 個錯誤，請先修正（關鍵因子 30 分）」
- 「符號 grounding 檢查偵測到 N 個可能的幻覺欄位/值」
- 「覆蓋率僅 X%，存在未被規則覆蓋的條件組合」
- 「偵測到規則衝突（INCONSISTENT_TABLE）」
- 「規則數 N > 200，建議考慮轉為 DecisionTree 或拆分」

#### 4. 自動計算、回填至 `GenerateResponse.confidence`
- 在 `RuleService.generateFull` 的 Step 9 自動執行（非 opt-in）
- `NOT_RECOMMENDED` 記 WARN log
- 失敗時 non-fatal

#### 5. 前端型別
- `types.ts` 新增 `ConfidenceReport` interface
- `GenerateResponse.confidence?: ConfidenceReport`

### Test Coverage — 12 new tests, 345 total

`ConfidenceScorerTest` 涵蓋：
- Happy path（100 分 / PRODUCTION_READY）
- Validation 失敗分級扣分
- Grounding HIGH 嫌疑觸發警告
- 低覆蓋率警告
- 規則衝突歸零
- 規則數 0 / 1 / 過多的特殊分數
- **Tier 分界測試**：剛好 85 分、84 分的 tier 轉換
- 所有 input 為 null 的健壯性
- breakdown 五因子完整性 + 加總等於 overallScore

### Tech Details

- 新增檔案：`ConfidenceScorer.java`、`ConfidenceScorerTest.java`
- 修改：`RuleService.generateFull` Step 9、`ToolDtos.GenerateResponse`（+confidence）、前端 `types.ts`
- 版本 bump：prompt-version p3.9.0 → p3.10.0、App.tsx VERSION → v3.10.0

### 🎯 v3.6 – v3.10 完整品質保證體系

| 層 | 版本 | 機制 | 研究背書 | 延遲 |
|----|------|------|---------|------|
| 1. 符號 Grounding | v3.8 | 確定性字串/字典檢查 | AWS Automated Reasoning 2024 | < 5ms |
| 2. LLM-as-Judge | v3.6 | 跨 provider + self-consistency | arxiv 2411.15594 / 2412.05579 | 1-2 LLM 呼叫 |
| 3. 結構化 Witness | v3.7 | 衝突反例 counter-example | IEEE 2024 Tabular SMT | 0 |
| 4. Per-Rule Rationale | v3.9 | 中文業務解釋 | Allemang 2024 / reason+verify | 1 LLM 呼叫 |
| **5. Composite Score** | **v3.10** | **五因子 → 0-100 + tier** | **WEF 2025 Neurosymbolic** | **< 1ms** |

**累計改動**：295 tests → 345 tests（+50）、+3 新 REST 端點、+5 新 service、依 2024-2025 主流研究全面升級。

---

## [v3.9.0] - 2026-04-22

### Added — Per-Rule Rationale Service（2024-2025 Explainability / Reason+Verify Pattern）

研究依據：
- Dean Allemang (2024) **"Explaining rules with LLMs"**（Knowledge Graph Conference）
- arxiv 2510.24476 "Mitigating Hallucination in LLMs: Application-Oriented Survey"（reason+verify pattern）
- Brain.co 2024 **"LLM-Generated Rules Engines: IF-THEN for Explainability in Regulated Industries"**

#### 1. `RuleEnvelope.RuleRow` 新 `rationale` 欄位
- 每條規則可帶 1-2 句中文業務解釋
- `@JsonInclude(NON_NULL)` — 未設時不出現在 JSON
- 向後相容：既有序列化 / 反序列化測試全過

#### 2. 新服務 `RuleExplanationService`
- `explainAll(description, envelope, provider)` → ruleId → 中文句子 map
- `applyRationales(envelope, map)` → 批次回填到 `RuleRow.rationale`
- `explainAndApply()` one-shot helper
- **一次 LLM 呼叫產完整張表的 rationale**（非 N 次）— 成本可控
- Opt-in：不自動跑在 `/generate` 流程（保留主流程延遲）
- 失敗 non-fatal：LLM 不可用 / JSON parse 失敗 → 回空 map，不拋例外

#### 3. 新端點 `POST /tools/explain`
- Request：`{ description, envelope, provider? }`
- Response：`{ envelope（含 rationale）, rationalesApplied, provider, durationMs }`

#### 4. Prompt 設計要點
- 明確要求引用描述中的關鍵字或數字（可追溯）
- 禁止解釋「做什麼」（顯而易見），只解釋「為何這樣定」
- 限制 30-60 中文字，避免過度空泛

#### 5. 前端型別
- `Dashboard/types.ts::RuleEntry.rationale?: string`（UI 呈現留待 Phase 2）

### Test Coverage — 9 new tests, 333 total

`RuleExplanationServiceTest`（9 tests）涵蓋：
- Happy path：LLM 回 JSON → rationale 填回 2 條規則
- 部分成功：LLM 只回一條 → 只填一條
- LLM 不可用 / null / garbage / markdown-fenced JSON → 空 map（皆 non-fatal）
- null envelope / empty rules 邊界
- Prompt 結構 smoke test：包含 description、ruleId、欄位名、JSON 指令

### Tech Details

- 新增檔案：`RuleExplanationService.java`、`RuleExplanationServiceTest.java`
- 修改檔案：`RuleEnvelope.RuleRow`（+`rationale`）、`ToolDtos`（+ `ExplainRequest` / `ExplainResponse`）、`ToolsController`（+`/explain`）、前端 `types.ts`
- 版本 bump：prompt-version p3.8.0 → p3.9.0、App.tsx VERSION → v3.9.0

### 成本意識

一次 `POST /tools/explain` 呼叫 1 個 LLM prompt（約 300-600 tokens 輸出）產整張表的 rationale。典型延遲 3-8 秒（Claude / Gemini），成本約 US\$0.005/張。相較每條規則單獨解釋（N 次呼叫），節省 N-1 倍 token + latency。

---

## [v3.8.0] - 2026-04-22

### Added — Symbolic Grounding Guard（AWS Automated Reasoning 2024 pattern）

研究依據：AWS re:Invent 2024 **"Automated Reasoning Checks"** preview — 以符號邏輯數學地驗證 LLM 輸出事實正確性，不再只依賴「第二個 LLM 評分」（避免 judge 本身也幻覺）。對應論文趨勢：arxiv 2510.24476 / MDPI Information 2025 多 agent 幻覺緩解。

#### 1. 新服務 `GroundingGuardService`
- **純 Java 符號檢查**，不呼叫任何 LLM；典型延遲 < 5ms
- 檢查 `RuleEnvelope` 中每個輸入／輸出欄位名與 ENUM 允許值是否能 ground 回：
  - 使用者原始描述中（中／英子字串）
  - 使用者提供的 `allowedFields` 白名單
  - 通用 output 名稱（decision / result / outcome / remark 等）
  - 中文 keyword → 英文名映射（reuse `DescriptionDimensionParser` 的內建字典）
- 回傳 `GroundingReport`：grounding ratio、ungrounded 欄位 / ENUM 值清單、suspicion level（LOW / MEDIUM / HIGH）

#### 2. `DescriptionDimensionParser` 擴充
- 新公開方法 `inferExpectedEnglishFields(description)` — 直接從 `CHINESE_TO_ENGLISH` 字典做 substring 檢查
- 不依賴 `parse()` 的 `X（A/B/C）` 括號格式（一般自由描述也能用）

#### 3. `GenerateResponse` 擴充
- 新欄位 `groundingCheck: GroundingReport`（`@JsonInclude(NON_NULL)` 隱藏 null）
- 在 `RuleService.generateFull` 的 Step 8 自動執行；HIGH suspicion 記 WARN log
- 失敗時 non-fatal（catch 住，主流程不受影響）

#### 4. 前端型別
- `types.ts`：新增 `GroundingReport` interface
- `GenerateResponse.groundingCheck?: GroundingReport` 可選欄位

### Test Coverage — 10 new tests, 324 total

`GroundingGuardServiceTest` 涵蓋：
- 全部 grounded（ratio=1.0、LOW）
- LLM 發明新欄位 → 進 `ungroundedFields`
- LLM 發明 ENUM 值 → 進 `ungroundedEnumValues`
- `allowedFields` 白名單覆蓋
- 通用 output 名稱自動 grounded
- 中文描述 → 英文欄位名透過字典 grounded
- Suspicion level 分級（LOW/MEDIUM/HIGH）
- null envelope / 空描述邊界

### Tech Details

- 新增檔案：`GroundingGuardService.java`、`GroundingGuardServiceTest.java`
- 修改檔案：`DescriptionDimensionParser.java`（+`inferExpectedEnglishFields`）、`RuleService.java`（wire-in）、`ToolDtos.GenerateResponse`、`types.ts`
- 版本 bump：prompt-version p3.7.0 → p3.8.0、App.tsx VERSION → v3.8.0

### 幻覺防線層級（v3.8.0 完整三層）

| 層 | 方法 | 成本 | 時機 |
|----|------|------|------|
| 1 | `GroundingGuardService`（新）| < 5ms，純符號 | **每次 generate 自動** |
| 2 | `LlmJudgeEvaluator`（v3.6.0，跨 provider + self-consistency）| 1-2 LLM 呼叫 | 使用者呼叫 `/tools/evaluate` |
| 3 | Self-Repair Level B（v2.1.0）| LLM repair 呼叫 | 驗證失敗時自動 |

---

## [v3.7.0] - 2026-04-22

### Added — Structured Witness Field for Rule Verification（基於 IEEE 2024）

研究依據：Frick / Teuchert / Wehrheim (2024) **"Completeness and Consistency of Tabular Requirements: An SMT-Based Verification Approach"**（IEEE TSE, Xplore 10844918）— 形式化驗證應回傳**結構化反例**（concrete counter-example）而非嵌在字串裡的訊息。

#### 1. `ValidationError` DTO 擴充
- 新欄位 `witness: Map<String, String>`：觸發此錯誤的具體輸入範例
- 加上 `@JsonInclude(NON_NULL)`：witness 為 null 時不出現在 JSON（無需動其他 6 個呼叫站）
- 型別對齊既有 `OverlapInfo.intersection`（同專案 structural sibling）

#### 2. `ConsistencyValidator` 回填 witness
- `detectConflicts()` 現在把 `findOverlapExample()` 的 Map 同時寫進 `witness` 欄位
- `message` 格式保留不變（"觸發條件範例：..."）— 100% 向後相容
- 既有幾何邏輯（interval intersection + set intersection）完全未動，semantic 等價於 tabular fragment 的 SMT 解

#### 3. MCP Resource Schema 同步
- `VALIDATE_RESPONSE_SCHEMA`：加入 `witness` property（optional）
- `TEST_RUN_RESPONSE_SCHEMA`：同步補上
- `required` 維持 `["code", "message"]`（witness 為選配）
- MCP clients 透過 `rules://schemas/validate-response` 可立即看到新欄位

#### 4. 前端呈現
- `types.ts`：`ValidationError.witness?: Record<string, string>`
- `ValidationReport.ErrorRow`：新增「觸發範例」chip 區塊，key / = / value 以不同顏色分隔，mono 字型
- 僅在 `witness` 存在且非空時渲染，避免空狀態雜訊

### Test Coverage（314 tests, 0 failures）

- `DecisionTableValidatorTest`：
  - `tc08_inconsistentTable`：補上 `assertNotNull(e.getWitness())` + 非空驗證
  - 新 `nonConflictError_hasNullWitness`：確認其他錯誤碼 witness 為 null
  - 新 `inconsistentTable_betweenAndIn_producesWitness`：between ∩ in 雙欄位 witness 驗證
  - 新 `noWitness_whenEqualsAndNotEqualsMutuallyExclusive`：互斥不產生 witness（反面驗證）
- 新增 `ValidationErrorSerializationTest`（3 tests）：
  - witness 存在 → JSON 含 `"witness": { ... }`
  - witness null → 整個 key 從 JSON 消失（確認 `@JsonInclude(NON_NULL)` 生效）
  - witness 空 map → 仍序列化為 `"witness": {}`（NON_NULL 只過濾 null 不過濾 empty）

### Tech Details

- 修改檔案：`ToolDtos.java`、`ConsistencyValidator.java`、`RulesMcpResourceProvider.java`、`types.ts`、`ValidationReport.tsx`
- 新增檔案：`ValidationErrorSerializationTest.java`
- 版本 bump：`prompt-version` p3.6.0 → p3.7.0、`App.tsx` VERSION v3.6.0 → v3.7.0
- 所有 7 個未動的 `ValidationError.builder()` 呼叫站（`StructureValidator`、`FieldDefinitionValidator`、`RuleSemanticValidator`、`ScoreCardValidator`、`DecisionTreeValidator`、`RuleService.err`、`RulesMcpToolService`）維持原狀，witness 自動為 null 並從 JSON 隱藏

---

## [v3.6.0] - 2026-04-22

### Added — LLM-as-Judge Bias Mitigation（基於 2024–2025 研究）

依三份 2024-2025 文獻升級 `LlmJudgeEvaluator`：
- **arxiv 2411.15594** "A Survey on LLM-as-a-Judge"（2024-11）
- **arxiv 2412.05579** "LLMs-as-Judges: A Comprehensive Survey"（2024-12）
- **arxiv 2512.16041** "Are We on the Right Way to Assessing LLM-as-a-Judge?"（2025-12）
- Spring AI LLM-as-Judge pattern（2025-11）

#### 1. Cross-Provider Judge — 消除 Self-Enhancement Bias
- 新 `pickJudgeProvider(generatorProvider)`：優先挑選**與生成者不同**的可用 provider
- `evaluate(description, envelope, generatorProvider)` 新 overload，接收生成者身份
- `JudgeResult` 新增 `crossProvider`、`generatorProvider` 欄位
- 當僅剩同一 provider 可用時自動 fallback，並於 `biasWarnings` 中提示 `self-enhancement risk`

#### 2. Self-Consistency — 對抗 Positional Bias
- 新 `evaluateWithSelfConsistency()`：以**兩種 criteria 順序**（A: faith→comp→hall→cons / B: cons→hall→comp→faith）各跑一次
- 取平均為最終分數，並計算 `selfConsistencyVariance`
- Variance > 0.05 時於 `biasWarnings` 加入提示

#### 3. Verbosity Bias 診斷
- 規則數 < 3 但 completeness > 0.85 → 警告
- 規則數 > 30 但 hallucination < 0.10 → 警告
- System prompt 加上 "verbosity-neutral" 明確指令

#### 4. 新 REST 端點
- **`POST /tools/evaluate`**：獨立 LLM-as-Judge 評估
- Request 欄位：`description`、`envelope`、`generatorProvider`、`selfConsistency`
- Response：`JudgeResult` 含 4 維度分數 + bias 診斷 + provider 資訊

### Test Coverage
- 新增 `LlmJudgeEvaluatorTest`：**13 tests, 0 failures**
- 涵蓋 cross-provider 選擇、self-enhancement warning、verbosity 診斷、self-consistency variance、fallback
- 全專案 **308 tests, 0 failures**（從 295 上升）

### Tech Details
- 修改檔案：`LlmJudgeEvaluator.java`（完整重寫）、`ToolsController.java`（+`/evaluate`）、`ToolDtos.java`（+`EvaluateRequest`）
- 新增檔案：`LlmJudgeEvaluatorTest.java`
- 向下相容：舊 `evaluate(description, envelope)` 簽名保留

---

## [v3.5.1] - 2026-04-21

### Fixed
- **.gitignore 更新**：移除已追蹤的本地設定 / 建置產物
- **最終清理**：移除暫時檔案、統一版本標示

### Test Results
- Backend：**295 unit tests, 0 failures**
- Frontend：TypeScript zero errors
- E2E：**23/23 API tests 全通過**（health、providers、suggest、recommend、generate、validate、analyze、lookup、OpenAPI、security headers）

---

## [v3.5.0] - 2026-04-21

### Added — 規則歷史、影響分析、商用報告

#### 1. Rule History（總覽 tab）
- 每次生成自動存 `localStorage`（最多 20 筆）
- 顯示時間、規則型態、規則數、覆蓋率、驗證狀態
- **「比較」**：與目前結果 side-by-side diff（規則數、覆蓋率）
- **「還原」**：回復至任一次先前的生成結果
- 「清除所有歷史」按鈕

#### 2. Impact Analysis（規則表 tab）
- 選任一規則 → 選輸出欄位 → 設定新值
- 顯示：有多少規則受影響、佔比、受影響的 ruleId 清單
- 輸出值分佈長條圖
- 目前規則的欄位高亮標記

#### 3. Business Report（匯出 tab）
- 5 段商用可列印報告：
  1. 需求描述
  2. 品質摘要（metrics grid）
  3. 欄位定義（inputs / outputs）
  4. 規則明細（前 20 條，中文條件）
  5. 覆蓋率分析（gaps / overlaps）
- `window.print()` 觸發
- 列印專用 CSS：A4 橫向、黑白版面

#### 4. LLM-as-Judge Evaluator（後端 wip）
- `LlmJudgeEvaluator`：以 LLM 評分 faithfulness / completeness / hallucination / consistency
- `ClaudeService`：system prompt 加上 `cache_control: ephemeral`
- Token log 新增 `cache_creation` / `cache_read`

### Added — 商用品牌與版面打磨
- Header 改稱 **「Group Rules · 智能規則引擎」**，實心 logo mark
- 伺服器狀態 pill 樣式（正常 / 離線 / 連線中）
- Hero 標題 **「業務規則生成」**（3xl），多行副標
- 輸入卡 shadow-lg 景深
- Footer 新增 pipeline 視覺：需求分析 → 規則生成 → 驗證 → 分析
- Footer 品牌標：「Group Financial Holdings · 智能規則管理平台」

---

## [v3.4.0] - 2026-04-17

### Added — Multi-LLM Provider 運行時切換

#### Backend
- **`LlmProviderRegistry`**：集中管理所有 Provider，支援 runtime 選擇
- 移除各 LLM service 的 `@ConditionalOnProperty`（統一註冊、運行時切換）
- **`GET /tools/providers`**：回傳可用 Provider 清單（name / model / enabled）
- `GenerateRequest.provider` 欄位：單次請求指定 LLM

#### Frontend
- **Provider 選擇器**（Claude / Ollama / Gemini）放在生成按鈕上方
- 自動從 `/tools/providers` 載入可用清單
- **Rule Type 選擇器**：AI 推薦 / 決策表 / 決策樹（明確指定覆蓋 AI 推薦）
- Rule Summary 預設收合，「展開」按鈕

### Added — DecisionTree View 增強
- **點擊節點展開詳情卡**：
  - 分支節點顯示條件（如「年齡 大於 60」）+ 每個分支走向
  - 葉節點顯示所有 result 欄位（中文欄位名 + 值）
- `DETAIL_FIELD_CN` / `DETAIL_OP_CN` 自動中譯
- 非技術人員說明面板（分支節點 / 葉節點 / 點擊高亮路徑）
- Legend 簡化為「判斷條件 / 決策結果 / 決策路徑」
- Marker 中譯：GAP→缺口、DEAD→無效、MERGE→可合併

### Fixed
- 進度列顯示與步驟計數精確同步（active = 半填、done = 實色、progress 含 active step）
- Rules tab 版面：Rule Lookup + What-If Simulator 2 欄並排於桌面

---

## [v3.3.0] - 2026-04-17

### Added — 商用級 Dashboard

#### Summary Statistics Bar
- 頂端 5 指標列：規則數、覆蓋率、inputs / outputs、衝突數
- 顯示生成時戳與耗時
- **「列印報告」**按鈕整合
- 驗證狀態 badge（pass / fail + 錯誤數）
- 標題自動切換：「決策表分析報告」/「決策樹分析報告」

#### Print Report
- `@media print` CSS：A4 橫向、黑白乾淨版面
- 列印時自動隱藏 header / footer / navigation
- 表格邊框與字體大小優化

#### Layout
- Max-width 擴展：`max-w-7xl` (header) / `max-w-6xl` (dashboard)
- 更寬版面容納資料密集表格

### Changed — 主題色系打磨
- **Dark theme**：純黑 → 深海軍灰（`#0c0e14` → `#262c3d`），bright teal → muted blue `#5b9cf5`
- **Light theme**：純白 → 暖象牙白 `#fafbfc`，更深的暖灰文字
- 字體：加入 **Inter** 為主字型（資料密集 UI 更友善）
- 整體感：Bloomberg / 金融終端機 — 嚴謹、冷靜、專業
- Accent 回調為 `#34d2a8`（比原始 `#00e5bf` 更柔和）

### Fixed
- CoverageHeatmap 說明文字（非技術人員易懂）：「每個格子代表一種條件組合」
- Legend 展開：已覆蓋 / 缺口 / 衝突
- 軸標改為「橫軸 / 縱軸」

---

## [v3.2.0] - 2026-04-17

### Added

#### 1. What-If Simulator
- 選任一 input 欄位，系統窮舉所有可能值
- 顯示對照表：每個值如何改變 output
- 自動從第一條規則的 conditions 填入 baseline

#### 2. Natural Language Rule Summary
- 每條規則自動轉中文商用敘述
- 「全部複製」按鈕，方便匯出至文件

#### 3. Rule Template Library（14 個情境）
- 分類：保險 / 金融 / 商務 / 測試，含篩選 tab
- DecisionTree 模板以「(樹)」後綴標示
- 新增情境：核保、費率、意外險、貸款審核樹、投資風險評級

### Changed
- 模板庫簡化：預設「保險」分類、移除 badge 雜訊、單一中性色
- 標題改為「快速範例」
- 主題色 / 版面微調多次（見 commits）

---

## [v3.1.0] - 2026-04-17

### Added — 保險精算師與非技術用戶的 UX 優化

#### Wave 1：術語與引導
- **WelcomeGuide modal**：首次使用 3 步引導（含撰寫技巧，`localStorage` 可忽略）
- **HelpTooltip 元件**：所有 metric 加「?」hover 提示
- 全面中文化：DecisionTable→決策表、FIRST→首次命中、COMPLETE→完整、NO_CONFLICT→無衝突、operator / typeRef labels
- EvaluationPanel：每個 MetricCard 都有 `helpText` 商用語言說明

#### Wave 2：結果呈現
- 總覽 tab 顯示原始需求描述卡，方便對照輸入與輸出
- 生成步驟中文化：「理解您的業務需求」/「識別條件與結果欄位」/「建立決策規則」

#### Wave 3：Rule Lookup 強化
- 欄位名中文化：ageGroup→年齡區間、hasHypertension→是否有高血壓
- 型別中文化：INTEGER→整數、BOOLEAN→是 / 否、ENUM→選項
- **「用範例填充」**：自動套用第一條規則的 conditions
- 結果顯示採中文欄位名

#### Wave 4：輸入體驗
- textarea 上方可摺疊撰寫指南（好 / 壞範例對照）
- 規則描述撰寫技巧提示

### Fixed — CI
- 移除 `application-local.yml`（含密鑰）
- 補入 23 個 CI 編譯缺少的 Java source：`exception/`、`audit/`、`converter/`、`optimizer/`、validator 各層、`analyzer/TreeAnalyzer`、generator 工具類、MCP resource providers

---

## [v3.0.0] - 2026-04-17

### Added — 產品化四 Sprint

#### Sprint 1：核心功能
- **`POST /tools/suggest`**：輸入完整性分析（領域偵測、缺失維度建議、品質分數）— `InputSuggestionService`
- **`POST /tools/lookup`**：以輸入值匹配規則，支援 DecisionTable（FIRST / MULTI）與 DecisionTree 遍歷 — `RuleLookupService`
- **`SuggestionPanel`**：即時輸入建議（debounce）、偵測到的維度、缺失欄位 chips、品質進度條
- **`RuleLookupPanel`**：從 input 定義自動生成表單、匹配規則高亮、evaluation path 顯示

#### Sprint 2：穩定性
- **Claude Level A retry loop**（3 次，含錯誤回饋）
- **Claude prompt 強化**（60+ 行、5 步驟工作流程、邊界範例）
- **Claude dimension 注入**（`DescriptionDimensionParser`）
- Token 計數（從 API response 取 input / output tokens）
- `EnvelopeNormalizer`：依 typeRef 自動填補 null / 空 result
- `application-prod.yml` 正式環境設定

#### Sprint 3：正式環境強化
- **`ApiKeyInterceptor`**：`X-API-Key` header 驗證（可設定開關，預設關）
- **Security headers**：CSP、HSTS、X-XSS-Protection、Permissions-Policy
- 前端無障礙：ARIA labels、semantic HTML、`sr-only` labels

#### Sprint 4：整合
- **MCP Tools**：`suggest_input_completeness` + `lookup_rule`（共 9 個 tool）
- **OpenAPI / Swagger UI** at `/swagger-ui.html`
- 全 E2E 驗證：**295 tests, 0 failures**

### Added — Phase 3 DecisionTree 支援
- **`DecisionTreeGenerator`** + **`DecisionTreeValidator`** 正式上線
- **`TreeAnalyzer`**：gaps / dead branches / merge hints
- **`TableToTreeConverter`** + **`TreeToTableConverter`** 雙向轉換（`POST /tools/convert`）
- **`TreeOptimizer`**（`POST /tools/optimize`）：節點合併 / 深度縮減

### Infrastructure
- `.github/workflows/ci.yml`：CI pipeline
- `.dockerignore` / `.env.example`
- `AuditService` + `GET /tools/audit`

---

## [v2.2.0] - 2026-04-10

### Added — 延遲優化三部曲

#### 1. 消除重複 API 呼叫
- **`GenerateResponse` 合併回應**：`/tools/generate` 一次回傳 `envelope`（RuleEnvelope）+ `validation`（ValidateResponse）+ `analysis`（AnalyzeResponse）+ `durationMs`
- **前端 `useRuleGeneration` 重構**：NL mode 只呼叫 1 次 API（原本 3 次：generate → validate → analyze）
- **效果**：省去 2 次 HTTP round-trip，減少 ~30ms 延遲 + 避免後端重複計算

#### 2. Ollama Warm-up 機制
- **`OllamaService.warmUp()`**：後端啟動時非同步發送輕量 `num_predict=1` 請求
- **觸發 Cloud Run 容器預啟動 + 模型預載**，避免首次請求冷啟動延遲
- **Daemon thread**，不阻塞應用啟動

#### 3. SSE Streaming 即時回饋
- **新增 `POST /tools/generate/stream`**：Server-Sent Events 端點
- **事件類型**：`step`（階段進度 active/done）、`result`（最終完整 GenerateResponse）、`error`
- **`OllamaService.generateRuleJsonStreaming()`**：新增 streaming 方法，逐 token 讀取 Ollama NDJSON
- **`LlmProvider` 介面擴展**：新增 `generateRuleJsonStreaming()` default 方法（向後相容）
- **前端 SSE 整合**：`api.generateStream()` 使用 `fetch` + `ReadableStream` 接收 SSE，自動 fallback 到非 streaming
- **Vite proxy SSE 支援**：禁用 event-stream 回應緩衝

### Fixed
- **`OllamaService.doParseCheck()`**：修復 DecisionTree 支援（檢查 `rule.root` 而非 `rule.rules`）

### Changed
- `application.yml`：provider 改為 `ollama`，model 改為 `gemma4:e4b`，timeout 改為 120s
- `/tools/generate` 回傳格式從 `RuleEnvelope` 改為 `GenerateResponse`（包含 envelope + validation + analysis）
- `RuleService.generateFull()` 為新主方法，`generate()` 向後相容（供 MCP Tools 使用）

### Tech Details
- 後端修改 7 個 Java 檔案（ToolDtos、RuleService、ToolsController、OllamaService、LlmProvider、ToolsControllerTest）
- 前端修改 4 個檔案（types.ts、rulesApi.ts、useRuleGeneration.ts、vite.config.ts）
- 測試數量維持 270，0 failures

### 延遲改善效果
```
Before:  Recommend(12ms) → Generate/LLM(~19s) → Validate(8ms) → Analyze(17ms)  = ~20s (4 HTTP calls)
After:   Generate+Validate+Analyze(~19s, 1 HTTP call) + SSE 即時進度回饋
         + Warm-up 消除首次冷啟動（省 ~10-15s）
```

---

## [v2.1.0] - 2026-04-01

### Added — Self-Repair Pipeline（Level B）
- **驗證錯誤回饋修復**：Generate 完成後自動 Validate，若有錯誤（INVALID_ENUM_VALUE、TYPE_MISMATCH、INCONSISTENT_TABLE 等）組合成 repair prompt 餵回 LLM 修正
- **覆蓋缺口回饋修復**：Generate 完成後自動 DmnAnalyzer 分析，若有 gaps（未覆蓋的條件組合）列入 repair prompt 讓 LLM 補充規則
- **品質比較機制**：修復後自動比較新舊版本的 errors 和 coverageRate，只在確實改善時才採納
- **LlmProvider.repairRuleJson()** 新介面方法（default 實作，向後相容）
- **GeminiService Level B repair**：語意修復 prompt 模板，包含具體錯誤碼和缺口描述

### Added — Frontend Dashboard 全面重構
- **DashboardContext** 全域狀態管理（useContext + useReducer），支援跨元件聯動
- **5 Tab 架構**：總覽 / 規則表 / 分析 / 驗證 / 匯出，Spring indicator 滑動動畫
- **RuleTable**：framer-motion 拖拽排序 + 點擊展開行（條件解說/範例輸入/衝突規則）+ 欄位排序
- **EvaluationPanel**：AnimatedNumber 數字跳動 + CoverageRing SVG 圓環動畫 + ShareButton
- **ValidationReport**：嚴重度排序 + ERROR_FIX_HINTS 修復建議 + ErrorBarChart + 點擊跳轉規則表
- **CoverageHeatmap**：Cell 點擊跨 tab 聯動 + 外部高亮 + MiniCoverageRing
- **GapAnalysis**：點擊 gap 高亮 heatmap cell + stagger 動畫
- **SimplificationHints**：點擊 ruleId 跳轉規則表 + 捲動定位
- **JsonViewer**：遞迴可摺疊 JSON 樹 + Ctrl+F 搜尋 + 語法高亮
- **CsvExporter**：可編輯預覽表格 + BOM 中文支援 + 修改計數
- **DiffView**：LCS 行級 diff 演算法 + word-level inline diff + side-by-side 顯示

### Fixed
- **types.ts 型別遺失**：還原所有 API contract 型別（RuleEnvelope、ValidateResponse 等）
- **GapInfo 欄位名稱**：`dimensions` → `conditions`、`description` → `message`（與後端一致）
- **validate API payload**：前端改送完整 RuleEnvelope 而非只送 rule 內層
- **Export/index.ts**：修正錯誤匯出（指向不存在的 Dashboard 元件）
- **ThemeToggle JSX namespace**：改用 ReactElement

### Tech Details
- framer-motion 加入前端依賴
- Self-Repair 最多 2 輪，每輪呼叫 1 次 Gemini repair API
- 新增 3 個後端 Java 方法、1 個介面方法
- 前端 11 個元件完全重寫
- 測試數量維持 209，0 failures

---

## [v2.0.0] - 2026-03-27

### Refactored — 架構改善（10 項）
- **拆解 DecisionTableGenerator 上帝類別**：1,064→250 行，抽出 `EnvelopeNormalizer`（正規化）、`EvaluationComputer`（指標計算）、`ConditionOverlapDetector`（重疊偵測共用服務）
- **拆解 DecisionTableValidator 上帝類別**：829→60 行，拆成 4 層 `ValidationLayer`（StructureValidator → FieldDefinitionValidator → RuleSemanticValidator → ConsistencyValidator）+ `ValidationContext` 上下文
- **修正 DmnAnalyzer 離散維度 gap detection**：BOOLEAN/ENUM 維度改為每個離散值獨立格子，不再使用連續區間近似
- **消除重疊偵測重複程式碼**：`ConsistencyValidator` 改用 `ConditionOverlapDetector` 共用邏輯（數值區間、集合交集）
- **新增例外階層 + 輸入驗證**：`RuleException` 階層（4 類）、`GlobalExceptionHandler`（6 種 handler）、DTO `@Validated` + Bean Validation、`HttpMessageNotReadableException` 處理
- **metrics/tracing 抽到 AOP 切面**：新增 `RuleServiceMetricsAspect`，`RuleService` 移除所有 MDC/Counter/Timer 程式碼
- **LLM 抽象為 LlmProvider 介面**：`GeminiService` 實作 `LlmProvider`，`DecisionTableGenerator` 解耦
- **DmnAnalyzer 結果快取**：Caffeine + `@Cacheable`（100 筆上限、5 分鐘 TTL）
- **型別安全驗證**：`RuleValidator` 新增 `validate(RuleEnvelope)` 預設方法，`RuleService` 內部用型別物件驗證
- **校準 RuleRecommender 信心分數**：新 sigmoid + 差距加分 + 無競爭加分公式，18/18 準確率

### Added
- **整合 OfflineFallbackService**：LLM 失敗 → 離線 scenario 關鍵字匹配 → stub 模板（三層 fallback）
- **單元測試 +130 個**：EnvelopeNormalizer（71）、ConditionOverlapDetector（35）、EvaluationComputer（11）、ToolsController MockMvc（11）、v110 fixture 測試（2）
- `spring-boot-starter-aop`、`spring-boot-starter-cache`、`caffeine` 依賴

### Changed
- `prompt-version`：p1.2.0 → p2.0.0
- 測試數量：69 → 199

### Tech Details
- 新增 15 個 Java 檔案（3 個 generator 元件、4 個 validation 層、3 個 exception、1 個 AOP 切面、1 個 cache config、1 個 LLM 介面、2 個測試類別）
- 修改 12 個既有檔案

---

## [v1.4.0] - 2026-03-25

### Added
- **DmnAnalyzer 幾何分析引擎**：基於 Calvanese et al. (2016) DMN 語義論文
  - Overlap Detection：O(n²) 超矩形交集偵測
  - Gap Detection：覆蓋率計算 + 具體缺口回報（網格切分 + 蒙地卡羅估計）
  - Simplification：相鄰且結果相同的規則合併建議
- **POST /tools/analyze 端點**：獨立 DMN 分析，讓前端可與 validate 平行呼叫
- **generate 回傳帶 DMN 分析結果**：gaps、overlaps、simplifications 塞入 evaluation
- **QualityScorer**：5 維度加權評分（schema、coverage、conflict、rule count、reason）
- **OfflineFallbackService**：7 個離線 scenario JSON + 關鍵字匹配
- **React SPA 基礎架構**：Vite + React 19 + TypeScript + Tailwind CSS

### Changed
- `prompt-version`：p1.2.0 → p2.0.0

---

## [v1.3.0] - 2026-03-25

### Added
- **Prompt v2**：5 步驟工作流程，強制窮舉笛卡爾積所有條件組合
- **LLM Retry Level A**：JSON parse 失敗自動 retry（最多 3 次），repair prompt 回傳錯誤訊息
- **RuleRecommender 強化**：中英文關鍵字擴充、語句結構偵測（BOOLEAN 描述、數值區間、加總描述）

### Fixed
- **覆蓋率計算修正**：anything 維度不計入笛卡爾積；基於「分段數」而非 distinctValues 估算 cardinality
- **衝突偵測修正**：支援 greaterThan/lessThan vs between、equals vs between、in/notIn 交集
- **normalize() 穩固性**：所有 List 保證可變（避免 `List.of()` 的 `UnsupportedOperationException`）

---

## [v1.2.0] - 2026-03-20

### Added
- **Gemini LLM 整合**：新增 `GeminiService`，接 Gemini 2.5 Flash 免費 API，支援自然語言 → RuleEnvelope JSON 生成
- **自然語言生成模式（模式 B-1）**：`DecisionTableGenerator` 偵測到純自然語言輸入時，自動呼叫 Gemini LLM 生成完整 DecisionTable
- **Prompt Template**：內嵌完整 RuleEnvelope 結構規格、typeRef/operator 說明、範例，確保 LLM 輸出格式正確
- **LLM fallback 機制**：Gemini 呼叫失敗時自動 fallback 為提示模板，不影響既有功能
- **RestTemplate 超時設定**：連線超時 10 秒、讀取超時 60 秒，防止 LLM 長時間無回應

### Fixed
- **`buildStubEnvelope` List.of() bug**：`List.of()` 建立的不可變 List 在 `normalize()` 的 `.sort()` 會拋出 `UnsupportedOperationException`，改為 `new ArrayList<>()`
- **`RuleRecommender` compile error**：lambda 引用非 effectively final 變數 `best`，新增 `final TypeCandidate chosen = best` 修復

### Changed
- `application.yml`：`rules.llm.enabled` 改為 `true`，model 設為 `gemini-2.5-flash`，timeout 60 秒
- `prompt-version`：p1.1.0 → p1.2.0

### Tech Details
- 新增檔案：`src/main/java/com/ruleengine/rules/service/llm/GeminiService.java`
- 修改檔案：`DecisionTableGenerator.java`、`RuleRecommender.java`、`application.yml`
- Gemini API：`responseMimeType=application/json`、`temperature=0.1`、`maxOutputTokens=65536`

---

## [v1.1.0] - 2026-03-18

### Added
- **Validator 9 項改進**：
  - inputs/outputs 欄位名稱不可重複
  - 同一規則中重複 condition field 偵測
  - 未被引用的 input 欄位警告
  - hitPolicy 只接受 FIRST/MULTI
  - anything/isNull 帶 value 提醒
  - in/notIn 空陣列檢查
  - result value 為 null 檢查
  - ruleType 值檢查
- **衝突偵測升級**：Range 範圍求交集，可抓 between vs greaterThan 等複雜衝突
- **Generator 正規化強化**：
  - typeRef alias 修正（int→INTEGER, bool→BOOLEAN 等）
  - BOOLEAN 字串值自動修復（"true"→true）
  - INTEGER/DECIMAL 字串數值自動轉型
  - ENUM 缺 allowedValues 自動從 rules 推斷
  - 重複規則自動移除
  - 空 condition/result 自動過濾

### Fixed
- Operator 正規化支援更多 alias（eq, neq, gt, gte, lt, lte, range 等）

---

## [v1.0.0] - 2026-03-13

### Added
- **Phase 1 初始版本**：DecisionTable generate + validate 閉環
- **4 個 MCP Tools**：generate_rule_payload、validate_rule_schema、getServerInfo、getRuleTypeExample
- **REST API**：POST /tools/generate、/validate、/recommend、/test-run
- **11 個驗證錯誤碼**：MISSING_FIELD、UNKNOWN_OPERATOR、TYPE_MISMATCH、UNKNOWN_FIELD、DUPLICATE_ID、ENUM_VALUE_MISSING、INVALID_ENUM_VALUE、INCONSISTENT_TABLE、INVALID_MULTI、MISSING_BRANCH、MISSING_RESULTS
- **RuleEnvelope 結構**：ruleType + reason + evaluation + rule + schemaVersion + promptVersion
- **6 個 typeRef**：INTEGER、DECIMAL、BOOLEAN、STRING、ENUM、DATE
- **12 個 operator**：equals、notEquals、greaterThan、greaterThanOrEqual、lessThan、lessThanOrEqual、between、in、notIn、isNull、isNotNull、anything
- **可擴展架構**：Strategy Pattern + RuleTypeRegistry，新增型態不改核心流程
- **Recommend（啟發式）**：中文關鍵字匹配分類（DecisionTable / DecisionTree / ScoreCard）
- **Observability**：Actuator + Micrometer + structured log（traceId、schemaVersion、耗時）
- **demo.html**：5 個預設案例、loading 動畫、決策表視覺化、驗證結果顯示
- **測試**：DecisionTableValidatorTest（25+ 案例）、RuleRecommenderTest、GenerateValidateClosedLoopTest
