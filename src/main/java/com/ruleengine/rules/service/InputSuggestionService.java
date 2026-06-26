package com.ruleengine.rules.service;

import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.DimensionInfo;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.ParsedDimensions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 分析使用者的自然語言描述，提供缺失維度建議、品質評分等資訊。
 *
 * <p>透過 {@link DescriptionDimensionParser} 解析描述後，與領域模板比對，
 * 找出缺失維度並計算描述品質分數。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InputSuggestionService {

    private final DescriptionDimensionParser parser;

    // ========== Response Records ==========

    public record SuggestResponse(
            List<DetectedDimension> detectedInputs,
            List<DetectedDimension> detectedOutputs,
            List<MissingDimension> missingDimensions,
            double qualityScore,
            List<String> suggestions,
            int estimatedRuleCount,
            String detectedDomain
    ) {}

    public record DetectedDimension(
            String chineseName,
            String englishName,
            String typeRef,
            List<String> values
    ) {}

    public record MissingDimension(
            String name,
            String englishName,
            String reason
    ) {}

    // ========== Domain Templates ==========

    private static final String DOMAIN_INSURANCE_UNDERWRITING = "insurance-underwriting";
    private static final String DOMAIN_INSURANCE_PRICING = "insurance-pricing";
    private static final String DOMAIN_INSURANCE_VALIDATION = "insurance-frontend-validation";
    private static final String DOMAIN_CREDIT_SCORING = "credit-scoring";
    private static final String DOMAIN_UNKNOWN = "unknown";

    /**
     * 領域模板：每個領域常見的欄位集合。
     * Key = englishName, Value = chineseName
     */
    private static final Map<String, Map<String, String>> DOMAIN_TEMPLATES = Map.of(
            DOMAIN_INSURANCE_UNDERWRITING, new LinkedHashMap<>(Map.of(
                    "age", "年齡",
                    "gender", "性別",
                    "smoking", "吸菸",
                    "bmi", "BMI",
                    "medical_history", "病史",
                    "occupation", "職業"
            )),
            DOMAIN_INSURANCE_PRICING, new LinkedHashMap<>(Map.of(
                    "age", "年齡",
                    "smoking", "吸菸",
                    "bmi", "BMI",
                    "occupation_risk", "職業風險等級",
                    "coverage_amount", "保額"
            )),
            // insurance-frontend-validation 不設固定模板：sub-scenario 過多
            // （ID/國籍、通路、繳費、投保始期…），任何單一模板都會在其他 sub-scenario
            // 上誤報「missing」。domain 仍被偵測，僅不顯示缺漏建議。
            DOMAIN_CREDIT_SCORING, new LinkedHashMap<>(Map.of(
                    "income", "收入",
                    "debt_ratio", "負債比",
                    "credit_history", "信用紀錄",
                    "employment_years", "工作年資"
            ))
    );

    // ========== Domain Detection Patterns ==========

    private static final List<DomainPattern> DOMAIN_PATTERNS = List.of(
            // 核保前端檢核：被保人/要保人/受理通路/授權書/繳費管道/投保始期 任一即觸發
            new DomainPattern(DOMAIN_INSURANCE_VALIDATION,
                    List.of(Pattern.compile("被保人"), Pattern.compile("要保人"),
                            Pattern.compile("受理通路"), Pattern.compile("授權書"),
                            Pattern.compile("繳費管道"), Pattern.compile("投保始期"),
                            Pattern.compile("國籍別"), Pattern.compile("拋訊息"))),
            new DomainPattern(DOMAIN_INSURANCE_PRICING,
                    List.of(Pattern.compile("費率"), Pattern.compile("保費"))),
            new DomainPattern(DOMAIN_INSURANCE_UNDERWRITING,
                    List.of(Pattern.compile("保險"), Pattern.compile("核保"))),
            new DomainPattern(DOMAIN_CREDIT_SCORING,
                    List.of(Pattern.compile("信用"), Pattern.compile("信貸")))
    );

    private record DomainPattern(String domain, List<Pattern> patterns) {}

    // ========== Main API ==========

    /**
     * 分析自然語言描述，回傳維度偵測結果、缺失建議與品質評分。
     *
     * @param description 使用者的自然語言描述（中文）
     * @return 分析建議結果
     */
    public SuggestResponse analyze(String description) {
        if (description == null || description.isBlank()) {
            return new SuggestResponse(
                    List.of(), List.of(), List.of(),
                    0.0, List.of("請提供規則描述"), 0, DOMAIN_UNKNOWN
            );
        }

        // 1. Parse dimensions
        ParsedDimensions parsed = parser.parse(description);

        // 2. Convert to DetectedDimension
        List<DetectedDimension> detectedInputs = parsed.inputs().stream()
                .map(this::toDetectedDimension)
                .toList();
        List<DetectedDimension> detectedOutputs = parsed.outputs().stream()
                .map(this::toDetectedDimension)
                .toList();

        // 3. Detect domain
        String detectedDomain = detectDomain(description);

        // 4. Find missing dimensions
        List<MissingDimension> missingDimensions = findMissingDimensions(detectedInputs, detectedDomain);

        // 5. Calculate quality score
        double qualityScore = calculateQualityScore(parsed, detectedDomain);

        // 6. Generate suggestions
        List<String> suggestions = generateSuggestions(parsed, detectedDomain, missingDimensions);

        log.info("描述分析完成 | domain={} | inputs={} | outputs={} | missing={} | quality={}",
                detectedDomain, detectedInputs.size(), detectedOutputs.size(),
                missingDimensions.size(), qualityScore);

        return new SuggestResponse(
                detectedInputs,
                detectedOutputs,
                missingDimensions,
                qualityScore,
                suggestions,
                parsed.estimatedCartesian(),
                detectedDomain
        );
    }

    // ========== Internal Methods ==========

    private DetectedDimension toDetectedDimension(DimensionInfo info) {
        return new DetectedDimension(
                info.chineseName(),
                info.suggestedEnglishName(),
                info.suggestedTypeRef(),
                info.values()
        );
    }

    /**
     * 透過關鍵字匹配偵測描述所屬領域。
     */
    private String detectDomain(String description) {
        for (DomainPattern dp : DOMAIN_PATTERNS) {
            for (Pattern p : dp.patterns()) {
                if (p.matcher(description).find()) {
                    log.debug("偵測到領域: {} (匹配: {})", dp.domain(), p.pattern());
                    return dp.domain();
                }
            }
        }
        return DOMAIN_UNKNOWN;
    }

    /**
     * 比對偵測到的輸入維度與領域模板，找出缺失的維度。
     */
    private List<MissingDimension> findMissingDimensions(List<DetectedDimension> detectedInputs,
                                                          String domain) {
        Map<String, String> template = DOMAIN_TEMPLATES.get(domain);
        if (template == null) {
            return List.of();
        }

        // Collect all detected english names (lowercase for comparison)
        Set<String> detectedNames = detectedInputs.stream()
                .map(d -> d.englishName().toLowerCase())
                .collect(Collectors.toSet());

        // Also collect chinese names for fuzzy matching
        Set<String> detectedChineseNames = detectedInputs.stream()
                .map(DetectedDimension::chineseName)
                .collect(Collectors.toSet());

        List<MissingDimension> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : template.entrySet()) {
            String templateEngName = entry.getKey();
            String templateChName = entry.getValue();

            // Check if any detected dimension matches (by english name or chinese name contains)
            boolean found = detectedNames.stream()
                    .anyMatch(name -> name.contains(templateEngName) || templateEngName.contains(name));

            if (!found) {
                found = detectedChineseNames.stream()
                        .anyMatch(name -> name.contains(templateChName) || templateChName.contains(name));
            }

            if (!found) {
                missing.add(new MissingDimension(
                        templateChName,
                        templateEngName,
                        String.format("領域「%s」通常包含「%s」欄位，但描述中未偵測到", domain, templateChName)
                ));
            }
        }

        return missing;
    }

    /**
     * 計算描述品質分數 (0.0 ~ 1.0)。
     *
     * <ul>
     *   <li>有輸入維度 → +0.2</li>
     *   <li>有輸出維度 → +0.2</li>
     *   <li>有值列舉 → +0.2</li>
     *   <li>輸入數量 >= 領域模板數量 → +0.2</li>
     *   <li>輸出數量 >= 2 → +0.2</li>
     * </ul>
     */
    private double calculateQualityScore(ParsedDimensions parsed, String domain) {
        double score = 0.0;

        // Has inputs? (+0.2)
        if (!parsed.inputs().isEmpty()) {
            score += 0.2;
        }

        // Has outputs? (+0.2)
        if (!parsed.outputs().isEmpty()) {
            score += 0.2;
        }

        // Has value enumerations? (+0.2)
        boolean hasEnumerations = parsed.inputs().stream()
                .anyMatch(d -> !d.values().isEmpty())
                || parsed.outputs().stream()
                .anyMatch(d -> !d.values().isEmpty());
        if (hasEnumerations) {
            score += 0.2;
        }

        // Input count >= domain template size? (+0.2)
        Map<String, String> template = DOMAIN_TEMPLATES.get(domain);
        if (template != null) {
            if (parsed.inputs().size() >= template.size()) {
                score += 0.2;
            }
        } else {
            // No domain template — give partial credit if there are at least 3 inputs
            if (parsed.inputs().size() >= 3) {
                score += 0.2;
            }
        }

        // Output count >= 2? (+0.2)
        if (parsed.outputs().size() >= 2) {
            score += 0.2;
        }

        return Math.round(score * 100.0) / 100.0;
    }

    /**
     * 根據分析結果生成中文建議文字。
     */
    private List<String> generateSuggestions(ParsedDimensions parsed, String domain,
                                             List<MissingDimension> missingDimensions) {
        List<String> suggestions = new ArrayList<>();

        // No inputs detected
        if (parsed.inputs().isEmpty()) {
            suggestions.add("未偵測到輸入欄位，建議明確列出條件維度，例如：年齡（18-30歲／31-50歲／51歲以上）");
        }

        // No outputs detected
        if (parsed.outputs().isEmpty()) {
            suggestions.add("未偵測到輸出欄位，建議加上「輸出：」標記並列出結果欄位");
        }

        // Missing value enumerations
        boolean inputsLackValues = parsed.inputs().stream().anyMatch(d -> d.values().isEmpty());
        if (inputsLackValues) {
            suggestions.add("建議補充輸入欄位的具體允許值，例如：性別（男／女）");
        }

        boolean outputsLackValues = !parsed.outputs().isEmpty()
                && parsed.outputs().stream().anyMatch(d -> d.values().isEmpty());
        if (outputsLackValues) {
            suggestions.add("建議補充輸出欄位的具體允許值");
        }

        // Missing domain dimensions
        if (!missingDimensions.isEmpty()) {
            String missingNames = missingDimensions.stream()
                    .map(MissingDimension::name)
                    .collect(Collectors.joining("、"));
            suggestions.add(String.format("根據「%s」領域模板，建議補充以下維度：%s", domain, missingNames));
        }

        // Output count < 2
        if (parsed.outputs().size() == 1) {
            suggestions.add("目前僅有一個輸出欄位，建議考慮是否需要額外的輸出（如備註說明）");
        }

        // High cartesian product warning
        if (parsed.estimatedCartesian() > 100) {
            suggestions.add(String.format(
                    "預估規則數為 %d 條，數量較多，建議確認是否所有組合皆有意義，或考慮簡化部分維度",
                    parsed.estimatedCartesian()));
        }

        // Unknown domain
        if (DOMAIN_UNKNOWN.equals(domain)) {
            suggestions.add("未能識別描述所屬領域，建議在描述中加入領域關鍵字（如：保險核保、費率、信用評分）");
        }

        return suggestions;
    }
}
