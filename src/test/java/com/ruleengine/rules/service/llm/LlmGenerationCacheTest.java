package com.ruleengine.rules.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ruleengine.rules.domain.dto.ToolDtos.GenerateRequest;
import com.ruleengine.rules.service.RuleService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code llmGenerate} 快取的回歸測試（v3.16.3）。
 *
 * <p>
 * <b>為什麼需要這個檔案：</b>在此之前，{@code @Cacheable} 標在各 provider 的
 * {@code doGenerateRuleJson} 上，卻只被同類別的 {@code generateRuleJson} 內部呼叫 ——
 * Spring Cache 預設 proxy 模式下內部呼叫不過 proxy，因此快取<b>從未生效</b>。
 * 專案當時對快取有 0 個測試，所以沒有任何訊號。本檔案的
 * {@link EndToEnd#同一描述第二次不再打LLM()} 就是當初會失敗的那條斷言。
 * </p>
 */
@SpringBootTest
@DisplayName("LLM 生成快取（v3.16.3 proxy 邊界修正）")
class LlmGenerationCacheTest {

    static WireMockServer wireMock;

    @Autowired LlmGenerationCache cache;
    @Autowired CacheManager cacheManager;
    @Autowired ObjectMapper objectMapper;
    @Autowired RuleService ruleService;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("rules.llm.provider", () -> "ollama");
        registry.add("rules.llm.ollama.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        // 快取現在是真的會生效的，測試之間必須清乾淨才有決定性
        var c = cacheManager.getCache("llmGenerate");
        assertNotNull(c, "llmGenerate 快取未註冊");
        c.clear();
    }

    // ================================================================
    // 快取語意（單元層）
    // ================================================================

    @Test
    @DisplayName("相同鍵：loader 只執行一次")
    void 相同鍵只執行一次() {
        AtomicInteger calls = new AtomicInteger();
        String key = LlmGenerationCache.key("test", "同一段描述", "normal", "DecisionTable");

        String first = cache.getOrGenerate(key, () -> {
            calls.incrementAndGet();
            return "ENVELOPE_JSON";
        });
        String second = cache.getOrGenerate(key, () -> {
            calls.incrementAndGet();
            return "SHOULD_NOT_BE_REACHED";
        });

        assertEquals("ENVELOPE_JSON", first);
        assertEquals("ENVELOPE_JSON", second, "第二次應回快取值");
        assertEquals(1, calls.get(), "快取命中時不得再次執行 loader");
    }

    @Test
    @DisplayName("null 結果不進快取 —— 一次暫時性失敗不該被固定 30 分鐘")
    void null不進快取() {
        AtomicInteger calls = new AtomicInteger();
        String key = LlmGenerationCache.key("test", "會先失敗的描述", "normal", "DecisionTable");

        assertNull(cache.getOrGenerate(key, () -> {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals("RECOVERED", cache.getOrGenerate(key, () -> {
            calls.incrementAndGet();
            return "RECOVERED";
        }), "上一次的 null 不該被快取住");
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("provider / mode / ruleType / description 都參與鍵")
    void 鍵的區分度() {
        String base = LlmGenerationCache.key("ollama", "描述A", "normal", "DecisionTable");
        assertNotEquals(base, LlmGenerationCache.key("claude", "描述A", "normal", "DecisionTable"));
        assertNotEquals(base, LlmGenerationCache.key("ollama", "描述B", "normal", "DecisionTable"));
        assertNotEquals(base, LlmGenerationCache.key("ollama", "描述A", "deep", "DecisionTable"));
        assertNotEquals(base, LlmGenerationCache.key("ollama", "描述A", "normal", "DecisionTree"));
        assertEquals(base, LlmGenerationCache.key("ollama", "描述A", "normal", "DecisionTable"));
    }

    @Test
    @DisplayName("描述以 SHA-256 摘要：鍵長度固定，不因 50KB 描述而膨脹")
    void 鍵長度不隨描述成長() {
        String shortKey = LlmGenerationCache.key("ollama", "短", "normal", "DecisionTable");
        String longKey = LlmGenerationCache.key("ollama", "長".repeat(50_000), "normal", "DecisionTable");
        assertEquals(shortKey.length(), longKey.length());
        assertNotEquals(shortKey, longKey);
    }

    // ================================================================
    // proxy 邊界（端對端）—— 這才是原本的 bug
    // ================================================================

    @Nested
    @DisplayName("端對端：快取確實攔在真正的 LLM 呼叫前面")
    class EndToEnd {

        private static final String MARKER = "快取回歸測試";

        @Test
        @DisplayName("同一描述連打兩次，只有第一次真的送到 LLM")
        void 同一描述第二次不再打LLM() throws Exception {
            String envelopeJson = """
                    {
                      "ruleType": "DecisionTable",
                      "reason": "快取回歸測試用規則",
                      "rule": {
                        "hitPolicy": "FIRST",
                        "inputs": [{ "name": "age", "typeRef": "INTEGER" }],
                        "outputs": [{ "name": "decision", "typeRef": "STRING" }],
                        "rules": [
                          { "ruleId": "R01", "priority": 1,
                            "conditions": [{ "field": "age", "operator": "greaterThan", "value": 60 }],
                            "results": [{ "field": "decision", "value": "reject" }] }
                        ]
                      }
                    }
                    """;
            wireMock.stubFor(post(urlPathEqualTo("/api/chat")).willReturn(
                    okJson("{\"message\":{\"content\":"
                            + objectMapper.writeValueAsString(envelopeJson) + "},\"done\":true}")));

            GenerateRequest req = GenerateRequest.builder()
                    .description(MARKER + "：年齡大於 60 拒保，否則承保")
                    .ruleType("DecisionTable").mode("normal").build();

            var first = ruleService.generateFull(req);
            var second = ruleService.generateFull(req);

            assertNotNull(first.getEnvelope());
            assertNotNull(second.getEnvelope());
            assertEquals(
                    first.getEnvelope().getRule().getRules().size(),
                    second.getEnvelope().getRule().getRules().size(),
                    "兩次結果應一致");

            // 只數「帶本測試 marker」的請求，避開 OllamaService 啟動時的 warm-up 呼叫
            wireMock.verify(exactly(1),
                    postRequestedFor(urlPathEqualTo("/api/chat")).withRequestBody(containing(MARKER)));
        }
    }

    // ================================================================
    // 維度預解析：取代 static ThreadLocal 的顯式契約（v3.16.3 #1）
    // ================================================================

    @Nested
    @DisplayName("維度預解析契約")
    class DimensionPreparseContract {

        @Autowired OllamaService ollamaService;
        @Autowired ClaudeService claudeService;

        @Test
        @DisplayName("只有本地小模型宣告需要維度預解析")
        void 只有Ollama宣告預解析() {
            assertTrue(ollamaService.usesDimensionPreparse(), "Ollama 是預解析機制的目標 provider");
            assertFalse(claudeService.usesDimensionPreparse(), "大模型不需要小模型補償機制");

            // 介面預設值必須是 false —— 新增 provider 時不應意外啟用維度後處理
            LlmProvider bare = new LlmProvider() {
                @Override public String generateRuleJson(String description) { return null; }
                @Override public boolean isAvailable() { return false; }
                @Override public String getProviderName() { return "bare"; }
            };
            assertFalse(bare.usesDimensionPreparse());
        }

        @Test
        @DisplayName("DescriptionDimensionParser 不得再持有跨請求的靜態可變狀態")
        void 不得有ThreadLocal靜態欄位() {
            for (Field f : DescriptionDimensionParser.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                assertNotEquals(ThreadLocal.class, f.getType(),
                        "欄位 " + f.getName() + "：ThreadLocal 在共用 llmExecutor 池上會把"
                                + "前一個請求的解析結果洩漏給下一個請求，維度必須顯式傳遞");
                assertTrue(Modifier.isFinal(f.getModifiers()),
                        "欄位 " + f.getName() + " 應為 final（僅允許 immutable 的 Pattern 常數）");
            }
        }
    }
}
