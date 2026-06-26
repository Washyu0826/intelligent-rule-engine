package com.ruleengine.rules.service.evaluator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Per-Rule Rationale Service (v3.9.0) — 為每條規則補上 1-2 句中文解釋。
 *
 * <p>研究依據：
 * <ul>
 *   <li>2024-2025 "Explaining rules with LLMs"（Allemang @ Knowledge Graph Conference）
 *   <li>Reason+Verify pattern（arxiv 2510.24476 survey）— LLM 輸出推理鏈後再提交結構化結果
 *   <li>Brain.co 2024 "LLM-Generated Rules Engines: IF-THEN for Explainability in Regulated Industries"
 * </ul>
 *
 * <p>設計：
 * <ul>
 *   <li>一次 LLM 呼叫為整張表的所有規則產 rationale（避免 N 次呼叫）
 *   <li>回傳 rationale map（ruleId → 中文句子），由呼叫端回填到 {@link RuleEnvelope.RuleRow#setRationale(String)}
 *   <li>opt-in：不自動跑在 generateFull 流程中（保留延遲），只在 {@code POST /tools/explain} 或 MCP tool 呼叫時觸發
 *   <li>失敗 non-fatal：LLM 不可用時回傳空 map
 * </ul>
 *
 * <p>Prompt 設計要點：
 * <ul>
 *   <li>給 LLM 原始 description + rules 摘要，要求回 JSON {ruleId: "...", ruleId: "..."}
 *   <li>每條限制 1-2 句中文，需明確引用描述中的對應段落關鍵字
 *   <li>禁止解釋規則「做什麼」（那是顯而易見的）— 解釋「為什麼這樣定」
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RuleExplanationService {

    private final LlmProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    // ========================================
    // 公開 API
    // ========================================

    /**
     * 為 envelope 中每條規則產生 rationale，回傳 ruleId → 中文句子 map。
     * 可選指定 providerName；null 則用預設。
     *
     * @return map；LLM 不可用或解析失敗時回傳空 map（不拋異常）
     */
    public Map<String, String> explainAll(String description, RuleEnvelope envelope,
                                           String providerName) {
        if (envelope == null || envelope.getRule() == null
                || envelope.getRule().getRules() == null
                || envelope.getRule().getRules().isEmpty()) {
            return Map.of();
        }

        LlmProvider provider = providerName != null && !providerName.isBlank()
                ? providerRegistry.getByName(providerName)
                : providerRegistry.getDefault();
        if (provider == null || !provider.isAvailable()) {
            log.warn("RuleExplanationService: LLM 不可用，跳過 rationale 生成");
            return Map.of();
        }

        long startMs = System.currentTimeMillis();
        try {
            String prompt = buildPrompt(description, envelope);
            String response = provider.generateRuleJson(prompt);
            if (response == null) return Map.of();

            Map<String, String> map = parseRationaleJson(response);
            log.info("RuleExplanationService | provider={} | rationale count={} | duration={}ms",
                    provider.getProviderName(), map.size(),
                    System.currentTimeMillis() - startMs);
            return map;

        } catch (Exception e) {
            log.warn("RuleExplanationService 失敗（non-fatal）: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 將 rationale map 回填到 envelope 的每個 RuleRow（mutates in place）。
     *
     * @return 實際回填的規則數
     */
    public int applyRationales(RuleEnvelope envelope, Map<String, String> rationales) {
        if (envelope == null || envelope.getRule() == null
                || envelope.getRule().getRules() == null || rationales == null || rationales.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (RuleEnvelope.RuleRow row : envelope.getRule().getRules()) {
            String r = rationales.get(row.getRuleId());
            if (r != null && !r.isBlank()) {
                row.setRationale(r.trim());
                count++;
            }
        }
        return count;
    }

    /**
     * One-shot：explainAll + applyRationales。
     */
    public int explainAndApply(String description, RuleEnvelope envelope, String providerName) {
        return applyRationales(envelope, explainAll(description, envelope, providerName));
    }

    // ========================================
    // Prompt & JSON 解析
    // ========================================

    String buildPrompt(String description, RuleEnvelope envelope) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT);
        sb.append("\n\n# 使用者原始描述\n").append(description == null ? "(無)" : description);
        sb.append("\n\n# 規則列表（每條需要一個 rationale）\n");

        RuleEnvelope.Rule rule = envelope.getRule();
        if (rule.getInputs() != null) {
            sb.append("inputs: ");
            rule.getInputs().forEach(f -> sb.append(f.getName()).append("(").append(f.getTypeRef()).append(") "));
            sb.append("\n");
        }
        if (rule.getOutputs() != null) {
            sb.append("outputs: ");
            rule.getOutputs().forEach(f -> sb.append(f.getName()).append("(").append(f.getTypeRef()).append(") "));
            sb.append("\n\n");
        }

        for (RuleEnvelope.RuleRow row : rule.getRules()) {
            sb.append(row.getRuleId()).append(": IF ");
            if (row.getConditions() != null) {
                for (int i = 0; i < row.getConditions().size(); i++) {
                    RuleEnvelope.Condition c = row.getConditions().get(i);
                    if (i > 0) sb.append(" AND ");
                    sb.append(c.getField()).append(" ").append(c.getOperator());
                    if (c.getValue() != null) sb.append(" ").append(c.getValue());
                }
            }
            sb.append(" THEN ");
            if (row.getResults() != null) {
                for (int i = 0; i < row.getResults().size(); i++) {
                    RuleEnvelope.Result r = row.getResults().get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(r.getField()).append("=").append(r.getValue());
                }
            }
            sb.append("\n");
        }

        sb.append("""

                # 輸出格式
                只輸出一個 JSON 物件，key 是 ruleId，value 是 1-2 句中文說明：
                {
                  "R01": "...",
                  "R02": "..."
                }
                不要 markdown、不要其他文字。
                """);
        return sb.toString();
    }

    Map<String, String> parseRationaleJson(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```json?\\s*", "").replaceAll("```$", "").trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return Map.of();
        cleaned = cleaned.substring(start, end + 1);

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isObject()) return Map.of();
            Map<String, String> result = new LinkedHashMap<>();
            ObjectNode obj = (ObjectNode) node;
            obj.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) {
                    result.put(e.getKey(), e.getValue().asText());
                }
            });
            return result;
        } catch (Exception ex) {
            log.warn("RuleExplanationService JSON parse 失敗: {}", ex.getMessage());
            return Map.of();
        }
    }

    // ========================================
    // Prompt
    // ========================================

    private static final String SYSTEM_PROMPT = """
            你是業務規則解釋專家，協助 BA（業務分析師）理解每條規則背後的業務理由。

            任務：為每條規則產生 1-2 句**中文**說明，解釋「為什麼這條規則會這樣定」。
            規範：
            - 明確引用原始描述中的對應段落關鍵字或數字（例如：「依描述中『年齡大於 60』的要求」）
            - 不要解釋規則「做什麼」（這從 condition/result 顯而易見）
            - 避免過度空泛的話（如「此規則判斷條件」）
            - 每條限 30-60 個中文字
            只輸出 JSON，不要 markdown。
            """;
}
