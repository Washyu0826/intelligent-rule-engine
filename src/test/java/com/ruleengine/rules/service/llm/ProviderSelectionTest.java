package com.ruleengine.rules.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.dto.ToolDtos.GenerateRequest;
import com.ruleengine.rules.service.RuleService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * per-request provider 選擇的端到端驗證（review 修復輪）。
 *
 * <p>
 * <b>修復前的斷層：</b>前端送 {@code GenerateRequest.provider}，但
 * {@code RuleService.generateFull} 丟棄它 —— UI 的模型下拉選單形同虛設。
 * 本測試起<b>兩個 WireMock</b>（一個扮 Claude API、一個扮 Ollama API），
 * 各自回可辨識的內容 —— 「選誰就打誰」不是看 log 猜，是驗證哪個 mock 收到請求。
 * </p>
 *
 * <p>Claude 端點可 mock 是本輪的連帶修復：原 API_URL 是寫死常數，攔不到。</p>
 */
@SpringBootTest
@DisplayName("Provider 選擇 - 端到端（雙 WireMock）")
class ProviderSelectionTest {

    static WireMockServer claudeMock;
    static WireMockServer ollamaMock;

    @Autowired RuleService ruleService;
    @Autowired CacheManager cacheManager;
    @Autowired ObjectMapper objectMapper;

    @BeforeAll
    static void startMocks() {
        claudeMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        ollamaMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        claudeMock.start();
        ollamaMock.start();
    }

    @AfterAll
    static void stopMocks() {
        claudeMock.stop();
        ollamaMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rules.llm.provider", () -> "claude");   // 系統預設 = claude
        r.add("rules.llm.claude.api-key", () -> "test-key-for-wiremock");
        r.add("rules.llm.claude.base-url", () -> "http://localhost:" + claudeMock.port() + "/v1/messages");
        r.add("rules.llm.ollama.base-url", () -> "http://localhost:" + ollamaMock.port());
    }

    /** 兩個 mock 各回「可辨識」的 envelope —— reason 欄位標明來源。 */
    private String envelopeJson(String source) {
        return """
                {"ruleType":"DecisionTable","reason":"FROM-%s",
                 "rule":{"hitPolicy":"FIRST",
                  "inputs":[{"name":"age","typeRef":"INTEGER"}],
                  "outputs":[{"name":"decision","typeRef":"STRING"}],
                  "rules":[{"ruleId":"R01","priority":1,
                    "conditions":[{"field":"age","operator":"greaterThan","value":60}],
                    "results":[{"field":"decision","value":"reject"}]}]}}""".formatted(source);
    }

    @BeforeEach
    void resetAll() throws Exception {
        claudeMock.resetAll();
        ollamaMock.resetAll();
        // Claude Messages API 格式
        claudeMock.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(okJson(
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + objectMapper.writeValueAsString(envelopeJson("CLAUDE")) + "}]}")));
        // Ollama /api/chat 格式
        ollamaMock.stubFor(post(urlPathEqualTo("/api/chat")).willReturn(okJson(
                "{\"message\":{\"content\":"
                        + objectMapper.writeValueAsString(envelopeJson("OLLAMA")) + "},\"done\":true}")));
        // 快取如今真的會生效（v3.16.3 修復）—— 測試間必須清，否則第二題拿到第一題的快取
        var cache = cacheManager.getCache("llmGenerate");
        if (cache != null) cache.clear();
    }

    private String generateAndGetReason(String provider, String description) {
        GenerateRequest req = GenerateRequest.builder()
                .description(description).ruleType("DecisionTable").mode("normal")
                .provider(provider)
                .build();
        var response = ruleService.generateFull(req);
        assertNotNull(response.getEnvelope(), "生成應成功");
        return response.getEnvelope().getReason();
    }

    @Test
    @DisplayName("provider=null → 打系統預設（claude mock 收到請求，ollama mock 零請求）")
    void defaultProvider() {
        String reason = generateAndGetReason(null, "年齡大於六十歲拒保-預設路徑");
        assertEquals("FROM-CLAUDE", reason);
        claudeMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/messages")));
        ollamaMock.verify(0, postRequestedFor(urlPathEqualTo("/api/chat")));
    }

    @Test
    @DisplayName("provider=ollama → 真的打 ollama（claude mock 零請求）—— 斷層修復的直接證據")
    void explicitOllama() {
        String reason = generateAndGetReason("ollama", "年齡大於六十歲拒保-指定ollama");
        assertEquals("FROM-OLLAMA", reason);
        ollamaMock.verify(1, postRequestedFor(urlPathEqualTo("/api/chat")));
        claudeMock.verify(0, postRequestedFor(urlPathEqualTo("/v1/messages")));
    }

    @Test
    @DisplayName("provider=claude 顯式指定 → 打 claude")
    void explicitClaude() {
        String reason = generateAndGetReason("claude", "年齡大於六十歲拒保-指定claude");
        assertEquals("FROM-CLAUDE", reason);
        claudeMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/messages")));
    }

    @Test
    @DisplayName("provider=不存在的名稱 → 靜默回預設（不是 500）")
    void unknownProviderFallsBack() {
        String reason = generateAndGetReason("no-such-llm", "年齡大於六十歲拒保-未知provider");
        assertEquals("FROM-CLAUDE", reason, "未知名稱應回預設 provider");
    }

    @Test
    @DisplayName("DecisionTree 路徑同樣尊重 provider（併發 currentMode 欄位修復的路徑）")
    void treeRespectsProvider() {
        GenerateRequest req = GenerateRequest.builder()
                .description("樹狀規則-指定ollama：年齡大於六十歲拒保，否則承保")
                .ruleType("DecisionTree").mode("normal").provider("ollama")
                .build();
        ruleService.generateFull(req);
        ollamaMock.verify(1, postRequestedFor(urlPathEqualTo("/api/chat")));
        claudeMock.verify(0, postRequestedFor(urlPathEqualTo("/v1/messages")));
    }
}
