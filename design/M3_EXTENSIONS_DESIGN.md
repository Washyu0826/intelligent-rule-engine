# M3 Design — RuleEnvelope Extensions

**Mission:** M3 — RuleEnvelope Extensions
**Author:** architect-worker (D2 strict mode — no reliance on `docs/v3.14-schema-evolution.md`)
**Date:** 2026-05-15
**Inputs reviewed:**
- `MISSION.md` §2.3, §4.M3, §6 (M3 row), §7
- `PROJECT_PLAN.md` §2.2 (Gap B), §4.1 (extension-by-addition)
- `src/main/java/com/ruleengine/rules/domain/envelope/RuleEnvelope.java` (the type under extension; `@JsonInclude(NON_NULL)`; existing `Condition.valueRef`)
- `src/main/java/com/ruleengine/rules/domain/dto/ToolDtos.java` (`TypeRefs.ALL`, `Operators.ALL`, `ErrorCodes`)
- `src/main/java/com/ruleengine/rules/service/generator/EnvelopeNormalizer.java` (the 8-pass `normalize()`)
- `src/main/java/com/ruleengine/rules/service/validator/DecisionTableValidator.java` and the four `ValidationLayer` impls (`StructureValidator`, `FieldDefinitionValidator`, `RuleSemanticValidator`, `ConsistencyValidator`, plus `ValidationContext`)
- `src/main/java/com/ruleengine/rules/service/analyzer/HyperRectangle.java` (typeRef switch in `toNumericValue` + `isDiscrete`)
- `src/main/java/com/ruleengine/rules/service/generator/EvaluationComputer.java` (`estimateFieldCardinality`)
- `golden-tests/sample-B/expected-envelope-part1.json` (channel = "特約" guard pattern)
- `golden-tests/sample-B/expected-envelope-part2.json` (DATE valueRef + relative-date evidence)
- `design/ARCHITECTURE_OBSERVATIONS.md` §6 risks #1 (`normalizeTypeRef` missing TIMESTAMP/VARIABLE), #2 (`unwrapNestedValue` collision), #3 (existing-test ordering brittleness)

> **Note on sample B fixtures.** The two `expected-envelope-part*.json` files do **not** themselves carry an `extensions{}` block yet — their `comment` fields explicitly defer the global guard (`受理通路` scope) and the field-OR (`anyOf[new, renewal]`) to future stub passes. PROJECT_PLAN.md §2.2 nonetheless lists both constructs as required by the underlying NL spec. This design proceeds against the spec, not against the current fixture state; the constructs below are sized to absorb sample B's `channel ∈ {保代,直效,特約}` guard and the `新契約繳費管道 ∨ 續期繳費管道` field-OR when the fixtures are extended in M4/M5.

---

## 1. Constructs the extensions block must cover

| Construct (PROJECT_PLAN §2.2) | Where it lives | Sub-type or field | Evidence |
|---|---|---|---|
| Global guard (`受理通路為 [行動保險] 或 [網路投保] 或 [直效線上成交]`) | `extensions{}` | `GlobalGuard` | sample B part2 `comment` line "受理通路守門待 F1 scope 完成後上移至 RuleEnvelope.scope" |
| Field-level OR (`[新契約繳費管道] 或 [續期繳費管道] 為 [指定帳戶轉帳]`) | `extensions{}` | `FieldOrSpec` | sample B part2 `comment` line "spec 真正要的是 anyOf[new, renewal]" |
| Cross-field value reference (`不可小於 [被保人生日]`) | `Condition.valueRef` (existing) | — already supported (`RuleEnvelope.java:203`) | sample B part2 R42: `"valueRef": "insuredBirthday"` |
| Relative date expression (`不可超過 [隔日]`) | `Condition.valueRef` (existing) | — already supported | sample B part2 R41: `"valueRef": "$today+1d"` |
| Rule grouping with subgroup headers | `extensions{}` | `RuleGrouping` | PROJECT_PLAN.md §2.2 explicit |
| Footnotes (`註1：在試算上傳中，保代通路不會進行檢核`) | `extensions{}` | `Footnote` | PROJECT_PLAN.md §2.2 explicit |
| (Added) Rule status / lifecycle (`整段程式已移除` in sample B's parent spec; PROJECT_PLAN.md §7 R8) | `extensions{}` | `RuleStatus` | MISSION.md §4.M3 enumerates this sub-type by name |

**Explicit non-redefinition.** `Condition.valueRef` is the canonical home of cross-field references (`"<fieldName>"`) and relative dates (`"$today"`, `"$today+1d"`, `"$today-30d"`, `"$today+1m"`, `"$today+1y"`). The grammar is already pinned by `RuleSemanticValidator.VALUE_REF_RELATIVE_DATE` (`^\\$today(\\s*[+-]\\s*\\d+\\s*[dmyDMY])?$`) and `validateValueRef`. The extensions block **must not** introduce any duplicate value-reference vocabulary. `FieldOrSpec` and `GlobalGuard` instead **reuse** `RuleEnvelope.Condition` so that any condition inside an extension can carry `valueRef` itself — no copy-paste, no parallel grammar.

---

## 2. Top-level `RuleEnvelopeExtensions` type

One new optional field appended to the existing fields of `RuleEnvelope`:

```java
// RuleEnvelope.java — one new field appended after `private AuditMetadata audit;`
/** v3.14: 額外結構（globalGuard / fieldOr / grouping / footnote / ruleStatus），預設 null。 */
private RuleEnvelopeExtensions extensions;
```

The new top-level class lives at `domain/RuleEnvelopeExtensions.java`:

```java
package com.ruleengine.rules.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ruleengine.rules.domain.extensions.*;
import lombok.*;

import java.util.List;

/**
 * RuleEnvelope 的可選擴充區塊（v3.14）。
 *
 * 設計原則：
 *   - 完全可選：null / 缺欄位 / 空集合 都視為「沒有擴充」，行為與 v1.0.0 完全相同。
 *   - 加法式設計：所有子型別都帶 @JsonInclude(NON_NULL)；五個欄位獨立，缺一不影響其他。
 *   - 不重複定義 valueRef：FieldOrSpec / GlobalGuard 內部的條件仍重用 RuleEnvelope.Condition，
 *     跨欄位 / 相對日期參照透過 Condition.valueRef，不再另立詞彙。
 *
 * Jackson 透過 @JsonInclude(NON_NULL) 確保此區塊 null 時不出現在序列化輸出；
 * 與既有 RuleEnvelope.@JsonInclude(NON_NULL) 一致，不破壞向後相容。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleEnvelopeExtensions {

    /** 全表級守門條件（AND 串接於所有 rules 之前）。 */
    private List<GlobalGuard> globalGuards;

    /** 欄位級 OR 群組宣告。 */
    private List<FieldOrSpec> fieldOr;

    /** 規則分組（子分組標題 + 成員 ruleId）。 */
    private List<RuleGrouping> groupings;

    /** 註腳清單。 */
    private List<Footnote> footnotes;

    /** 各規則的狀態（ACTIVE / DRAFT / RETIRED）；list 形式，內含 ruleId。 */
    private List<RuleStatus> ruleStatus;
}
```

### 2.1 Why `List<RuleStatus>` (not `Map<String, RuleStatus>`)

Three reasons:

1. **Jackson symmetry.** Every other extension is a `List<...>`. Mixing in a `Map<String, ...>` would force test fixtures and impl-worker to special-case one shape; round-trip tests would need a custom assertion.
2. **Ordering preservation.** Round-trip fidelity (MISSION.md §6 M3 row) is easier to assert on a `List` than on a JSON object whose key order is not guaranteed by every JVM. The `RuleStatus` carries its `ruleId` inline.
3. **Future extension.** A future `RuleStatus.effectiveFrom` / `effectiveTo` / `reason` fields don't break the shape; a `Map<String, RuleStatus>` would have required nesting.

Validator/normalizer treat the list as a logical map keyed by `ruleId` (duplicate `ruleId` keys → `INVALID_RULE_STATUS_REF` per §6).

---

## 3. Five sub-types

All sub-types live under `domain/extensions/` (new package, mirrors `MISSION.md` §4.M3 wording "`domain/extensions/`"). All are `@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(NON_NULL)`.

### 3.1 GlobalGuard

**Purpose.** A whole-table predicate that gates evaluation: if the input record does not satisfy every `GlobalGuard.condition`, no rule fires.

**Java skeleton.** `domain/extensions/GlobalGuard.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GlobalGuard {
    /** 守門 ID（G01, G02, ...），便於 footnote / status 交叉引用。 */
    private String guardId;

    /** 人類可讀說明（例：「僅適用受理通路 ∈ {行動保險, 網路投保, 直效線上成交}」）。 */
    private String description;

    /**
     * 守門條件本體 — 重用 RuleEnvelope.Condition，因此可直接用既有 operator
     * （含 in / equals / valueRef / 相對日期）。Adapter / engine 不需新詞彙。
     */
    private RuleEnvelope.Condition condition;

    /**
     * 失敗時短路結果（可選，預設「整個 RuleEnvelope 不適用」）。
     * 若提供，executor 在守門失敗時回傳該結果，不再 dispatch rules[]。
     */
    private List<RuleEnvelope.Result> onFailure;
}
```

**Sample JSON (covers sample B's `受理通路` guard).**

```json
{
  "guardId": "G01",
  "description": "僅適用受理通路 ∈ {行動保險, 網路投保, 直效線上成交}",
  "condition": {
    "field": "acceptanceChannel",
    "operator": "in",
    "value": ["行動保險", "網路投保", "直效線上成交"]
  },
  "onFailure": [
    { "field": "errorMessage", "value": "本檢核不適用此受理通路" }
  ]
}
```

**Round-trip guarantee.** `condition` is `RuleEnvelope.Condition` (already round-trips today). `onFailure` is `List<Result>` (already round-trips today). All four fields carry `@JsonInclude(NON_NULL)`, so e.g. an envelope with `onFailure` omitted serialises without that key.

### 3.2 FieldOrSpec

**Purpose.** Declares that a set of input fields behaves as a logical OR when evaluated together — used to express `[新契約繳費管道] 或 [續期繳費管道] 為 [指定帳戶轉帳]` without contorting the standard `Condition` row.

**Java skeleton.** `domain/extensions/FieldOrSpec.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldOrSpec {
    /** OR 群組 ID（FOR01, FOR02, ...）。 */
    private String orId;

    /** 參與 OR 的 input 欄位名稱（≥2 個）。 */
    private List<String> fields;

    /**
     * OR 群組所要比對的判定 — 同一個 operator 套用到 fields 中任一欄位即可命中。
     * 重用 RuleEnvelope.Condition，但 field 欄位於此忽略（fields 清單已涵蓋）。
     *
     * 範例：operator="equals", value="指定帳戶轉帳" 表示
     *   [newContractPaymentMethod 為 "指定帳戶轉帳"] OR [renewalPaymentMethod 為 "指定帳戶轉帳"]
     */
    private RuleEnvelope.Condition predicate;

    /**
     * 該 OR 在哪幾個 ruleId 中啟用；若為 null 或空，表示對整張表生效。
     */
    private List<String> appliesToRuleIds;
}
```

**Sample JSON (sample B's payment-channel OR).**

```json
{
  "orId": "FOR01",
  "fields": ["newContractPaymentMethod", "renewalPaymentMethod"],
  "predicate": {
    "operator": "equals",
    "value": "指定帳戶轉帳"
  },
  "appliesToRuleIds": ["R31", "R32"]
}
```

**Round-trip guarantee.** `predicate` reuses `RuleEnvelope.Condition`. `fields` and `appliesToRuleIds` are `List<String>`. All `@JsonInclude(NON_NULL)`.

**Risk #2 mitigation (M1 risk register).** The field is named `predicate` (not `value`) precisely so that `unwrapNestedValue` — which keys on `{"value": ...}`, `{"enum": ...}`, single-key maps — does not match this structural payload. The normalizer's extension pass (see §5) does **not** invoke `unwrapNestedValue` on `FieldOrSpec` directly; only on `predicate.value` after deserialisation, where the existing condition normalisation logic is appropriate.

### 3.3 RuleGrouping

**Purpose.** Adds subgroup headers around runs of rule rows, mirroring how BAs read printed underwriting spec sheets (`§2.1 / §2.2 / §2.3` style).

**Java skeleton.** `domain/extensions/RuleGrouping.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleGrouping {
    /** 分組 ID（G01, G02, ...，與 GlobalGuard.guardId 分開命名空間：分組用 RG 前綴 → RG01）。 */
    private String groupId;

    /** 分組標題（例：「2.3 特約通路檢核」）。 */
    private String title;

    /** 分組說明（可選）。 */
    private String description;

    /**
     * 所屬規則 ID 清單；順序維持，使下游 renderer 可印出
     * 「分組標題 → 該分組內的 rule 列表」這種人類友善版面。
     */
    private List<String> memberRuleIds;

    /** 可選：分組層級（1 = 一級標題、2 = 子標題……），預設 1。 */
    private Integer level;
}
```

**Sample JSON.**

```json
{
  "groupId": "RG03",
  "title": "2.3 特約通路檢核",
  "description": "授權書編號不可等於受理編號（針對特約通路）",
  "memberRuleIds": ["R23"],
  "level": 1
}
```

**Round-trip guarantee.** All scalar `String` / `Integer` / `List<String>`; trivially round-trippable.

### 3.4 Footnote

**Purpose.** Attaches an explanatory note either to a specific `ruleId` (per-rule footnote) or to the whole envelope (table-level footnote). Mirrors the underwriting-spec convention `註1：...`.

**Java skeleton.** `domain/extensions/Footnote.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Footnote {
    /** 註腳編號標籤（例：「註1」、「†」、「*」）。 */
    private String marker;

    /** 註腳內文（必填，非空白）。 */
    private String text;

    /**
     * 註腳掛載對象：
     *   - 指定 ruleId 字串：該規則的腳註
     *   - null / 空字串：整張表的腳註（出現在表尾）
     */
    private String appliesToRuleId;

    /**
     * 註腳是否會改變規則套用性（true = applicability note，
     * 例「在試算上傳中，保代通路不會進行檢核」會關閉某些 rule）。
     * 預設 false（純解說）。
     */
    private Boolean altersApplicability;
}
```

**Sample JSON.**

```json
{
  "marker": "註1",
  "text": "在試算上傳中，保代通路不會進行檢核",
  "appliesToRuleId": null,
  "altersApplicability": true
}
```

**Round-trip guarantee.** Pure scalar fields; trivially round-trippable.

### 3.5 RuleStatus

**Purpose.** Tracks lifecycle of an individual rule (`ACTIVE` / `DRAFT` / `RETIRED`), preserving retired rules for audit while letting the executor skip them — per PROJECT_PLAN.md §7 R8 (sample B's parent spec carries `整段程式已移除`).

**Java skeleton.** `domain/extensions/RuleStatus.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleStatus {
    /** 規則 ID（必填，必須對應 rule.rules[].ruleId）。 */
    private String ruleId;

    /** 狀態：ACTIVE | DRAFT | RETIRED；缺值預設 ACTIVE。 */
    private Lifecycle status;

    /** 狀態變更日期（ISO-8601 yyyy-MM-dd，可選）。 */
    private String since;

    /** 狀態變更原因（例：「業務需求廢止」），可選。 */
    private String reason;

    public enum Lifecycle {
        ACTIVE,   // 正式生效
        DRAFT,    // 草稿，executor 不執行
        RETIRED   // 退役，executor 不執行，但保留於 envelope 供稽核
    }
}
```

**Sample JSON.**

```json
{
  "ruleId": "R23",
  "status": "RETIRED",
  "since": "2026-04-01",
  "reason": "業務需求廢止；保留供稽核"
}
```

**Round-trip guarantee.** `Lifecycle` is a Java enum — Jackson default serialisation is the enum constant name (`"ACTIVE"` / `"DRAFT"` / `"RETIRED"`). No custom (de)serializer needed.

---

## 4. New `typeRef` values

### 4.1 `TypeRefs.ALL` after change

`ToolDtos.java`:

```java
public static final class TypeRefs {
    public static final String INTEGER   = "INTEGER";
    public static final String DECIMAL   = "DECIMAL";
    public static final String BOOLEAN   = "BOOLEAN";
    public static final String STRING    = "STRING";
    public static final String ENUM      = "ENUM";
    public static final String DATE      = "DATE";
    // v3.14: appended at end to preserve ordinal stability for any code that
    // iterates ALL in declared order (see MISSION.md §4.M3 "at the end of the enum").
    public static final String TIMESTAMP = "TIMESTAMP";
    public static final String VARIABLE  = "VARIABLE";
    public static final java.util.Set<String> ALL = java.util.Set.of(
            INTEGER, DECIMAL, BOOLEAN, STRING, ENUM, DATE,
            TIMESTAMP, VARIABLE
    );
    private TypeRefs() {}
}
```

### 4.2 Related enum

Search for an enum hosting these values: there is **no `enum`** in the codebase that mirrors `TypeRefs` constants — they live only as `String` constants in `ToolDtos.TypeRefs`. Confirmed by inspection of `FieldDef.typeRef` (typed as `String`, not as an enum) and by the comment block above `TypeRefs.ALL` ("6 個 typeRef"). The two new values therefore only need to be appended to the `TypeRefs.ALL` constant; no enum file requires modification.

### 4.3 xlsx ↔ RuleEnvelope typeRef mapping (M5 reference)

The canonical Group xlsx field-spec format (`格式定義_sample.xlsx`, sheet 「使用說明」) enumerates eight `資料型態` (dataType) values. Seven map directly to RuleEnvelope `typeRef` names; two carry a naming mismatch that M5's xlsx adapter must reconcile. This table is informational for M5 — M3 does not implement any of the mapping logic itself.

| xlsx dataType (sheet 「使用說明」) | RuleEnvelope typeRef | Notes |
|---|---|---|
| 1. `string` | `STRING` | direct |
| 2. `bigdecimal` | `DECIMAL` | **name mismatch** — adapter handles |
| 3. `boolean` | `BOOLEAN` | direct |
| 4. `date` | `DATE` | direct |
| 5. `timestamp` | `TIMESTAMP` | M3 NEW — added at end of `TypeRefs.ALL` |
| 6. `integer` | `INTEGER` | direct |
| 7. `code` | `ENUM` | **name mismatch** — adapter handles; xlsx 代碼表 = `List<map>` JSON |
| 8. `variable` | `VARIABLE` | M3 NEW — output-only (see new error code `INVALID_VARIABLE_IN_CONDITION` §6) |

**Field-name convention mismatch.** The xlsx spec uses SNAKE_CASE for `欄位名稱` (e.g. `PROD_CAT`, `WITHIN_TOT_AMT`, `MAX_AMT_ALL`), whereas existing RuleEnvelope fixtures use camelCase for `FieldDef.name` (e.g. `acceptanceChannel`, `newContractPaymentMethod`). The translation between these two conventions is M5's adapter problem to solve; M3 only records the contract here so M5 does not have to re-derive the table from the xlsx spec. M3 itself emits no SNAKE_CASE identifiers and validates only against the camelCase RuleEnvelope contract.

### 4.4 `EnvelopeNormalizer.normalizeTypeRef()` alias map

```java
return switch (upper) {
    case "INT", "LONG", "NUMBER" -> "INTEGER";
    case "DOUBLE", "FLOAT", "DEC", "BIGDECIMAL" -> "DECIMAL";
    case "BOOL" -> "BOOLEAN";
    case "STR", "TEXT", "VARCHAR" -> "STRING";
    case "ENUMERATION" -> "ENUM";
    // v3.14: DATETIME / LOCALDATE no longer collapse to DATE — they belong to TIMESTAMP
    case "LOCALDATE" -> "DATE";
    case "DATETIME", "LOCALDATETIME", "INSTANT", "TS", "ZONEDDATETIME" -> "TIMESTAMP";
    case "VAR", "VARIABLE_REF", "RUNTIME_VAR", "DERIVED" -> "VARIABLE";
    default -> upper;
};
```

**Behavioural note on `LOCALDATE` / `DATETIME`.** Today's normalizer rewrites both to `DATE` (`EnvelopeNormalizer.java:138`). The change above splits them: `LOCALDATE` stays → `DATE`, but `DATETIME` (which has time-of-day semantics) now maps to `TIMESTAMP`. This is intentional and addresses risk #1 of the M1 register. Test-worker must add a regression test asserting that a `DATETIME` typeRef no longer silently degrades to `DATE`.

### 4.5 Downstream switch sites — handling assessment

The following files contain `switch`/`if` chains on `typeRef`:

| File | Site | Current behaviour on unknown typeRef | M3 disposition |
|---|---|---|---|
| `HyperRectangle.toNumericValue` (~line 410) | switch on `TypeRefs.{INTEGER,DECIMAL,BOOLEAN,ENUM,STRING,DATE}` with `default → value.asDouble()` | TIMESTAMP/VARIABLE fall through `default`, returning `0.0` for non-numeric values | **Acceptable for M3.** Fields typed `TIMESTAMP`/`VARIABLE` are not expected to participate in geometric coverage analysis (DMN gap detection is meaningful for INTEGER/DECIMAL/ENUM/DATE; TIMESTAMP is a value-only point, VARIABLE is opaque). M3 makes no change here. M4/M5 may add a TIMESTAMP branch if the Group JSON tree exposes timestamp range conditions; flagged as deferred. |
| `HyperRectangle.isDiscrete` (line 603) | true iff `INTEGER`/`BOOLEAN`/`ENUM`/`STRING` | TIMESTAMP/VARIABLE → false (continuous) | **Acceptable.** TIMESTAMP is conceptually continuous; VARIABLE never reaches this path because EvaluationComputer guards it (see below). |
| `EvaluationComputer.estimateFieldCardinality` (line 123) | hard-codes `BOOLEAN→2`, `ENUM`, then if/else for `INTEGER`/`DECIMAL`/`DATE`, else string-distinct fallback | TIMESTAMP/VARIABLE hit the string-distinct fallback, returning ≥1 | **Acceptable for M3.** This produces a conservative (high-coverage) cardinality estimate; no false-positive coverage warnings. Deferred refinement noted. |
| `RuleSemanticValidator.NUMERIC_DATE_TYPES` (line 42) | set literal `{INTEGER, DECIMAL, DATE}` | TIMESTAMP is **excluded** — so `greaterThan` / `between` on a TIMESTAMP field will emit `TYPE_MISMATCH` | **M3 must update.** Add `TIMESTAMP` to `NUMERIC_DATE_TYPES`. Otherwise the new typeRef is functionally read-only. |
| `RuleSemanticValidator.IN_TYPES` (line 46) | `{STRING, ENUM}` | TIMESTAMP/VARIABLE → `in` operator rejected | **Acceptable.** `in` over a timestamp/variable is not a documented use case. |
| `RuleSemanticValidator.validateSingleValueType` (line 314) | switch on STRING/INTEGER/DECIMAL/BOOLEAN/DATE | TIMESTAMP/VARIABLE pass through (no type check, accept any value) | **Add minimal validation.** For TIMESTAMP: if `value.isTextual()`, attempt `Instant.parse` or ISO-8601 datetime pattern; on failure emit `TYPE_MISMATCH`. For VARIABLE: accept any value (it's opaque by definition); validator only checks that exactly one of `value` / `valueRef` is provided. |
| `RuleSemanticValidator.validateValueRef` (line 220) | `$today` family only allowed for `DATE` | TIMESTAMP also wants relative-date support | **M3 may extend** to allow `$today` family for both `DATE` and `TIMESTAMP`. Conservative alternative: defer to a future mission and keep `TIMESTAMP` non-relative for now. **Recommendation:** allow for both, since the cost is one symbol added to a Set. |
| `FieldDefinitionValidator.parseFieldDefs` line 91 | `TypeRefs.ALL.contains(typeRef)` | Once `TypeRefs.ALL` is extended, TIMESTAMP/VARIABLE pass the check | **No additional change.** |
| `EnvelopeNormalizer.repairNumericValue` line 462 | only INTEGER/DECIMAL | TIMESTAMP/VARIABLE skipped | **No change.** TIMESTAMP comes in as ISO-8601 string; VARIABLE is opaque. |

**Summary.** Two M3-mandatory downstream updates inside `RuleSemanticValidator`: (a) add `TIMESTAMP` to `NUMERIC_DATE_TYPES`, (b) add TIMESTAMP type validation in `validateSingleValueType` and allow `$today` for TIMESTAMP. All other call sites either handle the new types gracefully (graceful fallback) or are intentionally deferred to a later mission and noted as such — no analyzer / optimizer / generator file other than `RuleSemanticValidator` needs editing in M3.

---

## 5. `EnvelopeNormalizer` updates

### 5.1 New pass: `normalizeExtensions(envelope)`

Inserted **after `sortByPriority`** as the **9th and final pass** in `normalize()`:

```java
public void normalize(RuleEnvelope envelope, String schemaVersion, String promptVersion) {
    // ... (existing 8 passes unchanged) ...

    normalizeHitPolicy(rule);
    ensureMutableLists(rule);
    normalizeTypeRefs(rule);
    autoInferEnumAllowedValues(rule);
    normalizeRuleIds(rule);
    normalizeOperatorsAndValues(rule);
    deduplicateRules(rule);
    sortByPriority(rule);

    // v3.14: extensions normalisation. Must run AFTER sortByPriority because
    // ruleStatus / footnotes / grouping reference ruleIds, which are stable
    // only after sortByPriority has settled. Null-safe: zero work if no extensions.
    normalizeExtensions(envelope);
}
```

**Position justification.** Three earlier-pass alternatives were considered and rejected:

- **Before `normalizeRuleIds`.** `ruleStatus[].ruleId` and `grouping.memberRuleIds[]` reference generated rule IDs. If normalisation ran first, the LLM's `"R3"` could be remapped while `RuleStatus` still pointed at the old `ruleId`. After `normalizeRuleIds` is the earliest correct position.
- **Between `normalizeOperatorsAndValues` and `deduplicateRules`.** No advantage: deduplication never touches extension structures, so order is independent. Running after `deduplicateRules` is preferable because if dedup removes a rule, `ruleStatus` and `grouping` membership can be pruned in one pass.
- **Before `sortByPriority`.** Same problem as `normalizeRuleIds` (ordering moves around). After `sortByPriority` the rule list is final.

### 5.2 Null safety

```java
private void normalizeExtensions(RuleEnvelope envelope) {
    if (envelope.getExtensions() == null) return;   // zero allocation, zero log
    RuleEnvelopeExtensions ext = envelope.getExtensions();
    // ... pass body below ...
}
```

A `RuleEnvelope` with `extensions == null` exits the method on line 1. No logger is created, no list is allocated. This satisfies design constraint #5 and resolves M1 risk #3 (existing tests assert no warning is appended to `envelope.reason` when extensions are absent).

### 5.3 Behaviour

For each non-null sub-list, the pass performs the following idempotent normalisations:

1. **`globalGuards`:** deduplicate by `(guardId)` then by exact `condition` equality (`condition.equals(other.condition)` after Lombok-generated `@Data` equals). Trim `description`. Auto-assign `guardId = "G01..Gnn"` for entries with null/blank id.
2. **`fieldOr`:** ensure `fields.size() >= 2` (flag in WORK_LOG and remove the malformed entry — validator will not see this case). Auto-assign `orId = "FOR01..FORnn"`. Normalise `predicate.operator` via the existing `normalizeOperator` helper (reuses the proven `eq → equals` mapping). Do **not** invoke `unwrapNestedValue` on the `FieldOrSpec` object itself.
3. **`groupings`:** auto-assign `groupId = "RG01..RGnn"`. Trim `title`. Drop any `memberRuleIds` entry that does not exist in `rule.rules[i].ruleId` and log a `INFO`-level message (validator emits the error if needed).
4. **`footnotes`:** trim `text`; drop entries with blank `text` (validator emits `INVALID_FOOTNOTE_REF` separately if `appliesToRuleId` is dangling).
5. **`ruleStatus`:** coerce missing `status` to `RuleStatus.Lifecycle.ACTIVE` (per design constraint #6). Log `WARN` on duplicate `ruleId` entries (keep first, drop rest); validator emits `INVALID_RULE_STATUS_REF` for dangling IDs.
6. **Empty-list cleanup:** if any of the five lists is non-null but empty after normalisation, set it back to `null` so that Jackson's `@JsonInclude(NON_NULL)` strips it from output. This preserves "absent ≡ no-op" semantics on round-trip.

### 5.4 Risk #2 mitigation (`unwrapNestedValue` collision with `fieldOr`)

The M1 risk register flagged that `unwrapNestedValue` walks `{"value": X}` single-key maps and could flatten a `FieldOrSpec` payload of shape `{"predicate": {...}}`. Two layers of defence:

1. **Field naming.** The OR's payload field is named `predicate`, not `value`, ruling out the `{"value": X}` branch.
2. **Scoping.** `unwrapNestedValue` is invoked **only** from `normalizeOperatorsAndValues` → `Condition.value` / `Result.value`. The new `normalizeExtensions` pass invokes `normalizeOperator(predicate.operator)` but does **not** call `unwrapNestedValue` on the `FieldOrSpec` object or on its sub-fields directly. If we want to normalise `predicate.value` (the rhs of the OR's equality check), we invoke `unwrapNestedValue` only on that scalar, after deserialisation.

A unit test `EnvelopeNormalizerExtensionsTest.fieldOrPredicateDoesNotCollapse()` asserts that a `FieldOrSpec` with `predicate.value = { typeRef: STRING, allowedValues: ["指定帳戶轉帳"] }` round-trips with `predicate.value = "指定帳戶轉帳"` (the existing `allowedValues` unwrap rule applies inside `predicate.value` but not at the `FieldOrSpec` level).

---

## 6. Validator updates

### 6.1 New error codes in `ToolDtos.ErrorCodes`

Six new codes, appended to the existing 11:

```java
public static final class ErrorCodes {
    public static final String MISSING_FIELD                  = "MISSING_FIELD";
    public static final String UNKNOWN_OPERATOR               = "UNKNOWN_OPERATOR";
    public static final String TYPE_MISMATCH                  = "TYPE_MISMATCH";
    public static final String UNKNOWN_FIELD                  = "UNKNOWN_FIELD";
    public static final String DUPLICATE_ID                   = "DUPLICATE_ID";
    public static final String ENUM_VALUE_MISSING             = "ENUM_VALUE_MISSING";
    public static final String INVALID_ENUM_VALUE             = "INVALID_ENUM_VALUE";
    public static final String INCONSISTENT_TABLE             = "INCONSISTENT_TABLE";
    public static final String INVALID_MULTI                  = "INVALID_MULTI";
    public static final String MISSING_BRANCH                 = "MISSING_BRANCH";
    public static final String MISSING_RESULTS                = "MISSING_RESULTS";
    // v3.14 — extensions
    public static final String INVALID_GLOBAL_GUARD           = "INVALID_GLOBAL_GUARD";
    public static final String INVALID_FIELD_OR               = "INVALID_FIELD_OR";
    public static final String INVALID_RULE_STATUS_REF        = "INVALID_RULE_STATUS_REF";
    public static final String INVALID_FOOTNOTE_REF           = "INVALID_FOOTNOTE_REF";
    public static final String MALFORMED_GROUPING             = "MALFORMED_GROUPING";
    // v3.14 — xlsx schema rule: VARIABLE typeRef is output-only (sheet 「使用說明」: 區域變數 條件欄位不開放)
    public static final String INVALID_VARIABLE_IN_CONDITION  = "INVALID_VARIABLE_IN_CONDITION";
    private ErrorCodes() {}
}
```

**xlsx-derived rule.** The Group xlsx field-spec format (`格式定義_sample.xlsx`, sheet 「使用說明」) declares `8.variable - 區域變數 (條件欄位不開放)`, meaning the `VARIABLE` typeRef is reserved for `columnType=1` (result/output) rows and must never appear on `columnType=0` (condition/input) rows. Within the RuleEnvelope contract this translates to: any `FieldDef` in `rule.inputs[]` whose `typeRef == "VARIABLE"` is invalid. Placing this check at the RuleEnvelope validator layer (rather than only inside the M5 xlsx adapter) means any envelope — regardless of origin — is rejected if it violates the rule, which matches MISSION.md §4.M5's acceptance hint that adapters "must reject if found on a `columnType=0` row".

### 6.2 Assignment to ValidationLayer

A **new** `ValidationLayer` implementation is added: `ExtensionsValidator` at `service/validator/ExtensionsValidator.java`, ordered `@Order(5)`. Rationale: the M1 design observed (§4) that "M3's new error codes ... must live either inside an existing layer ... or as a new `@Order(5)` layer; the registry pattern allows the latter without touching `DecisionTableValidator`." The `@Order(5)` route is chosen because:

- Existing layers already have tight cohesion (Structure / FieldDef / RuleSemantic / Consistency). Inflating Layer 3 with extension validation diffuses responsibility.
- The new layer can short-circuit independently: if `envelope.extensions == null`, it returns an empty list in one line — zero risk to the existing 11-error-code flow.
- Backward-compat: existing tests that count error codes per layer (none currently exist, but defensive) keep their per-layer counts unchanged.
- Layer 5 runs **after** Layer 4 (`ConsistencyValidator`) so the parsed rule IDs and field types are already in `ValidationContext` — Layer 5 just reads them.

| Code | Layer | What it checks | Why this layer |
|---|---|---|---|
| `INVALID_GLOBAL_GUARD` | 5 (`ExtensionsValidator`) | `globalGuard.condition.field` references an input not in `context.inputTypes`; or operator unsupported; or `onFailure[].field` references an output not in `context.outputTypes` | Needs parsed inputs/outputs — only available after Layer 2. |
| `INVALID_FIELD_OR` | 5 | `fields.size() < 2`; any of `fields[i]` not in `context.inputTypes`; `appliesToRuleIds` refers to ruleIds not in `context.parsedRules` | Needs both inputs (Layer 2) and parsed rule IDs (Layer 3). |
| `INVALID_RULE_STATUS_REF` | 5 | `ruleStatus[i].ruleId` not in `context.parsedRules`, or duplicate `ruleId`s, or `status` value not in enum | Needs parsed rule IDs (Layer 3). |
| `INVALID_FOOTNOTE_REF` | 5 | `footnote.appliesToRuleId` is non-blank and not in `context.parsedRules`; or `text` is blank | Needs parsed rule IDs (Layer 3). |
| `MALFORMED_GROUPING` | 5 | `memberRuleIds` empty; any `memberRuleIds[i]` not in `context.parsedRules`; duplicate `groupId`; `level <= 0` | Needs parsed rule IDs (Layer 3). |
| `INVALID_VARIABLE_IN_CONDITION` | 3 (`RuleSemanticValidator`) | any `FieldDef` in `rule.inputs[]` (NOT `rule.outputs[]`) whose `typeRef == "VARIABLE"`; message format: `"input field '<name>' uses typeRef=VARIABLE which is reserved for output fields (Group xlsx 區域變數)"` | The check concerns core `inputs/outputs` typeRef semantics, NOT the `extensions{}` block — Layer 3 (`RuleSemanticValidator`) already iterates `inputs[]` and validates type contracts there. Placing it in `ExtensionsValidator` (Layer 5) would mis-route a core-contract check; placing it in `FieldDefinitionValidator` (Layer 2) is also tempting but Layer 2 validates each FieldDef in isolation without knowing whether the field lives in `inputs[]` or `outputs[]`. Layer 3 has both signals. |

The new layer carries no Spring wiring beyond `@Component @Order(5)` and is auto-collected by `DecisionTableValidator`'s constructor (which already takes `List<ValidationLayer>`).

### 6.3 Test-worker hooks

Each of the five extensions error codes gets a positive (well-formed → no error) and negative (malformed → error emitted with correct code) unit test, named `ExtensionsValidatorTest.<errorCode>_positive()` / `_negative()` — ten tests. The sixth code (`INVALID_VARIABLE_IN_CONDITION`, owned by `RuleSemanticValidator`) gets its own positive/negative pair in `RuleSemanticValidatorVariableTypeTest.variableInOutputs_positive()` / `.variableInInputs_negative()` — two additional tests. **Twelve tests total.**

---

## 7. Files manifest for impl-worker

| File | Action | LOC estimate |
|---|---|---|
| `domain/RuleEnvelopeExtensions.java` | NEW | ~50 |
| `domain/extensions/GlobalGuard.java` | NEW | ~30 |
| `domain/extensions/FieldOrSpec.java` | NEW | ~35 |
| `domain/extensions/RuleGrouping.java` | NEW | ~30 |
| `domain/extensions/Footnote.java` | NEW | ~30 |
| `domain/extensions/RuleStatus.java` | NEW | ~35 |
| `domain/envelope/RuleEnvelope.java` | MODIFY (one new field + import) | +5 |
| `domain/dto/ToolDtos.java` | MODIFY (`TypeRefs.ALL` +2 values; `ErrorCodes` +5 codes) | +15 |
| `service/generator/EnvelopeNormalizer.java` | MODIFY (one new pass + alias map update + call site) | +60 |
| `service/validator/RuleSemanticValidator.java` | MODIFY (`NUMERIC_DATE_TYPES` + TIMESTAMP value type + valueRef extension) | +20 |
| `service/validator/ExtensionsValidator.java` | NEW (`@Order(5)`) | ~150 |

**Totals.** 7 new files (~360 LOC), 4 modified files (~100 LOC delta).

---

## 8. §2.3 modification justifications

Per MISSION.md §2.3, files under `domain/`, `validator/`, `analyzer/`, `service/RuleService.java`, or `service/RuleTypeRegistry.java` require written justification. The four touched restricted files are below — impl-worker copies these paragraphs verbatim into `WORK_LOG_impl.md` when reporting the M3 implementation.

### 8.1 `domain/envelope/RuleEnvelope.java`

**What's changed.** One new optional field `private RuleEnvelopeExtensions extensions;` appended after `private AuditMetadata audit;`. One new import `com.ruleengine.rules.domain.RuleEnvelopeExtensions`. No existing field renamed, retyped, reordered, or removed. **Why no less invasive alternative.** Adding the new constructs as a sibling DTO (e.g. carrying both `RuleEnvelope` and `Extensions` separately at the controller layer) would force every adapter, validator, normalizer, and dashboard caller to thread a second parameter through; the existing 9-step `RuleService.generateFull` orchestration is built around a single envelope reference. A nested optional field is the only mechanism that preserves `RuleEnvelope` as the sole circulating value while satisfying `MISSION.md` §4.M3's explicit instruction to add `extensions` to `RuleEnvelope`. **Backward compatibility risk.** Negligible. Jackson with `@JsonInclude(NON_NULL)` (already on `RuleEnvelope`) ensures the new field disappears from JSON when null; all existing fixtures deserialise unchanged (the new field stays null); all existing serialisation tests pass byte-equivalence checks. **Mitigation.** Test-worker adds `RuleEnvelopeBackwardCompatTest.legacyEnvelopeRoundTripsByteForByte()` that loads each of the 12 built-in samples, deserialises, re-serialises, and asserts byte-equivalence.

### 8.2 `domain/dto/ToolDtos.java`

**What's changed.** `TypeRefs.ALL` extended with `TIMESTAMP` and `VARIABLE` appended at the end of the `Set.of(...)` literal; two new constants `TIMESTAMP` / `VARIABLE` declared. `ErrorCodes` extended with six new constants (`INVALID_GLOBAL_GUARD`, `INVALID_FIELD_OR`, `INVALID_RULE_STATUS_REF`, `INVALID_FOOTNOTE_REF`, `MALFORMED_GROUPING`, `INVALID_VARIABLE_IN_CONDITION`). No existing constants renamed or removed; comment headers updated from "6 個 typeRef" → "8 個 typeRef" and "11 個錯誤碼" → "17 個錯誤碼". **Why no less invasive alternative.** New `typeRef` enum values must live in the canonical `TypeRefs.ALL` Set or `FieldDefinitionValidator.parseFieldDefs` line 91 (`TypeRefs.ALL.contains(typeRef)`) will reject envelopes that declare them — the rejection cannot be skirted because the validator is fed straight from `JsonNode` and trusts only this Set. New error codes must live in `ErrorCodes` because every layer's `err(...)` helper reads codes from this class. **Backward compatibility risk.** Set membership grows from 6 to 8; legacy envelopes never declare TIMESTAMP / VARIABLE, so the legacy code paths (which check `INTEGER`, `DECIMAL`, etc. explicitly) are untouched. **Mitigation.** `TypeRefsTest.legacyTypeRefsStillContained()` asserts the original six constants remain in `ALL`; `ErrorCodesTest.legacyErrorCodesUnchanged()` asserts the original 11 constants remain.

### 8.3 `service/generator/EnvelopeNormalizer.java`

**What's changed.** (a) `normalizeTypeRef()` switch extended with five new alias rows (`DATETIME / LOCALDATETIME / INSTANT / TS / ZONEDDATETIME → TIMESTAMP`; `VAR / VARIABLE_REF / RUNTIME_VAR / DERIVED → VARIABLE`). One behavioural change: `DATETIME` no longer collapses to `DATE`, instead becoming `TIMESTAMP`. (b) A new 9th pass `normalizeExtensions(envelope)` invoked at the end of `normalize()`. (c) A new private method `normalizeExtensions(RuleEnvelope)` (~50 LOC) executing the behaviour in §5.3. **Why no less invasive alternative.** A 9th pass is the only correct location for extension normalisation because the steps depend on `normalizeRuleIds` (rule IDs must be settled before `ruleStatus` validation) and `deduplicateRules` (so `ruleStatus`/`grouping` membership tracks the final rule list). Refusing to normalise extensions inside `EnvelopeNormalizer` would leak responsibility into validators, breaking the established "normalize then validate" pipeline. The `DATETIME → TIMESTAMP` change is required to honour the M1 risk register's item #1 — leaving `DATETIME → DATE` would silently strip time-of-day from LLM output and produce subtly wrong rules. **Backward compatibility risk.** (1) Envelopes that previously relied on `DATETIME → DATE` will now end up with `TIMESTAMP`-typed fields. The existing test suite contains no fixture that uses `DATETIME` (verified by grep of `golden-tests/` and `src/test/`), so risk is zero in practice; if a fixture is later discovered, it can be migrated to `TIMESTAMP` explicitly. (2) The new 9th pass is null-safe: envelopes without `extensions` execute the method's single null-check and return. No log line, no mutation. **Mitigation.** `EnvelopeNormalizerExtensionsTest.legacyEnvelopeNotMutated()` asserts that a legacy envelope produces an identical object after normalisation (`equals()` on the Lombok-generated `@Data`). `EnvelopeNormalizerTypeRefTest.datetimeMapsToTimestampNotDate()` asserts the behavioural change.

### 8.4 `service/validator/RuleSemanticValidator.java`

**What's changed.** (a) `NUMERIC_DATE_TYPES` set literal extended to include `TypeRefs.TIMESTAMP`. (b) `validateSingleValueType` switch extended with a `case TypeRefs.TIMESTAMP` branch (textual `Instant.parse`-style validation; `TYPE_MISMATCH` on parse failure) and a `case TypeRefs.VARIABLE` branch (no-op — value is opaque). (c) `validateValueRef` extended so the `$today` family is accepted for both `DATE` and `TIMESTAMP` (`refType` allow-list grows from `{DATE}` to `{DATE, TIMESTAMP}`). (d) A new check in the `inputs[]` iteration of `RuleSemanticValidator.validate`: any input `FieldDef` whose `typeRef == "VARIABLE"` emits `INVALID_VARIABLE_IN_CONDITION` with message `"input field '<name>' uses typeRef=VARIABLE which is reserved for output fields (Group xlsx 區域變數)"`. **Why no less invasive alternative.** Without (a), all numeric operators (`greaterThan` / `between`) on a TIMESTAMP field emit `TYPE_MISMATCH`, making the new typeRef effectively useless. Without (b), the validator silently accepts any garbage in a TIMESTAMP field's `value`, defeating the type contract. Without (c), `$today+1d` cannot be applied to a TIMESTAMP — but timestamp-typed deadlines (e.g. policy submission cut-off) are the canonical use case. Without (d), the xlsx-canonical rule `8.variable - 區域變數 (條件欄位不開放)` (sheet 「使用說明」) is enforced only inside the M5 adapter, leaving non-xlsx envelopes (handwritten JSON, LLM-emitted JSON, sample-A/B fixtures) free to put `VARIABLE` on condition rows undetected; centring the check in Layer 3 makes the validator authoritative for any envelope source. **Backward compatibility risk.** Legacy envelopes never carry TIMESTAMP or VARIABLE typeRefs, so the new switch branches and the new (d) check are unreachable for them. Existing tests rely on string-substring assertions on `TYPE_MISMATCH` messages; the new branches and (d)'s new `INVALID_VARIABLE_IN_CONDITION` code emit new messages (not modify existing ones), so no existing assertion is affected. **Mitigation.** `RuleSemanticValidatorExtensionTest.timestampGreaterThanValidates()` and `.variableValueAcceptsAnyShape()` cover (a)–(c); `RuleSemanticValidatorVariableTypeTest.variableInInputs_negative()` / `.variableInOutputs_positive()` cover (d); `RuleSemanticValidatorLegacyTest.legacyDecimalGreaterThanStillValidates()` (new) guards against regression on the unchanged paths.

### 8.5 `service/validator/ExtensionsValidator.java` (new file)

**What's changed.** New file, new `@Component @Order(5)` Spring bean, auto-collected into `DecisionTableValidator.validationLayers`. Reads from `ValidationContext.inputTypes`, `outputTypes`, `parsedRules`; emits up to five new error codes per §6. **Why no less invasive alternative.** Documented in §6.2 — splitting into a fifth layer instead of inflating Layer 3 keeps each layer cohesive, supports independent null-short-circuit for envelopes without extensions, and avoids any change to `DecisionTableValidator` itself. **Backward compatibility risk.** None: when `envelope.extensions == null` the layer's first line returns an empty list. The constructor-collected `List<ValidationLayer>` grows from 4 to 5 entries, but `DecisionTableValidator.validate()` already iterates the list size-agnostically. **Mitigation.** `DecisionTableValidatorLayerCountTest.fiveLayersDiscovered()` asserts the new count; `ExtensionsValidatorTest.nullExtensionsZeroErrors()` asserts the null-safe short-circuit.

---

## 9. Acceptance trace (MISSION.md §6 M3 row)

MISSION.md §6 M3 row reads:
> `extensions{}` round-trips through Jackson without data loss; envelopes without an extensions block deserialise and validate exactly as before; the entire existing test suite remains green; new error codes are present and meaningful

MISSION.md §4.M3 acceptance reads:
> old envelopes (any with no `extensions` block) deserialise and validate exactly as before; new envelopes with each construct serialise, deserialise, and validate without loss; field-OR conditions are recognised in sample B; global guards parse from sample B; the existing test suite remains green

| §4.M3 / §6 criterion | Test-worker output | Fixture |
|---|---|---|
| Old envelopes deserialise/validate exactly as before | `RuleEnvelopeBackwardCompatTest.legacyEnvelopeRoundTripsByteForByte` — load each of the 12 built-in NL samples → generate envelope (offline path) → assert serialised byte-equivalence pre and post M3 | The 12 built-in samples (existing) plus sample-A `expected-envelope.json` |
| New envelopes with each construct serialise/deserialise/validate without loss (≥ 5 round-trip tests) | `ExtensionsRoundTripTest.globalGuardRoundTrip`, `.fieldOrRoundTrip`, `.ruleGroupingRoundTrip`, `.footnoteRoundTrip`, `.ruleStatusRoundTrip` — for each, build a `RuleEnvelope` containing exactly that sub-type, serialise → deserialise → assert `equals()` | Synthetic minimal envelopes constructed in-test |
| Field-OR conditions recognised in sample B | `SampleBExtensionsTest.fieldOrParsesFromSampleB` — load a sample-B fixture **enriched with the `newContractPaymentMethod` ∨ `renewalPaymentMethod` OR** under `extensions.fieldOr`; assert deserialised `FieldOrSpec.fields == ["newContractPaymentMethod", "renewalPaymentMethod"]`. (Note: sample-B fixture must be enriched in M3 because today's part1/part2 fixtures do not yet carry `extensions{}`.) | `golden-tests/sample-B/expected-envelope-part2.json` (extended additively under `extensions{}` — append-only per MISSION.md §2.3) |
| Global guards parse from sample B | `SampleBExtensionsTest.globalGuardParsesFromSampleB` — sample-B envelope with `extensions.globalGuards[0].condition.field == "acceptanceChannel"`; assert deserialised `GlobalGuard.condition.operator == "in"` | Same as above |
| Existing test suite remains green | Orchestrator runs `mvn test` after impl + tests land; review-worker confirms zero red tests in `WORK_LOG_review.md` | n/a (gate, not test) |
| New error code `INVALID_VARIABLE_IN_CONDITION` correctly flags VARIABLE typeRef on a condition row (xlsx 區域變數 條件欄位不開放) | `RuleSemanticValidatorVariableTypeTest.variableInInputs_negative` — build a `RuleEnvelope` whose `rule.inputs[]` contains a `FieldDef{typeRef:"VARIABLE"}`; assert validator emits exactly one error with `code == "INVALID_VARIABLE_IN_CONDITION"` and message containing the offending field name. Paired with `RuleSemanticValidatorVariableTypeTest.variableInOutputs_positive` — same envelope but the `FieldDef` is in `rule.outputs[]`; assert zero `INVALID_VARIABLE_IN_CONDITION` errors. | Synthetic minimal envelopes constructed in-test |

The "≥5 round-trip" requirement is met by one test per sub-type. The "validate without loss" requirement is met by adding a `.validatesClean()` assertion at the end of each round-trip test (`new DecisionTableValidator(...).validate(envelopeAsJsonNode).isEmpty()`).

---

## 10. CP2 reviewer checklist

- [ ] Does the design preserve `@JsonInclude(NON_NULL)` so an empty `extensions` disappears from JSON? (Yes — §2; the field is `null` by default and `RuleEnvelope` carries `@JsonInclude(NON_NULL)`.)
- [ ] Are `TIMESTAMP` and `VARIABLE` appended at the **END** of `TypeRefs.ALL`? (Yes — §4.1.)
- [ ] Does every new error code have an assigned `ValidationLayer`? (Yes — §6.2 table; five live in the new `ExtensionsValidator` at `@Order(5)`, the sixth — `INVALID_VARIABLE_IN_CONDITION` — lives in `RuleSemanticValidator` at `@Order(3)` because it concerns core `inputs/outputs` typeRef semantics, not the `extensions{}` block.)
- [ ] Is the xlsx-derived rule `8.variable - 區域變數 (條件欄位不開放)` honoured at the RuleEnvelope validator layer? (Yes — §6.1 declares `INVALID_VARIABLE_IN_CONDITION`; §6.2 routes it to `RuleSemanticValidator`; §4.3 records the xlsx ↔ RE typeRef mapping for M5 reference.)
- [ ] Does §8 modification justification cover every touched restricted file? (Yes — five paragraphs: `RuleEnvelope.java`, `ToolDtos.java`, `EnvelopeNormalizer.java`, `RuleSemanticValidator.java`, `ExtensionsValidator.java`.)
- [ ] Is the normalizer pass null-safe (zero work when `extensions == null`)? (Yes — §5.2; first line of `normalizeExtensions` returns on null.)
- [ ] Is the normalizer pass position justified (after `sortByPriority`)? (Yes — §5.1.)
- [ ] Are `Condition.valueRef`'s cross-field and relative-date semantics explicitly **not** re-defined in extensions? (Yes — §1 explicit non-redefinition note; §3 sub-types reuse `RuleEnvelope.Condition`.)
- [ ] Is M1 risk #2 (`unwrapNestedValue` collision with `fieldOr`) explicitly mitigated? (Yes — §3.2 + §5.4: field renamed to `predicate`, unwrap scoped to Condition.value only.)
- [ ] Does the design address the gap that sample-B fixtures **today** do not carry `extensions{}`? (Yes — preamble note before §1; §9 specifies append-only enrichment of sample-B fixtures during M3.)
- [ ] Is the downstream-typeRef impact assessment honest about deferrals (HyperRectangle, EvaluationComputer)? (Yes — §4.5 table marks them "Acceptable for M3" or "Deferred" with reasoning.)
- [ ] Does the file manifest in §7 sum to a single-dispatch impl-worker scope (~ 7 new + 4 modified)? (Yes — §7 totals.)
