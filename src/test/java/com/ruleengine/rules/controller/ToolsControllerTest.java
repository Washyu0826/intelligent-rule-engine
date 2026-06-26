package com.ruleengine.rules.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ToolsController 整合測試 — MockMvc。
 *
 * 驗證：
 * - HTTP 端點正確回應
 * - @Validated 請求驗證（空 description → 400）
 * - 正常流程回傳正確結構
 * - 錯誤處理（GlobalExceptionHandler）
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ToolsController - HTTP 端點測試")
class ToolsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ================================================================
    // POST /tools/generate
    // ================================================================

    @Test
    @DisplayName("generate: 合法 JSON → 200 + RuleEnvelope")
    void generateWithValidJson() throws Exception {
        String fixture = loadFixture("fixtures/tc01-valid-first.json");
        String requestBody = """
                {"description": %s, "ruleType": "DecisionTable"}
                """.formatted(objectMapper.writeValueAsString(fixture));

        // v3.16: /generate 改 DeferredResult（LLM 工作移出 Tomcat worker），
        // MockMvc 需先等待非同步結果再 dispatch
        var mvcResult = mockMvc.perform(post("/tools/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult(30_000); // 等待背景生成完成（上限 30s，避免測試無限期掛住）

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envelope.ruleType").value("DecisionTable"))
                .andExpect(jsonPath("$.envelope.rule").exists())
                .andExpect(jsonPath("$.envelope.schemaVersion").exists())
                .andExpect(jsonPath("$.envelope.evaluation").exists())
                .andExpect(jsonPath("$.validation").exists())
                .andExpect(jsonPath("$.analysis").exists())
                .andExpect(jsonPath("$.durationMs").isNumber());
    }

    @Test
    @DisplayName("generate: 空 description → 400 驗證錯誤")
    void generateWithEmptyDescription() throws Exception {
        String requestBody = """
                {"description": "", "ruleType": "DecisionTable"}
                """;

        mockMvc.perform(post("/tools/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("generate: description 為 null → 400 驗證錯誤")
    void generateWithNullDescription() throws Exception {
        String requestBody = """
                {"ruleType": "DecisionTable"}
                """;

        mockMvc.perform(post("/tools/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // POST /tools/validate
    // ================================================================

    @Test
    @DisplayName("validate: 合法 JSON → 200 + valid=true")
    void validateWithValidJson() throws Exception {
        String fixture = loadFixture("fixtures/tc01-valid-first.json");
        String requestBody = """
                {"ruleJson": %s, "ruleType": "DecisionTable"}
                """.formatted(fixture);

        mockMvc.perform(post("/tools/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    @DisplayName("validate: 有錯誤的 JSON → 200 + valid=false + errors 非空")
    void validateWithInvalidJson() throws Exception {
        String fixture = loadFixture("fixtures/tc09-missing-field.json");
        String requestBody = """
                {"ruleJson": %s, "ruleType": "DecisionTable"}
                """.formatted(fixture);

        mockMvc.perform(post("/tools/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    @DisplayName("validate: null ruleJson → 200 + valid=false")
    void validateWithNullRuleJson() throws Exception {
        String requestBody = """
                {"ruleJson": null, "ruleType": "DecisionTable"}
                """;

        mockMvc.perform(post("/tools/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    // ================================================================
    // POST /tools/recommend
    // ================================================================

    @Test
    @DisplayName("recommend: 合法描述 → 200 + recommendedRuleType")
    void recommendWithDescription() throws Exception {
        String requestBody = """
                {"description": "根據客戶年齡和性別組合對照費率表"}
                """;

        mockMvc.perform(post("/tools/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedRuleType").exists())
                .andExpect(jsonPath("$.confidence").isNumber())
                .andExpect(jsonPath("$.alternatives").isArray());
    }

    @Test
    @DisplayName("recommend: 空 description → 400 驗證錯誤")
    void recommendWithEmptyDescription() throws Exception {
        String requestBody = """
                {"description": ""}
                """;

        mockMvc.perform(post("/tools/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // POST /tools/analyze
    // ================================================================

    @Test
    @DisplayName("analyze: 合法 JSON → 200 + coverageRate")
    void analyzeWithValidJson() throws Exception {
        String fixture = loadFixture("fixtures/tc01-valid-first.json");
        String requestBody = """
                {"ruleJson": %s, "ruleType": "DecisionTable"}
                """.formatted(fixture);

        mockMvc.perform(post("/tools/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageRate").isNumber())
                .andExpect(jsonPath("$.gaps").isArray())
                .andExpect(jsonPath("$.overlaps").isArray())
                .andExpect(jsonPath("$.simplifications").isArray());
    }

    @Test
    @DisplayName("analyze: null ruleJson → 200 + coverageRate=0")
    void analyzeWithNullRuleJson() throws Exception {
        String requestBody = """
                {"ruleJson": null, "ruleType": "DecisionTable"}
                """;

        mockMvc.perform(post("/tools/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageRate").value(0.0));
    }

    @Test
    @DisplayName("optimize: documented /tools/optimize route is active")
    void optimizeUsesDocumentedRoute() throws Exception {
        String fixture = loadFixture("fixtures/tree-valid-basic.json");
        String requestBody = """
                {"ruleJson": %s, "ruleType": "DecisionTree", "aggressive": true}
                """.formatted(fixture);

        mockMvc.perform(post("/tools/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimized.ruleType").value("DecisionTree"))
                .andExpect(jsonPath("$.appliedOptimizations").isArray());
    }

    @Test
    @DisplayName("audit: documented audit routes are active")
    void auditUsesDocumentedRoutes() throws Exception {
        mockMvc.perform(get("/tools/audit").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isArray())
                .andExpect(jsonPath("$.stats").exists());

        mockMvc.perform(get("/tools/audit/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLogs").isNumber())
                .andExpect(jsonPath("$.operationBreakdown").exists());
    }

    @Test
    @DisplayName("health: public frontend health route is active")
    void healthRouteIsActive() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ================================================================
    // 錯誤格式驗證
    // ================================================================

    @Test
    @DisplayName("非 JSON body → 適當的錯誤回應")
    void nonJsonBody() throws Exception {
        mockMvc.perform(post("/tools/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("this is not json"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // POST /tools/diff-tree（方向③ 語意結構 diff）
    // ================================================================
    @Test
    @DisplayName("diff-tree: 改 nodeId 不改語意 → 200 + 編輯距離 0")
    void diffTreeRenameOnlyIsZero() throws Exception {
        String before = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01","condition":{"field":"age","operator":"greaterThan","value":60},
              "branches":[
                {"label":"是","child":{"nodeId":"N02","results":[{"field":"decision","value":"reject"}]}},
                {"label":"否","child":{"nodeId":"N03","results":[{"field":"decision","value":"approve"}]}}
              ]}}}
            """;
        String after = before.replace("N01", "X1").replace("N02", "X2").replace("N03", "X3");
        String body = """
            {"before": %s, "after": %s}
            """.formatted(before, after);

        mockMvc.perform(post("/tools/diff-tree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editDistance").value(0))
                .andExpect(jsonPath("$.operations").isArray());
    }

    @Test
    @DisplayName("diff-tree: 改一個葉結果 → 200 + 1 個 UPDATE")
    void diffTreeChangedLeaf() throws Exception {
        String before = """
            {"ruleType":"DecisionTree","rule":{"root":{
              "nodeId":"N01","condition":{"field":"age","operator":"greaterThan","value":60},
              "branches":[
                {"label":"是","child":{"nodeId":"N02","results":[{"field":"decision","value":"reject"}]}},
                {"label":"否","child":{"nodeId":"N03","results":[{"field":"decision","value":"approve"}]}}
              ]}}}
            """;
        String after = before.replace("\"reject\"", "\"defer\"");
        String body = """
            {"before": %s, "after": %s}
            """.formatted(before, after);

        mockMvc.perform(post("/tools/diff-tree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editDistance").value(1))
                .andExpect(jsonPath("$.nodesModified").value(1));
    }

    // ================================================================
    // POST /tools/diff-table（方向③ L2 行為比對）
    // ================================================================
    @Test
    @DisplayName("diff-table: 新版移除規則 → 200 + 偵測到回歸(lostCount>0)")
    void diffTableDetectsRegression() throws Exception {
        String abc = """
            {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
              "inputs":[{"name":"channel","typeRef":"ENUM","allowedValues":["A","B","C"]}],
              "rules":[
                {"ruleId":"R1","conditions":[{"field":"channel","operator":"equals","value":"A"}],"results":[{"field":"decision","value":"accept"}]},
                {"ruleId":"R2","conditions":[{"field":"channel","operator":"equals","value":"B"}],"results":[{"field":"decision","value":"accept"}]},
                {"ruleId":"R3","conditions":[{"field":"channel","operator":"equals","value":"C"}],"results":[{"field":"decision","value":"reject"}]}
              ]}}
            """;
        String ab = """
            {"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST",
              "inputs":[{"name":"channel","typeRef":"ENUM","allowedValues":["A","B","C"]}],
              "rules":[
                {"ruleId":"R1","conditions":[{"field":"channel","operator":"equals","value":"A"}],"results":[{"field":"decision","value":"accept"}]},
                {"ruleId":"R2","conditions":[{"field":"channel","operator":"equals","value":"B"}],"results":[{"field":"decision","value":"accept"}]}
              ]}}
            """;
        String body = """
            {"before": %s, "after": %s}
            """.formatted(abc, ab);

        mockMvc.perform(post("/tools/diff-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lostCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.regions").isArray());
    }

    // ================================================================
    // Helper
    // ================================================================

    private String loadFixture(String path) throws Exception {
        return new org.springframework.core.io.ClassPathResource(path)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
