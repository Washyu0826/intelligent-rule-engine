package com.ruleengine.rules.service.narrative;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BusinessNarrativeService — 為整張規則表產出「業務語言的整體敘事」。
 *
 * <p>與 v3.9 {@code RuleExplanationService}（per-rule rationale）互補：
 * <ul>
 *   <li>RuleExplanationService：每條規則 1-2 句解釋「為什麼這樣定」</li>
 *   <li>BusinessNarrativeService：整張表的總覽敘事，給非工程使用者第一眼理解</li>
 * </ul>
 *
 * <p>設計原則（針對保險業務／精算師受眾）：
 * <ul>
 *   <li>禁用 operator/typeRef 這類技術詞；用「超過／不超過／屬於」等業務詞</li>
 *   <li>先講涵蓋範圍（被保人母體），再講主要規則脈絡，最後講例外</li>
 *   <li>給精算師一個 short note（可能的費率假設、覆蓋 gap 警示）</li>
 *   <li>結構化 JSON 輸出（非純文字），讓前端可分區呈現</li>
 * </ul>
 *
 * <p>opt-in：不自動跑在 {@code /generate}；由 {@code POST /tools/narrate} 或 MCP tool 觸發。
 * LLM 不可用時回傳 {@link BusinessNarrative#empty()}（non-fatal）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BusinessNarrativeService {

    private final LlmProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    // ========================================
    // 公開 API
    // ========================================

    /**
     * 為 envelope 產出整體業務敘事。
     *
     * @param description  原始自然語言描述（可為 null）
     * @param envelope     已生成的 RuleEnvelope
     * @param providerName 指定 LLM provider（null 用預設）
     * @return BusinessNarrative；LLM 不可用或解析失敗時回傳 empty（不拋異常）
     */
    public BusinessNarrative narrate(String description, RuleEnvelope envelope, String providerName) {
        if (envelope == null || envelope.getRule() == null) {
            return BusinessNarrative.empty();
        }

        LlmProvider provider = providerName != null && !providerName.isBlank()
                ? providerRegistry.getByName(providerName)
                : providerRegistry.getDefault();
        if (provider == null || !provider.isAvailable()) {
            log.warn("BusinessNarrativeService: LLM 不可用，回傳 rule-based fallback");
            return ruleBasedFallback(envelope);
        }

        long startMs = System.currentTimeMillis();
        try {
            String prompt = buildPrompt(description, envelope);
            String raw = provider.generateRuleJson(prompt);
            if (raw == null) {
                return ruleBasedFallback(envelope);
            }

            BusinessNarrative n = parseNarrativeJson(raw);
            // 若 LLM 回傳無法解析（summary 為空且 highlights 為空），視為失敗，走 fallback
            if ((n.getSummary() == null || n.getSummary().isBlank())
                    && (n.getHighlights() == null || n.getHighlights().isEmpty())) {
                log.warn("BusinessNarrativeService: LLM 回應無法解析，走 fallback");
                return ruleBasedFallback(envelope);
            }
            n.setProvider(provider.getProviderName());
            n.setDurationMs(System.currentTimeMillis() - startMs);
            log.info("BusinessNarrativeService | provider={} | highlights={} | duration={}ms",
                    provider.getProviderName(),
                    n.getHighlights() == null ? 0 : n.getHighlights().size(),
                    n.getDurationMs());
            return n;

        } catch (Exception e) {
            log.warn("BusinessNarrativeService 失敗（non-fatal）: {}", e.getMessage());
            return ruleBasedFallback(envelope);
        }
    }

    // ========================================
    // Prompt & JSON 解析
    // ========================================

    String buildPrompt(String description, RuleEnvelope envelope) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT);

        sb.append("\n\n# 原始業務描述\n");
        sb.append(description == null || description.isBlank() ? "(未提供)" : description);

        sb.append("\n\n# 規則結構摘要\n");
        sb.append("規則型態：").append(envelope.getRuleType()).append("\n");

        RuleEnvelope.Rule rule = envelope.getRule();
        if (rule.getInputs() != null) {
            sb.append("輸入欄位：");
            rule.getInputs().forEach(f -> sb.append(f.getName())
                    .append(allowedValuesHint(f))
                    .append(" / "));
            sb.append("\n");
        }
        if (rule.getOutputs() != null) {
            sb.append("輸出欄位：");
            rule.getOutputs().forEach(f -> sb.append(f.getName())
                    .append(allowedValuesHint(f))
                    .append(" / "));
            sb.append("\n");
        }
        if (rule.getHitPolicy() != null) {
            sb.append("命中策略：").append(rule.getHitPolicy()).append("\n");
        }
        if (envelope.getEvaluation() != null) {
            RuleEnvelope.Evaluation ev = envelope.getEvaluation();
            sb.append("覆蓋率：")
                    .append(ev.getCoverageRate() == null ? "?" :
                            String.format("%.0f%%", ev.getCoverageRate() * 100))
                    .append(" / 規則總數：").append(ev.getTotalScenarios())
                    .append("\n");
        }

        if (rule.getRules() != null) {
            sb.append("\n規則條列（共 ").append(rule.getRules().size()).append(" 條）：\n");
            int limit = Math.min(rule.getRules().size(), 20);  // 避免 prompt 過長
            for (int i = 0; i < limit; i++) {
                RuleEnvelope.RuleRow row = rule.getRules().get(i);
                sb.append("- ").append(row.getRuleId()).append(": IF ");
                if (row.getConditions() != null) {
                    for (int j = 0; j < row.getConditions().size(); j++) {
                        RuleEnvelope.Condition c = row.getConditions().get(j);
                        if (j > 0) sb.append(" AND ");
                        sb.append(c.getField()).append(" ")
                                .append(c.getOperator());
                        if (c.getValue() != null) sb.append(" ").append(c.getValue());
                    }
                }
                sb.append(" THEN ");
                if (row.getResults() != null) {
                    for (int j = 0; j < row.getResults().size(); j++) {
                        RuleEnvelope.Result r = row.getResults().get(j);
                        if (j > 0) sb.append(", ");
                        sb.append(r.getField()).append("=").append(r.getValue());
                    }
                }
                sb.append("\n");
            }
            if (rule.getRules().size() > 20) {
                sb.append("... (另有 ").append(rule.getRules().size() - 20).append(" 條省略)\n");
            }
        }

        sb.append("\n\n# 輸出格式\n");
        sb.append("""
                只輸出一個 JSON 物件，嚴格符合以下 schema，不要 markdown、不要解釋：
                {
                  "summary": "一段 50-120 字的業務口吻敘事，首句講這張表在決定什麼，後半講涵蓋範圍與命中策略",
                  "highlights": ["2-4 個關鍵要點（每點 20-40 字，用業務語言）", "..."],
                  "coverage": "涵蓋範圍一句話描述，例如『被保人年齡 20-65 歲，含男女性，是否有三高分別處理』",
                  "exceptions": ["特殊處理或例外規則的白話描述（0-3 條）", "..."],
                  "actuarialNote": "給精算師的一句話提醒，例如覆蓋 gap、假設前提、可能的費率影響；若無可給空字串"
                }

                用詞規範：
                - 禁用：operator、typeRef、hitPolicy、FIRST、ENUM、BOOLEAN 等技術詞
                - 使用：超過／不超過／屬於／落在 X 至 Y／包含／排除／命中第一條／任一命中
                - 數值描述具體（例：「20 至 65 歲」不是「某個年齡區間」）
                """);
        return sb.toString();
    }

    private String allowedValuesHint(RuleEnvelope.FieldDef f) {
        if (f.getAllowedValues() == null || f.getAllowedValues().isEmpty()) return "";
        return "(" + String.join("/", f.getAllowedValues()) + ")";
    }

    BusinessNarrative parseNarrativeJson(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```json?\\s*", "").replaceAll("```$", "").trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return BusinessNarrative.empty();
        cleaned = cleaned.substring(start, end + 1);

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            BusinessNarrative n = new BusinessNarrative();
            n.setSummary(textOrEmpty(node, "summary"));
            n.setCoverage(textOrEmpty(node, "coverage"));
            n.setActuarialNote(textOrEmpty(node, "actuarialNote"));
            n.setHighlights(textListOrEmpty(node, "highlights"));
            n.setExceptions(textListOrEmpty(node, "exceptions"));
            return n;
        } catch (Exception e) {
            log.warn("BusinessNarrativeService JSON parse 失敗: {}", e.getMessage());
            return BusinessNarrative.empty();
        }
    }

    private String textOrEmpty(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v != null && v.isTextual() ? v.asText().trim() : "";
    }

    private List<String> textListOrEmpty(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isArray()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        v.forEach(e -> {
            if (e.isTextual() && !e.asText().isBlank()) out.add(e.asText().trim());
        });
        return out;
    }

    // ========================================
    // Rule-based fallback（LLM 不可用時）
    // ========================================

    BusinessNarrative ruleBasedFallback(RuleEnvelope envelope) {
        BusinessNarrative n = new BusinessNarrative();
        RuleEnvelope.Rule rule = envelope.getRule();
        int ruleCount = rule.getRules() == null ? 0 : rule.getRules().size();
        int inputCount = rule.getInputs() == null ? 0 : rule.getInputs().size();

        n.setSummary(String.format("本規則表共 %d 條規則，依 %d 個輸入欄位判斷結果。",
                ruleCount, inputCount));
        n.setHighlights(List.of(
                "規則型態：" + (envelope.getRuleType() == null ? "未指定" : envelope.getRuleType()),
                "命中策略：" + (rule.getHitPolicy() == null ? "未指定" : rule.getHitPolicy())
        ));
        n.setCoverage(envelope.getEvaluation() != null && envelope.getEvaluation().getCoverageRate() != null
                ? String.format("覆蓋率約 %.0f%%", envelope.getEvaluation().getCoverageRate() * 100)
                : "覆蓋率未評估");
        n.setExceptions(Collections.emptyList());
        n.setActuarialNote("（LLM 不可用，為結構化 fallback 摘要；建議啟用 LLM 取得完整業務敘事）");
        n.setProvider("fallback");
        return n;
    }

    // ========================================
    // Prompt
    // ========================================

    private static final String SYSTEM_PROMPT = """
            你是保險業的業務分析師，擅長把結構化規則表轉為「業務主管與精算師能直接讀懂的中文敘事」。
            收到一張規則表後，請產出一段摘要、2-4 個要點、涵蓋範圍一句、可能的例外、以及給精算師的備註。
            必須用業務語言（核保／承保／費率／保額／被保人）而非技術語言（operator／typeRef）。
            嚴格只輸出符合 schema 的 JSON，不要 markdown、不要解釋文字。
            """;

    // ========================================
    // Output DTO（嵌入式）
    // ========================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BusinessNarrative {
        /** 一段 50-120 字的整體業務敘事 */
        private String summary;
        /** 2-4 個關鍵要點 */
        private List<String> highlights;
        /** 涵蓋範圍一句話 */
        private String coverage;
        /** 特殊例外（0-3 條） */
        private List<String> exceptions;
        /** 給精算師的一句話提醒 */
        private String actuarialNote;
        /** 使用的 provider 名 */
        private String provider;
        /** 耗時 */
        private long durationMs;

        public static BusinessNarrative empty() {
            BusinessNarrative n = new BusinessNarrative();
            n.setSummary("");
            n.setHighlights(Collections.emptyList());
            n.setCoverage("");
            n.setExceptions(Collections.emptyList());
            n.setActuarialNote("");
            return n;
        }
    }
}
