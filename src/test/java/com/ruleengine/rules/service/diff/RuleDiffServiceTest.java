package com.ruleengine.rules.service.diff;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.analyzer.DmnAnalyzer;
import com.ruleengine.rules.service.diff.RuleDiffService.TableDiffResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RuleDiffService 單元測試（方向③ L2 — 超矩形集合差 / 行為比對）。
 *
 * 證明它抓得到文字/結構 diff 抓不到的「行為回歸」：
 * 移除規則→漏覆蓋、改結果→決策改變、整數區間縮小→某段年齡回歸。
 */
@DisplayName("RuleDiffService - DecisionTable 雙表行為比對")
class RuleDiffServiceTest {

    private final RuleDiffService svc = new RuleDiffService(new DmnAnalyzer(), new ObjectMapper());
    private final ObjectMapper mapper = new ObjectMapper();

    private RuleEnvelope env(String json) {
        try { return mapper.readValue(json, RuleEnvelope.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static boolean hasType(TableDiffResult r, String type) {
        return r.regions().stream().anyMatch(x -> x.type().equals(type));
    }

    /** ENUM channel A/B/C → R1/R2/R3 全覆蓋。 */
    private static final String ENUM_ABC = """
        {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
          "inputs":[{"name":"channel","typeRef":"ENUM","allowedValues":["A","B","C"]}],
          "rules":[
            {"ruleId":"R1","conditions":[{"field":"channel","operator":"equals","value":"A"}],"results":[{"field":"decision","value":"accept"}]},
            {"ruleId":"R2","conditions":[{"field":"channel","operator":"equals","value":"B"}],"results":[{"field":"decision","value":"accept"}]},
            {"ruleId":"R3","conditions":[{"field":"channel","operator":"equals","value":"C"}],"results":[{"field":"decision","value":"reject"}]}
          ]}}
        """;

    /** 同樣宣告 A/B/C，但只有 R1/R2（C 未被任何規則涵蓋）。 */
    private static final String ENUM_AB = """
        {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
          "inputs":[{"name":"channel","typeRef":"ENUM","allowedValues":["A","B","C"]}],
          "rules":[
            {"ruleId":"R1","conditions":[{"field":"channel","operator":"equals","value":"A"}],"results":[{"field":"decision","value":"accept"}]},
            {"ruleId":"R2","conditions":[{"field":"channel","operator":"equals","value":"B"}],"results":[{"field":"decision","value":"accept"}]}
          ]}}
        """;

    // ================================================================

    @Test
    @DisplayName("相同表 → 無任何差異")
    void identical() {
        TableDiffResult r = svc.diff(env(ENUM_ABC), env(ENUM_ABC));
        assertTrue(r.comparable());
        assertEquals(0, r.lostCount());
        assertEquals(0, r.changedCount());
        assertEquals(0, r.newCount());
        assertTrue(r.regions().isEmpty());
    }

    @Test
    @DisplayName("新版移除一條規則 → LOST_COVERAGE（回歸）")
    void removedRuleIsRegression() {
        TableDiffResult r = svc.diff(env(ENUM_ABC), env(ENUM_AB));
        assertTrue(r.lostCount() >= 1, "channel=C 應被偵測為漏覆蓋");
        assertTrue(hasType(r, RuleDiffService.LOST_COVERAGE));
        assertEquals(0, r.changedCount());
    }

    @Test
    @DisplayName("同條件改結果 → CHANGED_DECISION")
    void changedResult() {
        String after = ENUM_ABC.replace("\"reject\"", "\"defer\"");
        TableDiffResult r = svc.diff(env(ENUM_ABC), env(after));
        assertTrue(r.changedCount() >= 1);
        assertTrue(hasType(r, RuleDiffService.CHANGED_DECISION));
        assertEquals(0, r.lostCount());
    }

    @Test
    @DisplayName("新版新增涵蓋 → NEW_COVERAGE")
    void newCoverage() {
        TableDiffResult r = svc.diff(env(ENUM_AB), env(ENUM_ABC));
        assertTrue(r.newCount() >= 1);
        assertTrue(hasType(r, RuleDiffService.NEW_COVERAGE));
    }

    @Test
    @DisplayName("整數區間縮小 → 某段年齡回歸（最有感的治理情境）")
    void integerRangeRegression() {
        String before = """
            {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
              "inputs":[{"name":"age","typeRef":"INTEGER"}],
              "rules":[
                {"ruleId":"R1","conditions":[{"field":"age","operator":"between","value":[0,17]}],"results":[{"field":"decision","value":"minor"}]},
                {"ruleId":"R2","conditions":[{"field":"age","operator":"between","value":[18,65]}],"results":[{"field":"decision","value":"accept"}]},
                {"ruleId":"R3","conditions":[{"field":"age","operator":"greaterThanOrEqual","value":66}],"results":[{"field":"decision","value":"review"}]}
              ]}}
            """;
        // 新版把 R2 上限從 65 縮到 60 → 61~65 歲掉進無人涵蓋
        String after = before.replace("[18,65]", "[18,60]");
        TableDiffResult r = svc.diff(env(before), env(after));
        assertTrue(r.lostCount() >= 1, "61~65 歲應被偵測為回歸");
        assertTrue(hasType(r, RuleDiffService.LOST_COVERAGE));
        // 相鄰點應合併成單一區段（age 61 ~ 65），而非五個個別點
        var lostRegion = r.regions().stream()
                .filter(x -> x.type().equals(RuleDiffService.LOST_COVERAGE)).findFirst().orElseThrow();
        String ageCond = lostRegion.point().get("age");
        assertTrue(ageCond != null && ageCond.contains("~"), "age 應合併成區間，實得：" + ageCond);
        assertTrue(ageCond.contains("61") && ageCond.contains("65"), "區間應涵蓋 61~65，實得：" + ageCond);
        assertEquals(1, r.lostCount(), "5 個相鄰回歸點應合併為 1 個區段");
    }

    @Test
    @DisplayName("狹窄 DECIMAL 缺口也會被邊界切點偵測")
    void decimalNarrowGapRegression() {
        String before = """
            {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
              "inputs":[{"name":"rate","typeRef":"DECIMAL"}],
              "rules":[
                {"ruleId":"R1","conditions":[{"field":"rate","operator":"between","value":[0.0,10.0]}],
                 "results":[{"field":"decision","value":"accept"}]}
              ]}}
            """;
        String after = """
            {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
              "inputs":[{"name":"rate","typeRef":"DECIMAL"}],
              "rules":[
                {"ruleId":"R1","conditions":[{"field":"rate","operator":"between","value":[0.0,4.9]}],
                 "results":[{"field":"decision","value":"accept"}]},
                {"ruleId":"R2","conditions":[{"field":"rate","operator":"between","value":[5.1,10.0]}],
                 "results":[{"field":"decision","value":"accept"}]}
              ]}}
            """;

        TableDiffResult r = svc.diff(env(before), env(after));
        assertTrue(r.lostCount() >= 1, "rate=5.0 的狹窄缺口應被偵測");
        assertTrue(hasType(r, RuleDiffService.LOST_COVERAGE));
    }

    // ================================================================
    // 結構 diff（規則列對齊）
    // ================================================================

    @Test
    @DisplayName("結構 diff：相同表 → 全部不變")
    void structuralIdentical() {
        var r = svc.structuralDiff(env(ENUM_ABC), env(ENUM_ABC));
        assertEquals(0, r.added());
        assertEquals(0, r.removed());
        assertEquals(0, r.modified());
        assertEquals(3, r.unchanged());
    }

    @Test
    @DisplayName("結構 diff：改 ruleId 但條件/結果不變 → 全部不變（不靠 ruleId）")
    void structuralIgnoresRuleId() {
        String renamed = ENUM_ABC.replace("R1", "X1").replace("R2", "X2").replace("R3", "X3");
        var r = svc.structuralDiff(env(ENUM_ABC), env(renamed));
        assertEquals(0, r.added() + r.removed() + r.modified());
        assertEquals(3, r.unchanged());
    }

    @Test
    @DisplayName("結構 diff：移除一條 → REMOVED；新增一條 → ADDED")
    void structuralAddRemove() {
        var removed = svc.structuralDiff(env(ENUM_ABC), env(ENUM_AB));
        assertEquals(1, removed.removed());
        assertTrue(removed.changes().stream().anyMatch(c -> c.type().equals(RuleDiffService.REMOVED)));

        var added = svc.structuralDiff(env(ENUM_AB), env(ENUM_ABC));
        assertEquals(1, added.added());
        assertTrue(added.changes().stream().anyMatch(c -> c.type().equals(RuleDiffService.ADDED)));
    }

    @Test
    @DisplayName("結構 diff：同條件改結果 → MODIFIED（決策改變）")
    void structuralModified() {
        String after = ENUM_ABC.replace("\"reject\"", "\"defer\"");
        var r = svc.structuralDiff(env(ENUM_ABC), env(after));
        assertEquals(1, r.modified());
        var ch = r.changes().stream().filter(c -> c.type().equals(RuleDiffService.MODIFIED)).findFirst().orElseThrow();
        assertEquals("decision=reject", ch.oldDecision());
        assertEquals("decision=defer", ch.newDecision());
    }

    @Test
    @DisplayName("結構 diff：同條件重複規則以 multiset 計數，不會被折疊")
    void structuralCountsDuplicateSignatures() {
        // 注意：text block 會剝除共同縮排，ENUM_ABC 執行期的結尾實際是 "  ]}}"（2 空格），
        // 故搜尋字串須用剝除後的形式，否則 replace 不會命中、R4 不會被插入。
        String duplicated = ENUM_ABC.replace(
                "  ]}}",
                """
                    ,{"ruleId":"R4","conditions":[{"field":"channel","operator":"equals","value":"A"}],"results":[{"field":"decision","value":"accept"}]}
                  ]}}""");

        var added = svc.structuralDiff(env(ENUM_ABC), env(duplicated));
        assertEquals(1, added.added());
        assertEquals(3, added.unchanged());

        var removed = svc.structuralDiff(env(duplicated), env(ENUM_ABC));
        assertEquals(1, removed.removed());
        assertEquals(3, removed.unchanged());
    }

    @Test
    @DisplayName("結構 diff：valueRef 不同不是相同條件")
    void structuralDistinguishesValueRefs() {
        String before = """
            {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
              "inputs":[{"name":"start","typeRef":"DATE"},{"name":"end","typeRef":"DATE"},{"name":"renewal","typeRef":"DATE"}],
              "rules":[{"ruleId":"R1","conditions":[{"field":"start","operator":"lessThan","valueRef":"end"}],
                "results":[{"field":"decision","value":"reject"}]}]}}
            """;
        String after = before.replace("\"valueRef\":\"end\"", "\"valueRef\":\"renewal\"");

        var r = svc.structuralDiff(env(before), env(after));
        assertEquals(1, r.added());
        assertEquals(1, r.removed());
        assertEquals(0, r.unchanged());
    }

    @Test
    @DisplayName("IN 笛卡爾積超過展開上限 → summary 警示比對可能不完整（截斷透明化）")
    void cartesianTruncationIsReportedInSummary() {
        // 3 個 INTEGER 欄位各 in 11 值 → 11^3 = 1331 > 1000 展開上限
        StringBuilder in11 = new StringBuilder("[1");
        for (int i = 2; i <= 11; i++) in11.append(",").append(i);
        in11.append("]");
        String big = """
            {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
              "inputs":[
                {"name":"a","typeRef":"INTEGER"},
                {"name":"b","typeRef":"INTEGER"},
                {"name":"c","typeRef":"INTEGER"}],
              "rules":[
                {"ruleId":"BIG","conditions":[
                  {"field":"a","operator":"in","value":%s},
                  {"field":"b","operator":"in","value":%s},
                  {"field":"c","operator":"in","value":%s}],
                 "results":[{"field":"decision","value":"x"}]}
              ]}}
            """.formatted(in11, in11, in11);

        TableDiffResult r = svc.diff(env(big), env(big));

        assertTrue(r.comparable());
        assertTrue(r.summary().contains("不完整"),
                "截斷時 summary 應警示比對可能不完整，實際：" + r.summary());
        assertTrue(r.summary().contains("BIG"));
        // v3.16.2: 截斷必須結構化回報 — 機器消費者不該被迫 parse 中文 summary
        assertTrue(r.approximate(), "截斷時 approximate 應為 true（截斷區域可能漏報或誤報）");
        assertTrue(r.truncatedRuleIds().contains("BIG"));
    }

    @Test
    @DisplayName("typeRef 不一致時明確回報不可比對")
    void incompatibleInputTypesAreNotComparable() {
        String changedType = ENUM_ABC.replace("\"typeRef\":\"ENUM\"", "\"typeRef\":\"STRING\"");
        TableDiffResult behavioral = svc.diff(env(ENUM_ABC), env(changedType));
        assertFalse(behavioral.comparable());
        assertTrue(behavioral.summary().contains("typeRef"));

        var structural = svc.structuralDiff(env(ENUM_ABC), env(changedType));
        assertFalse(structural.comparable());
        assertTrue(structural.summary().contains("typeRef"));
    }

    @Test
    @DisplayName("非 DecisionTable → 友善訊息、零差異")
    void notATable() {
        TableDiffResult r = svc.diff(
                env("{\"ruleType\":\"DecisionTree\",\"rule\":{\"root\":{\"nodeId\":\"N1\"}}}"),
                env(ENUM_ABC));
        assertFalse(r.comparable());
        assertEquals(0, r.lostCount() + r.changedCount() + r.newCount());
        assertTrue(r.summary().contains("DecisionTable") || r.summary().contains("無法") || r.summary().contains("缺"));
    }
}
