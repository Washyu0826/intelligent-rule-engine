package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.TreePathService.TreePathsResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TreePathService - 決策樹路徑展開")
class TreePathServiceTest {

    private final TreePathService svc = new TreePathService();
    private final ObjectMapper mapper = new ObjectMapper();

    private RuleEnvelope env(String json) {
        try { return mapper.readValue(json, RuleEnvelope.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** root(age>60) → 是:reject / 否:approve（branches 形式）。 */
    private static final String TREE = """
        {"ruleType":"DecisionTree","rule":{"root":{
          "nodeId":"N01",
          "condition":{"field":"age","operator":"greaterThan","value":60},
          "branches":[
            {"label":"是","condition":{"field":"age","operator":"greaterThan","value":60},
             "child":{"nodeId":"N02","results":[{"field":"decision","value":"reject"}]}},
            {"label":"否","condition":{"field":"age","operator":"lessThanOrEqual","value":60},
             "child":{"nodeId":"N03","results":[{"field":"decision","value":"approve"}]}}
          ]}}}
        """;

    @Test
    @DisplayName("兩條 root→leaf 路徑各展開成一句白話規則")
    void twoPaths() {
        TreePathsResult r = svc.extractPaths(env(TREE));
        assertEquals(2, r.totalPaths());
        assertEquals(2, r.paths().size());
        // 每條路徑都有一步條件 + 一個結果 + 白話
        var p0 = r.paths().get(0);
        assertEquals(1, p0.steps().size());
        assertTrue(p0.outcome().containsKey("decision"));
        assertTrue(p0.narrative().startsWith("當 "));
        assertTrue(p0.narrative().contains("，則 decision="));
    }

    @Test
    @DisplayName("between 條件渲染成『介於 a ~ b』")
    void betweenRendering() {
        String tree = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01",
              "branches":[
                {"condition":{"field":"age","operator":"between","value":[18,65]},
                 "child":{"nodeId":"N02","results":[{"field":"decision","value":"accept"}]}}
              ]}}}
            """;
        TreePathsResult r = svc.extractPaths(env(tree));
        assertEquals(1, r.totalPaths());
        assertTrue(r.paths().get(0).narrative().contains("介於 18 ~ 65"),
                "實得：" + r.paths().get(0).narrative());
    }

    @Test
    @DisplayName("trueBranch/falseBranch 形式 → 否分支標記為 negated")
    void trueFalseBranches() {
        String tree = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01",
              "condition":{"field":"smoker","operator":"equals","value":true},
              "trueBranch":{"nodeId":"N02","results":[{"field":"decision","value":"surcharge"}]},
              "falseBranch":{"nodeId":"N03","results":[{"field":"decision","value":"standard"}]}
            }}}
            """;
        TreePathsResult r = svc.extractPaths(env(tree));
        assertEquals(2, r.totalPaths());
        boolean anyNegated = r.paths().stream()
                .flatMap(p -> p.steps().stream())
                .anyMatch(TreePathService.PathStep::negated);
        assertTrue(anyNegated, "false 分支應標記 negated");
    }

    @Test
    @DisplayName("N-ary fallback branch uses its label, not the parent condition")
    void naryFallbackUsesLabelOnly() {
        String tree = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01",
              "condition":{"field":"age","operator":"greaterThan","value":60},
              "branches":[
                {"label":"高齡","condition":{"field":"age","operator":"greaterThan","value":60},
                 "child":{"nodeId":"N02","results":[{"field":"decision","value":"review"}]}},
                {"label":"其他",
                 "child":{"nodeId":"N03","results":[{"field":"decision","value":"accept"}]}}
              ]}}}
            """;
        TreePathsResult r = svc.extractPaths(env(tree));
        var fallback = r.paths().stream()
                .filter(p -> "N03".equals(p.leafNodeId()))
                .findFirst().orElseThrow();

        assertEquals(1, fallback.steps().size());
        assertNull(fallback.steps().get(0).field());
        assertEquals("其他", fallback.steps().get(0).label());
        assertTrue(fallback.narrative().contains("其他"));
        assertFalse(fallback.narrative().contains("age"));
    }

    @Test
    @DisplayName("N-ary branches take precedence over legacy binary fields")
    void mixedFormatDoesNotDuplicatePaths() {
        String tree = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01",
              "branches":[
                {"label":"A","condition":{"field":"x","operator":"equals","value":"A"},
                 "child":{"nodeId":"N02","results":[{"field":"decision","value":"a"}]}}
              ],
              "trueBranch":{"nodeId":"N03","results":[{"field":"decision","value":"legacy"}]}
            }}}
            """;

        TreePathsResult r = svc.extractPaths(env(tree));
        assertEquals(1, r.totalPaths());
        assertEquals("N02", r.paths().get(0).leafNodeId());
    }

    @Test
    @DisplayName("非 DecisionTree → 友善訊息、零路徑")
    void notATree() {
        TreePathsResult r = svc.extractPaths(env("{\"ruleType\":\"DecisionTable\",\"rule\":{}}"));
        assertEquals(0, r.totalPaths());
        assertTrue(r.summary().contains("無法") || r.summary().contains("非"));
    }
}
