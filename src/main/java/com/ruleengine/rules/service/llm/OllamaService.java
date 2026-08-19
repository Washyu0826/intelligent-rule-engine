package com.ruleengine.rules.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Ollama LLM 服務 — 透過 Ollama REST API 將自然語言規則描述轉為 RuleEnvelope JSON。
 *
 * 僅在 rules.llm.provider=ollama 時啟用。
 * 支援自託管 Ollama 或 Cloud Run 部署的 Ollama endpoint。
 */
@Service
@Slf4j
public class OllamaService implements LlmProvider {

    private static final int MAX_RETRY = 3;

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final DescriptionDimensionParser dimensionParser;
    private final LlmGenerationCache generationCache;

    @Value("${rules.llm.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${rules.llm.ollama.model:${rules.llm.model:qwen2.5:7b}}")
    private String model;

    @Value("${rules.llm.timeout-seconds:120}")
    private int timeoutSeconds;

    public OllamaService(ObjectMapper objectMapper, DescriptionDimensionParser dimensionParser,
                         LlmGenerationCache generationCache) {
        this.objectMapper = objectMapper;
        this.dimensionParser = dimensionParser;
        this.generationCache = generationCache;
    }

    @PostConstruct
    void init() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutSeconds * 1000 / 3, 30_000));
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.restTemplate = new RestTemplate(factory);
        log.info("OllamaService 已初始化 | baseUrl={} | model={} | timeout={}s",
                baseUrl, model, timeoutSeconds);

        // 非同步 warm-up：喚醒 Cloud Run 容器 + 預載模型
        Thread warmupThread = new Thread(this::warmUp, "ollama-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    /**
     * Warm-up：發送一個極短的請求，觸發 Cloud Run 容器啟動 + 模型載入。
     * 非同步執行，不阻塞應用啟動。
     */
    private void warmUp() {
        if (baseUrl == null || baseUrl.isBlank()) return;
        String url = baseUrl.replaceAll("/$", "") + "/api/chat";
        try {
            log.info("Ollama warm-up 開始 | url={} | model={}", url, model);
            long start = System.currentTimeMillis();

            Map<String, Object> warmUpBody = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", "hi")),
                    "stream", false,
                    "options", Map.of("num_predict", 1)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(warmUpBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            long durationMs = System.currentTimeMillis() - start;
            log.info("Ollama warm-up 完成 | status={} | durationMs={}",
                    response.getStatusCode(), durationMs);
        } catch (Exception e) {
            log.warn("Ollama warm-up 失敗（不影響正常功能）| error={}", e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public String getProviderName() {
        return "Ollama (" + model + ")";
    }

    /** 本地小模型：需要維度預解析補償，generator 端據此啟用維度擴展 / 笛卡爾積填充。 */
    @Override
    public boolean usesDimensionPreparse() {
        return true;
    }

    @Override
    public String generateRuleJson(String description) {
        return generateRuleJson(description, "normal", "DecisionTable");
    }

    @Override
    public String generateRuleJson(String description, String mode) {
        return generateRuleJson(description, mode, "DecisionTable");
    }

    @Override
    public String generateRuleJson(String description, String mode, String ruleType) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.error("Ollama base URL 未設定");
            return null;
        }
        String resolvedMode = mode != null ? mode : "normal";
        String resolvedType = ruleType != null ? ruleType : "DecisionTable";
        // 見 ClaudeService 同段註解：快取必須跨 bean 邊界才會生效。
        return generationCache.getOrGenerate(
                LlmGenerationCache.key("ollama", description, resolvedMode, resolvedType),
                () -> doGenerateRuleJson(description, resolvedMode, resolvedType));
    }

    @Override
    public String callWithSchema(String prompt, Map<String, Object> jsonSchema) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        Map<String, Object> schema = jsonSchema != null ? jsonSchema : buildRuleEnvelopeSchema();
        return callOllamaApiWithSchema(prompt, schema);
    }

    /**
     * 帶自訂 schema 的 Ollama API 呼叫（不含 retry，由呼叫端自行處理）。
     */
    private String callOllamaApiWithSchema(String prompt, Map<String, Object> schema) {
        String url = baseUrl.replaceAll("/$", "") + "/api/chat";
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            requestBody.put("stream", false);
            requestBody.put("options", Map.of("temperature", 0.1));
            requestBody.put("format", schema);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            long startMs = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            long durationMs = System.currentTimeMillis() - startMs;

            log.info("Ollama batch API | status={} | durationMs={}", response.getStatusCode(), durationMs);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return null;

            JsonNode responseJson = objectMapper.readTree(response.getBody());
            String text = responseJson.path("message").path("content").asText();
            return (text != null && !text.isBlank()) ? text : null;
        } catch (Exception e) {
            log.error("Ollama batch API 失敗：{}", e.getMessage());
            return null;
        }
    }

    public String doGenerateRuleJson(String description, String mode, String ruleType) {
        log.info("生成模式：{}, 規則型態：{}", mode, ruleType);

        // 預解析維度（Phase 2.2.0 增強）—— 純函式，只用於本次 prompt 組裝。
        // Generator 端的後處理由 usesDimensionPreparse() 旗標驅動、各自獨立 parse，
        // 不再透過 ThreadLocal 隱性共享（見 LlmProvider.usesDimensionPreparse javadoc）。
        DescriptionDimensionParser.ParsedDimensions dims = dimensionParser.parse(description);
        String prompt = buildPrompt(description, ruleType, dims);

        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            if (attempt > 0) {
                // 延遲重試，避免打到已回收的 Cloud Run 容器
                try {
                    long delayMs = 5000L * attempt;
                    log.info("Retry delay {}ms before attempt {}", delayMs, attempt);
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            String rawJson = callOllamaApi(prompt, attempt, ruleType);
            if (rawJson == null) {
                log.warn("Ollama API attempt {} 回傳 null", attempt);
                continue;
            }

            // 嘗試 sanitize + parse
            String sanitized = sanitizeJson(rawJson);
            String parseError = doParseCheck(sanitized);
            if (parseError != null) {
                log.warn("JSON parse 失敗 (attempt {}) | 原因：{}", attempt, parseError);
                // 用修復 prompt 重試
                prompt = buildRepairPrompt(buildPrompt(description, ruleType, dims), rawJson, parseError);
                continue;
            }

            log.info("JSON parse 成功{} (attempt {})",
                    !sanitized.equals(rawJson) ? "（經自動修復）" : "", attempt);

            // 維度驗證：檢查 LLM 輸出是否涵蓋所有預解析的輸入維度
            String dimError = validateDimensions(sanitized, dims);
            if (dimError != null && attempt < MAX_RETRY - 1) {
                log.warn("維度驗證失敗 (attempt {}) | {}", attempt, dimError);
                prompt = buildDimensionRepairPrompt(description, ruleType, dims, sanitized, dimError);
                continue;
            }
            if (dimError != null) {
                log.warn("維度驗證失敗（最後一次嘗試，仍回傳結果）| {}", dimError);
            }

            return sanitized;
        }

        log.warn("Ollama {} 次嘗試全部失敗", MAX_RETRY);
        return null;
    }

    /**
     * 驗證 LLM 輸出是否涵蓋所有預解析的輸入/輸出維度。
     */
    private String validateDimensions(String json, DescriptionDimensionParser.ParsedDimensions dims) {
        if (dims == null || (dims.inputs().isEmpty() && dims.outputs().isEmpty())) {
            return null; // 無預解析維度，跳過驗證
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode rule = root.path("rule");
            JsonNode inputs = rule.path("inputs");
            JsonNode outputs = rule.path("outputs");

            // 收集 LLM 生成的欄位名
            Set<String> generatedInputs = new java.util.HashSet<>();
            if (inputs.isArray()) {
                for (JsonNode inp : inputs) {
                    generatedInputs.add(inp.path("name").asText("").toLowerCase());
                }
            }
            Set<String> generatedOutputs = new java.util.HashSet<>();
            if (outputs.isArray()) {
                for (JsonNode out : outputs) {
                    generatedOutputs.add(out.path("name").asText("").toLowerCase());
                }
            }

            // 收集所有 field name 用於模糊匹配
            Set<String> allFieldNames = new java.util.HashSet<>();
            allFieldNames.addAll(generatedInputs);
            allFieldNames.addAll(generatedOutputs);

            List<String> missingInputs = new java.util.ArrayList<>();
            for (DescriptionDimensionParser.DimensionInfo dim : dims.inputs()) {
                if (!fieldMatches(dim, generatedInputs, allFieldNames)) {
                    missingInputs.add(dim.chineseName() + "(" + dim.suggestedEnglishName() + ")");
                }
            }

            List<String> missingOutputs = new java.util.ArrayList<>();
            for (DescriptionDimensionParser.DimensionInfo dim : dims.outputs()) {
                if (!fieldMatches(dim, generatedOutputs, allFieldNames)) {
                    missingOutputs.add(dim.chineseName() + "(" + dim.suggestedEnglishName() + ")");
                }
            }

            if (missingInputs.isEmpty() && missingOutputs.isEmpty()) {
                return null; // 全部通過
            }

            StringBuilder sb = new StringBuilder();
            if (!missingInputs.isEmpty()) {
                sb.append("缺少輸入欄位：").append(String.join(", ", missingInputs));
            }
            if (!missingOutputs.isEmpty()) {
                if (!sb.isEmpty()) sb.append("；");
                sb.append("缺少輸出欄位：").append(String.join(", ", missingOutputs));
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("維度驗證時 JSON parse 出錯：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 模糊匹配：LLM 可能用不同的英文名。
     * 支持：exact match、部分包含、camelCase 變體。
     */
    private boolean fieldMatches(DescriptionDimensionParser.DimensionInfo dim,
                                  Set<String> targetFields, Set<String> allFields) {
        String eng = dim.suggestedEnglishName().toLowerCase();
        // exact match
        if (targetFields.contains(eng)) return true;
        // 部分包含（e.g. "age" matches "ageGroup", "insuredAge"）
        for (String field : targetFields) {
            if (field.contains(eng) || eng.contains(field)) return true;
        }
        // 中文名匹配（LLM 可能用中文作為 field name）
        String ch = dim.chineseName().toLowerCase();
        for (String field : allFields) {
            if (field.contains(ch) || ch.contains(field)) return true;
        }
        return false;
    }

    /**
     * 維度修復 prompt：告訴 LLM 哪些維度缺少。
     */
    private String buildDimensionRepairPrompt(String description, String ruleType,
                                               DescriptionDimensionParser.ParsedDimensions dims,
                                               String currentJson, String dimError) {
        StringBuilder sb = new StringBuilder();
        sb.append("【嚴重錯誤 — 缺少欄位】\n\n");
        sb.append("你上一次生成的結果有嚴重缺漏：").append(dimError).append("\n\n");
        sb.append("★ 你必須使用以下所有欄位，一個都不能少：\n");
        sb.append(dimensionParser.formatForPrompt(dims));
        sb.append("\n請根據原始業務描述重新生成完整的 RuleEnvelope JSON。\n\n");
        sb.append("原始業務描述：\n").append(description).append("\n\n");
        sb.append("只輸出純 JSON，不要 markdown 標記。\n");
        return sb.toString();
    }

    @Override
    public String repairRuleJson(String originalDescription, String currentJson, String issues) {
        String repairPrompt = buildSemanticRepairPrompt(originalDescription, currentJson, issues);
        log.info("Level B Self-Repair | issues.length={}", issues.length());

        String rawJson = callOllamaApi(repairPrompt, 0);
        if (rawJson == null) return null;

        String parseError = tryParseJson(rawJson);
        if (parseError != null) {
            log.warn("Level B repair 回傳的 JSON 結構有誤：{}", parseError);
            return null;
        }
        return rawJson;
    }

    @Override
    public String generateRuleJsonStreaming(String description, String ruleType,
                                            java.util.function.Consumer<String> tokenConsumer) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        String resolvedType = ruleType != null ? ruleType : "DecisionTable";
        DescriptionDimensionParser.ParsedDimensions dims = dimensionParser.parse(description);
        String prompt = buildPrompt(description, resolvedType, dims);

        log.info("Streaming 生成開始 | ruleType={}", resolvedType);
        String result = callOllamaApiStreaming(prompt, resolvedType, tokenConsumer);
        if (result == null) return null;

        String sanitized = sanitizeJson(result);
        String parseError = doParseCheck(sanitized);
        if (parseError == null) return sanitized;

        log.warn("Streaming JSON parse 失敗 | 原因：{}", parseError);
        return null;
    }

    private String callOllamaApiStreaming(String prompt, String ruleType,
                                          java.util.function.Consumer<String> tokenConsumer) {
        String url = baseUrl.replaceAll("/$", "") + "/api/chat";
        try {
            // 使用 Java HttpURLConnection 處理 streaming（逐行讀取 NDJSON）
            java.net.URL apiUrl = new java.net.URI(url).toURL();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(Math.min(timeoutSeconds * 1000 / 3, 30_000));
            conn.setReadTimeout(timeoutSeconds * 1000);

            Map<String, Object> bodyMap = new HashMap<>();
            bodyMap.put("model", model);
            bodyMap.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            bodyMap.put("stream", true);
            bodyMap.put("options", Map.of("temperature", 0.1));
            bodyMap.put("format", buildRuleEnvelopeSchema(ruleType));
            String requestBody = objectMapper.writeValueAsString(bodyMap);

            try (var os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() != 200) {
                log.error("Ollama streaming API 回傳非 200：{}", conn.getResponseCode());
                return null;
            }

            StringBuilder fullContent = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode chunk = objectMapper.readTree(line);
                        String token = chunk.path("message").path("content").asText("");
                        if (!token.isEmpty()) {
                            fullContent.append(token);
                            if (tokenConsumer != null) {
                                tokenConsumer.accept(token);
                            }
                        }
                        if (chunk.path("done").asBoolean(false)) break;
                    } catch (Exception e) {
                        log.debug("Streaming chunk parse 跳過：{}", e.getMessage());
                    }
                }
            }

            log.info("Ollama streaming 完成 | output.length={}", fullContent.length());
            return fullContent.toString();

        } catch (Exception e) {
            log.error("Ollama streaming API 呼叫失敗：{}", e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Ollama API 呼叫（非 streaming）
    // ========================================================================

    private String callOllamaApi(String prompt, int attempt) {
        return callOllamaApi(prompt, attempt, "DecisionTable");
    }

    private String callOllamaApi(String prompt, int attempt, String ruleType) {
        String url = baseUrl.replaceAll("/$", "") + "/api/chat";

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("stream", false);
            requestBody.put("options", Map.of("temperature", 0.1));
            requestBody.put("format", buildRuleEnvelopeSchema(ruleType));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("呼叫 Ollama API | url={} | model={} | attempt={} | prompt.length={}",
                    url, model, attempt, prompt.length());
            long startMs = System.currentTimeMillis();

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            long durationMs = System.currentTimeMillis() - startMs;
            log.info("Ollama API 回應 | status={} | durationMs={} | attempt={}",
                    response.getStatusCode(), durationMs, attempt);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Ollama API 回傳非 200：{}", response.getStatusCode());
                return null;
            }

            JsonNode responseJson = objectMapper.readTree(response.getBody());
            String generatedText = responseJson.path("message").path("content").asText();

            if (generatedText == null || generatedText.isBlank()) {
                log.error("Ollama API 回傳空內容");
                return null;
            }

            log.info("Ollama 生成完成 | output.length={} | attempt={}", generatedText.length(), attempt);
            return generatedText;

        } catch (Exception e) {
            log.error("Ollama API 呼叫失敗（attempt={}）：{}", attempt, e.getMessage(), e);
            return null;
        }
    }

    // ========================================================================
    // JSON Parse 檢查
    // ========================================================================

    /**
     * 嘗試修復並 parse JSON。先嘗試原始內容，失敗後嘗試自動修復。
     */
    private String tryParseJson(String json) {
        if (json == null || json.isBlank()) return "回傳內容為空";

        // 先嘗試直接 parse
        String error = doParseCheck(json);
        if (error == null) return null;

        // 嘗試自動修復後再 parse
        String fixed = sanitizeJson(json);
        if (!fixed.equals(json)) {
            String fixedError = doParseCheck(fixed);
            if (fixedError == null) {
                log.info("JSON 自動修復成功");
                return null;
            }
        }

        return error;
    }

    private String doParseCheck(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.has("ruleType")) return "缺少頂層欄位 'ruleType'";
            if (!root.has("rule")) return "缺少頂層欄位 'rule'";
            JsonNode rule = root.get("rule");
            if (!rule.has("inputs") || !rule.get("inputs").isArray()) return "rule.inputs 缺少或不是陣列";
            if (!rule.has("outputs") || !rule.get("outputs").isArray()) return "rule.outputs 缺少或不是陣列";

            String ruleType = root.get("ruleType").asText("");
            if ("DecisionTree".equalsIgnoreCase(ruleType)) {
                if (!rule.has("root") || !rule.get("root").isObject()) return "rule.root 缺少或不是物件";
            } else {
                if (!rule.has("rules") || !rule.get("rules").isArray()) return "rule.rules 缺少或不是陣列";
                if (rule.get("rules").isEmpty()) return "rule.rules 陣列為空";
            }
            return null;
        } catch (Exception e) {
            return "JSON parse 失敗：" + e.getMessage();
        }
    }

    /**
     * 嘗試修復常見的 JSON 格式問題：
     * - 去掉 markdown code block 標記
     * - 修復括號不匹配（追蹤 stack 自動補齊）
     * - 移除尾部逗號
     */
    private String sanitizeJson(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        // 去掉 markdown code block
        if (s.startsWith("```json")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();

        // 找到第一個 { 和最後一個 }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return raw;
        s = s.substring(start, end + 1);

        // 移除 // 和 /* */ 風格的 JSON 註解（不在字串內的）
        s = removeJsonComments(s);

        // 修復尾部逗號（,] 或 ,}）
        s = s.replaceAll(",\\s*]", "]");
        s = s.replaceAll(",\\s*}", "}");

        // 嘗試用 bracket stack 修復不匹配的括號
        StringBuilder fixed = new StringBuilder();
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        char prev = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '"' && prev != '\\') {
                inString = !inString;
            }

            if (!inString) {
                if (c == '{' || c == '[') {
                    stack.push(c);
                } else if (c == '}') {
                    if (!stack.isEmpty() && stack.peek() == '{') {
                        stack.pop();
                    } else if (!stack.isEmpty() && stack.peek() == '[') {
                        // 應該是 ] 而非 }，自動修正
                        c = ']';
                        stack.pop();
                        log.debug("JSON 修復：將位置 {} 的 '}}' 修正為 ']'", i);
                    }
                } else if (c == ']') {
                    if (!stack.isEmpty() && stack.peek() == '[') {
                        stack.pop();
                    } else if (!stack.isEmpty() && stack.peek() == '{') {
                        c = '}';
                        stack.pop();
                        log.debug("JSON 修復：將位置 {} 的 ']' 修正為 '}}'", i);
                    }
                }
            }

            fixed.append(c);
            prev = c;
        }

        // 補齊未關閉的括號
        while (!stack.isEmpty()) {
            char open = stack.pop();
            fixed.append(open == '{' ? '}' : ']');
        }

        return fixed.toString();
    }

    /**
     * 移除 JSON 中的 // 和 /* * / 風格註解（僅處理不在字串內的）。
     */
    private String removeJsonComments(String json) {
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\' && inString) {
                sb.append(c);
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                sb.append(c);
                continue;
            }

            if (!inString && c == '/' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '/') {
                    // 跳過到行尾
                    while (i < json.length() && json.charAt(i) != '\n') i++;
                    continue;
                } else if (next == '*') {
                    // 跳過到 */
                    i += 2;
                    while (i + 1 < json.length() && !(json.charAt(i) == '*' && json.charAt(i + 1) == '/')) i++;
                    i++; // skip /
                    continue;
                }
            }

            sb.append(c);
        }

        return sb.toString();
    }

    // ========================================================================
    // JSON Schema（Ollama Structured Output — constrained decoding）
    // ========================================================================

    /**
     * 建構 RuleEnvelope 的 JSON Schema，供 Ollama format 參數使用。
     * Ollama 會將此 schema 轉換為 GBNF grammar，強制每個 token 都符合結構。
     */
    private Map<String, Object> buildRuleEnvelopeSchema() {
        return buildRuleEnvelopeSchema("DecisionTable");
    }

    private Map<String, Object> buildRuleEnvelopeSchema(String ruleType) {
        Map<String, Object> conditionSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "field", Map.of("type", "string"),
                        "operator", Map.of("type", "string", "enum", List.of(
                                "equals", "notEquals",
                                "greaterThan", "greaterThanOrEqual",
                                "lessThan", "lessThanOrEqual",
                                "between", "in", "notIn",
                                "isNull", "isNotNull", "anything"
                        )),
                        "value", Map.of()  // any type
                ),
                "required", List.of("field", "operator", "value")
        );

        Map<String, Object> resultSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "field", Map.of("type", "string"),
                        "value", Map.of()
                ),
                "required", List.of("field", "value")
        );

        Map<String, Object> fieldDefSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "typeRef", Map.of("type", "string"),
                        "allowedValues", Map.of("type", "array", "items", Map.of("type", "string"))
                ),
                "required", List.of("name", "typeRef")
        );

        Map<String, Object> ruleRowSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "ruleId", Map.of("type", "string"),
                        "priority", Map.of("type", "integer"),
                        "conditions", Map.of("type", "array", "items", conditionSchema),
                        "results", Map.of("type", "array", "items", resultSchema)
                ),
                "required", List.of("ruleId", "priority", "conditions", "results")
        );

        Map<String, Object> evaluationSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "completeness", Map.of("type", "string"),
                        "totalScenarios", Map.of("type", "integer"),
                        "coverageRate", Map.of("type", "number"),
                        "conflictDetection", Map.of("type", "string"),
                        "recommendedStrategy", Map.of("type", "string")
                ),
                "required", List.of("completeness", "totalScenarios", "coverageRate")
        );

        Map<String, Object> ruleSchema;
        if ("DecisionTree".equalsIgnoreCase(ruleType)) {
            Map<String, Object> treeNodeSchema = buildTreeNodeSchema(conditionSchema, resultSchema, 4, true);

            ruleSchema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "inputs", Map.of("type", "array", "items", fieldDefSchema),
                            "outputs", Map.of("type", "array", "items", fieldDefSchema),
                            "root", treeNodeSchema
                    ),
                    "required", List.of("inputs", "outputs", "root")
            );
        } else {
            ruleSchema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "hitPolicy", Map.of("type", "string"),
                            "inputs", Map.of("type", "array", "items", fieldDefSchema),
                            "outputs", Map.of("type", "array", "items", fieldDefSchema),
                            "rules", Map.of("type", "array", "items", ruleRowSchema)
                    ),
                    "required", List.of("hitPolicy", "inputs", "outputs", "rules")
            );
        }

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "ruleType", Map.of("type", "string"),
                        "reason", Map.of("type", "string"),
                        "evaluation", evaluationSchema,
                        "rule", ruleSchema
                ),
                "required", List.of("ruleType", "reason", "evaluation", "rule")
        );
    }

    private Map<String, Object> buildTreeNodeSchema(Map<String, Object> conditionSchema,
                                                    Map<String, Object> resultSchema,
                                                    int remainingDepth,
                                                    boolean requireBranches) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeId", Map.of("type", "string"));
        properties.put("condition", conditionSchema);
        properties.put("results", Map.of(
                "type", "array",
                "minItems", 1,
                "items", resultSchema
        ));

        Map<String, Object> childSchema = remainingDepth <= 0
                ? buildTreeLeafNodeSchema(resultSchema)
                : buildTreeNodeSchema(conditionSchema, resultSchema, remainingDepth - 1, false);

        Map<String, Object> branchSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "label", Map.of("type", "string"),
                        "condition", conditionSchema,
                        "child", childSchema
                ),
                "required", List.of("label", "condition", "child")
        );

        properties.put("branches", Map.of(
                "type", "array",
                "minItems", 2,
                "items", branchSchema
        ));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", requireBranches ? List.of("nodeId", "branches") : List.of("nodeId")
        );
    }

    private Map<String, Object> buildTreeLeafNodeSchema(Map<String, Object> resultSchema) {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "nodeId", Map.of("type", "string"),
                        "results", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", resultSchema
                        )
                ),
                "required", List.of("nodeId", "results")
        );
    }

    // ========================================================================
    // Prompt 建構（複用 GeminiService 的 prompt 設計）
    // ========================================================================

    private String buildPrompt(String description, String ruleType,
                               DescriptionDimensionParser.ParsedDimensions dims) {
        if ("DecisionTree".equalsIgnoreCase(ruleType)) {
            return buildTreePromptV2(description);
        }
        return buildTablePrompt(description, dims);
    }

    private String buildTablePrompt(String description,
                                     DescriptionDimensionParser.ParsedDimensions dims) {
        String dimSection = (dims != null) ? dimensionParser.formatForPrompt(dims) : "";

        return PromptGuard.systemGuardSection() + """
                # 業務規則描述（最重要！請仔細閱讀）

                """ + PromptGuard.wrap(description) + """


                """ + dimSection + """

                # 你的任務
                你是資深業務規則分析師，將上述業務規則描述轉換為 RuleEnvelope JSON。

                # 核心原則
                ★ **窮舉原則**：必須覆蓋所有條件組合
                ★ **欄位完整**：必須使用上方列出的所有輸入和輸出欄位，不可增減
                ★ **conditions 完備性**：每條 rule 的 conditions 數量 = inputs 數量，不檢查的欄位用 `{"field":"X","operator":"anything"}`（**不帶 value**）

                # RuleEnvelope JSON Schema

                ```json
                {
                  "ruleType": "DecisionTable",
                  "reason": "（中文 >= 80 字）說明條件欄位、結果欄位、規則條數、命中策略",
                  "evaluation": {
                    "completeness": "COMPLETE",
                    "totalScenarios": <規則條數>,
                    "coverageRate": 1.0,
                    "conflictDetection": "NO_CONFLICT",
                    "recommendedStrategy": "FIRST or MULTI"
                  },
                  "rule": {
                    "hitPolicy": "FIRST or MULTI",
                    "inputs": [
                      { "name": "fieldName", "typeRef": "型別", "allowedValues": ["值（ENUM必填）"] }
                    ],
                    "outputs": [
                      { "name": "fieldName", "typeRef": "型別", "allowedValues": ["值（ENUM必填）"] }
                    ],
                    "rules": [
                      {
                        "ruleId": "R01",
                        "priority": 1,
                        "conditions": [
                          { "field": "fieldName", "operator": "操作符", "value": <值> }
                        ],
                        "results": [
                          { "field": "fieldName", "value": <值> }
                        ]
                      }
                    ]
                  }
                }
                ```

                # 詳細規則

                ## 1. hitPolicy 選擇
                - **MULTI**：規則彼此獨立、可同時多條觸發。Spec 含**對稱訊息編號**（如 1.1 / 1.2 / 1.3 / 1.4 mirror）必選 MULTI；多條檢核項各拋自己錯訊也選 MULTI
                - **FIRST**：規則互斥決策（如年齡分段 < 18 / 18-65 / > 65 只命中一條）
                - **預設**：見到 spec 有對稱多條規則 → MULTI

                ## 2. Operator 與 value 對應
                - **需要 value**：equals / notEquals / greaterThan / greaterThanOrEqual / lessThan / lessThanOrEqual / between / in / notIn
                - **絕對不需要 value（嚴禁塞 value 欄位）**：anything / isNull / isNotNull
                - between 的 value 是 `[min, max]` 含兩端
                - in / notIn 的 value 是 `["a","b","c",...]`

                ## 3. 抽象「格式 / 規則」概念
                Spec 中的值若是**抽象描述**（「中華民國身分證字號格式」、「受理編號規則」、「有效授權書編號規則」、「業務員編號規則」）：
                - **絕對不要**塞 regex 進 value（schema 不支援 regex）
                - 視為 caller 端**預先計算好的 BOOLEAN**
                - inputs 該欄位 typeRef=BOOLEAN、name 加 `IsXxx` / `MatchesXxx` 後綴（如 `idFormatIsRocId`、`authMatchesAcceptanceFormat`）
                - condition 用 `"operator":"equals","value":true` 或 `"value":false`

                ## 4. ENUM allowedValues 命名慣例
                若 spec 只給一個具體值（如 TW）、需要「除此之外」對立值：
                - 用 `NON_X` 命名（如 `NON_TW`）
                - **絕對不要**用 OTHER / ANYTHING / ELSE 這種模糊詞
                - ENUM 必有 allowedValues 列舉

                ## 5. Output 欄位命名
                - 反映 spec 詞彙
                - Spec 說「拋訊息 / 錯誤訊息」→ outputs.name = `errorMessage`
                - **不要**用 `message` / `result` / `output` 這種泛稱

                ## 6. 訊息編號保留
                - Spec 含訊息編號（1.1 / 1.2 / 2.3 / 4.1）→ result value **保留編號前綴**
                - 例：spec「拋訊息 **1.1** 被保人 ID...」→ result value = `"1.1 被保人 ID..."`

                ## 7. Scope（檢核範圍）
                若 spec 有「**檢核範圍**」/「**僅限**」/「**僅在**」/「**非 ... 件**」等限定詞：
                - 加一條 R00 守門規則：`"ruleId":"R00"`、`"priority":0`、conditions 為**限定條件的反面**（如「通路 `notIn` [行動保險, 網路投保, 直效線上成交]」）、`"results":[{"field":"applicable","value":false}]`
                - outputs 加欄位 `{"name":"applicable","typeRef":"BOOLEAN"}`
                - 真正的檢核規則從 R01 開始

                ## 8. 跨欄位 OR
                若 spec 寫「**A 欄位 或 B 欄位 = X**」（如「新契約繳費管道 或 續期繳費管道 = 指定帳戶轉帳」）：
                - 展開成**兩條規則**：一條檢「A=X」、另一條檢「B=X」
                - 都拋同一個錯訊（編號相同）
                - priority 編號連續、不重複（如 priority 31 / 32）

                ## 9. 其他格式
                - BOOLEAN value 是 JSON boolean（`true` / `false`，**不是** `"true"`）
                - INTEGER value 是 JSON number
                - ruleId 格式 `R01`, `R02`...（含 R00 守門時從 R00 起）
                - 每條 rule 的 results 涵蓋所有 outputs
                - 只輸出純 JSON、不要 markdown 標記
                - allowedValues 必須是字串陣列、ENUM 必填
                - operator 限定：equals / notEquals / greaterThan / greaterThanOrEqual / lessThan / lessThanOrEqual / between / in / notIn / isNull / isNotNull / anything
                """;
    }

    private String buildTreePromptV2(String description) {
        return """
                You are a senior business-rules analyst. Convert the user's natural-language
                business process into one valid DecisionTree RuleEnvelope JSON object.

                Output rules:
                - Return JSON only. Do not wrap it in markdown.
                - ruleType must be exactly "DecisionTree".
                - The tree must use rule.root. Do not use rule.rules.
                - Use n-ary branches: each decision node has "branches", and each branch has
                  "label", "condition", and "child".
                - Every decision node must have at least two branches.
                - Leaf nodes have "results" and no "branches".
                - Never output a node that has a condition but has neither branches nor results.
                - Prefer branch-level conditions: a decision node may omit its own condition
                  when each branch has a clear condition.
                - Cover every business outcome described by the user; do not stop after the
                  first condition.
                - Every condition field must be declared in rule.inputs.
                - Every result field must be declared in rule.outputs.
                - Use these operators only: equals, notEquals, greaterThan,
                  greaterThanOrEqual, lessThan, lessThanOrEqual, between, in, notIn,
                  isNull, isNotNull, anything.
                - Do not use isNull or isNotNull unless the user explicitly says the value
                  is missing, blank, empty, null, or not provided.
                - For status, category, type, yes/no, and proof fields, prefer equals or notEquals.
                - For amount, hours, age, and count fields, prefer greaterThan,
                  greaterThanOrEqual, lessThan, or lessThanOrEqual.
                - For Chinese business text, keep user-facing result values readable; field
                  names should be stable English camelCase.

                Required JSON shape:
                {
                  "ruleType": "DecisionTree",
                  "reason": "short explanation",
                  "evaluation": {
                    "completeness": "COMPLETE",
                    "totalScenarios": 1,
                    "coverageRate": 1.0,
                    "conflictDetection": "NO_CONFLICT",
                    "recommendedStrategy": "FIRST"
                  },
                  "rule": {
                    "inputs": [
                      { "name": "fieldName", "typeRef": "ENUM", "allowedValues": ["A", "B"] }
                    ],
                    "outputs": [
                      { "name": "decision", "typeRef": "ENUM", "allowedValues": ["APPROVE", "REJECT"] }
                    ],
                    "root": {
                      "nodeId": "N01",
                      "branches": [
                        {
                          "label": "A",
                          "condition": { "field": "fieldName", "operator": "equals", "value": "A" },
                          "child": {
                            "nodeId": "N02",
                            "results": [
                              { "field": "decision", "value": "APPROVE" }
                            ]
                          }
                        },
                        {
                          "label": "not A",
                          "condition": { "field": "fieldName", "operator": "notEquals", "value": "A" },
                          "child": {
                            "nodeId": "N03",
                            "results": [
                              { "field": "decision", "value": "REJECT" }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }

                User business description:

                """ + PromptGuard.wrap(description);
    }

    private String buildTreePrompt(String description) {
        return """
                # 角色
                你是資深業務規則分析師，專精決策樹（DecisionTree）設計。
                將自然語言業務規則描述轉換為完整的 DecisionTree RuleEnvelope JSON。

                # DecisionTree JSON Schema（n-ary，唯一格式；禁止使用 trueBranch/falseBranch）

                ```json
                {
                  "ruleType": "DecisionTree",
                  "reason": "（中文 >= 50 字）說明條件層級關係和決策邏輯",
                  "evaluation": {
                    "completeness": "COMPLETE",
                    "totalScenarios": <葉節點數量>,
                    "coverageRate": 1.0,
                    "conflictDetection": "NO_CONFLICT",
                    "recommendedStrategy": "FIRST"
                  },
                  "rule": {
                    "inputs":  [...],
                    "outputs": [...],
                    "root": <Node>
                  }
                }
                ```

                # Node 結構
                - 分支節點：{ "nodeId": "Nxx", "condition": <Condition>, "branches": [<Branch>, ...] }
                - 葉節點：  { "nodeId": "Nxx", "results": [ { "field": "<output 名>", "value": <值> } ] }
                - Branch： { "label": "TRUE|FALSE|具體 ENUM 值", "condition": <Condition>, "child": <Node> }
                  · 二元（boolean / 數值閾值）：TRUE 分支 condition 與父完全相同；FALSE 分支用相反 operator
                  · n-ary（ENUM 多值）：每個 branch 各自 equals 到具體 allowedValue

                # 極簡範例
                ```json
                {
                  "nodeId": "N01",
                  "condition": { "field": "age", "operator": "lessThan", "value": 18 },
                  "branches": [
                    { "label": "TRUE",  "condition": { "field": "age", "operator": "lessThan", "value": 18 },
                      "child": { "nodeId": "N02", "results": [ { "field": "decision", "value": "拒保" } ] } },
                    { "label": "FALSE", "condition": { "field": "age", "operator": "greaterThanOrEqual", "value": 18 },
                      "child": { "nodeId": "N03", "results": [ { "field": "decision", "value": "承保" } ] } }
                  ]
                }
                ```

                # 嚴格忠實規則
                - inputs / outputs 欄位名稱必須由 description 萃取，禁止杜撰
                - condition / results 的 value 必須完全對應 description（不可改數字、不可翻譯 ENUM 值）

                只輸出純 JSON，不要 markdown 圍欄、不要解釋文字。

                ====================================
                # 請轉換以下規則描述為 DecisionTree

                """ + PromptGuard.systemGuardSection() + PromptGuard.wrap(description);
    }

    private String buildRepairPrompt(String originalPrompt, String brokenJson, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("【修正指示】\n");
        sb.append("上一次生成的 JSON 有以下錯誤：").append(error).append("\n");
        sb.append("請修正並重新輸出完整 JSON。只輸出純 JSON，不要 markdown 標記。\n\n");
        if (brokenJson != null && brokenJson.length() < 8000) {
            sb.append("上一次的（有錯誤的）輸出：\n").append(brokenJson).append("\n\n");
        }
        sb.append("原始要求：\n").append(originalPrompt);
        return sb.toString();
    }

    private String buildSemanticRepairPrompt(String originalDescription, String currentJson, String issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 角色\n你是資深業務規則分析師。你之前生成了一份 DecisionTable，驗證後發現問題。\n");
        sb.append("請根據以下問題清單修正規則，輸出修正後的完整 RuleEnvelope JSON。\n\n");
        sb.append("# 需要修復的問題\n").append(issues).append("\n\n");
        sb.append("# 原始業務描述\n").append(originalDescription).append("\n\n");
        if (currentJson != null && currentJson.length() < 12000) {
            sb.append("# 當前 JSON（請以此為基礎修正）\n").append(currentJson).append("\n");
        }
        sb.append("\n只輸出純 JSON，不要 markdown 標記。\n");
        return sb.toString();
    }
}
