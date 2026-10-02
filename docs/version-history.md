# 版本演進與功能清單

> 從舊版 README 搬移而來。逐版變更見 [CHANGELOG](../CHANGELOG.md)。

Phase 1（v1.0.0）交付了「Generate → Validate 最小閉環」的承諾，之後陸續擴展為完整產品；v3.14 加入集團 Group 格式轉換與執行 mock，v3.15 完成三個研究方向（輸入前置閘門 / 樹路徑視覺化 / 規則 diff），v3.16 完成效能實測優化（JMH 185x）與 LLM 端點全面非同步化（thread starvation 修復）：

| 類別 | 功能 |
|------|------|
| **REST API（~28 端點）** | 生成/分析：`/tools/generate`（含 SSE 進度回報；非 token 級串流）、`/validate`、`/recommend`、`/analyze`、`/test-run`、`/convert`、`/optimize`、`/optimize/v2`、`/suggest`、`/preflight`、`/lookup`、`/scenario-expand`、`/evaluate`、`/explain`、`/narrate`、`/providers`、`/audit`、`/audit/stats`、`/glossary*`、`/health`；**比對**：`/diff-tree`、`/diff-table`、`/diff-rules`；**樹視覺化**：`/tree-paths`；**Group 整合**：`/export/group-json`、`/export/group-xlsx`、`/execute` |
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
- **v3.15.0**：三個研究方向 — ①輸入前置閘門（PreflightService，生成前純符號預檢、0 token、off/warn/block）②決策樹路徑視覺化（TreePathService，root→leaf 白話化）③規則 diff（TreeDiffService Zhang-Shasha 樹編輯距離 + RuleDiffService 行為超矩形差 / 結構簽章對齊）；前端打包進單一 jar
- **v3.15.1**：交付後穩健性 — 前端 ErrorBoundary、TreeDiff/TreePath 規模與循環防呆、diff 端點限流 + 集中式 429、SemanticTreeDiff comparable 友善訊息
- **v3.16.0**：效能實測優化 + LLM 端點全面非同步化 — 重疊偵測掃描線剪枝（JMH 實測 1000 條 1320ms→7.1ms，185x）、`/generate` `/test-run` `/explain` `/narrate` `/evaluate` 改 DeferredResult + 專用有界池（WireMock A/B 壓測 /health 6987ms→126ms）、expandCartesian 截斷透明化（`truncatedRuleIds`）、DmnAnalyzer/HyperRectangle 補單元測試、前端 fetch 分級 timeout

---
