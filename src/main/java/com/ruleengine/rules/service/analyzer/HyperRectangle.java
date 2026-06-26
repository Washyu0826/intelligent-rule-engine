package com.ruleengine.rules.service.analyzer;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * N 維超矩形（HyperRectangle）— DMN 幾何分析的核心資料結構。
 *
 * <p>基於 Calvanese, Dumas et al. (BPM 2016) 論文：
 * 每條 Decision Table 規則映射為一個 N 維超矩形，其中 N = input 欄位數。
 * 每個維度是一個閉區間 [min, max]。</p>
 *
 * <h3>型別映射規則</h3>
 * <ul>
 *   <li>INTEGER / DECIMAL → 直接使用數值區間</li>
 *   <li>BOOLEAN → 0 / 1（true=[1,1], false=[0,0], anything=[0,1]）</li>
 *   <li>ENUM → 每個 allowedValue 映射為整數索引</li>
 *   <li>STRING → 動態收集所有出現值，映射為整數索引</li>
 *   <li>DATE → 轉為 epoch days（自 1970-01-01 起算）</li>
 * </ul>
 */
@Slf4j
@Getter
public class HyperRectangle {

    /** 用於數值型的上下界，避免 Double.MAX_VALUE 溢位 */
    private static final double POS_MAX = 1e15;
    private static final double NEG_MAX = -1e15;

    /** DATE 型別的基準日（epoch） */
    private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 來源規則 ID */
    private final String ruleId;

    /** 維度名稱（對應 input 欄位名稱），保持順序 */
    private final List<String> dimensionNames;

    /** 每個維度的閉區間 [min, max]，索引對應 dimensionNames */
    private final double[][] intervals;

    /**
     * 建構子。
     *
     * @param ruleId         來源規則 ID
     * @param dimensionNames 維度名稱列表
     * @param intervals      每個維度的 [min, max] 閉區間
     */
    public HyperRectangle(String ruleId, List<String> dimensionNames, double[][] intervals) {
        if (dimensionNames.size() != intervals.length) {
            throw new IllegalArgumentException(
                    "維度數量不一致：dimensionNames=" + dimensionNames.size()
                            + ", intervals=" + intervals.length);
        }
        this.ruleId = ruleId;
        this.dimensionNames = List.copyOf(dimensionNames);
        this.intervals = deepCopy(intervals);
    }

    /** 維度數（= input 欄位數） */
    public int dimensions() {
        return dimensionNames.size();
    }

    /** 取得第 i 個維度的下界 */
    public double getMin(int i) {
        return intervals[i][0];
    }

    /** 取得第 i 個維度的上界 */
    public double getMax(int i) {
        return intervals[i][1];
    }

    // ================================================================
    // 幾何運算
    // ================================================================

    /**
     * 判斷此超矩形是否與另一個超矩形相交（非空交集）。
     * 兩個超矩形相交 ⟺ 在每個維度上的區間都有重疊。
     */
    public boolean intersects(HyperRectangle other) {
        checkDimensionMatch(other);
        for (int i = 0; i < dimensions(); i++) {
            if (intervals[i][0] > other.intervals[i][1]
                    || intervals[i][1] < other.intervals[i][0]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 計算此超矩形與另一個超矩形的交集。
     * 若不相交，回傳 null。
     */
    public HyperRectangle intersection(HyperRectangle other) {
        checkDimensionMatch(other);
        double[][] result = new double[dimensions()][2];
        for (int i = 0; i < dimensions(); i++) {
            result[i][0] = Math.max(intervals[i][0], other.intervals[i][0]);
            result[i][1] = Math.min(intervals[i][1], other.intervals[i][1]);
            if (result[i][0] > result[i][1]) {
                return null; // 某個維度不相交 → 整體不相交
            }
        }
        return new HyperRectangle(ruleId + "∩" + other.ruleId, dimensionNames, result);
    }

    /**
     * 判斷此超矩形是否完全包含另一個超矩形。
     */
    public boolean contains(HyperRectangle other) {
        checkDimensionMatch(other);
        for (int i = 0; i < dimensions(); i++) {
            if (intervals[i][0] > other.intervals[i][0]
                    || intervals[i][1] < other.intervals[i][1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 計算此超矩形的體積（各維度區間長度的乘積）。
     * 對於離散型（BOOLEAN、ENUM、STRING），區間長度用 (max - min + 1)。
     */
    /**
     * 計算體積（預設所有維度為離散型，即 span + 1）。
     * 若需要區分連續 / 離散維度，請使用 {@link #volume(boolean[])}。
     */
    public double volume() {
        double vol = 1.0;
        for (double[] interval : intervals) {
            double span = interval[1] - interval[0];
            if (span < 0) return 0.0;
            vol *= (span + 1.0); // 預設離散：[a,b] 包含 (b-a+1) 個值
        }
        return vol;
    }

    /**
     * 計算體積（指定哪些維度是離散型）。
     * 離散型維度用 (max - min + 1)，連續型用 (max - min)。
     */
    public double volume(boolean[] discrete) {
        double vol = 1.0;
        for (int i = 0; i < intervals.length; i++) {
            double span = intervals[i][1] - intervals[i][0];
            if (span < 0) return 0.0;
            if (discrete != null && i < discrete.length && discrete[i]) {
                vol *= (span + 1.0); // 離散：[a,b] 包含 (b-a+1) 個值
            } else {
                vol *= Math.max(span, 1.0); // 連續：至少 1.0 避免零體積
            }
        }
        return vol;
    }

    // ================================================================
    // 工廠方法
    // ================================================================

    /**
     * 從一條 RuleRow 建構超矩形。
     *
     * @param row          規則行
     * @param inputs       input 欄位定義列表（決定維度順序）
     * @param enumMappings 值 → 整數索引映射（key = "fieldName:value"）
     * @param dimBounds    各維度的全域邊界 [dimMin, dimMax]
     * @return 超矩形列表（若有 IN operator 含多個離散值，可能拆成多個超矩形）
     */
    public static List<HyperRectangle> fromRule(RuleRow row,
                                                 List<FieldDef> inputs,
                                                 Map<String, Integer> enumMappings,
                                                 double[][] dimBounds) {
        List<String> dimNames = new ArrayList<>();
        for (FieldDef f : inputs) {
            dimNames.add(f.getName());
        }

        // 先對每個維度解析出可能的區間列表（IN operator 會產生多個離散區間）
        List<List<double[]>> allDimIntervals = new ArrayList<>();

        for (int d = 0; d < inputs.size(); d++) {
            FieldDef field = inputs.get(d);
            String fieldName = field.getName();
            String typeRef = field.getTypeRef();
            Condition cond = findCondition(row, fieldName);

            List<double[]> dimIntervalList;
            if (cond == null) {
                // 此規則未限制此欄位 → 使用全域邊界（等同 anything）
                dimIntervalList = List.of(new double[]{dimBounds[d][0], dimBounds[d][1]});
            } else {
                dimIntervalList = mapConditionToIntervals(
                        cond, typeRef, fieldName, enumMappings,
                        dimBounds[d][0], dimBounds[d][1]);
            }

            if (dimIntervalList.isEmpty()) {
                // 條件不可能滿足 → 回傳空列表
                return List.of();
            }
            allDimIntervals.add(dimIntervalList);
        }

        // 笛卡爾積展開（處理 IN 造成的多區間）
        return expandCartesian(row.getRuleId(), dimNames, allDimIntervals);
    }

    /**
     * 從 JsonNode 格式的 condition 建構超矩形，回傳含截斷資訊的 {@link Expansion}，
     * 讓呼叫端（如 DmnAnalyzer、RuleDiffService）能把「分析不完整」回報給使用者。
     * 刻意不提供丟棄 truncated 旗標的捷徑版本 — 每個消費者都必須處理截斷訊號。
     */
    public static Expansion fromJsonRuleWithInfo(String ruleId,
                                                 JsonNode conditionsNode,
                                                 List<FieldDef> inputs,
                                                 Map<String, Integer> enumMappings,
                                                 double[][] dimBounds) {
        List<String> dimNames = new ArrayList<>();
        for (FieldDef f : inputs) {
            dimNames.add(f.getName());
        }

        List<List<double[]>> allDimIntervals = new ArrayList<>();

        for (int d = 0; d < inputs.size(); d++) {
            FieldDef field = inputs.get(d);
            String fieldName = field.getName();
            String typeRef = field.getTypeRef();

            // 從 conditionsNode 中找到對應的 condition
            JsonNode condNode = findConditionNode(conditionsNode, fieldName);

            List<double[]> dimIntervalList;
            if (condNode == null) {
                dimIntervalList = List.of(new double[]{dimBounds[d][0], dimBounds[d][1]});
            } else {
                String operator = condNode.has("operator") ? condNode.get("operator").asText() : Operators.ANYTHING;
                JsonNode value = condNode.get("value");
                dimIntervalList = mapOperatorToIntervals(
                        operator, value, typeRef, fieldName, enumMappings,
                        dimBounds[d][0], dimBounds[d][1]);
            }

            if (dimIntervalList.isEmpty()) {
                return new Expansion(List.of(), false, 0);
            }
            allDimIntervals.add(dimIntervalList);
        }

        return expandCartesianWithInfo(ruleId, dimNames, allDimIntervals);
    }

    // ================================================================
    // 條件 → 區間映射（核心邏輯）
    // ================================================================

    private static List<double[]> mapConditionToIntervals(Condition cond,
                                                          String typeRef,
                                                          String fieldName,
                                                          Map<String, Integer> enumMappings,
                                                          double dimMin,
                                                          double dimMax) {
        String operator = cond.getOperator();
        Object rawValue = cond.getValue();

        // 轉為 JsonNode 以統一處理
        JsonNode value = (rawValue instanceof JsonNode jn) ? jn : MAPPER.valueToTree(rawValue);

        return mapOperatorToIntervals(operator, value, typeRef, fieldName, enumMappings, dimMin, dimMax);
    }

    /**
     * 將 operator + value 映射為區間列表。
     * 回傳 List 是因為 IN operator 可能產生多個離散點區間。
     */
    private static List<double[]> mapOperatorToIntervals(String operator,
                                                          JsonNode value,
                                                          String typeRef,
                                                          String fieldName,
                                                          Map<String, Integer> enumMappings,
                                                          double dimMin,
                                                          double dimMax) {
        if (operator == null || Operators.ANYTHING.equals(operator)
                || Operators.IS_NOT_NULL.equals(operator)) {
            return List.of(new double[]{dimMin, dimMax});
        }

        if (Operators.IS_NULL.equals(operator)) {
            // null 不在幾何空間中，視為空
            return List.of();
        }

        double numVal;

        switch (operator) {
            case "equals": {
                numVal = toNumericValue(value, typeRef, fieldName, enumMappings);
                return List.of(new double[]{numVal, numVal});
            }
            case "notEquals": {
                // notEquals v → 整個值域減去 v（近似：回傳整個值域，gap detection 會處理）
                // 精確處理需要區間補集，此處近似保守
                numVal = toNumericValue(value, typeRef, fieldName, enumMappings);
                if (isDiscrete(typeRef)) {
                    // 離散型：拆成 [dimMin, v-1] ∪ [v+1, dimMax]
                    List<double[]> result = new ArrayList<>();
                    if (numVal - 1 >= dimMin) {
                        result.add(new double[]{dimMin, numVal - 1});
                    }
                    if (numVal + 1 <= dimMax) {
                        result.add(new double[]{numVal + 1, dimMax});
                    }
                    return result.isEmpty() ? List.of() : result;
                } else {
                    // 連續型近似：整個值域（一個點的測度為 0）
                    return List.of(new double[]{dimMin, dimMax});
                }
            }
            case "greaterThan": {
                numVal = toNumericValue(value, typeRef, fieldName, enumMappings);
                double lo = isDiscrete(typeRef) ? numVal + 1 : numVal + 1e-9;
                return lo <= dimMax ? List.of(new double[]{lo, dimMax}) : List.of();
            }
            case "greaterThanOrEqual": {
                numVal = toNumericValue(value, typeRef, fieldName, enumMappings);
                return numVal <= dimMax ? List.of(new double[]{numVal, dimMax}) : List.of();
            }
            case "lessThan": {
                numVal = toNumericValue(value, typeRef, fieldName, enumMappings);
                double hi = isDiscrete(typeRef) ? numVal - 1 : numVal - 1e-9;
                return hi >= dimMin ? List.of(new double[]{dimMin, hi}) : List.of();
            }
            case "lessThanOrEqual": {
                numVal = toNumericValue(value, typeRef, fieldName, enumMappings);
                return numVal >= dimMin ? List.of(new double[]{dimMin, numVal}) : List.of();
            }
            case "between": {
                if (value != null && value.isArray() && value.size() == 2) {
                    double lo = toNumericValue(value.get(0), typeRef, fieldName, enumMappings);
                    double hi = toNumericValue(value.get(1), typeRef, fieldName, enumMappings);
                    return lo <= hi ? List.of(new double[]{lo, hi}) : List.of();
                }
                return List.of(new double[]{dimMin, dimMax});
            }
            case "in": {
                if (value != null && value.isArray()) {
                    List<double[]> result = new ArrayList<>();
                    for (JsonNode v : value) {
                        double idx = toNumericValue(v, typeRef, fieldName, enumMappings);
                        result.add(new double[]{idx, idx});
                    }
                    return result;
                }
                return List.of(new double[]{dimMin, dimMax});
            }
            case "notIn": {
                // notIn → 值域減去指定值集合（近似：回傳整個值域）
                if (value != null && value.isArray() && isDiscrete(typeRef)) {
                    Set<Double> excluded = new HashSet<>();
                    for (JsonNode v : value) {
                        excluded.add(toNumericValue(v, typeRef, fieldName, enumMappings));
                    }
                    // 對離散值域逐個排除
                    List<double[]> result = new ArrayList<>();
                    for (double i = dimMin; i <= dimMax; i += 1.0) {
                        if (!excluded.contains(i)) {
                            result.add(new double[]{i, i});
                        }
                    }
                    return result.isEmpty() ? List.of() : result;
                }
                return List.of(new double[]{dimMin, dimMax});
            }
            default: {
                log.warn("未知 operator '{}'，視為 anything", operator);
                return List.of(new double[]{dimMin, dimMax});
            }
        }
    }

    // ================================================================
    // 型別轉換：任意值 → 數值
    // ================================================================

    /**
     * 將任意 JSON 值根據 typeRef 轉為數值表示。
     */
    static double toNumericValue(JsonNode value, String typeRef, String fieldName,
                                  Map<String, Integer> enumMappings) {
        if (value == null || value.isNull()) {
            return 0.0;
        }

        switch (typeRef) {
            case TypeRefs.INTEGER:
                return value.isNumber() ? value.asLong() : parseDoubleSafe(value.asText());
            case TypeRefs.DECIMAL:
                return value.isNumber() ? value.asDouble() : parseDoubleSafe(value.asText());
            case TypeRefs.BOOLEAN:
                if (value.isBoolean()) return value.asBoolean() ? 1.0 : 0.0;
                if (value.isTextual()) return "true".equalsIgnoreCase(value.asText()) ? 1.0 : 0.0;
                return value.asInt();
            case TypeRefs.ENUM:
            case TypeRefs.STRING: {
                String key = fieldName + ":" + value.asText();
                Integer idx = enumMappings.get(key);
                return idx != null ? idx : 0.0;
            }
            case TypeRefs.DATE: {
                if (value.isTextual()) {
                    try {
                        LocalDate date = LocalDate.parse(value.asText());
                        return ChronoUnit.DAYS.between(EPOCH, date);
                    } catch (Exception e) {
                        log.warn("無法解析日期 '{}'，使用 0", value.asText());
                        return 0.0;
                    }
                }
                return value.isNumber() ? value.asDouble() : 0.0;
            }
            default:
                return value.isNumber() ? value.asDouble() : 0.0;
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 建立全域邊界超矩形（值域空間）。 */
    public static HyperRectangle universe(List<String> dimNames, double[][] dimBounds) {
        return new HyperRectangle("UNIVERSE", dimNames, dimBounds);
    }

    /** 判斷此超矩形是否為合法（每個維度 min <= max）。 */
    public boolean isValid() {
        for (double[] interval : intervals) {
            if (interval[0] > interval[1]) return false;
        }
        return true;
    }

    /**
     * 判斷兩個超矩形在指定維度以外是否完全相同（用於 simplification 合併判斷）。
     * 若兩個超矩形僅在一個維度上相鄰，且其餘維度完全相同，可合併。
     */
    public boolean isAdjacentOn(HyperRectangle other, int dim, boolean discrete) {
        checkDimensionMatch(other);
        // 檢查其他維度是否完全相同
        for (int i = 0; i < dimensions(); i++) {
            if (i == dim) continue;
            if (Math.abs(intervals[i][0] - other.intervals[i][0]) > 1e-9
                    || Math.abs(intervals[i][1] - other.intervals[i][1]) > 1e-9) {
                return false;
            }
        }
        // 檢查目標維度是否相鄰
        if (discrete) {
            // 離散型：[a,b] 與 [b+1,c] 相鄰
            return Math.abs(intervals[dim][1] + 1 - other.intervals[dim][0]) < 1e-9
                    || Math.abs(other.intervals[dim][1] + 1 - intervals[dim][0]) < 1e-9;
        } else {
            // 連續型：[a,b] 與 [b,c] 相鄰（共享邊界點）
            return Math.abs(intervals[dim][1] - other.intervals[dim][0]) < 1e-9
                    || Math.abs(other.intervals[dim][1] - intervals[dim][0]) < 1e-9;
        }
    }

    /**
     * 合併兩個在指定維度上相鄰的超矩形。
     */
    public HyperRectangle mergeOn(HyperRectangle other, int dim) {
        double[][] merged = deepCopy(intervals);
        merged[dim][0] = Math.min(intervals[dim][0], other.intervals[dim][0]);
        merged[dim][1] = Math.max(intervals[dim][1], other.intervals[dim][1]);
        return new HyperRectangle(ruleId + "+" + other.ruleId, dimensionNames, merged);
    }

    /**
     * 將超矩形的區間轉為人類可讀的條件描述。
     */
    public Map<String, String> toConditionMap(List<FieldDef> inputs,
                                               Map<Integer, String> reverseEnumMap,
                                               double[][] dimBounds) {
        Map<String, String> conditions = new LinkedHashMap<>();
        for (int i = 0; i < dimensions(); i++) {
            double lo = intervals[i][0];
            double hi = intervals[i][1];
            // 跳過等同全域的維度
            if (Math.abs(lo - dimBounds[i][0]) < 1e-9
                    && Math.abs(hi - dimBounds[i][1]) < 1e-9) {
                continue;
            }
            FieldDef field = inputs.get(i);
            String typeRef = field.getTypeRef();
            String desc = formatInterval(lo, hi, typeRef, field.getName(), reverseEnumMap);
            conditions.put(field.getName(), desc);
        }
        return conditions;
    }

    private String formatInterval(double lo, double hi, String typeRef, String fieldName,
                                   Map<Integer, String> reverseEnumMap) {
        if (TypeRefs.BOOLEAN.equals(typeRef)) {
            if (lo == 1.0 && hi == 1.0) return "true";
            if (lo == 0.0 && hi == 0.0) return "false";
            return "[0,1]";
        }
        if (TypeRefs.ENUM.equals(typeRef) || TypeRefs.STRING.equals(typeRef)) {
            if (Math.abs(lo - hi) < 1e-9 && reverseEnumMap != null) {
                String label = reverseEnumMap.get((int) lo);
                return label != null ? label : String.valueOf((int) lo);
            }
            // 範圍
            StringBuilder sb = new StringBuilder("[");
            for (int v = (int) lo; v <= (int) hi; v++) {
                if (v > (int) lo) sb.append(",");
                String label = reverseEnumMap != null ? reverseEnumMap.get(v) : null;
                sb.append(label != null ? label : String.valueOf(v));
            }
            return sb.append("]").toString();
        }
        if (TypeRefs.INTEGER.equals(typeRef)) {
            if (lo == hi) return String.valueOf((long) lo);
            return "[" + (long) lo + "," + (long) hi + "]";
        }
        if (TypeRefs.DATE.equals(typeRef)) {
            String loDate = EPOCH.plusDays((long) lo).toString();
            String hiDate = EPOCH.plusDays((long) hi).toString();
            if (lo == hi) return loDate;
            return "[" + loDate + "," + hiDate + "]";
        }
        // DECIMAL — 微小區間（< 1e-6 寬度）視為單點，避免顯示 [30.0, 30.000000001]
        if (Math.abs(hi - lo) < 1e-6) {
            // 顯示為整數（如果是整數值）或小數
            if (lo == Math.floor(lo)) {
                return String.valueOf((long) lo);
            }
            return String.valueOf(lo);
        }
        // 格式化去除不必要的小數
        String loStr = (lo == Math.floor(lo)) ? String.valueOf((long) lo) : String.valueOf(lo);
        String hiStr = (hi == Math.floor(hi)) ? String.valueOf((long) hi) : String.valueOf(hi);
        return "[" + loStr + "," + hiStr + "]";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("HyperRect{").append(ruleId).append(": ");
        for (int i = 0; i < dimensions(); i++) {
            if (i > 0) sb.append(" × ");
            sb.append(dimensionNames.get(i))
                    .append("[").append(intervals[i][0]).append(",").append(intervals[i][1]).append("]");
        }
        return sb.append("}").toString();
    }

    // ================================================================
    // Private helpers
    // ================================================================

    private void checkDimensionMatch(HyperRectangle other) {
        if (this.dimensions() != other.dimensions()) {
            throw new IllegalArgumentException(
                    "維度不一致：" + this.dimensions() + " vs " + other.dimensions());
        }
    }

    private static Condition findCondition(RuleRow row, String fieldName) {
        if (row.getConditions() == null) return null;
        for (Condition c : row.getConditions()) {
            if (fieldName.equals(c.getField())) return c;
        }
        return null;
    }

    private static JsonNode findConditionNode(JsonNode conditionsNode, String fieldName) {
        if (conditionsNode == null || !conditionsNode.isArray()) return null;
        for (JsonNode cn : conditionsNode) {
            if (cn.has("field") && fieldName.equals(cn.get("field").asText())) {
                return cn;
            }
        }
        return null;
    }

    private static boolean isDiscrete(String typeRef) {
        return TypeRefs.INTEGER.equals(typeRef)
                || TypeRefs.BOOLEAN.equals(typeRef)
                || TypeRefs.ENUM.equals(typeRef)
                || TypeRefs.STRING.equals(typeRef);
    }

    private static double parseDoubleSafe(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** 展開上限：超過此數量的笛卡爾積組合會被截斷（分析結果標記 truncated） */
    public static final int MAX_EXPANSION = 1000;

    /** 截斷警告最多列出的規則 ID 數（其餘以「等 N 條」收斂，避免 summary/log 無上限膨脹） */
    private static final int TRUNCATION_NOTE_MAX_IDS = 5;

    /**
     * 截斷警告字串 — DmnAnalyzer 與 RuleDiffService 共用，避免同一文案兩處各寫一份而 drift。
     * 截斷的影響是雙向的：被丟棄的組合既可能讓分析「漏報」，也可能在比對時產生「誤報」。
     *
     * @param truncatedRuleIds 被截斷的規則 ID（保持插入順序）
     * @param activity         消費端的活動名稱，如「重疊/缺口分析」「比對」
     */
    public static String truncationNote(java.util.Collection<String> truncatedRuleIds, String activity) {
        List<String> ids = new ArrayList<>(truncatedRuleIds);
        String listed = String.join(", ", ids.subList(0, Math.min(ids.size(), TRUNCATION_NOTE_MAX_IDS)));
        if (ids.size() > TRUNCATION_NOTE_MAX_IDS) {
            listed += " 等共 " + ids.size() + " 條";
        }
        return "（注意：" + ids.size() + " 條規則的條件組合超過展開上限 " + MAX_EXPANSION
                + "，" + activity + "在該區域可能不完整或失準：" + listed + "）";
    }

    /**
     * 笛卡爾積展開結果。
     *
     * @param rects     展開後的超矩形（最多 {@link #MAX_EXPANSION} 個）
     * @param truncated true 表示組合總數超過上限、已截斷 — 後續 overlap/gap 分析
     *                  在被截斷的區域會漏報，呼叫端應讓使用者知道結果不完整
     * @param totalCombinations 截斷前的理論組合總數
     */
    public record Expansion(List<HyperRectangle> rects, boolean truncated, long totalCombinations) {}

    /**
     * 笛卡爾積展開：當 IN operator 產生多個離散點時，
     * 需要將各維度的區間列表展開為多個超矩形。
     */
    private static List<HyperRectangle> expandCartesian(String ruleId,
                                                         List<String> dimNames,
                                                         List<List<double[]>> allDimIntervals) {
        return expandCartesianWithInfo(ruleId, dimNames, allDimIntervals).rects();
    }

    private static Expansion expandCartesianWithInfo(String ruleId,
                                                     List<String> dimNames,
                                                     List<List<double[]>> allDimIntervals) {
        // 快速路徑：如果所有維度都只有一個區間
        boolean allSingle = allDimIntervals.stream().allMatch(l -> l.size() == 1);
        if (allSingle) {
            double[][] intervals = new double[dimNames.size()][2];
            for (int i = 0; i < dimNames.size(); i++) {
                intervals[i] = allDimIntervals.get(i).get(0);
            }
            return new Expansion(List.of(new HyperRectangle(ruleId, dimNames, intervals)), false, 1);
        }

        // 笛卡爾積展開（long 計算防多維大 IN 列表的 int 溢位）
        int[] indices = new int[dimNames.size()];
        int[] sizes = new int[dimNames.size()];
        long total = 1;
        for (int i = 0; i < dimNames.size(); i++) {
            sizes[i] = allDimIntervals.get(i).size();
            total *= sizes[i];
        }

        // 限制展開數量避免組合爆炸；截斷必須回報，否則 overlap/gap 分析會無聲漏報
        boolean truncated = total > MAX_EXPANSION;
        int limit = (int) Math.min(total, MAX_EXPANSION);
        if (truncated) {
            log.warn("expandCartesian: 規則 {} 的條件組合 {} 超過上限 {}，已截斷 — 分析結果可能不完整",
                    ruleId, total, MAX_EXPANSION);
        }

        List<HyperRectangle> result = new ArrayList<>(limit);
        for (int t = 0; t < limit; t++) {
            double[][] intervals = new double[dimNames.size()][2];
            int temp = t;
            for (int d = dimNames.size() - 1; d >= 0; d--) {
                indices[d] = temp % sizes[d];
                temp /= sizes[d];
                intervals[d] = allDimIntervals.get(d).get(indices[d]).clone();
            }
            result.add(new HyperRectangle(ruleId + (total > 1 ? "#" + t : ""), dimNames, intervals));
        }

        return new Expansion(result, truncated, total);
    }

    private static double[][] deepCopy(double[][] src) {
        double[][] copy = new double[src.length][2];
        for (int i = 0; i < src.length; i++) {
            copy[i][0] = src[i][0];
            copy[i][1] = src[i][1];
        }
        return copy;
    }
}
