package com.ruleengine.rules.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * OpenAI (GPT) LLM 服務 — 透過 Chat Completions API 將自然語言描述轉為 RuleEnvelope JSON。
 *
 * <p>v3.11.0 stub：僅做最小可用版。未設 {@code OPENAI_API_KEY} 時 {@link #isAvailable()} 回 false，
 * 前端 provider 選擇器會顯示「GPT」但 disabled，並顯示「API key 未設定」tooltip。
 *
 * <p>與 {@link ClaudeService} 的差異：不做 Level A retry、不做 prompt caching、不做 self-repair。
 * 這些進階功能未來可視需求加入。
 */
@Service
@Slf4j
public class OpenAiService implements LlmProvider {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rules.llm.openai.api-key:${OPENAI_API_KEY:}}")
    private String apiKey;

    @Value("${rules.llm.openai.model:gpt-4o-mini}")
    private String model;

    @Value("${rules.llm.timeout-seconds:120}")
    private int timeoutSeconds;

    public OpenAiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutSeconds * 1000 / 3, 30_000));
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.restTemplate = new RestTemplate(factory);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY 未設定！GPT provider 將顯示為 disabled。");
        } else {
            log.info("OpenAiService 已初始化 | model={} | timeout={}s | keyLen={}",
                    model, timeoutSeconds, apiKey.length());
        }
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getProviderName() {
        return "GPT (" + model + ")";
    }

    @Override
    public String generateRuleJson(String description) {
        if (!isAvailable()) {
            log.warn("OpenAI API Key 未設定，無法生成規則");
            return null;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system",
                                    "content", PromptGuard.systemGuardSection() + SYSTEM_PROMPT),
                            Map.of("role", "user",
                                    "content", PromptGuard.wrap(description))
                    ),
                    "response_format", Map.of("type", "json_object"),
                    "temperature", 0.1
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(API_URL, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("OpenAI API 回應失敗 | status={}", response.getStatusCode());
                return null;
            }

            JsonNode json = objectMapper.readTree(response.getBody());
            JsonNode content = json.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                log.error("OpenAI 回應缺少 choices[0].message.content");
                return null;
            }

            // Log token usage if available
            JsonNode usage = json.path("usage");
            if (!usage.isMissingNode()) {
                log.info("OpenAI token usage | prompt={} | completion={} | total={}",
                        usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt(),
                        usage.path("total_tokens").asInt());
            }

            return content.asText();

        } catch (Exception e) {
            log.error("OpenAI API 呼叫失敗: {}", e.getMessage());
            return null;
        }
    }

    private static final String SYSTEM_PROMPT = """
            你是業務規則生成專家。將使用者的中文業務描述轉為 RuleEnvelope JSON。

            輸出必須是合法 JSON，結構為：
            {
              "ruleType": "DecisionTable" | "DecisionTree",
              "reason": "為何選此型態的中文說明",
              "rule": {
                "hitPolicy": "FIRST" | "MULTI",
                "inputs": [ { "name": "fieldName", "typeRef": "INTEGER|DECIMAL|BOOLEAN|STRING|ENUM|DATE", "allowedValues": ["..."]? } ],
                "outputs": [ ... same shape ... ],
                "rules": [
                  {
                    "ruleId": "R01",
                    "priority": 1,
                    "conditions": [ { "field": "...", "operator": "equals|notEquals|greaterThan|greaterThanOrEqual|lessThan|lessThanOrEqual|between|in|notIn|isNull|isNotNull|anything", "value": ... } ],
                    "results":    [ { "field": "...", "value": ... } ]
                  }
                ]
              }
            }

            規則：
            - ENUM 型別必須有 allowedValues 且不能為空
            - between 的 value 為 [min, max]
            - in / notIn 的 value 為字串陣列
            - isNull / isNotNull / anything 不需要 value
            - ruleId 不可重複
            - conditions 中的 field 必須出現在 inputs
            - results 中的 field 必須出現在 outputs
            - BOOLEAN 值只接受 true / false（非字串）
            """;
}
