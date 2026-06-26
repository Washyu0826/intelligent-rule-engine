package com.ruleengine.rules.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAiService v3.11.0 stub 測試。
 *
 * <p>stub 範圍：僅驗證最小可用版的合約 — isAvailable()、getProviderName()、
 * generateRuleJson() 的 happy path / 無 key / 非 2xx / 空 content / exception 分支。
 * Level A retry、prompt caching、self-repair 為刻意省略的未來工作，見 {@link OpenAiService} javadoc。
 */
class OpenAiServiceTest {

    private OpenAiService service;
    private RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new OpenAiService(mapper);
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "model", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 120);
    }

    // ============================================================
    // isAvailable / getProviderName
    // ============================================================

    @Test
    void isAvailable_nullKey_false() {
        ReflectionTestUtils.setField(service, "apiKey", null);
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_blankKey_false() {
        ReflectionTestUtils.setField(service, "apiKey", "   ");
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_setKey_true() {
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void getProviderName_includesModel() {
        assertThat(service.getProviderName()).isEqualTo("GPT (gpt-4o-mini)");
    }

    // ============================================================
    // generateRuleJson — no API key → null（不呼叫 RestTemplate）
    // ============================================================

    @Test
    void generateRuleJson_noApiKey_returnsNull() {
        ReflectionTestUtils.setField(service, "apiKey", null);
        String result = service.generateRuleJson("年齡大於 60 拒保");
        assertThat(result).isNull();
    }

    @Test
    void generateRuleJson_blankApiKey_returnsNull() {
        ReflectionTestUtils.setField(service, "apiKey", "");
        String result = service.generateRuleJson("test");
        assertThat(result).isNull();
    }

    // ============================================================
    // Happy path：OpenAI Chat Completions → 取 choices[0].message.content
    // ============================================================

    @Test
    void generateRuleJson_happyPath_returnsContent() {
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");

        String expectedJson = """
                {"ruleType":"DecisionTable","reason":"r","rule":{"hitPolicy":"FIRST","inputs":[],"outputs":[],"rules":[]}}""";
        String openAiResponse = """
                {
                  "id": "chatcmpl-xxx",
                  "object": "chat.completion",
                  "choices": [
                    { "index": 0, "message": { "role": "assistant", "content": %s }, "finish_reason": "stop" }
                  ],
                  "usage": { "prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150 }
                }""".formatted(toJsonStringLiteral(expectedJson));

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(openAiResponse));

        String result = service.generateRuleJson("年齡大於 60 拒保");

        assertThat(result).isEqualTo(expectedJson);
    }

    // ============================================================
    // Error branches
    // ============================================================

    @Test
    void generateRuleJson_non2xx_returnsNull() {
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":\"rate_limit\"}"));

        String result = service.generateRuleJson("test");

        assertThat(result).isNull();
    }

    @Test
    void generateRuleJson_nullBody_returnsNull() {
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(null));

        String result = service.generateRuleJson("test");

        assertThat(result).isNull();
    }

    @Test
    void generateRuleJson_missingContent_returnsNull() {
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        // choices 陣列為空 → choices[0].message.content missing
        String openAiResponse = """
                { "id": "x", "object": "chat.completion", "choices": [], "usage": {} }""";
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(openAiResponse));

        String result = service.generateRuleJson("test");

        assertThat(result).isNull();
    }

    @Test
    void generateRuleJson_exception_returnsNull() {
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("network down"));

        String result = service.generateRuleJson("test");

        assertThat(result).isNull();
    }

    @Test
    void generateRuleJson_nullDescription_stillCalls() {
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        String openAiResponse = """
                {"choices":[{"message":{"content":"{}"}}],"usage":{}}""";
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(openAiResponse));

        // null description → content 區塊改用空字串，不應丟 NPE
        String result = service.generateRuleJson(null);

        assertThat(result).isEqualTo("{}");
    }

    // ============================================================
    // Helper：把 JSON 字串再 escape 成 JSON 字串字面值
    // ============================================================

    private String toJsonStringLiteral(String raw) {
        try {
            return mapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
