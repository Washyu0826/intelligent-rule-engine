package com.ruleengine.rules.service.analyzer;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把分析報出的缺口（欄位 → 區間描述）變成一列待填的決策表規則。
 *
 * <p>區間描述的格式由 {@link HyperRectangle#toConditionMap} 與 DmnAnalyzer 的取樣輸出決定：
 * 單值（{@code 42}、{@code true}、{@code 高}）、閉區間（{@code [36,50]}）、列舉清單（{@code [男,女]}）。
 * 結果欄一律不猜：決議類 ENUM 填「人工評估」這類保守值，其餘留空讓業務填。</p>
 */
@Component
public class GapCaseBuilder {

    private static final Pattern RULE_ID = Pattern.compile("^R(\\d+)$");
    private static final Set<String> MANUAL_MARKERS = Set.of(
            "人工評估", "人工核保", "人工審核", "人工覆核", "MANUAL_REVIEW", "MANUAL", "REVIEW", "REFER");

    public RuleRow build(RuleEnvelope envelope, Map<String, String> gapConditions) {
        RuleEnvelope.Rule rule = envelope.getRule();
        if (rule == null || rule.getInputs() == null) {
            throw new IllegalArgumentException("規則沒有 inputs，無法補列");
        }
        List<Condition> conditions = new ArrayList<>();
        for (FieldDef input : rule.getInputs()) {
            String desc = gapConditions == null ? null : gapConditions.get(input.getName());
            conditions.add(desc == null ? anything(input.getName()) : parse(input, desc));
        }

        List<Result> results = new ArrayList<>();
        if (rule.getOutputs() != null) {
            for (FieldDef output : rule.getOutputs()) {
                results.add(Result.builder().field(output.getName()).value(defaultFor(output)).build());
            }
        }

        int next = nextIndex(rule.getRules());
        return RuleRow.builder()
                .ruleId("R" + next)
                .priority(next)
                .conditions(conditions)
                .results(results)
                .rationale("系統依缺口補列，結果待填")
                .build();
    }

    private static Condition anything(String field) {
        return Condition.builder().field(field).operator("anything").build();
    }

    private static Condition parse(FieldDef input, String desc) {
        String typeRef = input.getTypeRef() == null ? "" : input.getTypeRef().toUpperCase();
        String d = desc.strip();
        boolean bracket = d.startsWith("[") && d.endsWith("]");
        List<String> parts = bracket ? List.of(d.substring(1, d.length() - 1).split("\\s*,\\s*")) : List.of(d);

        return switch (typeRef) {
            case "BOOLEAN" -> {
                if ("true".equalsIgnoreCase(d)) yield cond(input, "equals", Boolean.TRUE);
                if ("false".equalsIgnoreCase(d)) yield cond(input, "equals", Boolean.FALSE);
                yield anything(input.getName());
            }
            case "INTEGER" -> {
                if (bracket && parts.size() == 2) {
                    yield cond(input, "between", List.of(Long.parseLong(parts.get(0)), Long.parseLong(parts.get(1))));
                }
                yield cond(input, "equals", Long.parseLong(d));
            }
            case "DECIMAL" -> {
                if (bracket && parts.size() == 2) {
                    yield cond(input, "between", List.of(Double.parseDouble(parts.get(0)), Double.parseDouble(parts.get(1))));
                }
                yield cond(input, "equals", Double.parseDouble(d));
            }
            case "DATE" -> {
                if (bracket && parts.size() == 2) yield cond(input, "between", List.of(parts.get(0), parts.get(1)));
                yield cond(input, "equals", d);
            }
            default -> {
                if (bracket && parts.size() > 1) yield cond(input, "in", new ArrayList<>(parts));
                yield cond(input, "equals", bracket ? parts.get(0) : d);
            }
        };
    }

    private static Condition cond(FieldDef input, String operator, Object value) {
        return Condition.builder().field(input.getName()).operator(operator).value(value).build();
    }

    private static Object defaultFor(FieldDef output) {
        if (output.getAllowedValues() == null) return null;
        return output.getAllowedValues().stream()
                .filter(v -> MANUAL_MARKERS.contains(v.toUpperCase()))
                .findFirst().orElse(null);
    }

    private static int nextIndex(List<RuleRow> rows) {
        int max = 0;
        if (rows != null) {
            for (RuleRow r : rows) {
                if (r.getPriority() != null) max = Math.max(max, r.getPriority());
                if (r.getRuleId() != null) {
                    Matcher m = RULE_ID.matcher(r.getRuleId());
                    if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        }
        return max + 1;
    }
}
