package com.ruleengine.rules.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.service.RuleLookupService;
import com.ruleengine.rules.service.execution.ExecutionResult;
import com.ruleengine.rules.service.execution.RuleExecutionEngine;
import com.ruleengine.rules.service.execution.TraceLevel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 單次規則執行耗時（內建引擎，不含 HTTP）：決策表 10／100／500 列、理賠示範樹、評分卡。
 *
 * <pre>
 * mvn test-compile
 * mvn dependency:build-classpath "-Dmdep.outputFile=target\cp.txt" "-Dmdep.includeScope=test"
 * java -cp "target\classes;target\test-classes;$(Get-Content target\cp.txt -Raw)" com.ruleengine.rules.bench.ExecutionBenchmark
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ExecutionBenchmark {

    @Param({"10", "100", "500"})
    public int ruleCount;

    RuleExecutionEngine engine;
    RuleEnvelope table;
    RuleEnvelope tree;
    RuleEnvelope card;
    Map<String, Object> tableInputLast;
    Map<String, Object> treeInput;
    Map<String, Object> cardInput;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        engine = new RuleExecutionEngine(new RuleLookupService());
        ReflectionTestUtils.setField(engine, "maxTreeDepth", 10);
        ObjectMapper om = new ObjectMapper();
        table = syntheticTable(ruleCount);
        // 最後一列才命中：量最壞情況（FIRST 必須掃完整張表）
        tableInputLast = Map.of("age", 18 + (ruleCount - 1) % 60, "channel", "C" + ((ruleCount - 1) % 5), "amount", 1000);
        tree = om.readValue(Files.readString(Path.of("demo_tree_envelope.json"), StandardCharsets.UTF_8), RuleEnvelope.class);
        treeInput = Map.of("policyStatus", "ACTIVE", "incidentType", "ILLNESS", "isDrunkDriving", false,
                "exceededWaitingPeriod", true, "claimAmount", 120000);
        card = om.readValue("""
                {"ruleType":"ScoreCard","rule":{"inputs":[{"name":"age","typeRef":"INTEGER"},{"name":"smoker","typeRef":"BOOLEAN"},{"name":"bmi","typeRef":"ENUM","allowedValues":["正常","過重","肥胖"]}],
                 "outputs":[{"name":"decision","typeRef":"ENUM"}],
                 "scoringDimensions":[
                  {"field":"age","weight":1.0,"scoringRules":[{"ruleId":"S01","condition":{"field":"age","operator":"lessThan","value":40},"score":0},{"ruleId":"S02","condition":{"field":"age","operator":"between","value":[40,59]},"score":10},{"ruleId":"S03","condition":{"field":"age","operator":"anything"},"score":25}]},
                  {"field":"smoker","weight":2.0,"scoringRules":[{"ruleId":"S04","condition":{"field":"smoker","operator":"equals","value":true},"score":10}]},
                  {"field":"bmi","weight":1.0,"scoringRules":[{"ruleId":"S05","condition":{"field":"bmi","operator":"equals","value":"過重"},"score":5},{"ruleId":"S06","condition":{"field":"bmi","operator":"equals","value":"肥胖"},"score":15}]}],
                 "scoreBands":[{"bandId":"B01","minScore":0,"maxScore":14,"results":[{"field":"decision","value":"承保"}]},{"bandId":"B02","minScore":15,"maxScore":34,"results":[{"field":"decision","value":"加費"}]},{"bandId":"B03","minScore":35,"maxScore":100,"results":[{"field":"decision","value":"人工評估"}]}]}}
                """, RuleEnvelope.class);
        cardInput = Map.of("age", 45, "smoker", true, "bmi", "過重");
    }

    @Benchmark
    public ExecutionResult tableFirstWorstCase() {
        return engine.execute(table, tableInputLast, TraceLevel.NONE);
    }

    @Benchmark
    public ExecutionResult tableWithSummaryTrace() {
        return engine.execute(table, tableInputLast, TraceLevel.SUMMARY);
    }

    @Benchmark
    public ExecutionResult tree() {
        return engine.execute(tree, treeInput, TraceLevel.NONE);
    }

    @Benchmark
    public ExecutionResult scoreCard() {
        return engine.execute(card, cardInput, TraceLevel.NONE);
    }

    /** 年齡 × 通路 × 金額門檻的合成核保表；第 i 列只在 age=18+i%60、channel=C{i%5} 時命中。 */
    static RuleEnvelope syntheticTable(int n) {
        List<RuleRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int age = 18 + i % 60;
            rows.add(RuleRow.builder().ruleId("R" + i).priority(i + 1)
                    .conditions(List.of(
                            Condition.builder().field("age").operator("between").value(List.of(age, age)).build(),
                            Condition.builder().field("channel").operator("equals").value("C" + (i % 5)).build(),
                            Condition.builder().field("amount").operator("lessThanOrEqual").value(1_000_000).build()))
                    .results(List.of(
                            Result.builder().field("decision").value(i % 3 == 0 ? "承保" : "人工評估").build(),
                            Result.builder().field("factor").value(1.0 + (i % 5) * 0.1).build()))
                    .build());
        }
        RuleEnvelope.Rule rule = RuleEnvelope.Rule.builder()
                .hitPolicy("FIRST")
                .inputs(List.of(
                        FieldDef.builder().name("age").typeRef("INTEGER").build(),
                        FieldDef.builder().name("channel").typeRef("ENUM").allowedValues(List.of("C0", "C1", "C2", "C3", "C4")).build(),
                        FieldDef.builder().name("amount").typeRef("INTEGER").build()))
                .outputs(List.of(
                        FieldDef.builder().name("decision").typeRef("ENUM").allowedValues(List.of("承保", "人工評估")).build(),
                        FieldDef.builder().name("factor").typeRef("DECIMAL").build()))
                .rules(rows)
                .build();
        return RuleEnvelope.builder().ruleType("DecisionTable").rule(rule).build();
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ExecutionBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
