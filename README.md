# Intelligent Rule Engine

**規則上線前的風洞** — 把業務人員寫的中文規格，變成可驗證、可審核、可模擬執行、可回放的規則。

*English: a pre-production "wind tunnel" for business rules. Chinese spec → generate → validate → draft → maker-checker review → simulated execution → trace & replay. Exposed to AI clients over MCP, with the approval boundary deliberately kept out of the tool surface.*

保險業實習作品，源自規則治理的實際問題。Spring Boot 3.4 · Java 21 · Spring AI MCP · PostgreSQL · React 19

---

## 要解決的問題

規則（核保、理賠、費率）改一個條件，就可能讓一整群客戶被誤拒或誤賠。實務上的痛點有三個：

1. **規格寫在文件裡**，翻成規則表的過程靠人工，漏條件、重疊、缺口要等上線才發現。
2. **改了規則沒人敢說影響多大**，缺少上線前的模擬與回歸。
3. **出事後查不到當時為什麼是這個結果**：哪個版本、哪筆輸入、命中哪條規則。

## 定位：風洞，不是引擎

```
 中文規格 ─► 生成 ─► 驗證 ─► 草稿 ─► 送審 ─► 核准 ─► 生效 ─► 模擬執行 ─► trace / replay
 (BA)      LLM +   11 錯誤碼   DB      maker   checker           自有引擎    append-only
           離線備援 + 幾何分析  版本化   ≠ checker                 exec-1.0.0   不可竄改
```

正式環境的決策仍走既有規則引擎。本系統負責**上線之前**的事：規則寫得對不對、改動影響多大、當時為什麼這樣判。內建的 `RuleExecutionEngine` 是給上線前模擬與回歸用的，不是要取代正式引擎。

## 亮點

| | |
|---|---|
| **治理邊界 = MCP 暴露邊界** | AI 可以生成、驗證、分析、建草稿；**核准、生效、退役不提供 MCP 工具**，只能由具 CHECKER／ADMIN 身分的人在系統中操作。送審人不得審自己的案子（四眼原則）。 |
| **幾何分析而非猜測** | 規則表轉成超矩形，純符號找出覆蓋缺口與規則重疊（不靠 LLM）。重疊偵測掃描線剪枝，JMH 實測 1000 條規則 1320 ms → 7.1 ms（185x）。 |
| **四層防幻覺** | 符號 grounding → 跨 provider LLM-as-Judge → 結構化反例（witness）→ 逐條 rationale，彙整成 0–100 的 confidence。 |
| **可回放的決策軌跡** | `decision_trace` 由資料庫 trigger 保證只能追加；每筆記錄輸入快照、規則版本、引擎版本，可用 `/engine/replay` 重算並比對。 |
| **規則型態可互轉** | DecisionTable ↔ DecisionTree 雙向轉換；樹有 sparsity-aware 6-pass 優化與 Zhang-Shasha 樹編輯距離 diff。 |
| **LLM 不是單點** | Claude / Gemini / OpenAI / Ollama 執行期切換；LLM 失敗退回離線情境，再退回 stub。長請求走專用有界池，滿載回 429，不拖垮其他端點。 |

## 閉環對應的 API

| 階段 | 端點 | 角色 |
|---|---|---|
| 生成 / 驗證 / 分析 | `POST /tools/generate` · `/validate` · `/analyze` | 任何人 |
| 存為草稿 | `POST /rules` | MAKER |
| 送審 / 撤回 / 修訂 | `POST /rules/{id}/submit` · `/withdraw` · `/revise` | MAKER |
| 核准 / 退回 | `POST /rules/{id}/approve` · `/reject` | CHECKER（≠ 送審人） |
| 生效 | `POST /rules/{id}/activate` | CHECKER |
| 退役 | `POST /rules/{id}/retire` | ADMIN |
| 模擬執行 | `POST /engine/execute`（`traceLevel`: NONE / SUMMARY / FULL） | 登入者 |
| 回放 / 查軌跡 | `POST /engine/replay/{traceId}` · `GET /engine/trace/{id}` · `/engine/traces` | 登入者 |

規則狀態共 6 種、8 條合法轉換；同一個 `ruleKey` 同時只能有一個 ACTIVE 版本（資料庫 partial unique index 保證）。

## 快速開始

需要 JDK 21、Maven 3.9、Docker（跑 PostgreSQL）。

```bash
# 1. 起 PostgreSQL（對外 5433）
docker compose up -d postgres

# 2. 啟動服務（Flyway 會自動建表）
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/rules
mvn spring-boot:run
# → http://localhost:8080   Swagger UI: /swagger-ui.html
```

```bash
# 3. 登入取得 JWT（開發用 demo 帳號：maker / checker / admin，密碼見 application.yml）
curl -s localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"maker","password":"demo-pass-2026"}'
```

沒有 LLM 金鑰也能跑：預設 provider 是本機 Ollama，不可用時自動退回離線情境。要接雲端模型，設 `CLAUDE_API_KEY`／`GEMINI_API_KEY`／`OPENAI_API_KEY`。正式環境務必關閉 demo 帳號、設定 `RULES_JWT_SECRET`（`application-prod.yml` 已預設 `enforce`）。

前端：`./build-frontend.ps1` 會把 React SPA 打包進 jar；開發模式見 `frontend-src/`。

```bash
mvn test        # 單元 + 整合；Testcontainers 的 PostgreSQL 測試在無 Docker 時自動跳過
```

## 與 MCP 的關係

MCP 在這裡是**給 AI 用的介面**，不是系統本身。同一套能力有兩個入口：REST（給人與前端）、MCP（給 Claude 等 AI client，SSE 與 STDIO 皆可）。

- 13 個 tool：生成、驗證、分析、型態推薦、轉換、樹優化、輸入完整度檢查、批次測試…
- 7 個 resource：server 資訊、範例、response schema
- **刻意不暴露**：approve / activate / retire。AI 可以幫你把規則寫好、查出缺口，但不能自己放行。

STDIO 設定（Claude Desktop）：

```json
{ "mcpServers": { "rules": {
    "command": "java",
    "args": ["-jar", "target/rules-mcp-server-1.0.0-SNAPSHOT.jar", "--spring.profiles.active=stdio"] } } }
```

## 現況與限制

| 項目 | 狀態 |
|---|---|
| DecisionTable、DecisionTree | 正式支援：生成、驗證、分析、轉換、執行、diff |
| ScoreCard | **僅預留型態**（資料結構、生成器、驗證器已有；執行與分析尚未實作，MCP 說明明確標示不可用於正式輸出） |
| 執行引擎 | 直譯式，用於模擬與回歸；與生產引擎的格式差異透過 adapter SPI（`MockGroupEngine` 為示範）處理 |
| 前端 | 生成、分析、diff、What-If 完整；**審核工作台與執行／trace 檢視尚未做**，目前這兩段走 API |
| MCP | 只有 tool 與 resource；尚無 prompt template，也還沒有「建草稿／送審」tool |

## 文件

- [CHANGELOG](./CHANGELOG.md) · [版本演進](./docs/version-history.md)
- [RuleEnvelope 格式、錯誤碼、Operator](./docs/rule-envelope-reference.md)
- [專案結構](./docs/project-structure.md)
- [DecisionTree v2 優化設計](./docs/decision-tree-v2.md)
- [雲端部署藍圖](./docs/deployment-architecture.md) · [部署手冊](./docs/deployment-playbook.md) · [`k8s/`](./k8s/)
- [`design/`](./design/)：各子系統設計筆記
