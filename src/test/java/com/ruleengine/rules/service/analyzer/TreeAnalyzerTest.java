package com.ruleengine.rules.service.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TreeAnalyzer 測試 — 覆蓋路徑枚舉、缺口偵測、簡化建議、dead code 偵測。
 */
class TreeAnalyzerTest {

    private TreeAnalyzer analyzer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        analyzer = new TreeAnalyzer(objectMapper);
    }

    private JsonNode loadFixture(String filename) throws IOException {
        var resource = new ClassPathResource("fixtures/" + filename);
        String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("完整樹：coverage=1.0, gaps=0")
    void validTreeFullCoverage() throws Exception {
        JsonNode tree = loadFixture("tree-valid-basic.json");
        AnalysisResult result = analyzer.analyze(tree);

        assertEquals(1.0, result.getCoverageRate(), "完整二元樹覆蓋率應為 1.0");
        assertTrue(result.getGaps().isEmpty(), "完整樹不應有 gaps");
        assertEquals(3, result.getTotalRules(), "應有 3 條決策路徑");
    }

    @Test
    @DisplayName("缺少 falseBranch：coverage < 1.0, 有 gap")
    void missingBranchDetectsGap() throws Exception {
        JsonNode tree = loadFixture("tree-missing-branch.json");
        AnalysisResult result = analyzer.analyze(tree);

        assertTrue(result.getCoverageRate() < 1.0, "缺少分支覆蓋率應 < 1.0");
        assertFalse(result.getGaps().isEmpty(), "應偵測到至少一個 gap");
        assertTrue(result.getGaps().get(0).getMessage().contains("falseBranch"));
    }

    @Test
    @DisplayName("加權覆蓋率：root 缺分支 = 50%")
    void weightedCoverageRootGap() throws Exception {
        // root 只有 trueBranch，缺 falseBranch → 加權覆蓋率 = 50%
        String json = """
                {
                  "ruleType": "DecisionTree",
                  "rule": {
                    "inputs": [{ "name": "a", "typeRef": "BOOLEAN" }],
                    "outputs": [{ "name": "b", "typeRef": "STRING" }],
                    "root": {
                      "nodeId": "N01",
                      "condition": { "field": "a", "operator": "equals", "value": true },
                      "trueBranch": { "nodeId": "N02", "results": [{ "field": "b", "value": "yes" }] }
                    }
                  }
                }
                """;
        AnalysisResult result = analyzer.analyze(objectMapper.readTree(json));
        assertEquals(0.5, result.getCoverageRate(), 0.01, "root 缺 falseBranch 應為 50%");
    }

    @Test
    @DisplayName("簡化建議：true/false 結果相同")
    void simplificationDetected() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTree",
                  "rule": {
                    "inputs": [{ "name": "a", "typeRef": "BOOLEAN" }],
                    "outputs": [{ "name": "b", "typeRef": "STRING" }],
                    "root": {
                      "nodeId": "N01",
                      "condition": { "field": "a", "operator": "equals", "value": true },
                      "trueBranch": { "nodeId": "N02", "results": [{ "field": "b", "value": "same" }] },
                      "falseBranch": { "nodeId": "N03", "results": [{ "field": "b", "value": "same" }] }
                    }
                  }
                }
                """;
        AnalysisResult result = analyzer.analyze(objectMapper.readTree(json));
        assertFalse(result.getSimplifications().isEmpty(), "相同結果應偵測到簡化建議");
        assertTrue(result.getSimplifications().get(0).getSuggestion().contains("語意相同"));
    }

    @Test
    @DisplayName("語意比較：field 順序不同但值相同 → 仍偵測為可簡化")
    void semanticEqualityFieldOrder() throws Exception {
        // trueBranch results: [{b: "x"}, {c: "y"}]
        // falseBranch results: [{c: "y"}, {b: "x"}]  ← 順序不同但語意相同
        String json = """
                {
                  "ruleType": "DecisionTree",
                  "rule": {
                    "inputs": [{ "name": "a", "typeRef": "BOOLEAN" }],
                    "outputs": [{ "name": "b", "typeRef": "STRING" }, { "name": "c", "typeRef": "STRING" }],
                    "root": {
                      "nodeId": "N01",
                      "condition": { "field": "a", "operator": "equals", "value": true },
                      "trueBranch": { "nodeId": "N02", "results": [
                        { "field": "b", "value": "x" }, { "field": "c", "value": "y" }
                      ]},
                      "falseBranch": { "nodeId": "N03", "results": [
                        { "field": "c", "value": "y" }, { "field": "b", "value": "x" }
                      ]}
                    }
                  }
                }
                """;
        AnalysisResult result = analyzer.analyze(objectMapper.readTree(json));
        assertFalse(result.getSimplifications().isEmpty(),
                "語意相同但 field 順序不同的結果應被偵測為可簡化");
    }

    @Test
    @DisplayName("Dead code 偵測：同欄位矛盾條件")
    void deadCodeDetection() throws Exception {
        // root: a > 10 = TRUE → child: a < 5 → 永遠不會觸發
        String json = """
                {
                  "ruleType": "DecisionTree",
                  "rule": {
                    "inputs": [{ "name": "a", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "b", "typeRef": "STRING" }],
                    "root": {
                      "nodeId": "N01",
                      "condition": { "field": "a", "operator": "greaterThan", "value": 10 },
                      "trueBranch": {
                        "nodeId": "N02",
                        "condition": { "field": "a", "operator": "lessThan", "value": 5 },
                        "trueBranch": { "nodeId": "N03", "results": [{ "field": "b", "value": "dead" }] },
                        "falseBranch": { "nodeId": "N04", "results": [{ "field": "b", "value": "ok" }] }
                      },
                      "falseBranch": { "nodeId": "N05", "results": [{ "field": "b", "value": "no" }] }
                    }
                  }
                }
                """;
        AnalysisResult result = analyzer.analyze(objectMapper.readTree(json));
        boolean hasDeadCode = result.getSimplifications().stream()
                .anyMatch(s -> s.getSuggestion().contains("Dead Code"));
        assertTrue(hasDeadCode, "a > 10 (TRUE) 的子節點 a < 5 應被偵測為 dead code");
    }

    @Test
    @DisplayName("null root → 空結果")
    void nullRoot() throws Exception {
        String json = """
                { "ruleType": "DecisionTree", "rule": { "inputs": [], "outputs": [] } }
                """;
        AnalysisResult result = analyzer.analyze(objectMapper.readTree(json));
        assertEquals(0.0, result.getCoverageRate());
        assertEquals(0, result.getTotalRules());
    }

    @Test
    @DisplayName("只有一個葉節點的最小樹：coverage=1.0")
    void singleLeafTree() throws Exception {
        String json = """
                {
                  "ruleType": "DecisionTree",
                  "rule": {
                    "inputs": [{ "name": "a", "typeRef": "INTEGER" }],
                    "outputs": [{ "name": "b", "typeRef": "STRING" }],
                    "root": { "nodeId": "N01", "results": [{ "field": "b", "value": "x" }] }
                  }
                }
                """;
        AnalysisResult result = analyzer.analyze(objectMapper.readTree(json));
        assertEquals(1.0, result.getCoverageRate());
        assertEquals(1, result.getTotalRules());
    }
}
