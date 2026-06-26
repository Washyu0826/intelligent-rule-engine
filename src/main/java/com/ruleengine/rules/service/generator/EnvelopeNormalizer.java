package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.RuleEnvelopeExtensions;
import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.ruleengine.rules.domain.extensions.FieldOrSpec;
import com.ruleengine.rules.domain.extensions.Footnote;
import com.ruleengine.rules.domain.extensions.GlobalGuard;
import com.ruleengine.rules.domain.extensions.RuleGrouping;
import com.ruleengine.rules.domain.extensions.RuleStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RuleEnvelope 正規化服務。
 *
 * 負責修正 AI 生成的常見問題：
 *   - 補版本號（schemaVersion / promptVersion）
 *   - hitPolicy 正規化
 *   - 確保所有 List 可變（避免 List.of() / Collections.unmodifiableList 造成 UnsupportedOperationException）
 *   - typeRef alias 修正（int → INTEGER, bool → BOOLEAN 等）
 *   - ENUM 缺 allowedValues 時自動推斷
 *   - 自動補 ruleId / priority
 *   - operator 正規化（eq → equals, gt → greaterThan 等）
 *   - BOOLEAN 字串修正（"true" → true）
 *   - INTEGER/DECIMAL 字串轉數值（"25" → 25）
 *   - 移除空的 condition / result
 *   - 移除完全重複的規則
 *   - 按 priority 排序
 *
 * v2.0.0: 從 DecisionTableGenerator 抽出，單一職責。
 */
@Component
@Slf4j
public class EnvelopeNormalizer {

    /**
     * 對 RuleEnvelope 進行完整正規化（就地修改）。
     *
     * @param envelope       要正規化的信封
     * @param schemaVersion  預設的 schemaVersion
     * @param promptVersion  預設的 promptVersion
     */
    public void normalize(RuleEnvelope envelope, String schemaVersion, String promptVersion) {
        // 補版本資訊
        if (envelope.getSchemaVersion() == null) envelope.setSchemaVersion(schemaVersion);
        if (envelope.getPromptVersion() == null) envelope.setPromptVersion(promptVersion);
        // ruleType 修正：Ollama 常設成 "RuleEnvelope" 等不合法值
        if (envelope.getRuleType() == null
                || (!"DecisionTable".equals(envelope.getRuleType())
                    && !"DecisionTree".equals(envelope.getRuleType()))) {
            log.warn("normalizeRuleType: 不合法的 ruleType \"{}\" → 預設 DecisionTable", envelope.getRuleType());
            envelope.setRuleType("DecisionTable");
        }

        Rule rule = envelope.getRule();
        if (rule == null) return;

        normalizeHitPolicy(rule);
        ensureMutableLists(rule);
        normalizeTypeRefs(rule);
        autoInferEnumAllowedValues(rule);
        normalizeRuleIds(rule);
        normalizeOperatorsAndValues(rule);
        deduplicateRules(rule);
        sortByPriority(rule);

        // v3.14: extensions 正規化（第 9 趟），必須在 sortByPriority 之後執行，
        // 因為 ruleStatus / footnotes / grouping 都會引用 ruleId，而 ruleId 在
        // sortByPriority 完成後才算定案。null-safe：若無 extensions 直接 return。
        normalizeExtensions(envelope);
    }

    // ================================================================
    // hitPolicy 正規化
    // ================================================================

    private void normalizeHitPolicy(Rule rule) {
        if (rule.getHitPolicy() == null || rule.getHitPolicy().isBlank()) {
            rule.setHitPolicy("FIRST");
        } else {
            String hp = rule.getHitPolicy().toUpperCase().trim();
            // Ollama 常把 hitPolicy 設成描述文字，只保留 FIRST/MULTI
            if (!"FIRST".equals(hp) && !"MULTI".equals(hp)) {
                log.warn("normalizeHitPolicy: 不合法的 hitPolicy \"{}\" → 預設 FIRST", rule.getHitPolicy());
                rule.setHitPolicy("FIRST");
            } else {
                rule.setHitPolicy(hp);
            }
        }
    }

    // ================================================================
    // 確保所有 List 可變
    // ================================================================

    private void ensureMutableLists(Rule rule) {
        rule.setInputs(ensureMutableList(rule.getInputs()));
        rule.setOutputs(ensureMutableList(rule.getOutputs()));
        rule.setRules(ensureMutableList(rule.getRules()));

        for (FieldDef fd : rule.getInputs()) {
            if (fd.getAllowedValues() != null) {
                fd.setAllowedValues(ensureMutableList(fd.getAllowedValues()));
            }
        }
        for (FieldDef fd : rule.getOutputs()) {
            if (fd.getAllowedValues() != null) {
                fd.setAllowedValues(ensureMutableList(fd.getAllowedValues()));
            }
        }

        for (RuleRow row : rule.getRules()) {
            row.setConditions(ensureMutableList(row.getConditions()));
            row.setResults(ensureMutableList(row.getResults()));
        }
    }

    // ================================================================
    // typeRef alias 修正
    // ================================================================

    private void normalizeTypeRefs(Rule rule) {
        for (FieldDef fd : rule.getInputs()) {
            if (fd.getTypeRef() != null) fd.setTypeRef(normalizeTypeRef(fd.getTypeRef()));
        }
        for (FieldDef fd : rule.getOutputs()) {
            if (fd.getTypeRef() != null) fd.setTypeRef(normalizeTypeRef(fd.getTypeRef()));
        }
    }

    /**
     * typeRef 常見 AI 簡寫修正。
     * "int" → "INTEGER", "bool" → "BOOLEAN", "str" → "STRING" etc.
     *
     * v3.14 行為變更：DATETIME 不再 collapse 到 DATE，而是路由到 TIMESTAMP，
     * 以保留 time-of-day 語義（修 M1 risk register #1）。LOCALDATE 維持 → DATE。
     */
    public String normalizeTypeRef(String raw) {
        if (raw == null) return null;
        String upper = raw.toUpperCase().trim();
        return switch (upper) {
            case "INT", "LONG", "NUMBER" -> "INTEGER";
            case "DOUBLE", "FLOAT", "DEC", "BIGDECIMAL" -> "DECIMAL";
            case "BOOL" -> "BOOLEAN";
            case "STR", "TEXT", "VARCHAR" -> "STRING";
            case "ENUMERATION" -> "ENUM";
            case "LOCALDATE" -> "DATE";
            // v3.14: DATETIME / LOCALDATETIME / INSTANT / TS / ZONEDDATETIME → TIMESTAMP
            case "DATETIME", "LOCALDATETIME", "INSTANT", "TS", "ZONEDDATETIME" -> "TIMESTAMP";
            case "VAR", "VARIABLE_REF", "RUNTIME_VAR", "DERIVED" -> "VARIABLE";
            default -> upper;
        };
    }

    // ================================================================
    // ENUM allowedValues 自動推斷
    // ================================================================

    private void autoInferEnumAllowedValues(Rule rule) {
        for (FieldDef fd : rule.getInputs()) {
            if ("ENUM".equals(fd.getTypeRef()) && isNullOrEmpty(fd.getAllowedValues())) {
                List<String> inferred = inferAllowedValuesFromConditions(fd.getName(), rule.getRules());
                if (!inferred.isEmpty()) {
                    fd.setAllowedValues(inferred);
                    log.info("Auto-infer: input[{}] ENUM allowedValues = {}", fd.getName(), inferred);
                }
            }
        }
        for (FieldDef fd : rule.getOutputs()) {
            if ("ENUM".equals(fd.getTypeRef()) && isNullOrEmpty(fd.getAllowedValues())) {
                List<String> inferred = inferAllowedValuesFromResults(fd.getName(), rule.getRules());
                if (!inferred.isEmpty()) {
                    fd.setAllowedValues(inferred);
                    log.info("Auto-infer: output[{}] ENUM allowedValues = {}", fd.getName(), inferred);
                }
            }
        }
    }

    private List<String> inferAllowedValuesFromConditions(String fieldName, List<RuleRow> rules) {
        Set<String> values = new LinkedHashSet<>();
        for (RuleRow row : rules) {
            if (row.getConditions() == null) continue;
            for (Condition c : row.getConditions()) {
                if (fieldName.equals(c.getField()) && c.getValue() != null) {
                    if ("equals".equals(c.getOperator()) || "notEquals".equals(c.getOperator())) {
                        values.add(String.valueOf(c.getValue()));
                    } else if ("in".equals(c.getOperator()) && c.getValue() instanceof List) {
                        ((List<?>) c.getValue()).forEach(v -> values.add(String.valueOf(v)));
                    }
                }
            }
        }
        return new ArrayList<>(values);
    }

    private List<String> inferAllowedValuesFromResults(String fieldName, List<RuleRow> rules) {
        Set<String> values = new LinkedHashSet<>();
        for (RuleRow row : rules) {
            if (row.getResults() == null) continue;
            for (Result r : row.getResults()) {
                if (fieldName.equals(r.getField()) && r.getValue() != null) {
                    values.add(String.valueOf(r.getValue()));
                }
            }
        }
        return new ArrayList<>(values);
    }

    // ================================================================
    // 自動補 ruleId / priority
    // ================================================================

    private void normalizeRuleIds(Rule rule) {
        for (int i = 0; i < rule.getRules().size(); i++) {
            RuleRow row = rule.getRules().get(i);
            if (row.getRuleId() == null || row.getRuleId().isBlank()) {
                row.setRuleId(String.format("R%02d", i + 1));
            }
            if (row.getPriority() == null) {
                row.setPriority(i + 1);
            }
        }
    }

    // ================================================================
    // operator 正規化 + 值修復
    // ================================================================

    private void normalizeOperatorsAndValues(Rule rule) {
        for (RuleRow row : rule.getRules()) {
            // 移除空的 condition（field 或 operator 為 null）
            row.setConditions(row.getConditions().stream()
                    .filter(c -> c.getField() != null && !c.getField().isBlank()
                            && c.getOperator() != null && !c.getOperator().isBlank())
                    .collect(Collectors.toCollection(ArrayList::new)));

            for (Condition cond : row.getConditions()) {
                if (cond.getOperator() != null) {
                    cond.setOperator(normalizeOperator(cond.getOperator()));
                }
                // 展開嵌套值（LLM 常產出 {"type":"integer","value":60} 格式）
                cond.setValue(unwrapNestedValue(cond.getValue()));
                ensureMutableConditionValue(cond);
                repairBooleanValue(cond, rule.getInputs());
                repairNumericValue(cond, rule.getInputs());
            }

            // 移除空的 result（field 為 null）
            row.setResults(row.getResults().stream()
                    .filter(r -> r.getField() != null && !r.getField().isBlank())
                    .collect(Collectors.toCollection(ArrayList::new)));

            for (Result res : row.getResults()) {
                // 展開嵌套值
                res.setValue(unwrapNestedValue(res.getValue()));
                repairResultNumericValue(res, rule.getOutputs());
            }

            // 補全缺失 value 的 result（LLM 常對「拒保時為空值」省略 value）
            // 根據 output typeRef 決定預設值，避免 TYPE_MISMATCH
            Map<String, String> outputTypeMap = new HashMap<>();
            if (rule.getOutputs() != null) {
                rule.getOutputs().forEach(o -> outputTypeMap.put(o.getName(), o.getTypeRef()));
            }
            for (Result res : row.getResults()) {
                boolean isEmpty = res.getValue() == null
                        || (res.getValue() instanceof String s && s.isBlank());
                if (isEmpty) {
                    Object defaultVal = getDefaultValueForType(outputTypeMap.get(res.getField()));
                    res.setValue(defaultVal);
                    log.debug("Auto-repair: {} result[{}] value 為空 → {}",
                            row.getRuleId(), res.getField(), defaultVal);
                }
            }

            // 補全缺失的 output 欄位（LLM 可能未為某些 output 產生 result）
            if (rule.getOutputs() != null) {
                Set<String> coveredFields = row.getResults().stream()
                        .map(Result::getField)
                        .collect(Collectors.toSet());
                for (FieldDef output : rule.getOutputs()) {
                    if (!coveredFields.contains(output.getName())) {
                        Object defaultVal = getDefaultValueForType(output.getTypeRef());
                        row.getResults().add(Result.builder()
                                .field(output.getName())
                                .value(defaultVal)
                                .build());
                        log.debug("Auto-repair: {} 補全缺失的 result[{}] → {}",
                                row.getRuleId(), output.getName(), defaultVal);
                    }
                }
            }
        }
    }

    /**
     * 常見的 AI operator 寫法修正。
     */
    public String normalizeOperator(String op) {
        if (op == null) return null;
        String trimmed = op.trim();
        return switch (trimmed.toLowerCase()) {
            case "eq", "equal", "=", "==" -> Operators.EQUALS;
            case "neq", "ne", "!=", "<>" -> Operators.NOT_EQUALS;
            case "gt", ">" -> Operators.GREATER_THAN;
            case "gte", "ge", ">=" -> Operators.GREATER_THAN_OR_EQUAL;
            case "lt", "<" -> Operators.LESS_THAN;
            case "lte", "le", "<=" -> Operators.LESS_THAN_OR_EQUAL;
            case "range" -> Operators.BETWEEN;
            case "oneof", "one_of" -> Operators.IN;
            case "notoneof", "not_one_of" -> Operators.NOT_IN;
            case "null", "isnull" -> Operators.IS_NULL;
            case "notnull", "isnotnull" -> Operators.IS_NOT_NULL;
            case "any", "*", "all" -> Operators.ANYTHING;
            default -> trimmed; // 保留原值，讓 validator 報錯
        };
    }

    // ================================================================
    // 嵌套值展開（LLM 常見格式問題）
    // ================================================================

    /**
     * 展開 LLM 包裝的嵌套值物件。
     *
     * 常見模式：
     *   {"type":"integer","value":60}  → 60
     *   {"enum":"reject"}             → "reject"
     *   {"value":"text"}              → "text"
     *   {"string":"hello"}            → "hello"
     *   {"number":42}                 → 42
     *   {"boolean":true}              → true
     *
     * 也處理陣列中的嵌套值（例如 between / in 的值）。
     */

    /**
     * 根據 typeRef 回傳適當的預設值，用於補全 LLM 省略的 null result。
     */
    private Object getDefaultValueForType(String typeRef) {
        if (typeRef == null) return "N/A";
        return switch (typeRef.toUpperCase()) {
            case "INTEGER" -> 0;
            case "DECIMAL" -> 0.0;
            case "BOOLEAN" -> false;
            default -> "N/A";  // STRING, ENUM 等用 "N/A"
        };
    }

    @SuppressWarnings("unchecked")
    private Object unwrapNestedValue(Object value) {
        if (value == null) return null;

        // Map 類型：LLM 把值包成 {"type":"...","value":...} 或 {"enum":"..."}
        if (value instanceof Map<?, ?> map) {
            // {"value": X} 或 {"type":"...","value": X}
            if (map.containsKey("value")) {
                Object inner = map.get("value");
                log.debug("unwrap: {{value:{}}} → {}", inner, inner);
                return unwrapNestedValue(inner); // 遞迴展開
            }
            // {"enum": "reject"}
            if (map.containsKey("enum")) {
                Object inner = map.get("enum");
                log.debug("unwrap: {{enum:{}}} → {}", inner, inner);
                return inner instanceof String ? inner : String.valueOf(inner);
            }
            // {"string": "text"}
            if (map.containsKey("string")) {
                return String.valueOf(map.get("string"));
            }
            // {"number": 42} 或 {"integer": 42}
            if (map.containsKey("number")) return map.get("number");
            if (map.containsKey("integer")) return map.get("integer");
            if (map.containsKey("decimal")) return map.get("decimal");
            // {"boolean": true}
            if (map.containsKey("boolean")) return map.get("boolean");

            // {"typeRef":"STRING","allowedValues":["reject"]} → "reject"
            // {"typeRef":"NUMBER","allowedValues":[60]} → 60
            // {"typeRef":"BOOLEAN","allowedValues":[true]} → true
            if (map.containsKey("allowedValues") || map.containsKey("allowedvalues")) {
                Object allowed = map.containsKey("allowedValues") ? map.get("allowedValues") : map.get("allowedvalues");
                if (allowed instanceof List<?> avList && !avList.isEmpty()) {
                    Object first = avList.get(0);
                    // 如果 allowedValues 只有一個元素且不是 List，直接取值
                    if (avList.size() == 1 && !(first instanceof List)) {
                        log.debug("unwrap: allowedValues[0] → {}", first);
                        return first;
                    }
                    // 如果是 between 格式 [[min, max]]，展開成 [min, max]
                    if (avList.size() == 1 && first instanceof List<?> range) {
                        log.debug("unwrap: allowedValues[[min,max]] → {}", range);
                        return new ArrayList<>(range);
                    }
                    // 多個值：用於 in/notIn 等
                    if (avList.size() > 1) {
                        log.debug("unwrap: allowedValues → list of {}", avList.size());
                        return new ArrayList<>(avList);
                    }
                }
                return value;
            }

            // 只有一個 key 的 map → 取其值
            if (map.size() == 1) {
                Object single = map.values().iterator().next();
                log.debug("unwrap: single-key map → {}", single);
                return unwrapNestedValue(single);
            }

            // 有 2 個 key 但其中一個是 typeRef → 忽略 typeRef，取另一個
            if (map.size() == 2 && map.containsKey("typeRef")) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!"typeRef".equals(entry.getKey())) {
                        log.debug("unwrap: typeRef+{} → {}", entry.getKey(), entry.getValue());
                        return unwrapNestedValue(entry.getValue());
                    }
                }
            }

            log.warn("unwrap: 無法辨識的巢狀值格式 {}", map.keySet());
            return value; // 無法辨識，保留原樣
        }

        // List 類型：遞迴展開每個元素（between [{"value":1},{"value":2}] 等）
        if (value instanceof List<?> list) {
            List<Object> unwrapped = new ArrayList<>();
            boolean changed = false;
            for (Object item : list) {
                Object result = unwrapNestedValue(item);
                unwrapped.add(result);
                if (result != item) changed = true;
            }
            return changed ? unwrapped : value;
        }

        return value;
    }

    // ================================================================
    // 值修復
    // ================================================================

    /**
     * BOOLEAN 欄位的 value 如果是字串 "true"/"false"，自動修正為 boolean。
     */
    private void repairBooleanValue(Condition cond, List<FieldDef> inputs) {
        if (cond.getField() == null || cond.getValue() == null) return;
        String typeRef = findTypeRef(cond.getField(), inputs);
        if (!"BOOLEAN".equals(typeRef)) return;

        Object val = cond.getValue();
        if (val instanceof String strVal) {
            // 英文
            if ("true".equalsIgnoreCase(strVal) || "yes".equalsIgnoreCase(strVal)
                    || "\u662f".equals(strVal) || "\u6709".equals(strVal)) {
                cond.setValue(true);
                log.debug("Auto-repair: {} BOOLEAN value '{}' → true", cond.getField(), strVal);
            } else if ("false".equalsIgnoreCase(strVal) || "no".equalsIgnoreCase(strVal)
                    || "\u5426".equals(strVal) || "\u7121".equals(strVal) || "\u6c92\u6709".equals(strVal)) {
                cond.setValue(false);
                log.debug("Auto-repair: {} BOOLEAN value '{}' → false", cond.getField(), strVal);
            }
        }
    }

    /**
     * INTEGER/DECIMAL 欄位的 value 如果是字串數字，自動轉為數值。
     */
    private void repairNumericValue(Condition cond, List<FieldDef> inputs) {
        if (cond.getField() == null || cond.getValue() == null) return;
        String typeRef = findTypeRef(cond.getField(), inputs);
        if (typeRef == null) return;

        if ("INTEGER".equals(typeRef) || "DECIMAL".equals(typeRef)) {
            Object val = cond.getValue();
            if (val instanceof String strVal) {
                try {
                    if ("INTEGER".equals(typeRef)) {
                        cond.setValue(Long.parseLong(strVal.trim()));
                        log.debug("Auto-repair: {} INTEGER value '{}' → {}", cond.getField(), strVal, cond.getValue());
                    } else {
                        cond.setValue(Double.parseDouble(strVal.trim()));
                        log.debug("Auto-repair: {} DECIMAL value '{}' → {}", cond.getField(), strVal, cond.getValue());
                    }
                } catch (NumberFormatException e) {
                    log.warn("無法自動修復 {} 的值 '{}'（typeRef={}）", cond.getField(), strVal, typeRef);
                }
            }
            // 修復 between value（陣列中的元素）
            if (val instanceof List && "between".equals(cond.getOperator())) {
                List<?> listVal = (List<?>) val;
                List<Object> fixed = new ArrayList<>();
                for (Object item : listVal) {
                    if (item instanceof String s) {
                        try {
                            fixed.add("INTEGER".equals(typeRef) ? Long.parseLong(s.trim()) : Double.parseDouble(s.trim()));
                        } catch (NumberFormatException e) {
                            fixed.add(item);
                        }
                    } else {
                        fixed.add(item);
                    }
                }
                cond.setValue(fixed);
            }
        }
    }

    /**
     * result 的數值型態修復。
     */
    private void repairResultNumericValue(Result res, List<FieldDef> outputs) {
        if (res.getField() == null || res.getValue() == null) return;
        String typeRef = findTypeRef(res.getField(), outputs);
        if (typeRef == null) return;

        Object val = res.getValue();
        if (val instanceof String strVal) {
            try {
                if ("INTEGER".equals(typeRef)) {
                    res.setValue(Long.parseLong(strVal.trim()));
                    log.debug("Auto-repair result: {} INTEGER '{}' → {}", res.getField(), strVal, res.getValue());
                } else if ("DECIMAL".equals(typeRef)) {
                    res.setValue(Double.parseDouble(strVal.trim()));
                    log.debug("Auto-repair result: {} DECIMAL '{}' → {}", res.getField(), strVal, res.getValue());
                } else if ("BOOLEAN".equals(typeRef)) {
                    if ("true".equalsIgnoreCase(strVal)) res.setValue(true);
                    else if ("false".equalsIgnoreCase(strVal)) res.setValue(false);
                }
            } catch (NumberFormatException e) {
                // 無法修復，留給 validator 報錯
            }
        }
    }

    // ================================================================
    // 重複規則移除
    // ================================================================

    private void deduplicateRules(Rule rule) {
        if (rule.getRules() == null || rule.getRules().size() < 2) return;
        Set<String> seen = new LinkedHashSet<>();
        List<RuleRow> unique = new ArrayList<>();
        int removed = 0;
        for (RuleRow row : rule.getRules()) {
            String fp = row.getConditions().toString() + "|" + row.getResults().toString();
            if (seen.add(fp)) {
                unique.add(row);
            } else {
                removed++;
                log.warn("Dedup: 移除重複規則 {} (conditions+results 與前面的規則完全相同)", row.getRuleId());
            }
        }
        if (removed > 0) {
            rule.setRules(unique);
        }
    }

    // ================================================================
    // 排序
    // ================================================================

    private void sortByPriority(Rule rule) {
        rule.getRules().sort(Comparator.comparingInt(r -> r.getPriority() != null ? r.getPriority() : Integer.MAX_VALUE));
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private String findTypeRef(String fieldName, List<FieldDef> fields) {
        return fields.stream()
                .filter(f -> fieldName.equals(f.getName()))
                .map(FieldDef::getTypeRef)
                .findFirst().orElse(null);
    }

    private void ensureMutableConditionValue(Condition cond) {
        if (cond.getValue() instanceof List<?> original) {
            if (!(original instanceof ArrayList)) {
                cond.setValue(new ArrayList<>(original));
            }
        }
    }

    /**
     * 確保 List 為可變（避免 List.of() / Collections.unmodifiableList 造成 UnsupportedOperationException）。
     */
    <T> List<T> ensureMutableList(List<T> list) {
        if (list == null) return new ArrayList<>();
        if (list instanceof ArrayList) return list;
        return new ArrayList<>(list);
    }

    private boolean isNullOrEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    // ================================================================
    // v3.14：extensions 正規化（第 9 趟）
    // ================================================================

    /**
     * 對 envelope.extensions 進行 idempotent 正規化。
     *
     * 行為（每個非 null sub-list 各自處理）：
     *   1. globalGuards：trim description；自動補 guardId = G01..Gnn。
     *   2. fieldOr：自動補 orId = FOR01..FORnn；normalize predicate.operator；
     *      若 fields 不足 2 欄則移除（validator 不會再見到）。
     *   3. groupings：自動補 groupId = RG01..RGnn；trim title。
     *   4. footnotes：trim text；移除 text 空白者。
     *   5. ruleStatus：缺 status 預設 ACTIVE；重複 ruleId 留第一筆並 WARN log。
     *   6. 清空後的 list 設回 null，配合 @JsonInclude(NON_NULL) 從 JSON 移除。
     *
     * Null-safe：若 envelope.extensions == null 直接 return，無 log、無分配。
     */
    private void normalizeExtensions(RuleEnvelope envelope) {
        if (envelope.getExtensions() == null) return;
        RuleEnvelopeExtensions ext = envelope.getExtensions();

        normalizeGlobalGuards(ext);
        normalizeFieldOr(ext);
        normalizeGroupings(ext);
        normalizeFootnotes(ext);
        normalizeRuleStatusList(ext);
    }

    private void normalizeGlobalGuards(RuleEnvelopeExtensions ext) {
        List<GlobalGuard> guards = ext.getGlobalGuards();
        if (guards == null) return;
        int seq = 1;
        for (GlobalGuard g : guards) {
            if (g == null) continue;
            if (g.getGuardId() == null || g.getGuardId().isBlank()) {
                g.setGuardId(String.format("G%02d", seq));
            }
            if (g.getDescription() != null) {
                g.setDescription(g.getDescription().trim());
            }
            seq++;
        }
        if (guards.isEmpty()) ext.setGlobalGuards(null);
    }

    private void normalizeFieldOr(RuleEnvelopeExtensions ext) {
        List<FieldOrSpec> fieldOr = ext.getFieldOr();
        if (fieldOr == null) return;
        List<FieldOrSpec> kept = new ArrayList<>();
        int seq = 1;
        for (FieldOrSpec spec : fieldOr) {
            if (spec == null) continue;
            // fields 需 ≥ 2 欄才有意義；不足者丟棄並 WARN
            if (spec.getFields() == null || spec.getFields().size() < 2) {
                log.warn("normalizeFieldOr: 丟棄 orId={} (fields 數量 < 2)", spec.getOrId());
                continue;
            }
            if (spec.getOrId() == null || spec.getOrId().isBlank()) {
                spec.setOrId(String.format("FOR%02d", seq));
            }
            // 重用既有 normalizeOperator helper（eq → equals 等）
            if (spec.getPredicate() != null && spec.getPredicate().getOperator() != null) {
                spec.getPredicate().setOperator(normalizeOperator(spec.getPredicate().getOperator()));
            }
            kept.add(spec);
            seq++;
        }
        ext.setFieldOr(kept.isEmpty() ? null : kept);
    }

    private void normalizeGroupings(RuleEnvelopeExtensions ext) {
        List<RuleGrouping> groupings = ext.getGroupings();
        if (groupings == null) return;
        int seq = 1;
        for (RuleGrouping g : groupings) {
            if (g == null) continue;
            if (g.getGroupId() == null || g.getGroupId().isBlank()) {
                g.setGroupId(String.format("RG%02d", seq));
            }
            if (g.getTitle() != null) {
                g.setTitle(g.getTitle().trim());
            }
            seq++;
        }
        if (groupings.isEmpty()) ext.setGroupings(null);
    }

    private void normalizeFootnotes(RuleEnvelopeExtensions ext) {
        List<Footnote> footnotes = ext.getFootnotes();
        if (footnotes == null) return;
        List<Footnote> kept = new ArrayList<>();
        for (Footnote f : footnotes) {
            if (f == null) continue;
            if (f.getText() != null) {
                f.setText(f.getText().trim());
            }
            if (f.getText() == null || f.getText().isBlank()) {
                log.debug("normalizeFootnotes: 丟棄 marker={}（text 空白）", f.getMarker());
                continue;
            }
            kept.add(f);
        }
        ext.setFootnotes(kept.isEmpty() ? null : kept);
    }

    private void normalizeRuleStatusList(RuleEnvelopeExtensions ext) {
        List<RuleStatus> statuses = ext.getRuleStatus();
        if (statuses == null) return;
        Set<String> seenIds = new LinkedHashSet<>();
        List<RuleStatus> kept = new ArrayList<>();
        for (RuleStatus s : statuses) {
            if (s == null) continue;
            if (s.getStatus() == null) {
                s.setStatus(RuleStatus.Lifecycle.ACTIVE);
            }
            if (s.getRuleId() != null && !seenIds.add(s.getRuleId())) {
                log.warn("normalizeRuleStatusList: 重複 ruleId={}，保留首筆並丟棄後續", s.getRuleId());
                continue;
            }
            kept.add(s);
        }
        ext.setRuleStatus(kept.isEmpty() ? null : kept);
    }
}
