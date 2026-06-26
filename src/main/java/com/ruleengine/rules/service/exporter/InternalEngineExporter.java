package com.ruleengine.rules.service.exporter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.12 — Group 集團內部規則引擎 stub Exporter。
 *
 * 用途：示範 RuleExporter 介面如何運作，並在 demo 階段提供 adapter pre-flight 報告：
 *   - 哪些 inputs 缺 fieldCode（adapter mapping 必填）
 *   - 哪些 ENUM 缺 externalCodes（adapter 對應集團代碼必填）
 *   - 哪些 condition 使用了非標準 operator（valueRef 跨欄位 / 相對日期）
 *   - audit metadata 是否齊全（effectiveDate / businessOwner）
 *
 * 此 exporter 不產生真實部署檔；輸出的 JSON 只是 placeholder shape，
 * 對應「集團規則引擎接收的 wrapper 結構」概念。正式接入時應由 RuleExporter
 * 多實作版本取代（如 GroupCoreEngineExporter v8.x）。
 *
 * 命名 stub 而非 final，是讓 reviewer 能一眼看出「介面有切好、契約有報告，
 * 但部署檔仍由下游團隊定義」。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalEngineExporter implements RuleExporter {

    private static final String ENGINE_NAME = "group-internal-engine";
    private static final String ENGINE_VERSION = "0.1-stub";
    private static final String FORMAT = "json";

    private final ObjectMapper objectMapper;

    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    @Override
    public String engineVersion() {
        return ENGINE_VERSION;
    }

    @Override
    public ExportResult export(RuleEnvelope envelope) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();

        if (envelope == null || envelope.getRule() == null) {
            warnings.add("envelope or rule is null; cannot export");
            return new ExportResult(ENGINE_NAME, ENGINE_VERSION, FORMAT, "{}", warnings, metadata);
        }

        Rule rule = envelope.getRule();
        Map<String, FieldDef> inputsByName = indexFields(rule.getInputs());

        checkInputMetadata(rule.getInputs(), warnings);
        checkAuditMetadata(envelope, warnings, metadata);
        Map<String, Object> body = buildEngineWrapper(envelope, inputsByName, warnings);

        metadata.put("ruleType", envelope.getRuleType());
        metadata.put("ruleCount", rule.getRules() == null ? 0 : rule.getRules().size());
        metadata.put("inputCount", rule.getInputs() == null ? 0 : rule.getInputs().size());

        String content;
        try {
            content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Failed to serialize internal-engine wrapper", e);
            warnings.add("serialization failed: " + e.getMessage());
            content = "{}";
        }

        return new ExportResult(ENGINE_NAME, ENGINE_VERSION, FORMAT, content, warnings, metadata);
    }

    // ================================================================
    // Pre-flight checks（adapter 真正接入時最先會問的問題）
    // ================================================================

    private void checkInputMetadata(List<FieldDef> inputs, List<String> warnings) {
        if (inputs == null) return;
        for (FieldDef input : inputs) {
            if (input.getFieldCode() == null || input.getFieldCode().isBlank()) {
                warnings.add("input \"" + input.getName()
                        + "\" 缺少 fieldCode；下游 adapter 需自行 mapping 至集團欄位代碼");
            }
            if ("ENUM".equals(input.getTypeRef())
                    && (input.getExternalCodes() == null || input.getExternalCodes().isEmpty())) {
                warnings.add("input \"" + input.getName()
                        + "\" 為 ENUM 但未提供 externalCodes；下游 adapter 需補齊代碼對照");
            }
            if ("DECIMAL".equals(input.getTypeRef()) && input.getScale() == null) {
                warnings.add("input \"" + input.getName()
                        + "\" 為 DECIMAL 但未指定 scale；金額/利率類欄位建議顯式宣告精度");
            }
        }
    }

    private void checkAuditMetadata(RuleEnvelope envelope, List<String> warnings,
                                     Map<String, Object> metadata) {
        RuleEnvelope.AuditMetadata audit = envelope.getAudit();
        if (audit == null) {
            warnings.add("envelope.audit 缺失；下游引擎需要 effectiveDate / businessOwner 才能上線");
            return;
        }
        if (audit.getEffectiveDate() == null) {
            warnings.add("audit.effectiveDate 未設定；引擎部署排程需要此欄位");
        } else {
            metadata.put("effectiveDate", audit.getEffectiveDate());
        }
        if (audit.getExpiryDate() != null) {
            metadata.put("expiryDate", audit.getExpiryDate());
        }
        if (audit.getBusinessOwner() == null || audit.getBusinessOwner().isBlank()) {
            warnings.add("audit.businessOwner 未設定；建議指派業務負責人（核保/精算/理賠主管）");
        } else {
            metadata.put("businessOwner", audit.getBusinessOwner());
        }
        if (audit.getBusinessDomain() != null) {
            metadata.put("businessDomain", audit.getBusinessDomain());
        }
    }

    // ================================================================
    // Wrapper 結構（示意；正式接入由內部規則引擎定義）
    // ================================================================

    private Map<String, Object> buildEngineWrapper(RuleEnvelope envelope,
                                                    Map<String, FieldDef> inputsByName,
                                                    List<String> warnings) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("engineName", ENGINE_NAME);
        wrapper.put("engineVersion", ENGINE_VERSION);
        wrapper.put("schemaVersion", envelope.getSchemaVersion());
        wrapper.put("ruleType", envelope.getRuleType());
        wrapper.put("hitPolicy", envelope.getRule().getHitPolicy());

        wrapper.put("fields", buildFields(envelope.getRule().getInputs()));

        if (envelope.getRule().getRules() != null) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RuleRow row : envelope.getRule().getRules()) {
                rows.add(buildRow(row, inputsByName, warnings));
            }
            wrapper.put("rules", rows);
        }
        return wrapper;
    }

    private List<Map<String, Object>> buildFields(List<FieldDef> inputs) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (inputs == null) return result;
        for (FieldDef f : inputs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("logicalName", f.getName());
            entry.put("fieldCode", f.getFieldCode() != null ? f.getFieldCode() : "<TODO_ADAPTER_MAPPING>");
            entry.put("dataType", f.getTypeRef());
            if (f.getUnit() != null) entry.put("unit", f.getUnit());
            if (f.getScale() != null) entry.put("scale", f.getScale());
            if (Boolean.TRUE.equals(f.getNullable())) entry.put("nullable", true);
            if (f.getAllowedValues() != null) entry.put("allowedValues", f.getAllowedValues());
            if (f.getExternalCodes() != null) entry.put("externalCodes", f.getExternalCodes());
            result.add(entry);
        }
        return result;
    }

    private Map<String, Object> buildRow(RuleRow row, Map<String, FieldDef> inputsByName,
                                          List<String> warnings) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ruleId", row.getRuleId());
        entry.put("priority", row.getPriority());

        List<Map<String, Object>> conds = new ArrayList<>();
        if (row.getConditions() != null) {
            for (Condition c : row.getConditions()) {
                conds.add(buildCondition(row.getRuleId(), c, inputsByName, warnings));
            }
        }
        entry.put("conditions", conds);

        List<Map<String, Object>> results = new ArrayList<>();
        if (row.getResults() != null) {
            for (var r : row.getResults()) {
                Map<String, Object> rEntry = new LinkedHashMap<>();
                rEntry.put("field", r.getField());
                rEntry.put("value", r.getValue());
                results.add(rEntry);
            }
        }
        entry.put("results", results);
        return entry;
    }

    private Map<String, Object> buildCondition(String ruleId, Condition c,
                                                Map<String, FieldDef> inputsByName,
                                                List<String> warnings) {
        Map<String, Object> entry = new LinkedHashMap<>();
        FieldDef fieldDef = c.getField() != null ? inputsByName.get(c.getField()) : null;
        entry.put("logicalField", c.getField());
        entry.put("fieldCode", fieldDef != null && fieldDef.getFieldCode() != null
                ? fieldDef.getFieldCode() : "<TODO_ADAPTER_MAPPING>");
        entry.put("operator", c.getOperator());

        if (c.getValueRef() != null && !c.getValueRef().isBlank()) {
            // valueRef 表達跨欄位 / 相對日期 — 集團引擎大多需 adapter 額外編排
            warnings.add(ruleId + " condition[" + c.getField()
                    + "] 使用 valueRef=\"" + c.getValueRef()
                    + "\"，需 adapter 編排運行期解析（多數集團引擎不支援原生跨欄位 operator）");
            entry.put("valueRef", c.getValueRef());
        } else {
            entry.put("value", c.getValue());
        }
        return entry;
    }

    private Map<String, FieldDef> indexFields(List<FieldDef> inputs) {
        Map<String, FieldDef> map = new LinkedHashMap<>();
        if (inputs == null) return map;
        for (FieldDef f : inputs) {
            if (f.getName() != null) {
                map.put(f.getName(), f);
            }
        }
        return map;
    }
}
