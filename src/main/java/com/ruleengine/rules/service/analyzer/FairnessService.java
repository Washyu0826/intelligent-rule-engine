package com.ruleengine.rules.service.analyzer;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.glossary.GlossaryEntry;
import com.ruleengine.rules.service.engine.ExecutionResult;
import com.ruleengine.rules.service.engine.RuleEngineRunner;
import com.ruleengine.rules.service.glossary.GlossaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 保戶公平待遇分析（Q33）：對敏感維度（年齡、性別、職業，由詞彙包 semantic=sensitive-dimension 標出）
 * 用合成案件跑規則，列出各群體的結果分布；某個結果在群體間的比例差距超過閾值就警示。
 * 純統計、純合成資料，給審核人看風險，不替他下判斷。
 */
@Service
@RequiredArgsConstructor
public class FairnessService {

    private final RuleEngineRunner engine;
    private final RegressionService regression;
    private final GlossaryService glossary;

    @Value("${rules.fairness.threshold:0.3}")
    private double threshold;

    @Value("${rules.fairness.samples:300}")
    private int samples;

    public record Group(String value, int count, Map<String, Integer> outcomes, Map<String, Double> rates) {}

    public record Dimension(String field, String outputField, List<Group> groups, String widestOutcome,
                            double maxGap, boolean warning) {}

    public record Report(int sampleCount, double threshold, List<Dimension> dimensions, boolean anyWarning) {}

    public Report analyze(RuleEnvelope envelope) {
        RuleEnvelope.Rule rule = envelope.getRule();
        if (rule == null || rule.getInputs() == null || rule.getOutputs() == null) {
            return new Report(0, threshold, List.of(), false);
        }
        List<FieldDef> sensitive = rule.getInputs().stream().filter(this::isSensitive).toList();
        FieldDef outputField = pickOutcomeField(rule.getOutputs());
        if (sensitive.isEmpty() || outputField == null) {
            return new Report(0, threshold, List.of(), false);
        }

        List<Map<String, Object>> inputs = regression.synthesize(envelope, envelope, samples);
        List<Map.Entry<Map<String, Object>, ExecutionResult>> runs = new ArrayList<>();
        for (Map<String, Object> in : inputs) runs.add(Map.entry(in, engine.evaluate(envelope, in)));

        List<Dimension> dims = new ArrayList<>();
        boolean any = false;
        for (FieldDef f : sensitive) {
            Map<String, Map<String, Integer>> byGroup = new TreeMap<>();
            Map<String, Integer> totals = new TreeMap<>();
            for (var run : runs) {
                String g = groupLabel(f, run.getKey().get(f.getName()));
                String outcome = outcomeOf(run.getValue(), outputField.getName());
                byGroup.computeIfAbsent(g, k -> new TreeMap<>()).merge(outcome, 1, Integer::sum);
                totals.merge(g, 1, Integer::sum);
            }
            List<Group> groups = new ArrayList<>();
            Set<String> outcomes = byGroup.values().stream().flatMap(m -> m.keySet().stream()).collect(Collectors.toSet());
            for (var e : byGroup.entrySet()) {
                Map<String, Double> rates = new LinkedHashMap<>();
                for (String o : outcomes) {
                    rates.put(o, e.getValue().getOrDefault(o, 0) / (double) totals.get(e.getKey()));
                }
                groups.add(new Group(e.getKey(), totals.get(e.getKey()), e.getValue(), rates));
            }
            String widest = null;
            double maxGap = 0;
            for (String o : outcomes) {
                double lo = groups.stream().mapToDouble(g -> g.rates().getOrDefault(o, 0.0)).min().orElse(0);
                double hi = groups.stream().mapToDouble(g -> g.rates().getOrDefault(o, 0.0)).max().orElse(0);
                if (hi - lo > maxGap) { maxGap = hi - lo; widest = o; }
            }
            boolean warn = groups.size() > 1 && maxGap > threshold;
            any |= warn;
            dims.add(new Dimension(f.getName(), outputField.getName(), groups, widest, maxGap, warn));
        }
        return new Report(inputs.size(), threshold, dims, any);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private boolean isSensitive(FieldDef f) {
        String name = f.getName() == null ? "" : f.getName();
        for (GlossaryEntry e : glossary.findBySemantic("sensitive-dimension")) {
            if (matches(name, e.getZh_TW()) || matches(name, e.getEn())) return true;
            if (e.getSynonyms() != null && e.getSynonyms().stream().anyMatch(s -> matches(name, s))) return true;
        }
        return false;
    }

    private static boolean matches(String fieldName, String term) {
        if (term == null || term.isBlank()) return false;
        String a = fieldName.toLowerCase(Locale.ROOT), b = term.toLowerCase(Locale.ROOT);
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /** 結果欄：優先取語意為決議的 ENUM 輸出，否則第一個 ENUM／BOOLEAN，再否則第一個輸出。 */
    private FieldDef pickOutcomeField(List<FieldDef> outputs) {
        Set<String> decisionNames = glossary.findBySemantic("decision-outcome").stream()
                .flatMap(e -> {
                    List<String> names = new ArrayList<>();
                    if (e.getZh_TW() != null) names.add(e.getZh_TW());
                    if (e.getEn() != null) names.add(e.getEn());
                    if (e.getSynonyms() != null) names.addAll(e.getSynonyms());
                    return names.stream();
                }).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (FieldDef o : outputs) {
            if (o.getName() != null && decisionNames.contains(o.getName().toLowerCase(Locale.ROOT))) return o;
        }
        for (FieldDef o : outputs) {
            String t = o.getTypeRef() == null ? "" : o.getTypeRef().toUpperCase();
            if ("ENUM".equals(t) || "BOOLEAN".equals(t)) return o;
        }
        return outputs.isEmpty() ? null : outputs.get(0);
    }

    /** 數值敏感維度（年齡）分 10 歲一段，其餘用原值。 */
    private static String groupLabel(FieldDef f, Object v) {
        if (v == null) return "（空）";
        String t = f.getTypeRef() == null ? "" : f.getTypeRef().toUpperCase();
        if (("INTEGER".equals(t) || "DECIMAL".equals(t)) && v instanceof Number n) {
            long base = (long) Math.floor(n.doubleValue() / 10) * 10;
            return base + "–" + (base + 9);
        }
        return v.toString();
    }

    private static String outcomeOf(ExecutionResult r, String outputField) {
        if (!r.isMatched() || r.getResults() == null) return "未命中";
        Object v = r.getResults().get(outputField);
        return v == null ? "（空）" : v.toString();
    }
}
