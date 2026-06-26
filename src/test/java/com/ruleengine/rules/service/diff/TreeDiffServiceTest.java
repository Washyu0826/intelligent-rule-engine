package com.ruleengine.rules.service.diff;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.diff.TreeDiffService.TreeDiffResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TreeDiffService 單元測試（方向③ spike）。
 *
 * 核心是證明「語意 diff」相對「文字 diff」的三個優勢：
 * 改 nodeId / 重排兄弟 → 編輯距離 0；只有真正改條件/結果才計入距離且可定位。
 */
@DisplayName("TreeDiffService - DecisionTree 語意結構 diff")
class TreeDiffServiceTest {

    private final TreeDiffService svc = new TreeDiffService();
    private final ObjectMapper mapper = new ObjectMapper();

    private RuleEnvelope env(String json) {
        try { return mapper.readValue(json, RuleEnvelope.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 基準樹：root(age>60) → 是:reject / 否:approve。 */
    private static final String BASE = """
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

    // ================================================================

    @Test
    @DisplayName("相同樹 → 編輯距離 0、3 節點全部不變")
    void identical() {
        TreeDiffResult r = svc.diff(env(BASE), env(BASE));
        assertTrue(r.comparable());
        assertEquals(0, r.editDistance());
        assertEquals(3, r.nodesUnchanged());
        assertTrue(r.operations().isEmpty());
    }

    @Test
    @DisplayName("只改 nodeId（N01→X1...）→ 編輯距離 0（語意 diff 的關鍵勝點）")
    void renamedNodeIdsOnly() {
        String renamed = BASE.replace("N01", "X1").replace("N02", "X2").replace("N03", "X3");
        TreeDiffResult r = svc.diff(env(BASE), env(renamed));
        assertEquals(0, r.editDistance(), "改 nodeId 不該被視為變更");
    }

    @Test
    @DisplayName("兄弟分支重新排序 → 編輯距離 0（canonical 排序）")
    void reorderedSiblings() {
        // 把「否:approve」分支排到「是:reject」之前
        String reordered = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01",
              "condition":{"field":"age","operator":"greaterThan","value":60},
              "branches":[
                {"label":"否","condition":{"field":"age","operator":"lessThanOrEqual","value":60},
                 "child":{"nodeId":"N03","results":[{"field":"decision","value":"approve"}]}},
                {"label":"是","condition":{"field":"age","operator":"greaterThan","value":60},
                 "child":{"nodeId":"N02","results":[{"field":"decision","value":"reject"}]}}
              ]}}}
            """;
        TreeDiffResult r = svc.diff(env(BASE), env(reordered));
        assertEquals(0, r.editDistance(), "重排兄弟不該被視為變更");
    }

    @Test
    @DisplayName("改一個葉節點結果（reject→defer）→ 距離 1、1 個 UPDATE")
    void changedLeafResult() {
        String changed = BASE.replace("\"value\":\"reject\"", "\"value\":\"defer\"");
        TreeDiffResult r = svc.diff(env(BASE), env(changed));
        assertEquals(1, r.editDistance());
        assertEquals(1, r.nodesModified());
        assertEquals(1, r.operations().size());
        assertEquals("UPDATE", r.operations().get(0).type());
    }

    @Test
    @DisplayName("新增一個分支葉 → 至少 1 個 INSERT")
    void addedBranch() {
        String added = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01",
              "condition":{"field":"age","operator":"greaterThan","value":60},
              "branches":[
                {"label":"是","condition":{"field":"age","operator":"greaterThan","value":60},
                 "child":{"nodeId":"N02","results":[{"field":"decision","value":"reject"}]}},
                {"label":"否","condition":{"field":"age","operator":"lessThanOrEqual","value":60},
                 "child":{"nodeId":"N03","results":[{"field":"decision","value":"approve"}]}},
                {"label":"未知","condition":{"field":"age","operator":"isNull","value":null},
                 "child":{"nodeId":"N04","results":[{"field":"decision","value":"pending"}]}}
              ]}}}
            """;
        TreeDiffResult r = svc.diff(env(BASE), env(added));
        assertTrue(r.nodesAdded() >= 1, "應偵測到新增節點");
        assertTrue(r.operations().stream().anyMatch(o -> o.type().equals("INSERT")));
    }

    @Test
    @DisplayName("刪除一個分支葉 → 至少 1 個 DELETE")
    void removedBranch() {
        String removed = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01",
              "condition":{"field":"age","operator":"greaterThan","value":60},
              "branches":[
                {"label":"是","condition":{"field":"age","operator":"greaterThan","value":60},
                 "child":{"nodeId":"N02","results":[{"field":"decision","value":"reject"}]}}
              ]}}}
            """;
        TreeDiffResult r = svc.diff(env(BASE), env(removed));
        assertTrue(r.nodesRemoved() >= 1, "應偵測到刪除節點");
        assertTrue(r.operations().stream().anyMatch(o -> o.type().equals("DELETE")));
    }

    @Test
    @DisplayName("非 DecisionTree / 無 root → 回友善訊息、距離 0")
    void notATree() {
        TreeDiffResult r = svc.diff(env("{\"ruleType\":\"DecisionTable\",\"rule\":{}}"), env(BASE));
        assertFalse(r.comparable());
        assertEquals(0, r.editDistance());
        assertTrue(r.summary().contains("無法"));
    }

    @Test
    @DisplayName("超大樹（>200 節點）→ 拒絕比對、回友善訊息、距離 0（防 O(m²n²) hang）")
    void oversizedTreeRejected() {
        // root + 201 個葉分支 = 202 節點，超過 MAX_NODES=200
        StringBuilder sb = new StringBuilder(
                "{\"ruleType\":\"DecisionTree\",\"rule\":{\"root\":{\"nodeId\":\"N0\","
                        + "\"condition\":{\"field\":\"x\",\"operator\":\"equals\",\"value\":1},\"branches\":[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"label\":\"b").append(i).append("\",")
              .append("\"condition\":{\"field\":\"x\",\"operator\":\"equals\",\"value\":").append(i).append("},")
              .append("\"child\":{\"nodeId\":\"L").append(i).append("\",")
              .append("\"results\":[{\"field\":\"d\",\"value\":\"r").append(i).append("\"}]}}");
        }
        sb.append("]}}}");
        String big = sb.toString();

        TreeDiffResult r = svc.diff(env(big), env(BASE));
        assertFalse(r.comparable(), "超限應標記為不可比對");
        assertEquals(0, r.editDistance(), "超限應拒絕、不進行比對");
        assertTrue(r.operations().isEmpty());
        assertTrue(r.summary().contains("超過") || r.summary().contains("上限"),
                "應回節點數超限的友善訊息，實際：" + r.summary());
    }
}
