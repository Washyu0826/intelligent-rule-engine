package com.ruleengine.rules.service.bounds;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.service.converter.TreeToTableConverter;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 精算邊界：業務規則送審時逐列檢查，不得越過精算設定的上下限與禁止組合（Q28／Q32）。
 *
 * <p>邊界檔預設讀 classpath 的合成示範檔；正式環境以 {@code rules.bounds.file} 指向本地檔案。
 * 決策樹先攤平成表再檢查。</p>
 */
@Service
@Slf4j
public class BoundsService {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final TreeToTableConverter treeToTableConverter;

    @Value("${rules.bounds.enabled:true}")
    private boolean enabled;

    @Value("${rules.bounds.file:}")
    private String overrideFile;

    private BoundsFile bounds = new BoundsFile();

    public BoundsService(TreeToTableConverter treeToTableConverter) {
        this.treeToTableConverter = treeToTableConverter;
    }

    @PostConstruct
    public void load() {
        Resource res = overrideFile != null && !overrideFile.isBlank()
                ? new FileSystemResource(overrideFile)
                : new ClassPathResource("bounds/bounds-sample.yml");
        try (InputStream is = res.getInputStream()) {
            BoundsFile file = yaml.readValue(is, BoundsFile.class);
            if (file.getConstraints() == null) file.setConstraints(List.of());
            this.bounds = file;
            log.info("Bounds loaded | constraints={} | source={}", file.getConstraints().size(), res.getDescription());
        } catch (Exception e) {
            log.error("精算邊界檔載入失敗（{}），邊界檢查將視為沒有限制", res.getDescription(), e);
            this.bounds = new BoundsFile();
        }
    }

    public boolean isEnabled() { return enabled; }

    public BoundsFile current() { return bounds; }

    public record Violation(String constraintId, String description, String ruleId, String detail) {}

    public record BoundsReport(boolean checked, int constraintCount, List<Violation> violations, String escalateTo) {}

    public BoundsReport check(RuleEnvelope envelope) {
        if (!enabled || bounds.getConstraints().isEmpty()) {
            return new BoundsReport(false, 0, List.of(), bounds.getEscalateTo());
        }
        RuleEnvelope table = envelope;
        if ("DecisionTree".equalsIgnoreCase(envelope.getRuleType())) {
            try {
                table = treeToTableConverter.convert(envelope);
            } catch (Exception e) {
                log.warn("決策樹攤平失敗，略過邊界檢查：{}", e.getMessage());
                return new BoundsReport(false, bounds.getConstraints().size(), List.of(), bounds.getEscalateTo());
            }
        } else if (!"DecisionTable".equalsIgnoreCase(envelope.getRuleType())) {
            return new BoundsReport(false, bounds.getConstraints().size(), List.of(), bounds.getEscalateTo());
        }
        List<RuleRow> rows = table.getRule() == null || table.getRule().getRules() == null
                ? List.of() : table.getRule().getRules();

        List<Violation> violations = new ArrayList<>();
        for (Constraint c : bounds.getConstraints()) {
            for (RuleRow row : rows) {
                checkRow(c, row, violations);
            }
        }
        return new BoundsReport(true, bounds.getConstraints().size(), violations, bounds.getEscalateTo());
    }

    private static void checkRow(Constraint c, RuleRow row, List<Violation> out) {
        String type = c.getType() == null ? "bound" : c.getType().toLowerCase(Locale.ROOT);
        if ("bound".equals(type)) {
            Result r = findResult(row, c.getFields());
            if (r == null || !(r.getValue() instanceof Number n)) return;
            double v = n.doubleValue();
            if (c.getMax() != null && v > c.getMax()) {
                out.add(new Violation(c.getId(), c.getDescription(), row.getRuleId(),
                        r.getField() + " = " + v + "，超過上限 " + c.getMax()));
            }
            if (c.getMin() != null && v < c.getMin()) {
                out.add(new Violation(c.getId(), c.getDescription(), row.getRuleId(),
                        r.getField() + " = " + v + "，低於下限 " + c.getMin()));
            }
        } else if ("forbid".equals(type)) {
            if (c.getWhen() == null || c.getOutput() == null) return;
            Result r = findResult(row, c.getOutput().getFields());
            if (r == null || r.getValue() == null) return;
            Set<String> forbidden = c.getOutput().getValues() == null ? Set.of()
                    : c.getOutput().getValues().stream().map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
            if (!forbidden.contains(r.getValue().toString().toUpperCase(Locale.ROOT))) return;
            Condition cond = findCondition(row, c.getWhen().getFields());
            if (cond != null && rangeOverlaps(cond, c.getWhen().getMin(), c.getWhen().getMax())) {
                out.add(new Violation(c.getId(), c.getDescription(), row.getRuleId(),
                        "當 " + cond.getField() + " " + cond.getOperator() + " " + cond.getValue()
                                + " 時 " + r.getField() + " = " + r.getValue() + "，落在精算禁止的範圍"));
            }
        }
    }

    private static Result findResult(RuleRow row, List<String> names) {
        if (row.getResults() == null || names == null) return null;
        for (Result r : row.getResults()) {
            if (r.getField() != null && names.stream().anyMatch(n -> n.equalsIgnoreCase(r.getField()))) return r;
        }
        return null;
    }

    private static Condition findCondition(RuleRow row, List<String> names) {
        if (row.getConditions() == null || names == null) return null;
        for (Condition c : row.getConditions()) {
            if (c.getField() != null && names.stream().anyMatch(n -> n.equalsIgnoreCase(c.getField()))) return c;
        }
        return null;
    }

    /** 條件覆蓋的區間是否與 [min, max]（任一端可空）有交集；anything／無法判讀視為有交集（保守）。 */
    static boolean rangeOverlaps(Condition cond, Double min, Double max) {
        double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY;
        String op = cond.getOperator() == null ? "anything" : cond.getOperator();
        Object v = cond.getValue();
        try {
            switch (op) {
                case "equals" -> { lo = num(v); hi = lo; }
                case "between" -> {
                    List<?> l = (List<?>) v;
                    lo = num(l.get(0)); hi = num(l.get(1));
                }
                case "greaterThan" -> lo = Math.nextUp(num(v));
                case "greaterThanOrEqual" -> lo = num(v);
                case "lessThan" -> hi = Math.nextDown(num(v));
                case "lessThanOrEqual" -> hi = num(v);
                case "in" -> {
                    List<?> l = (List<?>) v;
                    lo = l.stream().mapToDouble(BoundsService::num).min().orElse(Double.NEGATIVE_INFINITY);
                    hi = l.stream().mapToDouble(BoundsService::num).max().orElse(Double.POSITIVE_INFINITY);
                }
                default -> { /* anything／notEquals／isNull：保守視為全域 */ }
            }
        } catch (Exception e) {
            return true;
        }
        double bMin = min == null ? Double.NEGATIVE_INFINITY : min;
        double bMax = max == null ? Double.POSITIVE_INFINITY : max;
        return hi >= bMin && lo <= bMax;
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString().trim());
    }

    // ───────────── YAML 結構 ─────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BoundsFile {
        private String owner;
        private String escalateTo;
        private List<Constraint> constraints = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Constraint {
        private String id;
        private String type;
        private String description;
        private List<String> fields;
        private Double max;
        private Double min;
        private When when;
        private Output output;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class When {
        private List<String> fields;
        private Double min;
        private Double max;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Output {
        private List<String> fields;
        private List<String> values;
    }
}
