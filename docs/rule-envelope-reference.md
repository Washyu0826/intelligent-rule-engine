# RuleEnvelope 參考：格式、錯誤碼、Operator、typeRef

> 從舊版 README 搬移而來。

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
