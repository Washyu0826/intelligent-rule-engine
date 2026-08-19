package com.ruleengine.rules.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.service.RuleLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 執行引擎深度驗證（P2-S2 加強）：
 * ① 12 種 operator 全覆蓋執行
 * ② 真實生成物（offline scenario JSON）直接執行
 * ③ 隨機化交叉驗證（固定 seed，數百組合 engine vs lookup 逐一比對）
 * ④ 已知語意差異的文件化測試
 * ⑤ 性能冒煙（上限規模 200 列；NONE vs FULL trace 開銷）
 */
@DisplayName("RuleExecutionEngine - 深度驗證")
class RuleExecutionEngineDeepTest {

    RuleLookupService lookupService;
    RuleExecutionEngine engine;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lookupService = new RuleLookupService();
        engine = new RuleExecutionEngine(lookupService);
        ReflectionTestUtils.setField(engine, "maxTreeDepth", 10);
    }

    private Condition cond(String field, String op, Object value) {
        Condition c = new Condition();
        c.setField(field); c.setOperator(op); c.setValue(value);
        return c;
    }

    private RuleEnvelope singleRuleTable(Condition condition) {
        RuleEnvelope e = new RuleEnvelope();
        e.setRuleType("DecisionTable");
        Rule rule = new Rule();
        rule.setHitPolicy("FIRST");
        RuleRow row = new RuleRow();
        row.setRuleId("R01"); row.setPriority(1);
        row.setConditions(List.of(condition));
        Result res = new Result();
        res.setField("out"); res.setValue("hit");
        row.setResults(List.of(res));
        rule.setRules(List.of(row));
        e.setRule(rule);
        return e;
    }

    private boolean hits(Condition c, Map<String, Object> input) {
        return engine.execute(singleRuleTable(c), input, TraceLevel.NONE).isMatched();
    }

    // ================================================================
    // ① 12 operator 全覆蓋
    // ================================================================

    @Nested
    @DisplayName("① 12 種 operator 執行全覆蓋（每種一正一反）")
    class OperatorCoverage {

        @Test void equals_op() {
            assertTrue(hits(cond("x", "equals", "A"), Map.of("x", "A")));
            assertFalse(hits(cond("x", "equals", "A"), Map.of("x", "B")));
        }

        @Test void notEquals_op() {
            assertTrue(hits(cond("x", "notEquals", "A"), Map.of("x", "B")));
            assertFalse(hits(cond("x", "notEquals", "A"), Map.of("x", "A")));
        }

        @Test void greaterThan_op() {
            assertTrue(hits(cond("x", "greaterThan", 10), Map.of("x", 11)));
            assertFalse(hits(cond("x", "greaterThan", 10), Map.of("x", 10)));
        }

        @Test void greaterThanOrEqual_op() {
            assertTrue(hits(cond("x", "greaterThanOrEqual", 10), Map.of("x", 10)));
            assertFalse(hits(cond("x", "greaterThanOrEqual", 10), Map.of("x", 9)));
        }

        @Test void lessThan_op() {
            assertTrue(hits(cond("x", "lessThan", 10), Map.of("x", 9)));
            assertFalse(hits(cond("x", "lessThan", 10), Map.of("x", 10)));
        }

        @Test void lessThanOrEqual_op() {
            assertTrue(hits(cond("x", "lessThanOrEqual", 10), Map.of("x", 10)));
            assertFalse(hits(cond("x", "lessThanOrEqual", 10), Map.of("x", 11)));
        }

        @Test void between_op() {
            assertTrue(hits(cond("x", "between", List.of(5, 10)), Map.of("x", 7)));
            assertTrue(hits(cond("x", "between", List.of(5, 10)), Map.of("x", 5)), "between 含邊界");
            assertFalse(hits(cond("x", "between", List.of(5, 10)), Map.of("x", 11)));
        }

        @Test void in_op() {
            assertTrue(hits(cond("x", "in", List.of("A", "B")), Map.of("x", "B")));
            assertFalse(hits(cond("x", "in", List.of("A", "B")), Map.of("x", "C")));
        }

        @Test void notIn_op() {
            assertTrue(hits(cond("x", "notIn", List.of("A", "B")), Map.of("x", "C")));
            assertFalse(hits(cond("x", "notIn", List.of("A", "B")), Map.of("x", "A")));
        }

        @Test void isNull_op() {
            Map<String, Object> withNull = new HashMap<>();
            withNull.put("x", null);
            assertTrue(hits(cond("x", "isNull", null), withNull), "顯式 null 命中 isNull");
            assertFalse(hits(cond("x", "isNull", null), Map.of("x", 1)));
            // 缺欄位 ≠ 顯式 null：完全不給 x 時 isNull 也不命中（既有語意）
            assertFalse(hits(cond("x", "isNull", null), Map.of("y", 1)));
        }

        @Test void isNotNull_op() {
            assertTrue(hits(cond("x", "isNotNull", null), Map.of("x", 1)));
            Map<String, Object> withNull = new HashMap<>();
            withNull.put("x", null);
            assertFalse(hits(cond("x", "isNotNull", null), withNull));
        }

        @Test void anything_op() {
            assertTrue(hits(cond("x", "anything", null), Map.of("x", 1)));
            assertTrue(hits(cond("x", "anything", null), Map.of("y", 1)), "anything 連缺欄位都放行（萬用格）");
        }

        @Test
        @DisplayName("valueRef 跨欄位：income > <expense> 於執行期解析")
        void valueRef_crossField() {
            Condition c = new Condition();
            c.setField("income"); c.setOperator("greaterThan"); c.setValueRef("expense");
            assertTrue(hits(c, Map.of("income", 100, "expense", 60)));
            assertFalse(hits(c, Map.of("income", 50, "expense", 60)));
        }
    }

    // ================================================================
    // ② 真實生成物執行
    // ================================================================

    @Nested
    @DisplayName("② 真實情境檔（離線 scenario JSON）直接執行")
    class RealScenario {

        private RuleEnvelope load(String path) throws Exception {
            return objectMapper.readValue(
                    new ClassPathResource(path).getInputStream(), RuleEnvelope.class);
        }

        @Test
        @DisplayName("scenario-06-credit（信用評分表）：真實 envelope 可執行且 FULL trace 完整")
        void creditScenarioExecutes() throws Exception {
            RuleEnvelope e = load("offline/scenario-06-credit.json");
            assertEquals("DecisionTable", e.getRuleType());

            // 用表定義的第一條規則反推一組必命中的輸入
            RuleRow first = e.getRule().getRules().get(0);
            Map<String, Object> input = new HashMap<>();
            for (Condition c : first.getConditions()) {
                input.put(c.getField(), representativeValue(c));
            }

            var r = engine.execute(e, input, TraceLevel.FULL);
            assertTrue(r.isMatched(), "依首條規則反推的輸入應命中");
            assertEquals(first.getRuleId(), r.getMatchedRules().get(0).getRuleId());
            assertFalse(r.getTrace().getSteps().isEmpty());
            assertEquals(RuleExecutionEngine.ENGINE_VERSION, r.getTrace().getEngineVersion());

            // 交叉驗證：真實檔上 engine 與 lookup 也一致
            var lr = lookupService.lookup(e, input);
            assertEquals(lr.matchedRules().get(0).ruleId(), r.getMatchedRules().get(0).getRuleId());
        }

        /** 從條件反推一個會使其成立的代表值。 */
        private Object representativeValue(Condition c) {
            Object v = c.getValue();
            return switch (c.getOperator()) {
                case "greaterThan" -> ((Number) v).doubleValue() + 1;
                case "greaterThanOrEqual", "lessThanOrEqual", "equals" -> v;
                case "lessThan" -> ((Number) v).doubleValue() - 1;
                case "between" -> ((List<?>) v).get(0);
                case "in" -> ((List<?>) v).get(0);
                case "anything" -> "whatever";
                default -> v;
            };
        }
    }

    // ================================================================
    // ③ 隨機化交叉驗證
    // ================================================================

    @Nested
    @DisplayName("③ 隨機化交叉驗證（固定 seed，可重現）")
    class RandomizedCrossValidation {

        @Test
        @DisplayName("20 張隨機表 × 25 組隨機輸入 = 500 組合，engine 與 lookup 逐一一致（FIRST+MULTI）")
        void fiveHundredCombinationsAgree() {
            Random rnd = new Random(42);   // 固定 seed —— 失敗可重現
            String[] ops = {"equals", "notEquals", "greaterThan", "greaterThanOrEqual",
                    "lessThan", "lessThanOrEqual", "between", "in", "anything"};
            int disagreements = 0;

            for (int t = 0; t < 20; t++) {
                boolean multi = t % 2 == 0;
                RuleEnvelope e = randomTable(rnd, ops, multi ? "MULTI" : "FIRST");
                for (int i = 0; i < 25; i++) {
                    Map<String, Object> input = Map.of(
                            "a", rnd.nextInt(100), "b", rnd.nextInt(100), "c", rnd.nextInt(100));
                    var er = engine.execute(e, input, TraceLevel.NONE);
                    var lr = lookupService.lookup(e, input);

                    List<String> engineIds = er.getMatchedRules().stream()
                            .map(ExecutionResult.MatchedRule::getRuleId).toList();
                    List<String> lookupIds = lr.matchedRules().stream()
                            .map(RuleLookupService.MatchedRule::ruleId).toList();
                    if (!engineIds.equals(lookupIds)) {
                        disagreements++;
                    }
                }
            }
            assertEquals(0, disagreements,
                    "500 組合中出現語意分歧 —— engine 與 lookup 的比對行為不再一致");
        }

        /** 產生 priority 已排序的隨機表（正常管線經 normalizer 後的狀態）。 */
        private RuleEnvelope randomTable(Random rnd, String[] ops, String hitPolicy) {
            RuleEnvelope e = new RuleEnvelope();
            e.setRuleType("DecisionTable");
            Rule rule = new Rule();
            rule.setHitPolicy(hitPolicy);
            List<RuleRow> rows = new ArrayList<>();
            int rowCount = 3 + rnd.nextInt(5);
            for (int i = 0; i < rowCount; i++) {
                RuleRow row = new RuleRow();
                row.setRuleId("R" + i); row.setPriority(i + 1);
                List<Condition> conds = new ArrayList<>();
                int condCount = 1 + rnd.nextInt(2);
                for (int j = 0; j < condCount; j++) {
                    String field = String.valueOf((char) ('a' + rnd.nextInt(3)));
                    String op = ops[rnd.nextInt(ops.length)];
                    Object value = switch (op) {
                        case "between" -> {
                            int lo = rnd.nextInt(50);
                            yield List.of(lo, lo + rnd.nextInt(50));
                        }
                        case "in" -> List.of(rnd.nextInt(100), rnd.nextInt(100));
                        case "anything" -> null;
                        default -> rnd.nextInt(100);
                    };
                    conds.add(cond(field, op, value));
                }
                row.setConditions(conds);
                Result res = new Result();
                res.setField("out"); res.setValue("v" + i);
                row.setResults(List.of(res));
                rows.add(row);
            }
            rule.setRules(rows);
            e.setRule(rule);
            return e;
        }
    }

    // ================================================================
    // ④ 已知語意差異的文件化
    // ================================================================

    @Nested
    @DisplayName("④ 已知語意差異（文件化，非 bug）")
    class KnownSemanticDifference {

        @Test
        @DisplayName("亂序表：engine 尊重 priority、lookup 尊重列序 —— 正常管線（normalizer 已排序）無此分歧")
        void unorderedTableDiffersByDesign() {
            RuleEnvelope e = new RuleEnvelope();
            e.setRuleType("DecisionTable");
            Rule rule = new Rule();
            rule.setHitPolicy("FIRST");
            // 兩條都會命中 x=5；列序 R-low 在前但 priority 較大（2）
            RuleRow low = new RuleRow();
            low.setRuleId("R-low"); low.setPriority(2);
            low.setConditions(List.of(cond("x", "anything", null)));
            Result r1 = new Result(); r1.setField("out"); r1.setValue("low");
            low.setResults(List.of(r1));
            RuleRow high = new RuleRow();
            high.setRuleId("R-high"); high.setPriority(1);
            high.setConditions(List.of(cond("x", "anything", null)));
            Result r2 = new Result(); r2.setField("out"); r2.setValue("high");
            high.setResults(List.of(r2));
            rule.setRules(List.of(low, high));   // 列序與 priority 相反
            e.setRule(rule);

            var er = engine.execute(e, Map.of("x", 5), TraceLevel.NONE);
            var lr = lookupService.lookup(e, Map.of("x", 5));

            assertEquals("R-high", er.getMatchedRules().get(0).getRuleId(),
                    "engine 尊重 priority（語意上正確：priority 存在就該生效）");
            assertEquals("R-low", lr.matchedRules().get(0).ruleId(),
                    "lookup 尊重列序（既有行為）—— 若此斷言失敗代表 lookup 行為改了，需重新評估一致性");
        }
    }

    // ================================================================
    // ⑤ 性能冒煙
    // ================================================================

    @Nested
    @DisplayName("⑤ 性能冒煙（上限規模；正式量測 P4 用 JMH）")
    class PerfSmoke {

        @Test
        @DisplayName("200 列（設定上限）× 1000 次執行 < 2 秒；FULL trace 開銷有界")
        void maxScaleUnderBudget() {
            // 造 200 列全不命中的表（最壞情況：每次都掃完全表）
            RuleEnvelope e = new RuleEnvelope();
            e.setRuleType("DecisionTable");
            Rule rule = new Rule();
            rule.setHitPolicy("FIRST");
            List<RuleRow> rows = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                RuleRow row = new RuleRow();
                row.setRuleId("R" + i); row.setPriority(i + 1);
                row.setConditions(List.of(cond("x", "greaterThan", 1_000_000)));
                Result res = new Result(); res.setField("out"); res.setValue(i);
                row.setResults(List.of(res));
                rows.add(row);
            }
            rule.setRules(rows);
            e.setRule(rule);
            Map<String, Object> input = Map.of("x", 1);

            // warmup（讓 JIT 至少過一輪）
            for (int i = 0; i < 200; i++) engine.execute(e, input, TraceLevel.NONE);

            long t0 = System.nanoTime();
            for (int i = 0; i < 1000; i++) engine.execute(e, input, TraceLevel.NONE);
            long noneMs = (System.nanoTime() - t0) / 1_000_000;

            long t1 = System.nanoTime();
            for (int i = 0; i < 1000; i++) engine.execute(e, input, TraceLevel.FULL);
            long fullMs = (System.nanoTime() - t1) / 1_000_000;

            System.out.printf("PERF-SMOKE | 200列×1000次 | NONE=%dms FULL=%dms (每次 NONE≈%.1fµs FULL≈%.1fµs)%n",
                    noneMs, fullMs, noneMs * 1000.0 / 1000, fullMs * 1000.0 / 1000);

            // 寬鬆上限：防「意外變成 O(n²) 或每列 new 大量物件」級別的退化，不是精密基準
            assertTrue(noneMs < 2000, "NONE 級 1000 次執行超過 2 秒 —— 引擎有嚴重效能退化");
            assertTrue(fullMs < 5000, "FULL 級 1000 次執行超過 5 秒 —— trace 記錄成本失控");
        }
    }
}
