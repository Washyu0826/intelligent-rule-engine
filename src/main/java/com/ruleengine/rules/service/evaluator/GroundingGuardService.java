package com.ruleengine.rules.service.evaluator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.DimensionInfo;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.ParsedDimensions;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Symbolic Grounding Guard (v3.8.0) — 純符號幻覺偵測。
 *
 * <p>設計理念：AWS Automated Reasoning Checks (2024 preview) 的精神 —
 * 「以符號邏輯驗證 LLM 輸出」，不依賴第二個 LLM 評審。
 * 補足 {@link LlmJudgeEvaluator}（語意評分）之外的**確定性**防線。
 *
 * <p>幻覺定義：LLM 在 RuleEnvelope 中生成的欄位名／ENUM 值，若完全無法在：
 * <ul>
 *   <li>使用者原始 description 中（中文 / 英文，含 {@link DescriptionDimensionParser} 的中英對照）</li>
 *   <li>使用者明確提供的 {@code allowedFields} 白名單中</li>
 *   <li>通用 output 欄位名（decision / result / output 等）</li>
 * </ul>
 * 找到對應，則視為 ungrounded — 是 LLM 擅自發明的欄位或值。
 *
 * <p>輸出：{@link GroundingReport} 含 grounding ratio、ungrounded 清單、suspicion level。
 *
 * <p>成本：純 Java 字串匹配 + 既有 parser；無 LLM 呼叫；典型 &lt; 5ms。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GroundingGuardService {

    private final DescriptionDimensionParser dimensionParser;

    /**
     * 通用輸出欄位名（任何規則幾乎都會出現）— 自動視為 grounded。
     */
    private static final Set<String> COMMON_OUTPUT_FIELDS = Set.of(
            "decision", "result", "output", "outcome", "action",
            "status", "grade", "level", "remark", "note", "comment",
            "score", "category", "tag", "label"
    );

    /**
     * 布林 ENUM 的允許值（任何規則都可能用到）— 視為 grounded。
     */
    private static final Set<String> COMMON_BOOLEAN_VALUES = Set.of(
            "true", "false", "yes", "no", "是", "否", "有", "無"
    );

    // ================================================================
    // DTO
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GroundingReport {
        /** 被檢查項目中 grounded 的比例，0.0 ~ 1.0 */
        private double groundingRatio;
        /** 懷疑等級：LOW / MEDIUM / HIGH */
        private String suspicionLevel;
        /** 在描述中找不到對應的欄位 */
        private List<UngroundedField> ungroundedFields;
        /** 在描述中找不到對應的 ENUM 允許值 */
        private List<UngroundedValue> ungroundedEnumValues;
        /** 總檢查項目數（含 ENUM 值展開） */
        private int totalChecks;
        /** 被 ground 的項目數 */
        private int groundedChecks;
        /** 本次檢查耗時（毫秒） */
        private long durationMs;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UngroundedField {
        private String name;
        /** "inputs" or "outputs" */
        private String source;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UngroundedValue {
        private String field;
        private String value;
    }

    // ================================================================
    // 主 API
    // ================================================================

    /**
     * 檢查 RuleEnvelope 是否符合原始描述 — 不依賴 LLM 的符號幻覺偵測。
     *
     * @param description    使用者原始自然語言描述
     * @param allowedFields  使用者明確指定的允許欄位（可 null）
     * @param envelope       LLM 生成的 RuleEnvelope
     * @return GroundingReport，若 envelope 為空則回傳 ratio=1.0 的 trivial report
     */
    public GroundingReport check(String description, List<String> allowedFields,
                                  RuleEnvelope envelope) {
        long startMs = System.currentTimeMillis();

        if (envelope == null || envelope.getRule() == null) {
            return trivialReport(startMs);
        }

        String descLower = description == null ? "" : description.toLowerCase();
        Set<String> allowedSet = allowedFields == null
                ? Set.of()
                : new HashSet<>(allowedFields.stream().map(String::toLowerCase).toList());

        // 用 parser 取得中英對照，擴充 grounded 欄位名集合。
        // v3.8.0：優先用 inferExpectedEnglishFields()（純字典 substring 檢查），
        //         它不依賴 parse() 的括號格式。parse() 結果併入作為補充。
        Set<String> groundedEnglishFromChinese =
                new HashSet<>(dimensionParser.inferExpectedEnglishFields(description == null ? "" : description));
        Set<String> chineseNamesFromDescription = new HashSet<>();
        ParsedDimensions parsed = dimensionParser.parse(description == null ? "" : description);
        for (DimensionInfo d : parsed.inputs()) {
            if (d.suggestedEnglishName() != null) {
                groundedEnglishFromChinese.add(d.suggestedEnglishName().toLowerCase());
            }
            if (d.chineseName() != null) chineseNamesFromDescription.add(d.chineseName());
        }
        for (DimensionInfo d : parsed.outputs()) {
            if (d.suggestedEnglishName() != null) {
                groundedEnglishFromChinese.add(d.suggestedEnglishName().toLowerCase());
            }
            if (d.chineseName() != null) chineseNamesFromDescription.add(d.chineseName());
        }

        List<UngroundedField> ungroundedFields = new ArrayList<>();
        List<UngroundedValue> ungroundedValues = new ArrayList<>();
        int total = 0;
        int grounded = 0;

        var rule = envelope.getRule();
        total += countAndCheckFields(rule.getInputs(), "inputs",
                descLower, allowedSet, groundedEnglishFromChinese, chineseNamesFromDescription,
                ungroundedFields, ungroundedValues);
        grounded = total - ungroundedFields.size() - ungroundedValues.size();

        int outputsTotal = countAndCheckFields(rule.getOutputs(), "outputs",
                descLower, allowedSet, groundedEnglishFromChinese, chineseNamesFromDescription,
                ungroundedFields, ungroundedValues);
        total += outputsTotal;
        grounded = total - ungroundedFields.size() - ungroundedValues.size();

        double ratio = total == 0 ? 1.0 : (double) grounded / total;
        String suspicion = suspicionOf(ratio);

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("Grounding check | total={} | grounded={} | ratio={} | suspicion={} | duration={}ms",
                total, grounded, String.format("%.2f", ratio), suspicion, durationMs);

        return GroundingReport.builder()
                .groundingRatio(ratio)
                .suspicionLevel(suspicion)
                .ungroundedFields(ungroundedFields.isEmpty() ? null : ungroundedFields)
                .ungroundedEnumValues(ungroundedValues.isEmpty() ? null : ungroundedValues)
                .totalChecks(total)
                .groundedChecks(grounded)
                .durationMs(durationMs)
                .build();
    }

    // ================================================================
    // 內部：檢查一個 FieldDef 列表（inputs 或 outputs）
    // ================================================================

    private int countAndCheckFields(List<RuleEnvelope.FieldDef> fields, String source,
                                    String descLower, Set<String> allowedSet,
                                    Set<String> groundedEnglishFromChinese,
                                    Set<String> chineseNamesFromDescription,
                                    List<UngroundedField> ungroundedFields,
                                    List<UngroundedValue> ungroundedValues) {
        if (fields == null || fields.isEmpty()) return 0;
        int total = 0;
        for (RuleEnvelope.FieldDef f : fields) {
            if (f.getName() == null) continue;
            total++;

            boolean fieldGrounded = isFieldGrounded(
                    f.getName(), source, descLower, allowedSet, groundedEnglishFromChinese);
            if (!fieldGrounded) {
                ungroundedFields.add(UngroundedField.builder()
                        .name(f.getName()).source(source).build());
            }

            // ENUM 值逐個檢查
            if ("ENUM".equalsIgnoreCase(f.getTypeRef()) && f.getAllowedValues() != null) {
                for (String v : f.getAllowedValues()) {
                    if (v == null || v.isBlank()) continue;
                    total++;
                    if (!isEnumValueGrounded(v, descLower, chineseNamesFromDescription)) {
                        ungroundedValues.add(UngroundedValue.builder()
                                .field(f.getName()).value(v).build());
                    }
                }
            }
        }
        return total;
    }

    // ================================================================
    // Grounding 判斷
    // ================================================================

    boolean isFieldGrounded(String fieldName, String source, String descLower,
                            Set<String> allowedSet, Set<String> groundedEnglishFromChinese) {
        if (fieldName == null) return false;
        String nameLower = fieldName.toLowerCase();

        // (a) 使用者白名單
        if (allowedSet.contains(nameLower)) return true;

        // (b) 通用 output 欄位名
        if ("outputs".equals(source) && COMMON_OUTPUT_FIELDS.contains(nameLower)) return true;

        // (c) 英文名直接出現在描述中
        if (!descLower.isEmpty() && descLower.contains(nameLower)) return true;

        // (d) 中文解析結果 → 英文名映射
        if (groundedEnglishFromChinese.contains(nameLower)) return true;

        // (e) 部分匹配：fieldName 是某個 parsed english name 的 prefix / suffix
        for (String eng : groundedEnglishFromChinese) {
            if (eng.length() >= 3 && (nameLower.startsWith(eng) || nameLower.endsWith(eng)
                    || eng.startsWith(nameLower) || eng.endsWith(nameLower))) {
                return true;
            }
        }

        return false;
    }

    boolean isEnumValueGrounded(String value, String descLower,
                                Set<String> chineseNamesFromDescription) {
        if (value == null || value.isBlank()) return false;

        // 通用布林值
        if (COMMON_BOOLEAN_VALUES.contains(value.toLowerCase())) return true;

        // 值直接出現在描述中
        if (!descLower.isEmpty() && descLower.contains(value.toLowerCase())) return true;

        // 中文值出現在描述中（保留原大小寫）
        for (String ch : chineseNamesFromDescription) {
            if (ch.contains(value) || value.contains(ch)) return true;
        }

        return false;
    }

    private String suspicionOf(double ratio) {
        if (ratio >= 0.9) return "LOW";
        if (ratio >= 0.7) return "MEDIUM";
        return "HIGH";
    }

    private GroundingReport trivialReport(long startMs) {
        return GroundingReport.builder()
                .groundingRatio(1.0)
                .suspicionLevel("LOW")
                .totalChecks(0)
                .groundedChecks(0)
                .durationMs(System.currentTimeMillis() - startMs)
                .build();
    }
}
