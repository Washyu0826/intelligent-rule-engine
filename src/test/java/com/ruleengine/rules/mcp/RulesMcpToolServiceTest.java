package com.ruleengine.rules.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.ai.mcp.server.enabled=true")
@DisplayName("RulesMcpToolService / RulesMcpInfoToolService")
class RulesMcpToolServiceTest {

    @Autowired
    private RulesMcpToolService toolService;

    @Autowired
    private RulesMcpInfoToolService infoToolService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RulesMcpResourceProvider resourceProvider;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private McpSyncServer mcpSyncServer;

    @Test
    @DisplayName("generate_rule_payload: valid fixture JSON -> RuleEnvelope")
    void generateRulePayloadReturnsEnvelope() throws Exception {
        String fixture = loadFixture("fixtures/tc01-valid-first.json");

        JsonNode response = objectMapper.readTree(
                toolService.generateRulePayload(fixture, "DecisionTable", null)
        );

        assertEquals("DecisionTable", response.path("ruleType").asText());
        assertTrue(response.has("rule"));
        assertTrue(response.has("evaluation"));
    }

    @Test
    @DisplayName("validate_rule_schema: valid fixture -> valid=true")
    void validateRuleSchemaReturnsValid() throws Exception {
        String fixture = loadFixture("fixtures/tc01-valid-first.json");

        JsonNode response = objectMapper.readTree(
                toolService.validateRuleSchema(fixture, "DecisionTable")
        );

        assertTrue(response.path("valid").asBoolean());
        assertTrue(response.path("errors").isArray());
    }

    @Test
    @DisplayName("recommend_rule_type: insurance description -> DecisionTable")
    void recommendRuleType() throws Exception {
        JsonNode response = objectMapper.readTree(
                toolService.recommendRuleType("根據年齡與高血壓條件產生保險核保決策表")
        );

        assertEquals("DecisionTable", response.path("recommendedRuleType").asText());
        assertTrue(response.path("confidence").asDouble() > 0.0);
    }

    @Test
    @DisplayName("analyze_rule_payload: valid fixture -> analysis result")
    void analyzeRulePayload() throws Exception {
        String fixture = loadFixture("fixtures/tc01-valid-first.json");

        JsonNode response = objectMapper.readTree(
                toolService.analyzeRulePayload(fixture, "DecisionTable")
        );

        assertTrue(response.has("coverageRate"));
        assertTrue(response.path("gaps").isArray());
        assertTrue(response.path("overlaps").isArray());
    }

    @Test
    @DisplayName("test_run_rules: one test case -> summary result")
    void testRunRules() throws Exception {
        String fixture = loadFixture("fixtures/tc01-valid-first.json");
        String testCasesJson = """
                [
                  {
                    "id": "TC01",
                    "description": %s,
                    "expectedRuleType": "DecisionTable",
                    "expectValid": true
                  }
                ]
                """.formatted(objectMapper.writeValueAsString(fixture));

        JsonNode response = objectMapper.readTree(
                toolService.testRunRules(testCasesJson)
        );

        assertEquals(1, response.path("total").asInt());
        assertEquals(1, response.path("passed").asInt());
    }

    @Test
    @DisplayName("get_server_info: official support DecisionTable + DecisionTree")
    void getServerInfo() throws Exception {
        JsonNode response = objectMapper.readTree(infoToolService.getServerInfo());

        assertEquals("Rules MCP Server", response.path("serverName").asText());
        assertEquals("tools-and-resources", response.path("mcpMode").asText());
        assertTrue(response.path("resourceCapabilityEnabled").asBoolean());
        assertEquals(2, response.path("officialSupportedTypes").size());
        assertEquals("DecisionTable",
                response.path("officialSupportedTypes").get(0).path("code").asText());
        assertEquals("DecisionTree",
                response.path("officialSupportedTypes").get(1).path("code").asText());
    }

    @Test
    @DisplayName("get_rule_type_example: DecisionTree -> returns tree example")
    void getRuleTypeExampleForDecisionTree() throws Exception {
        JsonNode response = objectMapper.readTree(infoToolService.getRuleTypeExample("DecisionTree"));

        assertEquals("DecisionTree", response.path("ruleType").asText());
        assertTrue(response.path("rule").has("root"), "DecisionTree example should have root node");
    }

    @Test
    @DisplayName("mcp resources: info, example, and schema resources are registered")
    void registeredResources() {
        assertTrue(applicationContext.containsBean("syncResourceSpecifications"));

        List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications = resourceProvider.syncResources();
        assertEquals(7, resourceSpecifications.size());
        assertTrue(resourceSpecifications.stream()
                .anyMatch(spec -> RulesMcpResourceProvider.SERVER_INFO_URI.equals(spec.resource().uri())));
        assertTrue(resourceSpecifications.stream()
                .anyMatch(spec -> RulesMcpResourceProvider.DECISION_TABLE_EXAMPLE_URI.equals(spec.resource().uri())));
        assertTrue(resourceSpecifications.stream()
                .anyMatch(spec -> RulesMcpResourceProvider.RULE_ENVELOPE_SCHEMA_URI.equals(spec.resource().uri())));
        assertTrue(resourceSpecifications.stream()
                .anyMatch(spec -> RulesMcpResourceProvider.VALIDATE_RESPONSE_SCHEMA_URI.equals(spec.resource().uri())));
        assertTrue(resourceSpecifications.stream()
                .anyMatch(spec -> RulesMcpResourceProvider.ANALYZE_RESPONSE_SCHEMA_URI.equals(spec.resource().uri())));
        assertTrue(resourceSpecifications.stream()
                .anyMatch(spec -> RulesMcpResourceProvider.RECOMMEND_RESPONSE_SCHEMA_URI.equals(spec.resource().uri())));
        assertTrue(resourceSpecifications.stream()
                .anyMatch(spec -> RulesMcpResourceProvider.TEST_RUN_RESPONSE_SCHEMA_URI.equals(spec.resource().uri())));
    }

    @Test
    @DisplayName("mcp resource read: server-info returns structured content")
    void readServerInfoResource() throws Exception {
        List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications = resourceProvider.syncResources();
        McpServerFeatures.SyncResourceSpecification specification = resourceSpecifications.stream()
                .filter(spec -> RulesMcpResourceProvider.SERVER_INFO_URI.equals(spec.resource().uri()))
                .findFirst()
                .orElseThrow();

        McpSchema.ReadResourceResult result = specification.readHandler()
                .apply(null, new McpSchema.ReadResourceRequest(RulesMcpResourceProvider.SERVER_INFO_URI));

        assertEquals(1, result.contents().size());
        McpSchema.TextResourceContents content = (McpSchema.TextResourceContents) result.contents().get(0);
        JsonNode response = objectMapper.readTree(content.text());
        assertEquals("Rules MCP Server", response.path("serverName").asText());
    }

    @Test
    @DisplayName("mcp resource read: rule-envelope schema exposes DecisionTable contract")
    void readRuleEnvelopeSchemaResource() throws Exception {
        List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications = resourceProvider.syncResources();
        McpServerFeatures.SyncResourceSpecification specification = resourceSpecifications.stream()
                .filter(spec -> RulesMcpResourceProvider.RULE_ENVELOPE_SCHEMA_URI.equals(spec.resource().uri()))
                .findFirst()
                .orElseThrow();

        McpSchema.ReadResourceResult result = specification.readHandler()
                .apply(null, new McpSchema.ReadResourceRequest(RulesMcpResourceProvider.RULE_ENVELOPE_SCHEMA_URI));

        McpSchema.TextResourceContents content = (McpSchema.TextResourceContents) result.contents().get(0);
        JsonNode schema = objectMapper.readTree(content.text());
        assertEquals("RuleEnvelope", schema.path("title").asText());
        assertEquals("DecisionTable",
                schema.path("properties").path("ruleType").path("enum").get(0).asText());
        assertTrue(schema.path("properties").path("rule").path("required").isArray());
    }

    @Test
    @DisplayName("mcp resource read: recommend and test-run schemas expose response contracts")
    void readRecommendAndTestRunSchemas() throws Exception {
        List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications = resourceProvider.syncResources();

        McpServerFeatures.SyncResourceSpecification recommendSpec = resourceSpecifications.stream()
                .filter(spec -> RulesMcpResourceProvider.RECOMMEND_RESPONSE_SCHEMA_URI.equals(spec.resource().uri()))
                .findFirst()
                .orElseThrow();
        McpSchema.TextResourceContents recommendContent = (McpSchema.TextResourceContents) recommendSpec.readHandler()
                .apply(null, new McpSchema.ReadResourceRequest(RulesMcpResourceProvider.RECOMMEND_RESPONSE_SCHEMA_URI))
                .contents().get(0);
        JsonNode recommendSchema = objectMapper.readTree(recommendContent.text());
        assertEquals("RecommendResponse", recommendSchema.path("title").asText());
        assertTrue(recommendSchema.path("properties").path("recommendedRuleType").path("enum").isArray());

        McpServerFeatures.SyncResourceSpecification testRunSpec = resourceSpecifications.stream()
                .filter(spec -> RulesMcpResourceProvider.TEST_RUN_RESPONSE_SCHEMA_URI.equals(spec.resource().uri()))
                .findFirst()
                .orElseThrow();
        McpSchema.TextResourceContents testRunContent = (McpSchema.TextResourceContents) testRunSpec.readHandler()
                .apply(null, new McpSchema.ReadResourceRequest(RulesMcpResourceProvider.TEST_RUN_RESPONSE_SCHEMA_URI))
                .contents().get(0);
        JsonNode testRunSchema = objectMapper.readTree(testRunContent.text());
        assertEquals("TestRunResponse", testRunSchema.path("title").asText());
        assertTrue(testRunSchema.path("properties").path("results").path("items").path("required").isArray());
    }

    @Test
    @DisplayName("mcp sync server: capabilities expose tools and resources")
    void mcpSyncServerCapabilities() {
        McpSchema.ServerCapabilities capabilities = mcpSyncServer.getServerCapabilities();

        assertNotNull(capabilities.tools());
        assertTrue(capabilities.tools().listChanged());
        assertNotNull(capabilities.resources());
        assertFalse(capabilities.resources().subscribe());
        assertTrue(capabilities.resources().listChanged());
        assertNotNull(mcpSyncServer.getServerInfo().name());
        assertFalse(mcpSyncServer.getServerInfo().name().isBlank());
        assertNotNull(mcpSyncServer.getServerInfo().version());
        assertFalse(mcpSyncServer.getServerInfo().version().isBlank());
    }

    private String loadFixture(String path) throws Exception {
        return new ClassPathResource(path)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
