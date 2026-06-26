# M2 SPI Design — RuleEngineAdapter

**Mission:** M2 — SPI Skeleton
**Author:** architect-worker
**Date:** 2026-05-15

**Inputs reviewed:**
- `MISSION.md` §2.3, §4.M2, §6 (M2 row), §7
- `PROJECT_PLAN.md` §4.2, Appendix §B
- `design/ARCHITECTURE_OBSERVATIONS.md` §5 (coexistence rationale), §6 (risks #4 and #6)
- `src/main/java/com/ruleengine/rules/service/exporter/RuleExporter.java` (existing SPI shape, `ExportResult` record)
- `src/main/java/com/ruleengine/rules/service/exporter/InternalEngineExporter.java` (existing `ENGINE_NAME = "group-internal-engine"` constant — must not collide)
- `src/main/java/com/ruleengine/rules/registry/RuleTypeRegistry.java` (registry idiom to mirror)

---

## 1. Scope

This design covers the **three M2 deliverables only**: (a) the `RuleEngineAdapter` Java interface with its method signatures and supporting types; (b) the `AdapterRegistry` Spring component that auto-discovers adapter beans via constructor-injected `List<RuleEngineAdapter>`; (c) the `GroupAdapter` stub that registers under a non-colliding engine name and throws `UnsupportedOperationException` from every conversion method with messages naming the mission that will fill it in. **Deferred to M4:** the actual `RuleEnvelope ↔ Group JSON` translation (`GroupJsonExporter`, `GroupJsonImporter`, `GroupCodeTable`, `GroupLayoutEngine`). **Deferred to M5:** the `RuleEnvelope → xlsx` binary emitter (`GroupXlsxExporter`). **Deferred to M6:** any engine evaluation (lives in `service/engine/`). **Deferred to M7:** wiring into `RuleService` / `ToolsController`. The `RuleEngineAdapter` interface must be wide enough that M4 and M5 can fill in real conversion logic without changing the signature; that is its only design pressure.

---

## 2. Coexistence with `service/exporter/RuleExporter`

The new `RuleEngineAdapter` SPI must not absorb, replace, or shadow the existing `service/exporter/RuleExporter` SPI. `MISSION.md` §2.3 states "*The contents of `service/exporter/` are not to be removed. The stub remains so that any caller depending on its presence keeps working*." The two SPIs coexist on three distinct axes, as established in `ARCHITECTURE_OBSERVATIONS.md` §5:

| Axis | `RuleExporter` (existing) | `RuleEngineAdapter` (new, M2) |
|---|---|---|
| Direction | Forward only — `RuleEnvelope → ExportResult` | **Bidirectional** — `RuleEnvelope ↔ payload` |
| Content shape | Single `content : String` (text-only) | Per-format payload — `byte[]` to permit binary (XLSX) and text (JSON) |
| Format domain | One `format` string per impl (e.g. `"json"`) | Multi-format per impl — `Set<AdapterFormat>` (e.g. `{JSON, XLSX}`) |
| Lifecycle stage | Governance / deployment pre-flight (warnings about missing `fieldCode`, missing `externalCodes`, missing `effectiveDate`) | Runtime conversion feeding the in-process `MockGroupEngine` and the new "Engine Execution" dashboard tab |
| Registry | Auto-collected as `List<RuleExporter>` Spring beans, separately discoverable | Auto-collected as `List<RuleEngineAdapter>` via the new `AdapterRegistry` — never merged with `RuleExporter` beans |
| `engineName()` namespace | `"group-internal-engine"` (constant in `InternalEngineExporter:39`) | `"group"` (this design — see §5, deliberately distinct per `ARCHITECTURE_OBSERVATIONS.md` risk #6) |

The three reasons cited in `ARCHITECTURE_OBSERVATIONS.md` §5 ("(a) `ExportResult.content : String` cannot carry binary XLSX or the importer reverse direction", "(b) `MISSION.md` §2.3 forbids removal", "(c) different lifecycle stages — governance pre-flight vs runtime conversion") are the load-bearing argument for keeping the two SPIs separate. `RuleExporter` remains untouched.

---

## 3. `RuleEngineAdapter` interface

**Package:** `com.ruleengine.rules.service.adapter`
**File:** `src/main/java/com/ruleengine/rules/service/adapter/RuleEngineAdapter.java`

### 3.1 Method signatures

The interface declares five methods. All parameter types and return types are either `java.lang.*`, `java.util.*`, the existing `com.ruleengine.rules.domain.envelope.RuleEnvelope`, or types declared inside this same package — **no Group-specific type leaks into the public signature** (per `MISSION.md` §4.M6 acceptance, which we honour proactively in M2 to avoid later refactor).

- `String engineName()` — short stable identifier for the downstream engine this adapter targets (e.g. `"group"`, future: `"drools"`, `"fico"`, `"ibm-odm"`). Must differ from any value already published by a `RuleExporter` bean. Used as the registry key in `AdapterRegistry`.
- `String engineVersion()` — adapter's compatibility tag against the engine's format version (e.g. `"0.1-stub"` in M2, `"1.0"` once M4/M5 land). Logged at startup; surfaced through `AdapterRegistry.getEngineInfo()`.
- `Set<AdapterFormat> supportedFormats()` — declares which `AdapterFormat` enum values this adapter can handle for both `export` and `importFrom`. `GroupAdapter` returns `Set.of(JSON, XLSX)` in M2 even though the implementations are stubbed; `AdapterRegistry` and downstream callers use this for capability discovery (e.g. dashboard "show xlsx export button only if adapter declares XLSX").
- `AdapterExportResult export(RuleEnvelope envelope, AdapterFormat format)` — convert the engine-neutral envelope into the downstream engine's native payload in the requested format. Throws `AdapterException` if the adapter does not support `format` (i.e. `format ∉ supportedFormats()`) or if conversion fails. M2 stub throws `UnsupportedOperationException` instead, with the M4/M5 mission id in the message.
- `RuleEnvelope importFrom(byte[] payload, AdapterFormat format)` — parse a downstream-engine payload back into a `RuleEnvelope`. The `byte[]` choice is deliberate: XLSX is binary, JSON is `UTF-8` bytes (callers wrap `String.getBytes(StandardCharsets.UTF_8)` when they have text). Throws `AdapterException` for unsupported format or parse failure. M2 stub throws `UnsupportedOperationException` (`importFrom` is M4-only; XLSX import is not in the mission scope — see §5 stub note).

### 3.2 Supporting types (records / enums)

All three types live in the same `com.ruleengine.rules.service.adapter` package as the interface, so callers import one package.

#### 3.2.1 `AdapterFormat` (enum)

**File:** `src/main/java/com/ruleengine/rules/service/adapter/AdapterFormat.java`

```
enum AdapterFormat {
    JSON,      // text payload; UTF-8 bytes; produced by M4 GroupJsonExporter, consumed by M4 GroupJsonImporter
    XLSX       // binary payload; produced by M5 GroupXlsxExporter; import is OUT OF SCOPE for the mission
}
```

Only two values in M2. Future engines may need `XML` (Drools `.drl`-adjacent), `YAML` (FICO Blaze export), or `DMN` — those are explicitly **not** added now; `MISSION.md` §1.2 lists them as out-of-mission. Adding a value later is additive and does not break any existing impl: the contract is "throw if `format ∉ supportedFormats()`", so existing adapters keep working.

#### 3.2.2 `AdapterExportResult` (record)

**File:** `src/main/java/com/ruleengine/rules/service/adapter/AdapterExportResult.java`

```
record AdapterExportResult(
        String engineName,             // mirror of adapter.engineName() — eases response building
        String engineVersion,          // mirror of adapter.engineVersion()
        AdapterFormat format,          // the format that was produced
        byte[] content,                // the payload — JSON-as-UTF-8 or binary XLSX
        List<String> warnings,         // adapter pre-flight warnings (parallels RuleExporter.ExportResult.warnings)
        Map<String, Object> metadata   // free-form metadata (ruleCount, layoutAlgorithm, sheetName, etc.)
) {}
```

**Why `byte[]` and not `String`:** the M5 XLSX path produces binary, which `String` cannot carry without base64 wrapping; forcing all adapters to base64-encode would make the JSON path needlessly indirect and would diverge from how `MockGroupEngine` will consume the bytes in M6. `byte[]` is the lowest-common-denominator that handles both. The `RuleExporter.ExportResult.content : String` choice was correct for its single-format scope (`json` text only), but `RuleEngineAdapter` cannot reuse it.

**Why mirror `engineName` / `engineVersion` into the record:** so downstream code (M7's `GenerateResponse.execution` block) can serialise the result without holding the adapter bean reference. `RuleExporter.ExportResult` does the same.

#### 3.2.3 `AdapterException` (checked or unchecked?)

**File:** `src/main/java/com/ruleengine/rules/service/adapter/AdapterException.java`

**Decision: `RuntimeException` subclass (unchecked).**

Reasoning: existing codebase exceptions (`RuleGenerationException`, `RuleValidationException`, `EnvelopeNormalizationException` — observed via `RuleService.generateFull` flow) are all unchecked, and `RuleService` catches them at the orchestration boundary via the project's `@RestControllerAdvice`. A new checked exception would force every M7 caller to wrap with `try/catch`, breaking style parity. We do not reuse `RuleGenerationException` because it semantically belongs to the LLM-generation phase, not adapter conversion; conflating them would muddy error reporting in the new `execution` response block.

```
class AdapterException extends RuntimeException {
    // engineName + format passed to constructor for structured error logging
    public AdapterException(String engineName, AdapterFormat format, String message) { ... }
    public AdapterException(String engineName, AdapterFormat format, String message, Throwable cause) { ... }
    public String getEngineName() { ... }
    public AdapterFormat getFormat() { ... }
}
```

The M2 stub uses `UnsupportedOperationException` (not `AdapterException`) for the stubbed methods, because the failure mode is "not implemented yet" rather than "this format is unsupported by this engine" — they are categorically different failure shapes. M4/M5 impl-workers should switch to `AdapterException` once real conversion logic lands.

### 3.3 Why this shape, not `RuleExporter`'s shape

`RuleExporter.ExportResult.content : String` cannot carry the M5 XLSX deliverable, which is binary by definition (POI's `XSSFWorkbook.write(OutputStream)` emits bytes that are not safely round-trippable through `new String(bytes)` without base64). Switching to `byte[]` makes JSON and XLSX uniform. Beyond the payload type, `RuleExporter` is forward-only — it has no `parse` / `import` method, because its purpose is "build a deployment artefact" rather than "convert between representations". M4 explicitly needs `Group JSON → RuleEnvelope` (the importer side, required to round-trip `TREE-SAMPLE-001_決策樹.json` per `MISSION.md` §5.2). That direction cannot be retrofitted onto `RuleExporter` without breaking its v3.12 contract (which `InternalEngineExporter` and any pinned downstream consumer rely on). Hence a parallel, additive SPI.

---

## 4. `AdapterRegistry` component

**Package:** `com.ruleengine.rules.service.adapter`
**File:** `src/main/java/com/ruleengine/rules/service/adapter/AdapterRegistry.java`

`AdapterRegistry` is a `@Component` (`@Slf4j`) that mirrors `RuleTypeRegistry` (the registry idiom established in `src/main/java/com/ruleengine/rules/registry/RuleTypeRegistry.java`): Spring constructor-injects `List<RuleEngineAdapter>`, and `@PostConstruct init()` indexes the list into a `Map<String, RuleEngineAdapter>` keyed by `adapter.engineName()`. On collision (two adapters publishing the same engine name) the registry logs a `WARN` and keeps the first-registered bean — the M2 stub deliberately uses a name distinct from `InternalEngineExporter.ENGINE_NAME`, so this branch should never fire today.

The `@PostConstruct` block also logs **one** human-readable startup line listing all registered adapters by name and version, which is what satisfies `MISSION.md` §6 M2 acceptance criterion "*AdapterRegistry exposes a list of known adapter names at startup*". Suggested format (matches `RuleTypeRegistry`'s log style):

```
AdapterRegistry 初始化完成，已註冊 1 個 RuleEngineAdapter：[group@0.1-stub]
```

**Public methods:**

- `Optional<RuleEngineAdapter> getByName(String engineName)` — primary lookup. Returns empty Optional when no adapter is registered under that name, matching `RuleTypeRegistry.getGenerator(type)`'s contract style.
- `List<String> getRegisteredEngineNames()` — sorted list of all registered adapter names. Used by the startup log line and (later) by the dashboard's adapter-picker dropdown. Returning `List` (not `Set`) so callers get a deterministic order.
- `Map<String, String> getEngineInfo()` — `engineName → engineVersion` summary. Used by the M2 unit test (verifies `GroupAdapter` shows up with the expected version) and by future MCP `RulesMcpResourceProvider` views — parallels `RuleTypeRegistry.getTypeDescriptions()`.
- `boolean isRegistered(String engineName)` — convenience boolean, parallels `RuleTypeRegistry.isSupported(type)`.

**What `AdapterRegistry` does NOT do (explicit non-goals):**

- It does **not** select adapters by `AdapterFormat` capability (no `getByFormat(JSON)` method in M2). Callers ask by engine name; capability lookup via `supportedFormats()` is the responsibility of the caller, not the registry. If M7 needs format-based selection it can add a thin helper later.
- It does **not** merge with `RuleExporter` discovery (`RuleExporter` beans remain auto-collected wherever their consumers `@Autowired List<RuleExporter>` today — typically `InternalEngineExporter` is wired solo, but the registry never aggregates them).
- It does **not** invoke any `export()` or `importFrom()` method. The registry is read-only lookup; conversion calls happen at the M7 integration point in `RuleService`.

---

## 5. `group/GroupAdapter` stub

**Package:** `com.ruleengine.rules.service.adapter.group`
**File:** `src/main/java/com/ruleengine/rules/service/adapter/group/GroupAdapter.java`

`GroupAdapter` is a `@Component` (so Spring picks it up via `List<RuleEngineAdapter>` injection in `AdapterRegistry`) that implements `RuleEngineAdapter`. In M2 it is a **discoverable stub** — every conversion method throws `UnsupportedOperationException` with a message naming the mission id that will fill it in. The identifying methods (`engineName`, `engineVersion`, `supportedFormats`) return real values so that `AdapterRegistry` and tests can observe registration.

**Chosen `engineName()` value:** `"group"`.

**Why `"group"` and explicitly NOT `"group-internal-engine"` or `"group-group-engine"`:**

- `InternalEngineExporter.ENGINE_NAME = "group-internal-engine"` (constant at `InternalEngineExporter.java:39`) is the existing `RuleExporter` identifier. `ARCHITECTURE_OBSERVATIONS.md` risk #6 explicitly calls out the collision risk if `GroupAdapter` published the same string; mitigation #6 in the risk register recommended `"group-group-engine"`. I am narrowing further to `"group"` because (a) the two SPIs live in separate bean maps (`RuleExporter` consumers vs `AdapterRegistry`) so the *technical* collision risk is zero, (b) the *human* collision risk — a future reader wondering "is `group-internal-engine` the same thing as `group-group-engine`?" — is sharper than just shortening to `"group"`, and (c) future engines published through `RuleEngineAdapter` will follow the same one-word convention (`"drools"`, `"fico"`, `"ibm-odm"`), keeping the namespace clean.
- The constraint from the dispatch ("the new engineName must differ from `InternalEngineExporter.ENGINE_NAME = "group-internal-engine"`") is satisfied: `"group"` ≠ `"group-internal-engine"` as strings.

**Stub shape:**

- `@Component` (Spring picks it up; no `@RequiredArgsConstructor` needed in M2 because the stub holds no collaborators — M4 will inject `ObjectMapper` and `GroupCodeTable` for the real impl).
- `@Slf4j` for consistency with `InternalEngineExporter` / `RuleService`.
- `engineName()` → `"group"` (constant).
- `engineVersion()` → `"0.1-stub"` (constant, mirrors `InternalEngineExporter.ENGINE_VERSION` style).
- `supportedFormats()` → `Set.of(AdapterFormat.JSON, AdapterFormat.XLSX)` — declared up-front so M2 test can verify the adapter advertises both, and so the dashboard can wire its UI against the declaration without waiting for M4/M5. The format is declared even though `export` is stubbed; this is intentional and matches the "discoverable stub" goal.
- `export(envelope, format)`:
  - If `format == JSON`: throw `UnsupportedOperationException("GroupAdapter.export(JSON) will be implemented in M4 — Group JSON Adapter")`.
  - If `format == XLSX`: throw `UnsupportedOperationException("GroupAdapter.export(XLSX) will be implemented in M5 — Group XLSX Adapter")`.
  - If `format` is anything else (e.g. a future enum value not in `supportedFormats()`): throw `UnsupportedOperationException("GroupAdapter does not support format " + format)`. This branch is defensive against future `AdapterFormat` additions; today it is unreachable because the enum only has JSON and XLSX.
- `importFrom(payload, format)`:
  - If `format == JSON`: throw `UnsupportedOperationException("GroupAdapter.importFrom(JSON) will be implemented in M4 — Group JSON Adapter")`.
  - If `format == XLSX`: throw `UnsupportedOperationException("GroupAdapter.importFrom(XLSX) is out of scope; XLSX import is not in this mission. See MISSION.md §1.2.")`. (XLSX import is not a mission deliverable — only XLSX export in M5.)

**No real conversion logic in M2.** No `ObjectMapper` use, no envelope traversal, no `GroupCodeTable` reference. Those land in M4 (JSON) and M5 (XLSX).

---

## 6. Acceptance trace

`MISSION.md` §6 M2 row states three acceptance criteria. Each maps to this design as follows:

- **"Project compiles"** → the design introduces three new Java files (`RuleEngineAdapter.java`, `AdapterRegistry.java`, `GroupAdapter.java`) plus three support types (`AdapterFormat.java`, `AdapterExportResult.java`, `AdapterException.java`). All depend only on `java.util.*` and `com.ruleengine.rules.domain.envelope.RuleEnvelope` (already on the classpath). No edits to existing source files in M2 — Spring auto-collection picks up `GroupAdapter` because it is `@Component` in a sub-package of the existing `com.ruleengine.rules` scanned root. Compile cleanliness is achieved by impl-worker.
- **"`AdapterRegistry` exposes a list of known adapter names at startup"** → `AdapterRegistry.@PostConstruct init()` logs one line of the form `"AdapterRegistry 初始化完成，已註冊 1 個 RuleEngineAdapter：[group@0.1-stub]"`. The public method `getRegisteredEngineNames()` is the programmatic equivalent and is exercised by the M2 unit test.
- **"`GroupAdapter` is discoverable via the registry; one unit test confirms this"** → test-worker creates **`src/test/java/com/ruleengine/rules/service/adapter/AdapterRegistryTest.java`**. Suggested test cases (test-worker may add more): (a) `AdapterRegistry.getByName("group")` returns a present `Optional<RuleEngineAdapter>`; (b) the returned adapter's `engineName()` is `"group"` and `engineVersion()` is `"0.1-stub"`; (c) `getRegisteredEngineNames()` contains `"group"`; (d) `getEngineInfo().get("group")` equals `"0.1-stub"`; (e) `getByName("group-internal-engine")` returns empty `Optional` (verifies the registry does NOT merge with `RuleExporter` namespace — guards risk #6); (f) calling `groupAdapter.export(envelope, JSON)` throws `UnsupportedOperationException` whose message contains the string `"M4"` (verifies the stub message points to the correct future mission).

---

## 7. File manifest for impl-worker

Six files total. Impl-worker writes the first five; test-worker writes the sixth.

| File path (absolute) | Purpose | LOC estimate |
|---|---|---|
| `src/main/java/com/ruleengine/rules/service/adapter/RuleEngineAdapter.java` | The SPI interface — 5 methods declared, javadoc explaining bidirectional and multi-format contract; no default methods | 40–55 |
| `src/main/java/com/ruleengine/rules/service/adapter/AdapterFormat.java` | Enum with `JSON` and `XLSX` values; one-line javadoc per value naming the mission that produces / consumes it | 20–30 |
| `src/main/java/com/ruleengine/rules/service/adapter/AdapterExportResult.java` | Record with `engineName`, `engineVersion`, `format`, `content : byte[]`, `warnings`, `metadata`; javadoc explaining the `byte[]` choice (binary XLSX safety) | 30–45 |
| `src/main/java/com/ruleengine/rules/service/adapter/AdapterException.java` | `RuntimeException` subclass carrying `engineName` and `format`; two constructors (with / without cause); javadoc explaining why unchecked | 35–50 |
| `src/main/java/com/ruleengine/rules/service/adapter/AdapterRegistry.java` | `@Component @Slf4j`; constructor-injected `List<RuleEngineAdapter>`; `@PostConstruct init()` indexes by `engineName()`, logs registration line; public methods `getByName`, `getRegisteredEngineNames`, `getEngineInfo`, `isRegistered`; mirrors `RuleTypeRegistry` style | 80–105 |
| `src/main/java/com/ruleengine/rules/service/adapter/group/GroupAdapter.java` | `@Component @Slf4j implements RuleEngineAdapter`; constants `ENGINE_NAME = "group"` and `ENGINE_VERSION = "0.1-stub"`; `supportedFormats()` returns `Set.of(JSON, XLSX)`; `export` and `importFrom` throw `UnsupportedOperationException` with mission-id-tagged messages per §5 | 75–95 |
| `src/test/java/com/ruleengine/rules/service/adapter/AdapterRegistryTest.java` (**test-worker**) | `@SpringBootTest`-loaded test asserting the six cases (a–f) in §6 above | 70–95 |

Total new production code: ~280–380 LOC across six files. No existing file is modified in M2.

---

*End of M2 design. Awaiting impl-worker dispatch.*
