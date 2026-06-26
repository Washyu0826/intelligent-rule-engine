# Input Pre-flight Gate — 生成前輸入偵測設計

**Author:** architect (research direction #1)
**Date:** 2026-06-03
**Priority:** #1（主管三方向優先序 1→3→2 的第一項）
**Status:** draft — 待人類 checkpoint 後 dispatch impl

---

## 1. 目的與動機

主管要求：**在呼叫 LLM 生成「之前」**偵測輸入「不完整」或「邏輯錯誤」，避免花 token 後才發現規則錯誤。

目前 `RuleService.generateFull()`（`RuleService.java:84`）的 Step 1-3 直接決定型態 → 取 generator → **立即呼叫 LLM**，前面沒有任何阻擋閘。`/tools/suggest`（`InputSuggestionService`）雖已能給完整性建議，但：

- 它只是**諮詢**，不阻擋生成；品質 20% 也照樣燒 token。
- 它只查「不完整」（有沒有 input/output/值），**完全不查「邏輯錯誤」**（矛盾、區間重疊、互斥值）。
- 真正的邏輯檢查（`ConsistencyValidator` witness、`DmnAnalyzer` overlap/gap）**全在生成之後**，token 已花。

**成本論述**：一次完整生成 = 數千 token + 失敗 retry×3（Level A）+ self-repair（Level B）。把爛 spec 擋在 LLM 前面，整條重生成鏈直接省掉。本設計的 pre-flight 成本目標：**符號層 < 5ms / 0 token；可選 spec-lint LLM 層僅數百 token**。

---

## 2. 範圍

### In scope
- 在 `generateFull` LLM 呼叫前插入 **pre-flight gate**，兩層：
  - **Layer A — 完整性閘**（升級既有 `InputSuggestionService`，純符號）
  - **Layer B — 描述層邏輯預檢**（新 `SpecLintService`，純符號 + 可選一次廉價 LLM lint）
- 新增 gate 的請求旗標與結構化回應。
- gate 命中時**短路、不呼叫 LLM**，回 200 + 結構化「需澄清/有矛盾」清單（非 5xx —— 與專案「驗證回 200」哲學一致）。

### Out of scope
- 重寫 `DescriptionDimensionParser` 的 regex 解析引擎（沿用，僅消費其輸出）。
- 方向 2（樹視覺化）、方向 3（規則比對）。
- 改變既有 `/tools/suggest` 對外契約（**additive only**；前端即時建議行為不變）。
- 把 gate 設成永遠強制（預設 `warn`，硬閘為 opt-in，見 §5）。

---

## 3. 設計總覽

```
generateFull(request)
  ├─ Step 0  ← NEW: preflightGate(request)
  │            ├─ Layer A 完整性閘 (InputSuggestionService.analyze)
  │            └─ Layer B 邏輯預檢 (SpecLintService.lint)
  │            ↓
  │      命中阻擋條件? ──yes──▶ 回 GenerateResponse{ preflight, 無 envelope } 並 return（0 token）
  │            │no
  ├─ Step 1-3  決定型態 → generator → 呼叫 LLM（維持原樣）
  └─ Step 4-9  維持原樣；preflight 結果一併掛進 response（warn 模式時）
```

`preflight` 區塊永遠計算並回傳（供前端顯示）；是否**阻擋**由 mode 決定。

---

## 4. Layer A — 完整性閘（升級 InputSuggestionService）

沿用既有 `analyze()` 輸出（`detectedInputs/Outputs`、`missingDimensions`、`qualityScore`、`suggestions`、`estimatedRuleCount`）。新增**阻擋判定**：

| 阻擋條件（blockers） | 理由 |
|---|---|
| `detectedOutputs` 為空 | 沒有結果欄位，LLM 只能瞎猜輸出 |
| `qualityScore < 0.4`（可調） | 結構嚴重不足 |
| 描述 < 15 字且無編號條目 | 過短，必為占位 |

未達阻擋但有疑慮者列為 **warnings**（不阻擋）。門檻走 `application.yml`：

```yaml
rules:
  preflight:
    enabled: true
    mode: warn            # off | warn | block
    min-quality: 0.4
    require-outputs: true
```

**附帶修正**：`InputSuggestionService` 的 `insurance-frontend-validation` 領域刻意無模板（`:80`），導致最重要場景缺失偵測不觸發。本設計不強加模板（會誤報），改為在 Layer B 用「描述自洽性」補位（見 §5.2）。

---

## 5. Layer B — 描述層邏輯預檢（新 SpecLintService）

**核心洞見**：很多邏輯錯誤在「描述」階段就看得出來，不必等生成出規則表。把 `ConsistencyValidator` / `ConditionOverlapDetector` 的**幾何判定邏輯下放**到「已解析維度 + 句子層」即可。

### 5.1 純符號檢查（0 token，預設開）

輸入：`DescriptionDimensionParser.parse()` 的維度 + 原始描述切句。檢查項：

| Lint code | 偵測 | 範例 |
|---|---|---|
| `EMPTY_VALUE_DOMAIN` | 某 input 被偵測為 ENUM 卻沒列任何允許值 | 「依通路擋件」未說哪些通路 |
| `CONTRADICTORY_RANGE` | 同欄位數值區間在描述中互相矛盾/無交集卻要求同時成立 | 「年齡>60 且 年齡<30」 |
| `OVERLAPPING_BRANCH` | 同欄位多個值域區間重疊（可能產生衝突規則） | 「18-30」與「25-40」並列 |
| `MUTEX_VALUE` | 同欄位被要求同時等於兩個互斥 enum 值 | 「國籍=TW 且 國籍=非TW」 |
| `NO_OUTPUT_ON_HIT` | 偵測到檢核條件但無對應錯誤碼/結果 | 「ID 格式不符」但沒寫回什麼 |
| `UNBOUNDED_CARTESIAN` | 估算規則數 > 上限（沿用既有 estimatedCartesian） | 笛卡爾積爆炸 |

實作直接複用 `ConditionOverlapDetector` 的數值區間交集與集合交集邏輯，套在「解析出的維度值」而非「已生成的 RuleRow」。

### 5.2 可選 LLM spec-lint（數百 token，opt-in）

當符號層無法判斷自由散文的語意矛盾時，提供**一次廉價 LLM 呼叫**——只回「描述有哪些問題」的 JSON 清單，**不生成規則**：

- prompt 走既有 `PromptGuard` 的 `<bu_spec>` sentinel（防注入）。
- 經 `LlmProviderRegistry` 四層 fallback；LLM 不可用 → 僅符號層結果（non-fatal）。
- 輸出 `List<LintFinding>{ code, severity, message, span? }`。
- 成本：輸出限 ≤300 token，遠低於完整生成。

`mode=block` 且 Layer B 有 `severity=ERROR` 命中時短路。

---

## 6. API 契約（additive）

### 6.1 GenerateRequest 新欄位
```java
/** Pre-flight 模式覆寫：off | warn | block（預設取 application.yml） */
private String preflightMode;
/** 是否啟用 Layer B 的 LLM spec-lint（預設 false，純符號就夠） */
private Boolean specLint;
```

### 6.2 GenerateResponse 新欄位（@JsonInclude(NON_NULL)）
```java
private PreflightReport preflight;
```
```java
record PreflightReport(
    boolean blocked,              // true 時 envelope 為 null
    double qualityScore,
    List<LintFinding> findings,   // ERROR / WARNING / INFO
    List<MissingDimension> missing,
    List<String> suggestions,
    int estimatedRuleCount,
    boolean specLintUsed
) {}
record LintFinding(String code, String severity, String message, String span) {}
```

`blocked=true` 時：`envelope/validation/analysis` 皆 null，HTTP **200**，前端據 `preflight` 顯示「請先修正描述」並列 findings + 一鍵插入建議維度（複用 `SuggestionPanel` 既有 `onInsertDimension`）。

### 6.3 獨立端點（前端即時用，不阻擋）
`POST /tools/preflight` —— 回 `PreflightReport`，供 textarea debounce 即時呼叫（取代/擴充現有 `/suggest`），讓使用者**按生成前**就看到紅黃燈。

---

## 7. 不變式

- **A**：`preflightMode=off` 時，`generateFull` 行為與今日**位元級相同**（既有測試全綠）。
- **B**：`blocked=true` ⇒ 全程 0 次 LLM 呼叫（除非 `specLint=true` 跑了 Layer B 的一次廉價 lint）。
- **C**：`/tools/suggest` 既有契約不破（前端不需改即可運作）。
- **D**：所有 pre-flight 失敗皆 non-fatal —— 符號/LLM lint 例外不可阻斷主流程（catch 住，視為「無 findings」放行）。

---

## 8. 測試計畫

- `SpecLintServiceTest`：6 個 lint code 各 happy/edge、區間交集複用正確性、null/空描述。
- `InputSuggestionServiceTest`（擴充）：blocker 判定（無 outputs / 低品質 / 過短）、門檻邊界。
- `RuleServiceTest`（擴充）：`mode=off` 行為不變、`mode=block` 短路 0 LLM 呼叫（WireMock 驗證 LLM endpoint 未被打）、`mode=warn` 照常生成但帶 preflight。
- `ToolsControllerTest`：`/tools/preflight` 端點、blocked 回 200 且 envelope null。

---

## 9. 風險

| # | 風險 | 緩解 |
|---|---|---|
| R1 | 阻擋誤報擋掉合法描述（regex 假陰性） | 預設 `warn` 不硬擋；硬擋為 opt-in；門檻可調 |
| R2 | `DescriptionDimensionParser` 用 `ThreadLocal` 傳維度，pre-flight 在 LLM 前先 parse 可能與生成階段重複/污染 | pre-flight 用獨立 `parse()` 呼叫，不碰 `setCurrentDims`；確認無 ThreadLocal 殘留 |
| R3 | Layer B LLM lint 本身幻覺 | 僅 opt-in；severity=ERROR 才阻擋；符號層為主、LLM 為輔 |
| R4 | 與既有 `/suggest` 前端行為衝突 | `/preflight` additive，`/suggest` 保留；前端漸進切換 |

---

## 10. 落地順序（建議）

1. 修 `InputSuggestionService:149` log bug（**已完成**）。
2. `SpecLintService` 純符號層 + 測試（0 token，價值最高）。
3. `InputSuggestionService` 加 blocker 判定 + 門檻設定。
4. `RuleService.generateFull` Step 0 wire-in + `mode` 短路 + DTO 欄位。
5. `/tools/preflight` 端點 + 前端紅黃燈。
6.（可選）Layer B LLM spec-lint。

*End of design.*
