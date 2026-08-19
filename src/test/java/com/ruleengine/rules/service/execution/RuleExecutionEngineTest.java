package com.ruleengine.rules.service.execution;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import com.ruleengine.rules.service.RuleLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 規則執行引擎測試（P2-S2）。
 *
 * <p>純單元測試（不起 Spring context）—— 引擎的依賴只有 RuleLookupService，
 * 而後者無 Spring 依賴，直接 new。</p>
 */
@DisplayName("RuleExecutionEngine - 執行引擎核心")
class RuleExecutionEngineTest {

    RuleLookupService lookupService;
    RuleExecutionEngine engine;

    @BeforeEach
    void setUp() {
        lookupService = new RuleLookupService();
        engine = new RuleExecutionEngine(lookupService);
        ReflectionTestUtils.setField(engine, "maxTreeDepth", 10);
    }

    // ── fixture helpers ──

    private Condition cond(String field, String op, Object value) {
        Condition c = new Condition();
        c.setField(field); c.setOperator(op); c.setValue(value);
        return c;
    }

    private Result result(String field, Object value) {
        Result r = new Result();
        r.setField(field); r.setValue(value);
        return r;
    }

    private RuleRow row(String id, int priority, List<Condition> conds, List<Result> results) {
        RuleRow r = new RuleRow();
        r.setRuleId(id); r.setPriority(priority);
        r.setConditions(conds); r.setResults(results);
        return r;
    }

    /** 信用審核風格的三列表：score>=700 核准 / 600-699 人工 / 其餘拒絕。 */
    private RuleEnvelope creditTable(String hitPolicy) {
        RuleEnvelope e = new RuleEnvelope();
        e.setRuleType("DecisionTable");
        Rule rule = new Rule();
        rule.setHitPolicy(hitPolicy);
        rule.setRules(List.of(
                row("R01", 1, List.of(cond("score", "greaterThanOrEqual", 700)),
                        List.of(result("decision", "approve"), result("limit", 500000))),
                row("R02", 2, List.of(cond("score", "between", List.of(600, 699))),
                        List.of(result("decision", "manual-review"))),
                row("R03", 3, List.of(cond("score", "lessThan", 600)),
                        List.of(result("decision", "reject")))));
        e.setRule(rule);
        return e;
    }

    private RuleEnvelope creditTree() {
        RuleEnvelope e = new RuleEnvelope();
        e.setRuleType("DecisionTree");
        TreeNode approve = new TreeNode();
        approve.setNodeId("N02");
        approve.setResults(List.of(result("decision", "approve")));
        TreeNode reject = new TreeNode();
        reject.setNodeId("N03");
        reject.setResults(List.of(result("decision", "reject")));

        TreeNode root = new TreeNode();
        root.setNodeId("N01");
        Branch high = new Branch();
        high.setLabel("HIGH"); high.setCondition(cond("score", "greaterThanOrEqual", 700)); high.setChild(approve);
        Branch low = new Branch();
        low.setLabel("LOW"); low.setCondition(cond("score", "lessThan", 700)); low.setChild(reject);
        root.setBranches(List.of(high, low));

        Rule rule = new Rule();
        rule.setRoot(root);
        e.setRule(rule);
        return e;
    }

    // ================================================================

    @Nested
    @DisplayName("DecisionTable - FIRST")
    class TableFirst {

        @Test
        @DisplayName("首中即停：score=750 命中 R01，R02/R03 不出現在命中清單")
        void firstHitStops() {
            var r = engine.execute(creditTable("FIRST"), Map.of("score", 750), TraceLevel.NONE);
            assertTrue(r.isMatched());
            assertEquals(1, r.getMatchedRules().size());
            assertEquals("R01", r.getMatchedRules().get(0).getRuleId());
            assertEquals("approve", r.getOutputs().get("decision"));
            assertEquals(500000, r.getOutputs().get("limit"));
        }

        @Test
        @DisplayName("priority 決定評估順序：亂序輸入的表仍先評 priority 小者")
        void prioritySorting() {
            RuleEnvelope e = creditTable("FIRST");
            // 打亂列順序（R03 排最前），priority 仍應讓 R01 先評
            Rule rule = e.getRule();
            rule.setRules(List.of(rule.getRules().get(2), rule.getRules().get(0), rule.getRules().get(1)));
            var r = engine.execute(e, Map.of("score", 750), TraceLevel.NONE);
            assertEquals("R01", r.getMatchedRules().get(0).getRuleId());
        }

        @Test
        @DisplayName("無命中：matched=false、outputs 空（決策缺口如實回報，不擲例外）")
        void noMatch() {
            RuleEnvelope e = creditTable("FIRST");
            // 只留 R01（>=700），輸入 500 → 無規則可命中
            e.getRule().setRules(List.of(e.getRule().getRules().get(0)));
            var r = engine.execute(e, Map.of("score", 500), TraceLevel.NONE);
            assertFalse(r.isMatched());
            assertTrue(r.getOutputs().isEmpty());
        }

        @Test
        @DisplayName("缺欄位 ≠ 顯式 null：不給 score 時所有規則不命中（與 lookup 語意一致）")
        void missingFieldSemantics() {
            var r = engine.execute(creditTable("FIRST"), Map.of("other", 1), TraceLevel.NONE);
            assertFalse(r.isMatched());
        }
    }

    @Nested
    @DisplayName("DecisionTable - MULTI")
    class TableMulti {

        @Test
        @DisplayName("收集所有命中（DMN COLLECT 語意）")
        void collectsAll() {
            RuleEnvelope e = creditTable("MULTI");
            // 加一條與 R01 重疊的規則：score>=700 也給 vip 標記
            var rules = new java.util.ArrayList<>(e.getRule().getRules());
            rules.add(row("R04", 4, List.of(cond("score", "greaterThan", 740)),
                    List.of(result("vip", true))));
            e.getRule().setRules(rules);

            var r = engine.execute(e, Map.of("score", 750), TraceLevel.NONE);
            assertEquals(List.of("R01", "R04"),
                    r.getMatchedRules().stream().map(ExecutionResult.MatchedRule::getRuleId).toList());
            // 頂層 outputs 取第一個命中；完整資訊在 matchedRules
            assertEquals("approve", r.getOutputs().get("decision"));
            assertEquals(true, r.getMatchedRules().get(1).getOutputs().get("vip"));
        }
    }

    @Nested
    @DisplayName("DecisionTree")
    class Tree {

        @Test
        @DisplayName("走訪到葉節點：score=750 走 HIGH 分支 → approve")
        void traversesToLeaf() {
            var r = engine.execute(creditTree(), Map.of("score", 750), TraceLevel.SUMMARY);
            assertTrue(r.isMatched());
            assertEquals("approve", r.getOutputs().get("decision"));
            // trace 記錄了走的分支
            var rootStep = r.getTrace().getSteps().get(0);
            assertEquals("N01", rootStep.getId());
            assertEquals("HIGH", rootStep.getBranchTaken());
        }

        @Test
        @DisplayName("決策缺口：無分支成立 → matched=false 如實回報（不擲例外）")
        void gapReportsUnmatched() {
            RuleEnvelope e = creditTree();
            // 拿掉 LOW 分支 → score=500 無路可走
            e.getRule().getRoot().setBranches(List.of(e.getRule().getRoot().getBranches().get(0)));
            var r = engine.execute(e, Map.of("score", 500), TraceLevel.SUMMARY);
            assertFalse(r.isMatched());
            assertFalse(r.getTrace().getSteps().get(0).isMatched());
        }

        @Test
        @DisplayName("深度上限：自指的循環樹被 maxTreeDepth 擋下")
        void depthLimitBitesOnCycle() {
            RuleEnvelope e = creditTree();
            TreeNode root = e.getRule().getRoot();
            root.getBranches().get(0).setChild(root);   // HIGH 分支指回自己 = 循環
            var ex = assertThrows(RuleExecutionEngine.ExecutionException.class,
                    () -> engine.execute(e, Map.of("score", 750), TraceLevel.NONE));
            assertTrue(ex.getMessage().contains("深度"));
        }

        @Test
        @DisplayName("未正規化的樹（無 branches 無 results）給出可行動的錯誤訊息")
        void unnormalizedTreeError() {
            RuleEnvelope e = creditTree();
            TreeNode bad = new TreeNode();
            bad.setNodeId("N99");   // 空節點
            e.getRule().getRoot().getBranches().get(0).setChild(bad);
            var ex = assertThrows(RuleExecutionEngine.ExecutionException.class,
                    () -> engine.execute(e, Map.of("score", 750), TraceLevel.NONE));
            assertTrue(ex.getMessage().contains("TreeNormalizer"));
        }
    }

    @Nested
    @DisplayName("DecisionTrace 三級")
    class Tracing {

        @Test
        @DisplayName("NONE：零 trace（高頻路徑零開銷）")
        void noneLevel() {
            var r = engine.execute(creditTable("FIRST"), Map.of("score", 750), TraceLevel.NONE);
            assertNull(r.getTrace());
        }

        @Test
        @DisplayName("SUMMARY：只記命中者、無條件明細、無輸入快照")
        void summaryLevel() {
            var r = engine.execute(creditTable("FIRST"), Map.of("score", 650), TraceLevel.SUMMARY);
            DecisionTrace t = r.getTrace();
            assertNotNull(t);
            assertEquals(RuleExecutionEngine.ENGINE_VERSION, t.getEngineVersion());
            // score=650：R01 不中（不記）、R02 中（記）
            assertEquals(1, t.getSteps().size());
            assertEquals("R02", t.getSteps().get(0).getId());
            assertNull(t.getSteps().get(0).getConditions(), "SUMMARY 不含條件明細");
            assertNull(t.getInputSnapshot(), "SUMMARY 不含輸入快照");
        }

        @Test
        @DisplayName("FULL：逐規則逐條件、含解析後期望值與實際值 —— 「為什麼不命中」看得到")
        void fullLevel() {
            var r = engine.execute(creditTable("FIRST"), Map.of("score", 650), TraceLevel.FULL);
            DecisionTrace t = r.getTrace();
            // FULL 記所有已評估的列：R01（未中）+ R02（中）；R03 因 FIRST 短路未評
            assertEquals(2, t.getSteps().size());
            var r01 = t.getSteps().get(0);
            assertFalse(r01.isMatched());
            var c = r01.getConditions().get(0);
            assertEquals("score", c.getField());
            assertEquals("greaterThanOrEqual", c.getOperator());
            assertEquals(700, c.getExpected());
            assertEquals(650, c.getActual());
            assertFalse(c.isMatched());
            assertEquals(Map.of("score", 650), t.getInputSnapshot());
        }

        @Test
        @DisplayName("輸入快照不可變：執行後改原 map，trace 不受污染（回放要素②）")
        void snapshotIsImmutable() {
            Map<String, Object> input = new HashMap<>();
            input.put("score", 750);
            var r = engine.execute(creditTable("FIRST"), input, TraceLevel.FULL);
            input.put("score", 0);   // 呼叫端事後亂改
            assertEquals(750, r.getTrace().getInputSnapshot().get("score"));
            assertThrows(UnsupportedOperationException.class,
                    () -> r.getTrace().getInputSnapshot().put("hacked", true));
        }
    }

    @Nested
    @DisplayName("交叉驗證：引擎 FIRST 與 RuleLookupService.lookup 語意一致")
    class CrossValidation {

        @Test
        @DisplayName("同 envelope 掃 score=550..760，兩邊命中的 ruleId 完全一致")
        void agreesWithLookupAcrossRange() {
            RuleEnvelope e = creditTable("FIRST");
            for (int score = 550; score <= 760; score += 10) {
                Map<String, Object> input = Map.of("score", score);
                var engineResult = engine.execute(e, input, TraceLevel.NONE);
                var lookupResult = lookupService.lookup(e, input);

                assertEquals(lookupResult.matched(), engineResult.isMatched(),
                        "score=" + score + " 命中與否不一致");
                if (lookupResult.matched()) {
                    assertEquals(lookupResult.matchedRules().get(0).ruleId(),
                            engineResult.getMatchedRules().get(0).getRuleId(),
                            "score=" + score + " 命中的規則不一致 —— 語意分歧！");
                }
            }
        }
    }

    @Nested
    @DisplayName("防呆")
    class Guards {

        @Test
        @DisplayName("空 envelope / 空表 / 不支援型態的錯誤都可行動")
        void actionableErrors() {
            assertThrows(RuleExecutionEngine.ExecutionException.class,
                    () -> engine.execute(null, Map.of(), TraceLevel.NONE));
            RuleEnvelope empty = new RuleEnvelope();
            empty.setRuleType("DecisionTable");
            empty.setRule(new Rule());
            assertThrows(RuleExecutionEngine.ExecutionException.class,
                    () -> engine.execute(empty, Map.of(), TraceLevel.NONE));
            RuleEnvelope sc = creditTable("FIRST");
            sc.setRuleType("ScoreCard");
            var ex = assertThrows(RuleExecutionEngine.ExecutionException.class,
                    () -> engine.execute(sc, Map.of("score", 700), TraceLevel.NONE));
            assertTrue(ex.getMessage().contains("ScoreCard"));
        }

        @Test
        @DisplayName("null input 視為空輸入，不 NPE")
        void nullInputTolerated() {
            var r = engine.execute(creditTable("FIRST"), null, TraceLevel.NONE);
            assertFalse(r.isMatched());
        }
    }
}
