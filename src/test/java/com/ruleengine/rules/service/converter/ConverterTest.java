package com.ruleengine.rules.service.converter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tree ↔ Table 雙向轉換器測試。
 */
class ConverterTest {

    private TreeToTableConverter treeToTable;
    private TableToTreeConverter tableToTree;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        treeToTable = new TreeToTableConverter();
        tableToTree = new TableToTreeConverter();
    }

    private RuleEnvelope loadEnvelope(String fixture) throws IOException {
        var resource = new ClassPathResource("fixtures/" + fixture);
        String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return objectMapper.readValue(json, RuleEnvelope.class);
    }

    // ================================================================
    // TreeToTable
    // ================================================================

    @Nested
    @DisplayName("Tree → Table 轉換")
    class TreeToTableTests {

        @Test
        @DisplayName("基本轉換：3 葉節點 → 3 條 rules")
        void basicConversion() throws Exception {
            RuleEnvelope tree = loadEnvelope("tree-valid-basic.json");
            RuleEnvelope table = treeToTable.convert(tree);

            assertEquals("DecisionTable", table.getRuleType());
            assertNotNull(table.getRule());
            assertEquals("FIRST", table.getRule().getHitPolicy());
            assertEquals(3, table.getRule().getRules().size(),
                    "3 個葉節點應產生 3 條 rules");

            // 每條 rule 都應有 conditions 和 results
            for (RuleRow row : table.getRule().getRules()) {
                assertNotNull(row.getRuleId());
                assertFalse(row.getConditions().isEmpty(), row.getRuleId() + " 應有 conditions");
                assertFalse(row.getResults().isEmpty(), row.getRuleId() + " 應有 results");
            }
        }

        @Test
        @DisplayName("轉換保留 inputs/outputs 定義")
        void preservesFieldDefs() throws Exception {
            RuleEnvelope tree = loadEnvelope("tree-valid-basic.json");
            RuleEnvelope table = treeToTable.convert(tree);

            assertEquals(tree.getRule().getInputs().size(), table.getRule().getInputs().size());
            assertEquals(tree.getRule().getOutputs().size(), table.getRule().getOutputs().size());
        }

        @Test
        @DisplayName("缺失分支的 rule 欄位填入 anything")
        void fillsMissingWithAnything() throws Exception {
            RuleEnvelope tree = loadEnvelope("tree-valid-basic.json");
            RuleEnvelope table = treeToTable.convert(tree);

            // 檢查每條 rule 覆蓋所有 input 欄位
            int inputCount = tree.getRule().getInputs().size();
            for (RuleRow row : table.getRule().getRules()) {
                assertTrue(row.getConditions().size() >= inputCount,
                        row.getRuleId() + " 應覆蓋所有 " + inputCount + " 個 input 欄位");
            }
        }

        @Test
        @DisplayName("null root → IllegalArgumentException")
        void nullRoot() {
            RuleEnvelope empty = RuleEnvelope.builder()
                    .ruleType("DecisionTree")
                    .rule(Rule.builder().build())
                    .build();
            assertThrows(IllegalArgumentException.class, () -> treeToTable.convert(empty));
        }

        @Test
        @DisplayName("ruleId 從 R01 遞增")
        void ruleIdsSequential() throws Exception {
            RuleEnvelope tree = loadEnvelope("tree-valid-basic.json");
            RuleEnvelope table = treeToTable.convert(tree);

            List<RuleRow> rules = table.getRule().getRules();
            for (int i = 0; i < rules.size(); i++) {
                assertEquals(String.format("R%02d", i + 1), rules.get(i).getRuleId());
            }
        }

        @Test
        @DisplayName("否定邏輯：greaterThan → lessThanOrEqual")
        void negationLogic() throws Exception {
            RuleEnvelope tree = loadEnvelope("tree-valid-basic.json");
            RuleEnvelope table = treeToTable.convert(tree);

            // root 條件是 age greaterThan 60
            // falseBranch 的 rule 應有 age lessThanOrEqual 60
            RuleRow falsePathRule = table.getRule().getRules().stream()
                    .filter(r -> r.getConditions().stream()
                            .anyMatch(c -> "age".equals(c.getField())
                                    && "lessThanOrEqual".equals(c.getOperator())))
                    .findFirst()
                    .orElse(null);
            assertNotNull(falsePathRule, "應有 age lessThanOrEqual 60 的 rule（falseBranch 否定）");
        }
    }

    // ================================================================
    // TableToTree
    // ================================================================

    @Nested
    @DisplayName("Table → Tree 轉換")
    class TableToTreeTests {

        @Test
        @DisplayName("基本轉換：DecisionTable → DecisionTree")
        void basicConversion() throws Exception {
            RuleEnvelope table = loadEnvelope("tc01-valid-first.json");
            RuleEnvelope tree = tableToTree.convert(table);

            assertEquals("DecisionTree", tree.getRuleType());
            assertNotNull(tree.getRule());
            assertNotNull(tree.getRule().getRoot());
            assertNotNull(tree.getRule().getRoot().getNodeId());
        }

        @Test
        @DisplayName("轉換後有葉節點")
        void hasLeaves() throws Exception {
            RuleEnvelope table = loadEnvelope("tc01-valid-first.json");
            RuleEnvelope tree = tableToTree.convert(table);

            int leafCount = countLeaves(tree.getRule().getRoot());
            assertTrue(leafCount > 0, "轉換後應至少有 1 個葉節點，實際 " + leafCount);
        }

        @Test
        @DisplayName("MULTI hitPolicy → 拒絕轉換")
        void rejectsMulti() throws Exception {
            RuleEnvelope multi = loadEnvelope("tc02-valid-multi.json");
            assertThrows(IllegalArgumentException.class,
                    () -> tableToTree.convert(multi),
                    "MULTI hitPolicy 不應支援轉換");
        }

        @Test
        @DisplayName("保留 inputs/outputs")
        void preservesFieldDefs() throws Exception {
            RuleEnvelope table = loadEnvelope("tc01-valid-first.json");
            RuleEnvelope tree = tableToTree.convert(table);

            assertEquals(table.getRule().getInputs().size(), tree.getRule().getInputs().size());
            assertEquals(table.getRule().getOutputs().size(), tree.getRule().getOutputs().size());
        }

        @Test
        @DisplayName("nodeId 從 N01 開始")
        void nodeIdsStartFromN01() throws Exception {
            RuleEnvelope table = loadEnvelope("tc01-valid-first.json");
            RuleEnvelope tree = tableToTree.convert(table);

            assertEquals("N01", tree.getRule().getRoot().getNodeId());
        }
    }

    // ================================================================
    // Round-trip 測試
    // ================================================================

    @Nested
    @DisplayName("Round-trip 轉換")
    class RoundTripTests {

        @Test
        @DisplayName("Tree → Table → Tree：葉節點數保持一致")
        void treeRoundTrip() throws Exception {
            RuleEnvelope originalTree = loadEnvelope("tree-valid-basic.json");
            int originalLeaves = countLeaves(originalTree.getRule().getRoot());

            // Tree → Table
            RuleEnvelope table = treeToTable.convert(originalTree);
            assertEquals(originalLeaves, table.getRule().getRules().size(),
                    "Table 的 rule 數應等於原始葉節點數");

            // Table → Tree
            RuleEnvelope reconstructedTree = tableToTree.convert(table);
            assertNotNull(reconstructedTree.getRule().getRoot());
            int reconstructedLeaves = countLeaves(reconstructedTree.getRule().getRoot());
            assertTrue(reconstructedLeaves >= originalLeaves,
                    "重建後葉節點數 (" + reconstructedLeaves + ") 應 >= 原始 (" + originalLeaves + ")");
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private int countLeaves(TreeNode node) {
        if (node == null) return 0;
        boolean hasBranches = node.getBranches() != null && !node.getBranches().isEmpty();
        boolean isLeaf = node.getResults() != null && !node.getResults().isEmpty()
                && node.getCondition() == null && !hasBranches;
        if (isLeaf) return 1;

        int count = 0;
        if (hasBranches) {
            for (var branch : node.getBranches()) {
                count += countLeaves(branch.getChild());
            }
        }
        count += countLeaves(node.getTrueBranch());
        count += countLeaves(node.getFalseBranch());
        return count;
    }
}
