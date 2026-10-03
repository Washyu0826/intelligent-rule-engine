package com.ruleengine.rules.service.exporter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.service.converter.TreeToTableConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RuleEnvelope → 標準 DMN 1.3 XML（OMG 20191111 model namespace）。
 *
 * <p>只有決策表有對應的 DMN 結構；決策樹先用 {@link TreeToTableConverter} 攤成表再匯出。
 * 條件寫成 FEEL unary tests（{@code [18..50]}、{@code "男","女"}、{@code > 5}、{@code -}）。
 * DATE 欄位以字串比對（{@code "2024-01-01"}），跨欄位 valueRef 沒有 DMN 對應，以 {@code -} 代替並列為警告。</p>
 */
@Component
@RequiredArgsConstructor
public class DmnExporter {

    public static final String DMN_NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";
    public static final String DECISION_ID = "decision";

    private final TreeToTableConverter treeToTableConverter;

    public record DmnExport(String xml, String decisionId, List<String> warnings) {}

    public DmnExport export(RuleEnvelope envelope) {
        return export(envelope, "rules-mcp-decision");
    }

    public DmnExport export(RuleEnvelope envelope, String name) {
        List<String> warnings = new ArrayList<>();
        RuleEnvelope table = envelope;
        if ("DecisionTree".equalsIgnoreCase(envelope.getRuleType())) {
            table = treeToTableConverter.convert(envelope);
            warnings.add("決策樹已先攤平成決策表再匯出（DMN 以決策表為單位）");
        } else if (!"DecisionTable".equalsIgnoreCase(envelope.getRuleType())) {
            throw new IllegalArgumentException("DMN 匯出只支援 DecisionTable 與 DecisionTree，收到：" + envelope.getRuleType());
        }
        RuleEnvelope.Rule rule = table.getRule();
        if (rule == null || rule.getInputs() == null || rule.getOutputs() == null) {
            throw new IllegalArgumentException("規則缺少 inputs／outputs，無法匯出 DMN");
        }

        Map<String, FieldDef> outputTypes = rule.getOutputs().stream()
                .collect(Collectors.toMap(FieldDef::getName, f -> f, (a, b) -> a, LinkedHashMap::new));

        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<definitions xmlns=\"").append(DMN_NS).append("\" id=\"definitions_").append(slug(name))
                .append("\" name=\"").append(esc(name)).append("\" namespace=\"http://rules-mcp-server/dmn\">\n");
        x.append("  <decision id=\"").append(DECISION_ID).append("\" name=\"").append(esc(name)).append("\">\n");
        x.append("    <decisionTable id=\"decisionTable\" hitPolicy=\"").append(hitPolicy(rule.getHitPolicy())).append("\">\n");

        for (FieldDef in : rule.getInputs()) {
            String id = slug(in.getName());
            x.append("      <input id=\"input_").append(id).append("\" label=\"").append(esc(in.getName())).append("\">\n");
            x.append("        <inputExpression id=\"inputExpression_").append(id).append("\" typeRef=\"")
                    .append(feelType(in.getTypeRef())).append("\">\n");
            x.append("          <text>").append(esc(in.getName())).append("</text>\n");
            x.append("        </inputExpression>\n");
            x.append("      </input>\n");
        }
        for (FieldDef out : rule.getOutputs()) {
            String id = slug(out.getName());
            x.append("      <output id=\"output_").append(id).append("\" label=\"").append(esc(out.getName()))
                    .append("\" name=\"").append(esc(out.getName())).append("\" typeRef=\"")
                    .append(feelType(out.getTypeRef())).append("\" />\n");
        }

        List<RuleRow> rows = new ArrayList<>(rule.getRules() == null ? List.of() : rule.getRules());
        rows.sort(Comparator.comparing(r -> r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()));
        int seq = 0;
        for (RuleRow row : rows) {
            seq++;
            String rid = row.getRuleId() == null ? "rule_" + seq : slug(row.getRuleId());
            x.append("      <rule id=\"").append(rid).append("\">\n");
            if (row.getRationale() != null && !row.getRationale().isBlank()) {
                x.append("        <description>").append(esc(row.getRationale())).append("</description>\n");
            }
            Map<String, Condition> byField = new LinkedHashMap<>();
            if (row.getConditions() != null) {
                for (Condition c : row.getConditions()) byField.put(c.getField(), c);
            }
            for (FieldDef in : rule.getInputs()) {
                Condition c = byField.get(in.getName());
                String entry = c == null ? "-" : unaryTest(c, in, rid, warnings);
                x.append("        <inputEntry id=\"").append(rid).append("_in_").append(slug(in.getName())).append("\">\n");
                x.append("          <text>").append(esc(entry)).append("</text>\n");
                x.append("        </inputEntry>\n");
            }
            Map<String, Object> results = new LinkedHashMap<>();
            if (row.getResults() != null) {
                for (Result r : row.getResults()) results.put(r.getField(), r.getValue());
            }
            for (FieldDef out : rule.getOutputs()) {
                x.append("        <outputEntry id=\"").append(rid).append("_out_").append(slug(out.getName())).append("\">\n");
                x.append("          <text>").append(esc(literal(results.get(out.getName()), outputTypes.get(out.getName())))).append("</text>\n");
                x.append("        </outputEntry>\n");
            }
            x.append("      </rule>\n");
        }

        x.append("    </decisionTable>\n");
        x.append("  </decision>\n");
        x.append("</definitions>\n");
        return new DmnExport(x.toString(), DECISION_ID, warnings);
    }

    // ───────────────────────────── FEEL ─────────────────────────────

    static String unaryTest(Condition c, FieldDef field, String ruleId, List<String> warnings) {
        String op = c.getOperator() == null ? "anything" : c.getOperator();
        if (c.getValueRef() != null && !c.getValueRef().isBlank()) {
            warnings.add(ruleId + "." + c.getField() + "：跨欄位 valueRef「" + c.getValueRef() + "」沒有 DMN 對應，以「-」代替");
            return "-";
        }
        Object v = c.getValue();
        return switch (op) {
            case "anything" -> "-";
            case "isNull" -> "null";
            case "isNotNull" -> "not(null)";
            case "equals" -> literal(v, field);
            case "notEquals" -> "not(" + literal(v, field) + ")";
            case "greaterThan" -> "> " + literal(v, field);
            case "greaterThanOrEqual" -> ">= " + literal(v, field);
            case "lessThan" -> "< " + literal(v, field);
            case "lessThanOrEqual" -> "<= " + literal(v, field);
            case "between" -> {
                List<?> pair = asList(v);
                if (pair.size() != 2) {
                    warnings.add(ruleId + "." + c.getField() + "：between 需要 [min,max]，以「-」代替");
                    yield "-";
                }
                yield "[" + literal(pair.get(0), field) + ".." + literal(pair.get(1), field) + "]";
            }
            case "in" -> asList(v).stream().map(o -> literal(o, field)).collect(Collectors.joining(","));
            case "notIn" -> "not(" + asList(v).stream().map(o -> literal(o, field)).collect(Collectors.joining(",")) + ")";
            default -> {
                warnings.add(ruleId + "." + c.getField() + "：operator「" + op + "」沒有 DMN 對應，以「-」代替");
                yield "-";
            }
        };
    }

    static String literal(Object v, FieldDef field) {
        if (v == null) return "null";
        String type = field == null || field.getTypeRef() == null ? "" : field.getTypeRef().toUpperCase();
        if (v instanceof Boolean b) return b.toString();
        if (v instanceof Number n) {
            if ("INTEGER".equals(type) || n.doubleValue() == Math.rint(n.doubleValue())) return String.valueOf(n.longValue());
            return n.toString();
        }
        String s = v.toString();
        if ("BOOLEAN".equals(type)) return s.toLowerCase();
        if ("INTEGER".equals(type) || "DECIMAL".equals(type)) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<?> asList(Object v) {
        if (v instanceof List<?> l) return l;
        return v == null ? List.of() : List.of(v);
    }

    static String feelType(String typeRef) {
        if (typeRef == null) return "string";
        return switch (typeRef.toUpperCase()) {
            case "INTEGER" -> "integer";
            case "DECIMAL" -> "double";
            case "BOOLEAN" -> "boolean";
            default -> "string"; // STRING / ENUM / DATE（DATE 以字串比對）
        };
    }

    static String hitPolicy(String hp) {
        if (hp == null) return "FIRST";
        return switch (hp.toUpperCase()) {
            case "MULTI", "COLLECT" -> "COLLECT";
            case "UNIQUE" -> "UNIQUE";
            default -> "FIRST";
        };
    }

    public static String slug(String s) {
        String r = s == null ? "x" : s.replaceAll("[^A-Za-z0-9_]", "_");
        return r.isEmpty() || Character.isDigit(r.charAt(0)) ? "_" + r : r;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
