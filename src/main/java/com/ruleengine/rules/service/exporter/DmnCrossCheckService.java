package com.ruleengine.rules.service.exporter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.service.engine.ExecutionResult;
import com.ruleengine.rules.service.engine.RuleEngineRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.dmn.engine.DmnDecision;
import org.camunda.bpm.dmn.engine.DmnDecisionTableResult;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 同一組輸入，內建引擎跑一次、匯出的 DMN 交給 Camunda DMN 引擎再跑一次，逐欄比對輸出。
 *
 * <p>目的：證明「轉成標準格式後，第三方引擎得到相同結果」。這裡的 Camunda 只是驗證用的參考引擎，
 * 不是正式執行端。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DmnCrossCheckService {

    private final DmnExporter exporter;
    private final RuleEngineRunner engine;
    private final DmnEngine dmnEngine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();

    public record CrossCheck(boolean consistent,
                             ExecutionResult ours,
                             boolean dmnMatched,
                             List<Map<String, Object>> dmnResults,
                             List<String> differences,
                             List<String> warnings,
                             long dmnNanos) {}

    public CrossCheck check(RuleEnvelope envelope, Map<String, Object> input) {
        DmnExporter.DmnExport export = exporter.export(envelope);
        ExecutionResult ours = engine.evaluate(envelope, input);

        Map<String, Object> variables = coerceInputs(envelope, input);
        long t0 = System.nanoTime();
        DmnDecision decision = dmnEngine.parseDecision(export.decisionId(),
                new ByteArrayInputStream(export.xml().getBytes(StandardCharsets.UTF_8)));
        DmnDecisionTableResult dmnResult = dmnEngine.evaluateDecisionTable(decision, variables);
        long dmnNanos = System.nanoTime() - t0;

        List<Map<String, Object>> dmnRows = new ArrayList<>();
        for (Map<String, Object> r : dmnResult.getResultList()) {
            dmnRows.add(new LinkedHashMap<>(r));
        }

        List<String> differences = new ArrayList<>();
        boolean dmnMatched = !dmnRows.isEmpty();
        if (ours.isMatched() != dmnMatched) {
            differences.add("命中與否不同：內建引擎 " + (ours.isMatched() ? "命中" : "未命中")
                    + "，DMN 引擎 " + (dmnMatched ? "命中" : "未命中"));
        } else if (dmnMatched) {
            Map<String, Object> oursOut = ours.getResults() == null ? Map.of() : ours.getResults();
            Map<String, Object> dmnOut = dmnRows.get(0);
            for (String field : outputNames(envelope)) {
                String a = norm(oursOut.get(field));
                String b = norm(dmnOut.get(field));
                if (!Objects.equals(a, b)) {
                    differences.add(field + "：內建引擎 " + a + "，DMN 引擎 " + b);
                }
            }
            int oursCount = ours.getAllMatches() != null ? ours.getAllMatches().size() : 1;
            if (dmnRows.size() > 1 || oursCount > 1) {
                if (oursCount != dmnRows.size()) {
                    differences.add("命中條數不同：內建引擎 " + oursCount + "，DMN 引擎 " + dmnRows.size());
                }
            }
        }
        log.info("DMN cross-check | consistent={} | ours.matched={} | dmn.rows={} | dmnNanos={}",
                differences.isEmpty(), ours.isMatched(), dmnRows.size(), dmnNanos);
        return new CrossCheck(differences.isEmpty(), ours, dmnMatched, dmnRows, differences, export.warnings(), dmnNanos);
    }

    private static List<String> outputNames(RuleEnvelope envelope) {
        List<String> names = new ArrayList<>();
        if (envelope.getRule() != null && envelope.getRule().getOutputs() != null) {
            for (FieldDef f : envelope.getRule().getOutputs()) names.add(f.getName());
        }
        return names;
    }

    /** 依 inputs 的 typeRef 把 JSON 來的值轉成 DMN typeRef 接受的 Java 型別。 */
    private static Map<String, Object> coerceInputs(RuleEnvelope envelope, Map<String, Object> input) {
        Map<String, Object> vars = new LinkedHashMap<>(input == null ? Map.of() : input);
        if (envelope.getRule() == null || envelope.getRule().getInputs() == null) return vars;
        for (FieldDef f : envelope.getRule().getInputs()) {
            Object v = vars.get(f.getName());
            if (v == null) continue;
            String type = f.getTypeRef() == null ? "" : f.getTypeRef().toUpperCase();
            switch (type) {
                case "INTEGER" -> {
                    if (v instanceof Number n) vars.put(f.getName(), n.longValue());
                    else vars.put(f.getName(), Long.parseLong(v.toString().trim()));
                }
                case "DECIMAL" -> {
                    if (v instanceof Number n) vars.put(f.getName(), n.doubleValue());
                    else vars.put(f.getName(), Double.parseDouble(v.toString().trim()));
                }
                case "BOOLEAN" -> {
                    if (!(v instanceof Boolean)) vars.put(f.getName(), Boolean.parseBoolean(v.toString().trim()));
                }
                default -> vars.put(f.getName(), v.toString());
            }
        }
        return vars;
    }

    private static String norm(Object v) {
        if (v == null) return "null";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return v.toString();
    }
}
