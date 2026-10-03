package com.ruleengine.rules.service.analyzer;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.service.engine.ExecutionResult;
import com.ruleengine.rules.service.engine.RuleEngineRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * 合成資料批次回歸（Q23）：依欄位型別與兩版規則用到的邊界值組合出測試輸入，
 * 新舊版各跑一次，列出結果改變的案件。純合成，不碰任何真實資料。
 */
@Service
@RequiredArgsConstructor
public class RegressionService {

    private static final int DEFAULT_SAMPLES = 200;
    private static final int MAX_EXAMPLES = 10;

    private final RuleEngineRunner engine;

    public record Outcome(boolean matched, Map<String, Object> outputs, String hitRuleId) {}

    public record Change(Map<String, Object> input, Outcome before, Outcome after) {}

    public record Report(int sampleCount, int changedCount, double changeRate,
                         int newlyMatched, int newlyUnmatched, int outputChanged,
                         List<Change> examples) {}

    public Report run(RuleEnvelope before, RuleEnvelope after) {
        return run(before, after, DEFAULT_SAMPLES);
    }

    public Report run(RuleEnvelope before, RuleEnvelope after, int maxSamples) {
        List<Map<String, Object>> inputs = synthesize(before, after, maxSamples);
        int changed = 0, newlyMatched = 0, newlyUnmatched = 0, outputChanged = 0;
        List<Change> examples = new ArrayList<>();
        for (Map<String, Object> input : inputs) {
            Outcome b = outcome(engine.evaluate(before, input));
            Outcome a = outcome(engine.evaluate(after, input));
            boolean diff;
            if (b.matched() != a.matched()) {
                diff = true;
                if (a.matched()) newlyMatched++; else newlyUnmatched++;
            } else {
                diff = b.matched() && !sameOutputs(b.outputs(), a.outputs());
                if (diff) outputChanged++;
            }
            if (diff) {
                changed++;
                if (examples.size() < MAX_EXAMPLES) examples.add(new Change(input, b, a));
            }
        }
        double rate = inputs.isEmpty() ? 0 : (double) changed / inputs.size();
        return new Report(inputs.size(), changed, rate, newlyMatched, newlyUnmatched, outputChanged, examples);
    }

    private static Outcome outcome(ExecutionResult r) {
        Map<String, Object> outputs = r.getResults() == null ? Map.of() : new LinkedHashMap<>(r.getResults());
        return new Outcome(r.isMatched(), outputs, r.getHitRuleId());
    }

    private static boolean sameOutputs(Map<String, Object> a, Map<String, Object> b) {
        if (a.size() != b.size()) return false;
        for (var e : a.entrySet()) {
            if (!Objects.equals(norm(e.getValue()), norm(b.get(e.getKey())))) return false;
        }
        return true;
    }

    private static String norm(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return v.toString();
    }

    // ───────────── 合成輸入 ─────────────

    /** 每個欄位的候選值 = 型別固有值（布林、列舉）∪ 兩版條件用到的邊界值及其鄰近值；再做笛卡爾積，超過上限時固定種子抽樣。 */
    List<Map<String, Object>> synthesize(RuleEnvelope before, RuleEnvelope after, int maxSamples) {
        Map<String, FieldDef> fields = new LinkedHashMap<>();
        for (RuleEnvelope e : List.of(before, after)) {
            if (e.getRule() != null && e.getRule().getInputs() != null) {
                for (FieldDef f : e.getRule().getInputs()) fields.putIfAbsent(f.getName(), f);
            }
        }
        if (fields.isEmpty()) return List.of();

        Map<String, List<Object>> candidates = new LinkedHashMap<>();
        for (FieldDef f : fields.values()) {
            candidates.put(f.getName(), candidateValues(f, before, after));
        }

        List<Map<String, Object>> all = new ArrayList<>();
        all.add(new LinkedHashMap<>());
        long total = 1;
        for (var e : candidates.entrySet()) {
            total *= Math.max(1, e.getValue().size());
            if (total > 50_000) break;
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> base : all) {
                for (Object v : e.getValue()) {
                    Map<String, Object> m = new LinkedHashMap<>(base);
                    m.put(e.getKey(), v);
                    next.add(m);
                }
            }
            all = next;
        }
        if (total > 50_000) {
            // 組合爆炸：改為隨機組合
            Random rnd = new Random(42);
            List<Map<String, Object>> sampled = new ArrayList<>();
            List<String> names = new ArrayList<>(candidates.keySet());
            for (int i = 0; i < maxSamples; i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String n : names) {
                    List<Object> vals = candidates.get(n);
                    m.put(n, vals.get(rnd.nextInt(vals.size())));
                }
                sampled.add(m);
            }
            return sampled;
        }
        if (all.size() > maxSamples) {
            Collections.shuffle(all, new Random(42));
            all = new ArrayList<>(all.subList(0, maxSamples));
        }
        return all;
    }

    private static List<Object> candidateValues(FieldDef f, RuleEnvelope before, RuleEnvelope after) {
        String type = f.getTypeRef() == null ? "STRING" : f.getTypeRef().toUpperCase();
        Set<Object> vals = new LinkedHashSet<>();
        switch (type) {
            case "BOOLEAN" -> { vals.add(Boolean.TRUE); vals.add(Boolean.FALSE); }
            case "ENUM" -> {
                if (f.getAllowedValues() != null) vals.addAll(f.getAllowedValues());
                collectStrings(f.getName(), before, vals);
                collectStrings(f.getName(), after, vals);
            }
            case "INTEGER", "DECIMAL" -> {
                TreeSet<Double> bounds = new TreeSet<>();
                collectNumbers(f.getName(), before, bounds);
                collectNumbers(f.getName(), after, bounds);
                boolean integer = "INTEGER".equals(type);
                double step = integer ? 1 : 0.5;
                for (double b : bounds) {
                    vals.add(box(b - step, integer));
                    vals.add(box(b, integer));
                    vals.add(box(b + step, integer));
                }
                // 邊界之外再鋪幾個分布點，讓群體統計（公平待遇）不只看臨界值
                if (!bounds.isEmpty()) {
                    double lo = bounds.first(), hi = bounds.last();
                    double span = Math.max(hi - lo, integer ? 10 : 1);
                    for (double q : new double[]{-0.25, 0.25, 0.5, 0.75, 1.25}) {
                        vals.add(box(lo + span * q, integer));
                    }
                }
                if (vals.isEmpty()) { vals.add(box(0, integer)); vals.add(box(1, integer)); }
            }
            default -> {
                collectStrings(f.getName(), before, vals);
                collectStrings(f.getName(), after, vals);
                if (vals.isEmpty()) vals.add("sample");
            }
        }
        return new ArrayList<>(vals);
    }

    private static Object box(double d, boolean integer) {
        return integer ? (Object) (long) Math.round(d) : (Object) d;
    }

    private static void collectNumbers(String field, RuleEnvelope e, Set<Double> out) {
        forEachCondition(e, field, c -> {
            Object v = c.getValue();
            if (v instanceof Number n) out.add(n.doubleValue());
            else if (v instanceof List<?> l) for (Object o : l) if (o instanceof Number n) out.add(n.doubleValue());
        });
    }

    private static void collectStrings(String field, RuleEnvelope e, Set<Object> out) {
        forEachCondition(e, field, c -> {
            Object v = c.getValue();
            if (v instanceof String s) out.add(s);
            else if (v instanceof List<?> l) for (Object o : l) if (o instanceof String s) out.add(s);
        });
    }

    private static void forEachCondition(RuleEnvelope e, String field, java.util.function.Consumer<Condition> fn) {
        if (e.getRule() == null) return;
        if (e.getRule().getRules() != null) {
            for (var row : e.getRule().getRules()) {
                if (row.getConditions() == null) continue;
                for (Condition c : row.getConditions()) if (field.equals(c.getField())) fn.accept(c);
            }
        }
        if (e.getRule().getRoot() != null) walk(e.getRule().getRoot(), field, fn);
        if (e.getRule().getScoringDimensions() != null) {
            for (var dim : e.getRule().getScoringDimensions()) {
                if (dim.getScoringRules() == null) continue;
                for (var sr : dim.getScoringRules()) {
                    Condition c = sr.getCondition();
                    if (c != null && field.equals(c.getField() != null ? c.getField() : dim.getField())) fn.accept(c);
                }
            }
        }
    }

    private static void walk(RuleEnvelope.TreeNode node, String field, java.util.function.Consumer<Condition> fn) {
        if (node == null) return;
        if (node.getCondition() != null && field.equals(node.getCondition().getField())) fn.accept(node.getCondition());
        if (node.getBranches() != null) {
            for (var b : node.getBranches()) {
                if (b.getCondition() != null && field.equals(b.getCondition().getField())) fn.accept(b.getCondition());
                walk(b.getChild(), field, fn);
            }
        }
        walk(node.getTrueBranch(), field, fn);
        walk(node.getFalseBranch(), field, fn);
    }
}
