package com.ruleengine.rules.controller;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link GroupJsonExportController}
 * (POST /tools/export/group-json) — Demo-sprint P1 #8.
 *
 * <p>Mirrors the annotation set of {@code ToolsControllerTest}:
 * {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} so the full Spring
 * context (including {@code GroupJsonExporter} + {@code GroupLayoutEngine}) is
 * exercised against real fixtures from {@code golden-tests/}.</p>
 *
 * <p>Contract asserted: DEMO_PLAN_3H.md §3.1.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GroupJsonExportController — POST /tools/export/group-json")
class GroupJsonExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ================================================================
    // sample-A — DecisionTable MULTI (4 rules)
    // ================================================================

    @Test
    @DisplayName("sample-A → 200 + tree.treeId starts with DRAFT- + non-empty nodes/edges + non-negative positions")
    void exportSampleA_returnsValidTreeJson() throws Exception {
        String envelopeJson = loadGolden("sample-A/expected-envelope.json");
        String requestBody = "{\"envelope\":" + envelopeJson + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/export/group-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvc.getResponse().getContentAsString());
        JsonNode tree = response.get("tree");
        assertThat(tree).as("tree object").isNotNull();

        assertThat(tree.get("treeId").asText())
                .as("tree.treeId convention DRAFT-<timestamp>")
                .startsWith("DRAFT-");

        JsonNode nodes = tree.get("nodes");
        JsonNode edges = tree.get("edges");
        assertThat(nodes.isArray()).isTrue();
        assertThat(edges.isArray()).isTrue();
        assertThat(nodes).as("tree.nodes non-empty").isNotEmpty();
        assertThat(edges).as("tree.edges non-empty").isNotEmpty();

        // Every node must carry a non-negative position
        for (JsonNode node : nodes) {
            JsonNode pos = node.get("position");
            assertThat(pos).as("position present on " + node.get("nodeId")).isNotNull();
            assertThat(pos.get("x").asInt())
                    .as("position.x for " + node.get("nodeId"))
                    .isGreaterThanOrEqualTo(0);
            assertThat(pos.get("y").asInt())
                    .as("position.y for " + node.get("nodeId"))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // ================================================================
    // sample-B — DecisionTable with extensions.globalGuards
    // ================================================================

    @Test
    @DisplayName("sample-B (含 globalGuards) → 至少一節點是 GUARD- 或 labelChinese 含「守門」（banner 呈現）")
    void exportSampleB_globalGuardsSurfaced() throws Exception {
        String envelopeJson = loadGolden("sample-B/expected-envelope-part1.json");
        String requestBody = "{\"envelope\":" + envelopeJson + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/export/group-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvc.getResponse().getContentAsString());
        JsonNode nodes = response.get("tree").get("nodes");
        assertThat(nodes).isNotEmpty();

        boolean hasGuard = false;
        for (JsonNode node : nodes) {
            String nodeId = node.path("nodeId").asText("");
            String label = node.path("labelChinese").asText("");
            if (nodeId.startsWith("GUARD-") || label.contains("守門")) {
                hasGuard = true;
                break;
            }
        }
        // The rewritten exporter is supposed to surface globalGuards as a banner.
        // If neither a GUARD- nodeId nor a "守門" label appears, that's a flag.
        assertThat(hasGuard)
                .as("expected at least one node with nodeId starting GUARD- "
                        + "or labelChinese containing 守門; nodes=" + nodes)
                .isTrue();
    }

    // ================================================================
    // DecisionTree — depth carries through to y positions
    // ================================================================

    @Test
    @DisplayName("3-level DecisionTree → 最深節點 position.y ≥ 240 (3 levels × 120px)")
    void exportDecisionTree_walksFullTree() throws Exception {
        RuleEnvelope envelope = buildThreeLevelTreeEnvelope();
        String envelopeJson = objectMapper.writeValueAsString(envelope);
        String requestBody = "{\"envelope\":" + envelopeJson + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/export/group-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvc.getResponse().getContentAsString());
        JsonNode nodes = response.get("tree").get("nodes");
        assertThat(nodes).as("3-level tree must emit nodes").isNotEmpty();

        int maxY = 0;
        for (JsonNode node : nodes) {
            maxY = Math.max(maxY, node.get("position").get("y").asInt());
        }
        // GroupLayoutEngine uses y = depth * 120; with 3 levels (depth 0/1/2),
        // the deepest leaves should sit at y >= 240.
        assertThat(maxY)
                .as("max y across nodes for a 3-level tree")
                .isGreaterThanOrEqualTo(240);
    }

    // ================================================================
    // between [a,b] → abbreviated edge label "[] a..b"
    // ================================================================

    @Test
    @DisplayName("between [18, 35] → 至少一條 edge.label 開頭符合 ^\\[\\] 18\\.\\.35")
    void exportRespectsAbbreviations() throws Exception {
        RuleEnvelope envelope = buildBetweenEnvelope();
        String envelopeJson = objectMapper.writeValueAsString(envelope);
        String requestBody = "{\"envelope\":" + envelopeJson + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/export/group-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvc.getResponse().getContentAsString());
        JsonNode edges = response.get("tree").get("edges");
        assertThat(edges).as("edges").isNotEmpty();

        boolean matched = false;
        for (JsonNode edge : edges) {
            String label = edge.path("label").asText("");
            if (label.matches("^\\[\\] 18\\.\\.35.*")) {
                matched = true;
                break;
            }
        }
        assertThat(matched)
                .as("expected at least one edge whose label matches ^\\[\\] 18\\.\\.35; "
                        + "edges=" + edges)
                .isTrue();
    }

    // ================================================================
    // null envelope → 400 (Bean Validation @NotNull)
    // ================================================================

    @Test
    @DisplayName("envelope=null → 400 Bean Validation 觸發")
    void nullEnvelope_returns400() throws Exception {
        mockMvc.perform(post("/tools/export/group-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"envelope\": null}"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // Helpers — fixture loaders + inline envelope builders
    // ================================================================

    private String loadGolden(String relativePath) throws Exception {
        // DemoEnvelopeIntegrationTest uses the same convention: golden-tests/
        // sits at the project root, and the test working directory is the
        // project root when run via Maven.
        Path p = Path.of("golden-tests", relativePath).toAbsolutePath();
        if (!Files.exists(p)) {
            p = Path.of("..", "golden-tests", relativePath).toAbsolutePath().normalize();
        }
        return Files.readString(p);
    }

    /**
     * Build a 3-level decision tree: root branch → 2 mid branches → 2+2 leaves.
     * Total 7 nodes; the deepest leaves should land at depth 2 → y = 240.
     */
    private RuleEnvelope buildThreeLevelTreeEnvelope() {
        // Leaves
        RuleEnvelope.TreeNode leaf1 = RuleEnvelope.TreeNode.builder()
                .nodeId("N04")
                .results(List.of(RuleEnvelope.Result.builder().field("decision").value("A1").build()))
                .build();
        RuleEnvelope.TreeNode leaf2 = RuleEnvelope.TreeNode.builder()
                .nodeId("N05")
                .results(List.of(RuleEnvelope.Result.builder().field("decision").value("A2").build()))
                .build();
        RuleEnvelope.TreeNode leaf3 = RuleEnvelope.TreeNode.builder()
                .nodeId("N06")
                .results(List.of(RuleEnvelope.Result.builder().field("decision").value("B1").build()))
                .build();
        RuleEnvelope.TreeNode leaf4 = RuleEnvelope.TreeNode.builder()
                .nodeId("N07")
                .results(List.of(RuleEnvelope.Result.builder().field("decision").value("B2").build()))
                .build();

        // Mid branches
        RuleEnvelope.TreeNode mid1 = RuleEnvelope.TreeNode.builder()
                .nodeId("N02")
                .condition(RuleEnvelope.Condition.builder()
                        .field("subType").operator("equals").value("X").build())
                .branches(List.of(
                        RuleEnvelope.Branch.builder()
                                .label("TRUE")
                                .condition(RuleEnvelope.Condition.builder()
                                        .field("subType").operator("equals").value("X").build())
                                .child(leaf1).build(),
                        RuleEnvelope.Branch.builder()
                                .label("FALSE")
                                .condition(RuleEnvelope.Condition.builder()
                                        .field("subType").operator("equals").value("Y").build())
                                .child(leaf2).build()))
                .build();
        RuleEnvelope.TreeNode mid2 = RuleEnvelope.TreeNode.builder()
                .nodeId("N03")
                .condition(RuleEnvelope.Condition.builder()
                        .field("subType").operator("equals").value("P").build())
                .branches(List.of(
                        RuleEnvelope.Branch.builder()
                                .label("TRUE")
                                .condition(RuleEnvelope.Condition.builder()
                                        .field("subType").operator("equals").value("P").build())
                                .child(leaf3).build(),
                        RuleEnvelope.Branch.builder()
                                .label("FALSE")
                                .condition(RuleEnvelope.Condition.builder()
                                        .field("subType").operator("equals").value("Q").build())
                                .child(leaf4).build()))
                .build();

        // Root
        RuleEnvelope.TreeNode root = RuleEnvelope.TreeNode.builder()
                .nodeId("N01")
                .condition(RuleEnvelope.Condition.builder()
                        .field("category").operator("equals").value("A").build())
                .branches(List.of(
                        RuleEnvelope.Branch.builder()
                                .label("A")
                                .condition(RuleEnvelope.Condition.builder()
                                        .field("category").operator("equals").value("A").build())
                                .child(mid1).build(),
                        RuleEnvelope.Branch.builder()
                                .label("B")
                                .condition(RuleEnvelope.Condition.builder()
                                        .field("category").operator("equals").value("B").build())
                                .child(mid2).build()))
                .build();

        return RuleEnvelope.builder()
                .ruleType("DecisionTree")
                .reason("test 3-level tree")
                .schemaVersion("1.0.0")
                .promptVersion("p-test")
                .rule(RuleEnvelope.Rule.builder()
                        .inputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("category").typeRef("STRING").build(),
                                RuleEnvelope.FieldDef.builder().name("subType").typeRef("STRING").build()))
                        .outputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("decision").typeRef("STRING").build()))
                        .root(root)
                        .build())
                .build();
    }

    /**
     * Build a tiny DecisionTree whose root branch carries a {@code between [18,35]}
     * condition. DecisionTree envelopes go through the exporter's
     * {@code walkTreeNode} path which renders {@code Condition.operator="between"}
     * verbatim as {@code "[] a..b"} per group-tree-rendering-spec.md §4.1.
     * (DecisionTable+FIRST would be rewritten into binary splits by
     * {@code TableToTreeConverter}, dropping the {@code between} operator.)
     */
    private RuleEnvelope buildBetweenEnvelope() {
        RuleEnvelope.TreeNode leafEligible = RuleEnvelope.TreeNode.builder()
                .nodeId("N02")
                .results(List.of(RuleEnvelope.Result.builder()
                        .field("eligible").value(true).build()))
                .build();
        RuleEnvelope.TreeNode leafIneligible = RuleEnvelope.TreeNode.builder()
                .nodeId("N03")
                .results(List.of(RuleEnvelope.Result.builder()
                        .field("eligible").value(false).build()))
                .build();
        RuleEnvelope.TreeNode root = RuleEnvelope.TreeNode.builder()
                .nodeId("N01")
                .condition(RuleEnvelope.Condition.builder()
                        .field("age").operator("between")
                        .value(List.of(18, 35)).build())
                .branches(List.of(
                        RuleEnvelope.Branch.builder()
                                .label("IN")
                                .condition(RuleEnvelope.Condition.builder()
                                        .field("age").operator("between")
                                        .value(List.of(18, 35)).build())
                                .child(leafEligible).build(),
                        RuleEnvelope.Branch.builder()
                                .label("OUT")
                                .condition(RuleEnvelope.Condition.builder()
                                        .field("age").operator("greaterThan")
                                        .value(35).build())
                                .child(leafIneligible).build()))
                .build();

        return RuleEnvelope.builder()
                .ruleType("DecisionTree")
                .reason("test between abbreviation")
                .schemaVersion("1.0.0")
                .promptVersion("p-test")
                .rule(RuleEnvelope.Rule.builder()
                        .inputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("age").typeRef("INTEGER").build()))
                        .outputs(List.of(
                                RuleEnvelope.FieldDef.builder().name("eligible").typeRef("BOOLEAN").build()))
                        .root(root)
                        .build())
                .build();
    }

    // Avoid unused-import warning for Map (kept for readability of the test file)
    @SuppressWarnings("unused")
    private Map<String, Object> _mapHolder() { return Map.of(); }
}
