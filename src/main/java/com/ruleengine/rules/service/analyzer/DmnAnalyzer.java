package com.ruleengine.rules.service.analyzer;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DMN 決策表幾何分析引擎。
 *
 * <p>基於 Calvanese, Dumas, Laurson, Maggi, Montali, Teinemaa (2016)
 * "Semantics and Analysis of DMN Decision Tables." BPM 2016, Springer LNCS vol 9850.</p>
 *
 * <h3>核心概念</h3>
 * <ul>
 *   <li>每條規則 = N 維超矩形（N = input 欄位數）</li>
 *   <li>Overlap = 兩個超矩形的交集非空</li>
 *   <li>Gap = 值域空間中未被任何超矩形覆蓋的區域</li>
 *   <li>Simplification = 結果相同且相鄰的超矩形可合併</li>
 * </ul>
 *
 * <h3>分析步驟</h3>
 * <ol>
 *   <li>從 ruleJson 解析 inputs[] 和 rules[]</li>
 *   <li>建立 enum/string 值域映射</li>
 *   <li>將每條 rule 轉為 HyperRectangle</li>
 *   <li>Overlap Detection：O(n²) 比對所有規則對的超矩形交集</li>
 *   <li>Gap Detection：計算覆蓋率與具體缺口</li>
 *   <li>Simplification：找相鄰且結果相同的超矩形，建議合併</li>
 * </ol>
 */
@Component
@Slf4j
public class DmnAnalyzer {

    /** DATE 型別的基準日 */
    private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

    /** 數值型的預設邊界 */
    private static final double DEFAULT_NUM_MIN = 0.0;
    private static final double DEFAULT_NUM_MAX = 200.0;

    /** DATE 型的預設邊界（2000-01-01 ~ 2050-12-31） */
    private static final double DEFAULT_DATE_MIN = ChronoUnit.DAYS.between(EPOCH, LocalDate.of(2000, 1, 1));
    private static final double DEFAULT_DATE_MAX = ChronoUnit.DAYS.between(EPOCH, LocalDate.of(2050, 12, 31));

    /** Gap 回報上限 */
    private static final int MAX_GAPS_REPORTED = 20;

    /** Overlap 回報上限 */
    private static final int MAX_OVERLAPS_REPORTED = 50;

    /**
     * 分析 DMN 決策表 JSON，回傳完整分析結果。
     *
     * <p>接受的 JSON 格式為 RuleEnvelope 結構（包含 rule.inputs / rule.rules）。</p>
     *
     * @param ruleJson RuleEnvelope 格式的 JsonNode
     * @return 分析結果
     */
    @Cacheable(value = "dmnAnalysis", key = "#ruleJson.toString().hashCode()")
    public AnalysisResult analyze(JsonNode ruleJson) {
        log.info("開始 DMN 幾何分析...");

        try {
            // Step 1: 解析 inputs 和 rules
            JsonNode ruleNode = resolveRuleNode(ruleJson);
            if (ruleNode == null) {
                return emptyResult("無法找到 rule 節點");
            }

            JsonNode inputsNode = ruleNode.get("inputs");
            JsonNode rulesNode = ruleNode.get("rules");

            if (inputsNode == null || !inputsNode.isArray() || inputsNode.isEmpty()) {
                return emptyResult("inputs 為空或不存在");
            }
            if (rulesNode == null || !rulesNode.isArray() || rulesNode.isEmpty()) {
                return emptyResult("rules 為空或不存在");
            }

            // Step 2: 解析欄位定義
            List<FieldDef> inputs = parseInputs(inputsNode);
            int dims = inputs.size();

            // Step 3: 建立 enum/string 值域映射
            Map<String, Integer> enumMappings = buildEnumMappings(inputs, rulesNode);
            Map<Integer, String> reverseEnumMap = buildReverseEnumMap(enumMappings);

            // Step 4: 計算各維度的全域邊界
            boolean[] discrete = new boolean[dims];
            double[][] dimBounds = computeDimBounds(inputs, enumMappings, rulesNode, discrete);

            // Step 5: 將每條 rule 轉為 HyperRectangle（IN 笛卡爾積超限的規則記入 truncated）
            List<RuleHyperRects> ruleRects = new ArrayList<>();
            List<String> truncatedRuleIds = new ArrayList<>();
            for (int i = 0; i < rulesNode.size(); i++) {
                JsonNode row = rulesNode.get(i);
                String ruleId = row.has("ruleId") ? row.get("ruleId").asText() : "rule-" + i;
                JsonNode conditionsNode = row.get("conditions");

                HyperRectangle.Expansion expansion = HyperRectangle.fromJsonRuleWithInfo(
                        ruleId, conditionsNode, inputs, enumMappings, dimBounds);
                if (expansion.truncated()) {
                    truncatedRuleIds.add(ruleId);
                }

                if (!expansion.rects().isEmpty()) {
                    ruleRects.add(new RuleHyperRects(ruleId, expansion.rects(), row));
                }
            }

            List<HyperRectangle> allRects = ruleRects.stream()
                    .flatMap(r -> r.rects.stream())
                    .toList();

            log.info("解析完成：{} 條規則 → {} 個超矩形，{} 個維度",
                    rulesNode.size(), allRects.size(), dims);

            // Step 6: Overlap Detection
            List<AnalysisResult.OverlapInfo> overlaps = detectOverlaps(
                    ruleRects, inputs, reverseEnumMap, dimBounds);

            // Step 7: Gap Detection + Coverage
            double coverageRate = computeCoverage(allRects, dimBounds, discrete);
            List<AnalysisResult.GapInfo> gaps = detectGaps(
                    allRects, inputs, dimBounds, discrete, reverseEnumMap);

            // Step 8: Simplification
            List<AnalysisResult.SimplificationHint> simplifications = detectSimplifications(
                    ruleRects, inputs, discrete);

            // 產生摘要（截斷時明確告知結果不完整，避免使用者誤信漏報的分析）
            String summary = buildSummary(rulesNode.size(), dims, coverageRate,
                    overlaps.size(), gaps.size(), simplifications.size());
            if (!truncatedRuleIds.isEmpty()) {
                summary += HyperRectangle.truncationNote(truncatedRuleIds, "重疊/缺口分析");
            }

            AnalysisResult result = AnalysisResult.builder()
                    .coverageRate(Math.round(coverageRate * 10000.0) / 10000.0)
                    .gaps(gaps)
                    .overlaps(overlaps)
                    .simplifications(simplifications)
                    .truncatedRuleIds(List.copyOf(truncatedRuleIds))
                    .totalRules(rulesNode.size())
                    .totalDimensions(dims)
                    .summary(summary)
                    .build();

            log.info("DMN 分析完成：覆蓋率={}, overlaps={}, gaps={}, simplifications={}",
                    result.getCoverageRate(), overlaps.size(), gaps.size(), simplifications.size());

            return result;

        } catch (Exception e) {
            log.error("DMN 分析失敗", e);
            return emptyResult("分析失敗：" + e.getMessage());
        }
    }

    // ================================================================
    // Step 1 & 2: 解析
    // ================================================================

    /**
     * 解析 rule 節點。支持 envelope 格式（有 rule 包裹層）和直接格式。
     */
    private JsonNode resolveRuleNode(JsonNode ruleJson) {
        if (ruleJson == null) return null;
        // envelope 格式
        if (ruleJson.has("rule") && ruleJson.get("rule").has("inputs")) {
            return ruleJson.get("rule");
        }
        // 直接格式
        if (ruleJson.has("inputs") && ruleJson.has("rules")) {
            return ruleJson;
        }
        return null;
    }

    /**
     * 解析 rule 節點（envelope 或直接格式）。供雙表比對等外部元件重用。
     */
    public JsonNode resolveRule(JsonNode ruleJson) {
        return resolveRuleNode(ruleJson);
    }

    /**
     * 共用幾何模型（enum 映射 + 維度邊界 + 離散旗標）。
     * 由 {@link #analyze} 與雙表比對（RuleDiffService）共用，確保幾何語意一致。
     */
    public record GeometryModel(
            Map<String, Integer> enumMappings,
            Map<Integer, String> reverseEnumMap,
            double[][] dimBounds,
            boolean[] discrete) {}

    /**
     * 依給定的 inputs 與 rules，建立共用幾何模型（委派既有私有邏輯，行為與 {@link #analyze} 一致）。
     *
     * <p>雙表比對時可餵入「合併後的 rules」與「聯集後的 inputs」，得到統一座標系。</p>
     */
    public GeometryModel buildGeometry(List<FieldDef> inputs, JsonNode rulesNode) {
        Map<String, Integer> enumMappings = buildEnumMappings(inputs, rulesNode);
        Map<Integer, String> reverseEnumMap = buildReverseEnumMap(enumMappings);
        boolean[] discrete = new boolean[inputs.size()];
        double[][] dimBounds = computeDimBounds(inputs, enumMappings, rulesNode, discrete);
        return new GeometryModel(enumMappings, reverseEnumMap, dimBounds, discrete);
    }

    public List<FieldDef> parseInputs(JsonNode inputsNode) {
        List<FieldDef> inputs = new ArrayList<>();
        for (JsonNode fn : inputsNode) {
            FieldDef fd = FieldDef.builder()
                    .name(fn.has("name") ? fn.get("name").asText() : "unknown")
                    .typeRef(fn.has("typeRef") ? fn.get("typeRef").asText() : TypeRefs.STRING)
                    .build();
            if (fn.has("allowedValues") && fn.get("allowedValues").isArray()) {
                List<String> allowed = new ArrayList<>();
                fn.get("allowedValues").forEach(v -> allowed.add(v.asText()));
                fd.setAllowedValues(allowed);
            }
            inputs.add(fd);
        }
        return inputs;
    }

    // ================================================================
    // Step 3: 建立 Enum/String 映射
    // ================================================================

    /**
     * 建立值 → 整數索引映射。
     * <ul>
     *   <li>ENUM：使用 allowedValues 的順序</li>
     *   <li>BOOLEAN：true=1, false=0</li>
     *   <li>STRING：動態收集所有規則中出現的值</li>
     * </ul>
     *
     * @return Map key = "fieldName:value", value = 整數索引
     */
    private Map<String, Integer> buildEnumMappings(List<FieldDef> inputs, JsonNode rulesNode) {
        Map<String, Integer> mappings = new LinkedHashMap<>();

        for (FieldDef field : inputs) {
            String name = field.getName();
            String typeRef = field.getTypeRef();

            switch (typeRef) {
                case TypeRefs.BOOLEAN -> {
                    mappings.put(name + ":false", 0);
                    mappings.put(name + ":true", 1);
                }
                case TypeRefs.ENUM -> {
                    if (field.getAllowedValues() != null) {
                        for (int i = 0; i < field.getAllowedValues().size(); i++) {
                            mappings.put(name + ":" + field.getAllowedValues().get(i), i);
                        }
                    }
                }
                case TypeRefs.STRING -> {
                    // 動態收集所有規則中此欄位出現的值
                    Set<String> values = new LinkedHashSet<>();
                    for (JsonNode row : rulesNode) {
                        JsonNode conditions = row.get("conditions");
                        if (conditions == null || !conditions.isArray()) continue;
                        for (JsonNode cond : conditions) {
                            if (!name.equals(cond.path("field").asText())) continue;
                            JsonNode val = cond.get("value");
                            if (val == null || val.isNull()) continue;
                            if (val.isTextual()) {
                                values.add(val.asText());
                            } else if (val.isArray()) {
                                val.forEach(v -> {
                                    if (v.isTextual()) values.add(v.asText());
                                });
                            }
                        }
                    }
                    int idx = 0;
                    for (String v : values) {
                        mappings.put(name + ":" + v, idx++);
                    }
                }
                default -> {
                    // INTEGER, DECIMAL, DATE: 不需要映射
                }
            }
        }

        return mappings;
    }

    /**
     * 建立反向映射：整數索引 → "fieldName:value" 的值部分。
     * 用於將幾何區間轉回人類可讀的條件。
     */
    private Map<Integer, String> buildReverseEnumMap(Map<String, Integer> enumMappings) {
        Map<Integer, String> reverse = new HashMap<>();
        for (Map.Entry<String, Integer> e : enumMappings.entrySet()) {
            String key = e.getKey();
            int colonIdx = key.indexOf(':');
            String valuePart = colonIdx >= 0 ? key.substring(colonIdx + 1) : key;
            reverse.put(e.getValue(), valuePart);
        }
        return reverse;
    }

    // ================================================================
    // Step 4: 全域邊界計算
    // ================================================================

    /**
     * 計算各維度的全域邊界。
     * <ul>
     *   <li>INTEGER/DECIMAL：從規則中收集所有出現的數值，取 min-1 ~ max+1</li>
     *   <li>BOOLEAN：[0, 1]</li>
     *   <li>ENUM：[0, allowedValues.size()-1]</li>
     *   <li>STRING：[0, 收集到的值數量-1]</li>
     *   <li>DATE：轉為 epoch days</li>
     * </ul>
     */
    private double[][] computeDimBounds(List<FieldDef> inputs,
                                         Map<String, Integer> enumMappings,
                                         JsonNode rulesNode,
                                         boolean[] discrete) {
        int dims = inputs.size();
        double[][] bounds = new double[dims][2];

        for (int d = 0; d < dims; d++) {
            FieldDef field = inputs.get(d);
            String typeRef = field.getTypeRef();
            String name = field.getName();

            switch (typeRef) {
                case TypeRefs.BOOLEAN -> {
                    bounds[d][0] = 0;
                    bounds[d][1] = 1;
                    discrete[d] = true;
                }
                case TypeRefs.ENUM -> {
                    bounds[d][0] = 0;
                    int count = field.getAllowedValues() != null ? field.getAllowedValues().size() : 1;
                    bounds[d][1] = count - 1;
                    discrete[d] = true;
                }
                case TypeRefs.STRING -> {
                    bounds[d][0] = 0;
                    long count = enumMappings.keySet().stream()
                            .filter(k -> k.startsWith(name + ":"))
                            .count();
                    bounds[d][1] = Math.max(count - 1, 0);
                    discrete[d] = true;
                }
                case TypeRefs.INTEGER -> {
                    double[] minMax = collectNumericBounds(name, rulesNode);
                    bounds[d][0] = minMax[0];
                    bounds[d][1] = minMax[1];
                    discrete[d] = true;
                }
                case TypeRefs.DECIMAL -> {
                    double[] minMax = collectNumericBounds(name, rulesNode);
                    bounds[d][0] = minMax[0];
                    bounds[d][1] = minMax[1];
                    discrete[d] = false;
                }
                case TypeRefs.DATE -> {
                    double[] minMax = collectDateBounds(name, rulesNode);
                    bounds[d][0] = minMax[0];
                    bounds[d][1] = minMax[1];
                    discrete[d] = true; // 以天為單位，視為離散
                }
                default -> {
                    bounds[d][0] = DEFAULT_NUM_MIN;
                    bounds[d][1] = DEFAULT_NUM_MAX;
                    discrete[d] = false;
                }
            }
        }

        return bounds;
    }

    /**
     * 從規則中收集某個數值型欄位的所有出現值，推算邊界。
     */
    private double[] collectNumericBounds(String fieldName, JsonNode rulesNode) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        boolean found = false;

        for (JsonNode row : rulesNode) {
            JsonNode conditions = row.get("conditions");
            if (conditions == null || !conditions.isArray()) continue;
            for (JsonNode cond : conditions) {
                if (!fieldName.equals(cond.path("field").asText())) continue;
                JsonNode val = cond.get("value");
                if (val == null || val.isNull()) continue;

                if (val.isNumber()) {
                    found = true;
                    min = Math.min(min, val.asDouble());
                    max = Math.max(max, val.asDouble());
                } else if (val.isArray()) {
                    for (JsonNode v : val) {
                        if (v.isNumber()) {
                            found = true;
                            min = Math.min(min, v.asDouble());
                            max = Math.max(max, v.asDouble());
                        }
                    }
                }
            }
        }

        if (!found) {
            return new double[]{DEFAULT_NUM_MIN, DEFAULT_NUM_MAX};
        }
        // 擴展邊界，確保包含邊緣情況
        double range = max - min;
        double padding = Math.max(range * 0.1, 1.0);
        double lowerBound = Math.floor(min - padding);
        double upperBound = Math.ceil(max + padding);
        // INTEGER 型別且規則中最小值 >= 0 時，下界不低於 0（避免負數假陽性 gap）
        if (min >= 0 && lowerBound < 0) {
            lowerBound = 0;
        }
        return new double[]{lowerBound, upperBound};
    }

    /**
     * 從規則中收集某個日期型欄位的所有出現值，推算邊界。
     */
    private double[] collectDateBounds(String fieldName, JsonNode rulesNode) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        boolean found = false;

        for (JsonNode row : rulesNode) {
            JsonNode conditions = row.get("conditions");
            if (conditions == null || !conditions.isArray()) continue;
            for (JsonNode cond : conditions) {
                if (!fieldName.equals(cond.path("field").asText())) continue;
                JsonNode val = cond.get("value");
                if (val == null || val.isNull()) continue;

                List<JsonNode> toCheck = new ArrayList<>();
                if (val.isArray()) {
                    val.forEach(toCheck::add);
                } else {
                    toCheck.add(val);
                }

                for (JsonNode v : toCheck) {
                    if (v.isTextual()) {
                        try {
                            double days = ChronoUnit.DAYS.between(EPOCH, LocalDate.parse(v.asText()));
                            found = true;
                            min = Math.min(min, days);
                            max = Math.max(max, days);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        if (!found) {
            return new double[]{DEFAULT_DATE_MIN, DEFAULT_DATE_MAX};
        }
        return new double[]{min - 365, max + 365}; // 擴展一年
    }

    // ================================================================
    // Step 6: Overlap Detection
    // ================================================================

    /**
     * O(n²) 比對所有規則對的超矩形交集。
     */
    private List<AnalysisResult.OverlapInfo> detectOverlaps(
            List<RuleHyperRects> ruleRects,
            List<FieldDef> inputs,
            Map<Integer, String> reverseEnumMap,
            double[][] dimBounds) {

        List<AnalysisResult.OverlapInfo> overlaps = new ArrayList<>();

        for (int i = 0; i < ruleRects.size() && overlaps.size() < MAX_OVERLAPS_REPORTED; i++) {
            for (int j = i + 1; j < ruleRects.size() && overlaps.size() < MAX_OVERLAPS_REPORTED; j++) {
                RuleHyperRects rA = ruleRects.get(i);
                RuleHyperRects rB = ruleRects.get(j);

                // 比對 rA 和 rB 所有超矩形對
                HyperRectangle firstIntersection = null;
                for (HyperRectangle hrA : rA.rects) {
                    for (HyperRectangle hrB : rB.rects) {
                        HyperRectangle inter = hrA.intersection(hrB);
                        if (inter != null) {
                            firstIntersection = inter;
                            break;
                        }
                    }
                    if (firstIntersection != null) break;
                }

                if (firstIntersection != null) {
                    Map<String, String> interConditions = firstIntersection.toConditionMap(
                            inputs, reverseEnumMap, dimBounds);

                    String condStr = interConditions.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(", "));

                    overlaps.add(AnalysisResult.OverlapInfo.builder()
                            .ruleIds(List.of(rA.ruleId, rB.ruleId))
                            .intersection(interConditions)
                            .message(rA.ruleId + " 與 " + rB.ruleId
                                    + " 的條件範圍有重疊。交集條件：" + condStr)
                            .build());
                }
            }
        }

        return overlaps;
    }

    // ================================================================
    // Step 7: Gap Detection + Coverage
    // ================================================================

    /**
     * 計算覆蓋率。使用包含-排除原理（Inclusion-Exclusion）。
     *
     * <p>精確的包含-排除在規則數多時計算量為 2^n，因此對規則數 > 20 的情況使用
     * 蒙地卡羅隨機取樣估計。</p>
     */
    private double computeCoverage(List<HyperRectangle> allRects,
                                    double[][] dimBounds,
                                    boolean[] discrete) {
        if (allRects.isEmpty()) return 0.0;

        // 計算全域體積
        int dims = dimBounds.length;
        double totalVolume = 1.0;
        for (int d = 0; d < dims; d++) {
            double span = dimBounds[d][1] - dimBounds[d][0];
            totalVolume *= discrete[d] ? (span + 1) : Math.max(span, 1.0);
        }

        if (totalVolume <= 0) return 0.0;

        double unionVolume;
        if (allRects.size() <= 20) {
            // 精確包含-排除
            unionVolume = inclusionExclusion(allRects, discrete);
        } else {
            // 蒙地卡羅估計
            unionVolume = monteCarloEstimate(allRects, dimBounds, discrete, totalVolume);
        }

        return Math.min(unionVolume / totalVolume, 1.0);
    }

    /**
     * 包含-排除原理計算聯集體積。
     */
    private double inclusionExclusion(List<HyperRectangle> rects, boolean[] discrete) {
        int n = rects.size();
        double unionVolume = 0.0;

        // 遍歷所有非空子集合（位元遮罩）
        for (int mask = 1; mask < (1 << n); mask++) {
            int bits = Integer.bitCount(mask);
            double sign = (bits % 2 == 1) ? 1.0 : -1.0;

            // 計算子集合的交集
            HyperRectangle intersection = null;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    if (intersection == null) {
                        intersection = rects.get(i);
                    } else {
                        intersection = intersection.intersection(rects.get(i));
                        if (intersection == null) break; // 交集為空
                    }
                }
            }

            if (intersection != null) {
                unionVolume += sign * intersection.volume(discrete);
            }
        }

        return Math.max(unionVolume, 0.0);
    }

    /**
     * 蒙地卡羅隨機取樣估計聯集體積。
     */
    private double monteCarloEstimate(List<HyperRectangle> rects,
                                       double[][] dimBounds,
                                       boolean[] discrete,
                                       double totalVolume) {
        Random rand = new Random(42); // 固定種子確保可重現
        int samples = 50_000;
        int hits = 0;
        int dims = dimBounds.length;

        for (int s = 0; s < samples; s++) {
            double[] point = new double[dims];
            for (int d = 0; d < dims; d++) {
                if (discrete[d]) {
                    int range = (int) (dimBounds[d][1] - dimBounds[d][0]) + 1;
                    point[d] = dimBounds[d][0] + rand.nextInt(Math.max(range, 1));
                } else {
                    point[d] = dimBounds[d][0] + rand.nextDouble() * (dimBounds[d][1] - dimBounds[d][0]);
                }
            }

            // 檢查此點是否落在任何超矩形內
            for (HyperRectangle rect : rects) {
                boolean inside = true;
                for (int d = 0; d < dims; d++) {
                    if (point[d] < rect.getMin(d) || point[d] > rect.getMax(d)) {
                        inside = false;
                        break;
                    }
                }
                if (inside) {
                    hits++;
                    break;
                }
            }
        }

        return ((double) hits / samples) * totalVolume;
    }

    /**
     * 偵測具體的 Gap（未被覆蓋的區域）。
     *
     * <p>策略：對每個維度的邊界點進行網格切分，檢測切分後的小格子是否被覆蓋。
     * 未覆蓋的小格子合併後回報為 Gap。</p>
     */
    private List<AnalysisResult.GapInfo> detectGaps(
            List<HyperRectangle> allRects,
            List<FieldDef> inputs,
            double[][] dimBounds,
            boolean[] discrete,
            Map<Integer, String> reverseEnumMap) {

        List<AnalysisResult.GapInfo> gaps = new ArrayList<>();
        int dims = inputs.size();

        // 收集每個維度的分割點
        List<List<Double>> splitPoints = new ArrayList<>();
        for (int d = 0; d < dims; d++) {
            if (discrete[d]) {
                // 離散型維度：每個整數值都是一個獨立的分割點。
                // 例如 BOOLEAN [0,1] → {0, 1}，ENUM [0,2] → {0, 1, 2}。
                // 後續每個分割點會成為一個獨立的 [v, v] 格子，
                // 而非連續型的 [sp[i], sp[i+1]] 區間。
                List<Double> points = new ArrayList<>();
                for (double v = dimBounds[d][0]; v <= dimBounds[d][1]; v += 1.0) {
                    points.add(v);
                    if (points.size() > 10000) break; // 防爆
                }
                splitPoints.add(points);
            } else {
                // 連續型維度：使用規則邊界作為分割點
                Set<Double> points = new TreeSet<>();
                points.add(dimBounds[d][0]);
                points.add(dimBounds[d][1]);

                for (HyperRectangle rect : allRects) {
                    points.add(rect.getMin(d));
                    points.add(rect.getMax(d));
                }

                splitPoints.add(new ArrayList<>(points));
            }
        }

        // 計算格子總數（限制避免組合爆炸）
        long totalCells = 1;
        for (int d = 0; d < dims; d++) {
            List<Double> sp = splitPoints.get(d);
            // 離散型：每個分割點是一個格子；連續型：每個相鄰對是一個格子
            long dimCells = discrete[d] ? sp.size() : Math.max(sp.size() - 1, 1);
            totalCells *= dimCells;
            if (totalCells > 100_000) {
                return detectGapsBySampling(allRects, inputs, dimBounds, discrete, reverseEnumMap);
            }
        }

        // 遍歷所有小格子
        int[] indices = new int[dims];
        double totalVolume = computeTotalVolume(dimBounds, discrete);

        outer:
        while (gaps.size() < MAX_GAPS_REPORTED) {
            // 建立當前格子的超矩形
            double[][] cellBounds = new double[dims][2];
            boolean validCell = true;
            for (int d = 0; d < dims; d++) {
                List<Double> sp = splitPoints.get(d);
                if (discrete[d]) {
                    // 離散型：每個分割點是一個獨立格子 [v, v]
                    if (indices[d] >= sp.size()) {
                        validCell = false;
                        break;
                    }
                    cellBounds[d][0] = sp.get(indices[d]);
                    cellBounds[d][1] = sp.get(indices[d]);
                } else {
                    // 連續型：相鄰分割點之間的區間 [sp[i], sp[i+1]]
                    if (indices[d] >= sp.size() - 1) {
                        validCell = false;
                        break;
                    }
                    cellBounds[d][0] = sp.get(indices[d]);
                    cellBounds[d][1] = sp.get(indices[d] + 1);
                }
            }

            if (validCell) {
                List<String> cellDimNames = inputs.stream()
                        .map(FieldDef::getName).collect(Collectors.toList());
                HyperRectangle cell = new HyperRectangle("cell", cellDimNames, cellBounds);

                // 檢查此格子是否被任何規則覆蓋
                boolean covered = false;
                for (HyperRectangle rect : allRects) {
                    if (rect.contains(cell)) {
                        covered = true;
                        break;
                    }
                }

                if (!covered) {
                    // 進一步確認：檢查格子的中心點是否在任何規則內
                    boolean centerCovered = isCenterCovered(cell, allRects);
                    if (!centerCovered) {
                        Map<String, String> conditions = cell.toConditionMap(
                                inputs, reverseEnumMap, dimBounds);
                        if (!conditions.isEmpty()) {
                            double cellVol = cell.volume(discrete);
                            String condStr = conditions.entrySet().stream()
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .collect(Collectors.joining(", "));

                            gaps.add(AnalysisResult.GapInfo.builder()
                                    .conditions(conditions)
                                    .message("條件組合 {" + condStr + "} 沒有對應規則")
                                    .volumeRatio(totalVolume > 0 ? cellVol / totalVolume : 0.0)
                                    .build());
                        }
                    }
                }
            }

            // 遞增索引（多維計數器）
            int carry = 1;
            for (int d = dims - 1; d >= 0; d--) {
                indices[d] += carry;
                // 離散型：上限是 sp.size()；連續型：上限是 sp.size() - 1
                int limit = discrete[d] ? splitPoints.get(d).size() : splitPoints.get(d).size() - 1;
                if (indices[d] >= limit) {
                    if (d == 0) break outer; // 全部遍歷完
                    indices[d] = 0;
                    carry = 1;
                } else {
                    carry = 0;
                    break;
                }
            }
            if (carry == 1) break; // 溢出，結束
        }

        return gaps;
    }

    /**
     * 隨機取樣偵測 Gap（用於維度或分割點過多的情況）。
     */
    private List<AnalysisResult.GapInfo> detectGapsBySampling(
            List<HyperRectangle> allRects,
            List<FieldDef> inputs,
            double[][] dimBounds,
            boolean[] discrete,
            Map<Integer, String> reverseEnumMap) {

        List<AnalysisResult.GapInfo> gaps = new ArrayList<>();
        Random rand = new Random(42);
        int dims = inputs.size();
        int samples = 10_000;
        double totalVolume = computeTotalVolume(dimBounds, discrete);

        for (int s = 0; s < samples && gaps.size() < MAX_GAPS_REPORTED; s++) {
            double[] point = new double[dims];
            for (int d = 0; d < dims; d++) {
                if (discrete[d]) {
                    int range = (int) (dimBounds[d][1] - dimBounds[d][0]) + 1;
                    point[d] = dimBounds[d][0] + rand.nextInt(Math.max(range, 1));
                } else {
                    point[d] = dimBounds[d][0] + rand.nextDouble() * (dimBounds[d][1] - dimBounds[d][0]);
                }
            }

            boolean covered = false;
            for (HyperRectangle rect : allRects) {
                boolean inside = true;
                for (int d = 0; d < dims; d++) {
                    if (point[d] < rect.getMin(d) || point[d] > rect.getMax(d)) {
                        inside = false;
                        break;
                    }
                }
                if (inside) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                Map<String, String> conditions = new LinkedHashMap<>();
                for (int d = 0; d < dims; d++) {
                    FieldDef field = inputs.get(d);
                    String val = formatPointValue(point[d], field, null);
                    conditions.put(field.getName(), val);
                }

                String condStr = conditions.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", "));

                gaps.add(AnalysisResult.GapInfo.builder()
                        .conditions(conditions)
                        .message("條件組合 {" + condStr + "} 沒有對應規則（隨機取樣發現）")
                        .build());
            }
        }

        return gaps;
    }

    private boolean isCenterCovered(HyperRectangle cell, List<HyperRectangle> allRects) {
        int dims = cell.dimensions();
        for (HyperRectangle rect : allRects) {
            boolean inside = true;
            for (int d = 0; d < dims; d++) {
                double center = (cell.getMin(d) + cell.getMax(d)) / 2.0;
                if (center < rect.getMin(d) || center > rect.getMax(d)) {
                    inside = false;
                    break;
                }
            }
            if (inside) return true;
        }
        return false;
    }

    // ================================================================
    // Step 8: Simplification
    // ================================================================

    /**
     * 找相鄰且結果（outputs）相同的規則對，建議合併。
     */
    private List<AnalysisResult.SimplificationHint> detectSimplifications(
            List<RuleHyperRects> ruleRects,
            List<FieldDef> inputs,
            boolean[] discrete) {

        List<AnalysisResult.SimplificationHint> hints = new ArrayList<>();
        int dims = inputs.size();

        for (int i = 0; i < ruleRects.size(); i++) {
            for (int j = i + 1; j < ruleRects.size(); j++) {
                RuleHyperRects rA = ruleRects.get(i);
                RuleHyperRects rB = ruleRects.get(j);

                // 檢查結果是否相同
                if (!sameOutputs(rA.ruleNode, rB.ruleNode)) continue;

                // 檢查是否在某個維度上相鄰（其餘維度完全相同）
                for (int d = 0; d < dims; d++) {
                    // 取每個規則的第一個超矩形做比較（簡化處理）
                    if (rA.rects.isEmpty() || rB.rects.isEmpty()) continue;
                    HyperRectangle hrA = rA.rects.get(0);
                    HyperRectangle hrB = rB.rects.get(0);

                    if (hrA.isAdjacentOn(hrB, d, discrete[d])) {
                        HyperRectangle merged = hrA.mergeOn(hrB, d);
                        String dimName = inputs.get(d).getName();

                        hints.add(AnalysisResult.SimplificationHint.builder()
                                .ruleIds(List.of(rA.ruleId, rB.ruleId))
                                .suggestion("規則 " + rA.ruleId + " 和 " + rB.ruleId
                                        + " 的結果相同且在 " + dimName + " 維度上相鄰，"
                                        + "可合併為 " + dimName + "=["
                                        + formatBound(merged.getMin(d), inputs.get(d))
                                        + "," + formatBound(merged.getMax(d), inputs.get(d))
                                        + "]")
                                .build());
                        break; // 每對規則只報一個合併建議
                    }
                }
            }
        }

        return hints;
    }

    /**
     * 比較兩條規則的 outputs 是否相同。
     */
    private boolean sameOutputs(JsonNode rowA, JsonNode rowB) {
        JsonNode resA = rowA.get("results");
        JsonNode resB = rowB.get("results");
        if (resA == null || resB == null) return false;
        if (!resA.isArray() || !resB.isArray()) return false;
        if (resA.size() != resB.size()) return false;

        // 建立 field → value 映射比較
        Map<String, String> mapA = new LinkedHashMap<>();
        Map<String, String> mapB = new LinkedHashMap<>();
        for (JsonNode r : resA) {
            if (r.has("field") && r.has("value")) {
                mapA.put(r.get("field").asText(), r.get("value").toString());
            }
        }
        for (JsonNode r : resB) {
            if (r.has("field") && r.has("value")) {
                mapB.put(r.get("field").asText(), r.get("value").toString());
            }
        }

        return mapA.equals(mapB);
    }

    // ================================================================
    // Utility
    // ================================================================

    private double computeTotalVolume(double[][] dimBounds, boolean[] discrete) {
        double vol = 1.0;
        for (int d = 0; d < dimBounds.length; d++) {
            double span = dimBounds[d][1] - dimBounds[d][0];
            vol *= discrete[d] ? (span + 1) : Math.max(span, 1.0);
        }
        return vol;
    }

    private String formatBound(double value, FieldDef field) {
        String typeRef = field.getTypeRef();
        if (TypeRefs.BOOLEAN.equals(typeRef)) {
            return value >= 0.5 ? "true" : "false";
        }
        if (TypeRefs.INTEGER.equals(typeRef)) {
            return String.valueOf((long) value);
        }
        if (TypeRefs.DATE.equals(typeRef)) {
            return EPOCH.plusDays((long) value).toString();
        }
        if (TypeRefs.ENUM.equals(typeRef) && field.getAllowedValues() != null) {
            int idx = (int) value;
            if (idx >= 0 && idx < field.getAllowedValues().size()) {
                return field.getAllowedValues().get(idx);
            }
        }
        return String.valueOf(value);
    }

    private String formatPointValue(double value, FieldDef field,
                                     Map<Integer, String> reverseEnumMap) {
        String typeRef = field.getTypeRef();
        if (TypeRefs.BOOLEAN.equals(typeRef)) {
            return value >= 0.5 ? "true" : "false";
        }
        if (TypeRefs.INTEGER.equals(typeRef)) {
            return String.valueOf((long) value);
        }
        if (TypeRefs.DATE.equals(typeRef)) {
            return EPOCH.plusDays((long) value).toString();
        }
        if ((TypeRefs.ENUM.equals(typeRef) || TypeRefs.STRING.equals(typeRef))
                && reverseEnumMap != null) {
            String label = reverseEnumMap.get((int) value);
            return label != null ? label : String.valueOf((int) value);
        }
        if (TypeRefs.ENUM.equals(typeRef) && field.getAllowedValues() != null) {
            int idx = (int) value;
            if (idx >= 0 && idx < field.getAllowedValues().size()) {
                return field.getAllowedValues().get(idx);
            }
        }
        return String.valueOf(value);
    }

    private String buildSummary(int ruleCount, int dims, double coverage,
                                 int overlapCount, int gapCount, int simplCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("分析 ").append(ruleCount).append(" 條規則，")
                .append(dims).append(" 個輸入維度。");
        sb.append("覆蓋率 ").append(String.format("%.1f%%", coverage * 100)).append("。");
        if (overlapCount > 0) {
            sb.append("發現 ").append(overlapCount).append(" 對規則重疊。");
        } else {
            sb.append("無規則重疊。");
        }
        if (gapCount > 0) {
            sb.append("發現 ").append(gapCount).append(" 個覆蓋缺口。");
        } else {
            sb.append("無覆蓋缺口。");
        }
        if (simplCount > 0) {
            sb.append("有 ").append(simplCount).append(" 項規則合併建議。");
        }
        return sb.toString();
    }

    private AnalysisResult emptyResult(String reason) {
        return AnalysisResult.builder()
                .coverageRate(0.0)
                .gaps(List.of())
                .overlaps(List.of())
                .simplifications(List.of())
                .totalRules(0)
                .totalDimensions(0)
                .summary(reason)
                .build();
    }

    // ================================================================
    // 內部資料結構
    // ================================================================

    /**
     * 一條規則對應的超矩形集合（IN operator 可能拆出多個）。
     */
    private record RuleHyperRects(String ruleId, List<HyperRectangle> rects, JsonNode ruleNode) {
    }
}
