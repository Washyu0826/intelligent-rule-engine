package com.ruleengine.rules.bench;

import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.service.generator.ConditionOverlapDetector;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ConditionOverlapDetector 熱路徑基準測試。
 *
 * 背景：findAllOverlaps / hasAnyOverlap 為 O(n²) 全配對比較，
 * 且每次配對都重建 conditionMap 與重新解析數值區間。
 * 本基準固定優化前後的量測條件：
 *   - 合成決策表貼近實際生成形態：年齡帶 between（10 帶分割）×
 *     通路 equals × 金額帶 between，外加 5% 萬用（anything）列
 *   - 固定 seed 確保可重現
 *
 * 執行：
 *   mvn test-compile
 *   mvn org.codehaus.mojo:exec-maven-plugin:3.1.0:java ^
 *       -Dexec.mainClass=com.ruleengine.rules.bench.OverlapDetectorBenchmark ^
 *       -Dexec.classpathScope=test
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class OverlapDetectorBenchmark {

    @Param({"100", "200", "500", "1000"})
    public int ruleCount;

    List<RuleRow> rows;
    final ConditionOverlapDetector detector = new ConditionOverlapDetector();

    @Setup(Level.Trial)
    public void setup() {
        rows = syntheticTable(ruleCount);
    }

    @Benchmark
    public List<int[]> findAllOverlaps() {
        return detector.findAllOverlaps(rows);
    }

    @Benchmark
    public boolean hasAnyOverlap() {
        return detector.hasAnyOverlap(rows);
    }

    /**
     * 合成決策表：模擬核保類生成結果。
     * - age: between [band*8, band*8+7]，10 帶分割（帶間互斥、帶內重疊候選）
     * - channel: equals C0..C3
     * - amount: between 千元帶
     * - 每 20 列 1 列 age 為 anything（萬用列，與所有列都是候選）
     */
    static List<RuleRow> syntheticTable(int n) {
        List<RuleRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Condition> conds = new ArrayList<>(3);
            if (i % 20 == 19) {
                conds.add(Condition.builder().field("age").operator("anything").build());
            } else {
                int band = i % 10;
                conds.add(Condition.builder().field("age").operator("between")
                        .value(List.of(band * 8, band * 8 + 7)).build());
            }
            conds.add(Condition.builder().field("channel").operator("equals")
                    .value("C" + (i / 10) % 4).build());
            conds.add(Condition.builder().field("amount").operator("between")
                    .value(List.of((i / 40) * 1000, (i / 40) * 1000 + 999)).build());

            rows.add(RuleRow.builder()
                    .ruleId("R" + String.format("%04d", i + 1))
                    .priority(i + 1)
                    .conditions(conds)
                    .results(List.of(Result.builder().field("decision").value("APPROVE").build()))
                    .build());
        }
        return rows;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(OverlapDetectorBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
