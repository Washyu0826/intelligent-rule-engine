package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 條件重疊偵測共用服務。
 *
 * 提供條件互斥判斷、數值區間轉換、集合交集運算等功能，
 * 由 Generator（快速衝突偵測）、Validator（INCONSISTENT_TABLE）、DmnAnalyzer（overlap 分析）共用。
 *
 * v2.0.0: 從 DecisionTableGenerator 抽出，消除三處重複實作。
 */
@Component
@Slf4j
public class ConditionOverlapDetector {

    // ================================================================
    // 公開 API
    // ================================================================

    /**
     * 判斷兩條規則是否可能重疊（所有條件欄位都無法證明互斥）。
     *
     * @return true 若兩條規則可能同時命中
     */
    public boolean canOverlap(RuleRow a, RuleRow b) {
        if (a.getConditions() == null || b.getConditions() == null) return false;

        Map<String, Condition> mapA = toConditionMap(a.getConditions());
        Map<String, Condition> mapB = toConditionMap(b.getConditions());

        for (String field : mapA.keySet()) {
            Condition cA = mapA.get(field);
            Condition cB = mapB.get(field);
            if (cB == null) continue;

            // anything 不造成互斥
            if (isAnything(cA) || isAnything(cB)) continue;

            if (areMutuallyExclusive(cA, cB)) {
                return false;
            }
        }
        return true; // 無法證明互斥 → 可能重疊
    }

    /**
     * 快速衝突偵測：在規則列表中是否存在任何一對可能重疊的規則。
     *
     * 實作：profile 預計算 + 掃描線剪枝（見 {@link #findAllOverlaps}），首見重疊即返回。
     *
     * @return true 若存在至少一對重疊的規則
     */
    public boolean hasAnyOverlap(List<RuleRow> rows) {
        if (rows == null || rows.size() < 2) return false;
        return !scanOverlaps(rows, true).isEmpty();
    }

    /**
     * 找出所有重疊的規則對。
     *
     * 效能（v3.16）：原為樸素 O(n²)，且每對都重建 conditionMap、重新解析數值區間。
     * 改為兩段式：
     *   1. profile 預計算 — conditionMap 與數值區間每列只算一次（O(n·m)）
     *   2. 掃描線剪枝 — 挑數值區間覆蓋率最高的欄位排序掃描；
     *      該欄位區間不相交的配對 = 已證明互斥，直接跳過完整比對
     * 語意與原全配對版本完全等價（互斥判斷共用 areMutuallyExclusive 同一份邏輯），
     * 輸出順序維持 (i asc, j asc)。
     *
     * @return 重疊的規則索引對列表 [i, j]
     */
    public List<int[]> findAllOverlaps(List<RuleRow> rows) {
        if (rows == null || rows.size() < 2) return new ArrayList<>();
        return scanOverlaps(rows, false);
    }

    // ================================================================
    // 批次偵測核心（profile 預計算 + 掃描線剪枝）
    // ================================================================

    /** 每列預計算結果：field → (條件, 數值區間)；conditionsNull 對應原版「null conditions 不重疊」語意 */
    private record CondProfile(Condition cond, double[] range) {}
    private record RowProfile(Map<String, CondProfile> byField, boolean conditionsNull) {}

    /**
     * @param firstHitOnly true = 找到第一對即返回（hasAnyOverlap 用，省去後續比對與排序）
     * @return 重疊對列表（空 list = 無重疊）；完整模式下依 (i asc, j asc) 排序
     */
    private List<int[]> scanOverlaps(List<RuleRow> rows, boolean firstHitOnly) {
        int n = rows.size();
        RowProfile[] profiles = new RowProfile[n];
        for (int i = 0; i < n; i++) profiles[i] = profile(rows.get(i));

        // 挑掃描欄位：數值區間覆蓋列數最多的 field（過半才值得剪枝）
        Map<String, Integer> rangeCoverage = new HashMap<>();
        for (RowProfile p : profiles) {
            for (var e : p.byField().entrySet()) {
                if (e.getValue().range() != null) rangeCoverage.merge(e.getKey(), 1, Integer::sum);
            }
        }
        String sweepField = rangeCoverage.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() >= n / 2 && e.getValue() >= 2)
                .map(Map.Entry::getKey).orElse(null);

        List<int[]> overlaps = new ArrayList<>();

        if (sweepField == null) {
            // 無合適掃描欄位 → 全配對，但仍享有 profile 預計算的增益
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (canOverlapProfiled(profiles[i], profiles[j])) {
                        overlaps.add(new int[]{i, j});
                        if (firstHitOnly) return overlaps;
                    }
                }
            }
            return overlaps;
        }

        // 掃描線：依 sweepField 區間下界排序；活躍集中上界 < 當前下界者淘汰。
        // 候選配對「即查即收」不先物化（全重疊的最壞情況下候選對是 O(n²)，物化會白耗記憶體）。
        List<Integer> ranged = new ArrayList<>();
        List<Integer> wild = new ArrayList<>();   // 該欄位無數值區間的列（含 anything／缺欄位）
        for (int i = 0; i < n; i++) {
            CondProfile cp = profiles[i].byField().get(sweepField);
            if (cp != null && cp.range() != null) ranged.add(i); else wild.add(i);
        }
        // 區間上下界一次取出（避免掃描迴圈內重複 map 查找）
        double[] lo = new double[n];
        double[] hi = new double[n];
        for (int i : ranged) {
            double[] r = profiles[i].byField().get(sweepField).range();
            lo[i] = r[0];
            hi[i] = r[1];
        }
        ranged.sort(Comparator.comparingDouble(i -> lo[i]));

        List<Integer> active = new ArrayList<>();
        for (int idx : ranged) {
            double currentLo = lo[idx];
            active.removeIf(p -> hi[p] < currentLo);
            for (int p : active) {
                if (collectIfOverlap(p, idx, profiles, overlaps) && firstHitOnly) return overlaps;
            }
            active.add(idx);
        }
        // 萬用列與所有其他列都是候選
        for (int wi = 0; wi < wild.size(); wi++) {
            int w = wild.get(wi);
            for (int r : ranged) {
                if (collectIfOverlap(w, r, profiles, overlaps) && firstHitOnly) return overlaps;
            }
            for (int wj = wi + 1; wj < wild.size(); wj++) {
                if (collectIfOverlap(w, wild.get(wj), profiles, overlaps) && firstHitOnly) return overlaps;
            }
        }

        // 輸出順序與原全配對版本一致（只排真正重疊的結果，不排全部候選）
        overlaps.sort(Comparator.<int[]>comparingInt(p -> p[0]).thenComparingInt(p -> p[1]));
        return overlaps;
    }

    /** 配對檢查後直接收進結果（以 i&lt;j 正規化）；回傳是否為重疊對 */
    private boolean collectIfOverlap(int a, int b, RowProfile[] profiles, List<int[]> out) {
        if (!canOverlapProfiled(profiles[a], profiles[b])) return false;
        out.add(a < b ? new int[]{a, b} : new int[]{b, a});
        return true;
    }

    private RowProfile profile(RuleRow row) {
        if (row.getConditions() == null) return new RowProfile(Map.of(), true);
        Map<String, CondProfile> m = new LinkedHashMap<>();
        for (Condition c : row.getConditions()) {
            if (c.getField() != null) m.put(c.getField(), new CondProfile(c, toNumericRange(c)));
        }
        return new RowProfile(m, false);
    }

    /** 與 canOverlap(RuleRow, RuleRow) 語意等價，但使用預計算的 map 與區間 */
    private boolean canOverlapProfiled(RowProfile a, RowProfile b) {
        if (a.conditionsNull() || b.conditionsNull()) return false;
        for (var e : a.byField().entrySet()) {
            CondProfile pa = e.getValue();
            CondProfile pb = b.byField().get(e.getKey());
            if (pb == null) continue;
            if (isAnything(pa.cond()) || isAnything(pb.cond())) continue;
            if (areMutuallyExclusiveWithRanges(pa.cond(), pb.cond(), pa.range(), pb.range())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判斷兩個條件是否互斥（不可能同時成立）。
     * 支援所有 operator 組合：equals, between, greaterThan, lessThan, in, notIn。
     *
     * @return true 若兩條件互斥（不可能同時滿足）
     */
    public boolean areMutuallyExclusive(Condition cA, Condition cB) {
        return areMutuallyExclusiveWithRanges(cA, cB, toNumericRange(cA), toNumericRange(cB));
    }

    /**
     * 互斥判斷核心 — 數值區間由呼叫端提供（批次路徑用預計算區間，避免每對重新解析）。
     */
    private boolean areMutuallyExclusiveWithRanges(Condition cA, Condition cB,
                                                   double[] rangeA, double[] rangeB) {
        // 如果兩個都能轉為數值區間 → 比較區間是否不重疊
        if (rangeA != null && rangeB != null) {
            return rangeA[1] < rangeB[0] || rangeB[1] < rangeA[0];
        }

        String opA = cA.getOperator();
        String opB = cB.getOperator();

        // ===== 離散值比較 =====

        // equals vs equals：值不同 → 互斥
        if (Operators.EQUALS.equals(opA) && Operators.EQUALS.equals(opB)) {
            return cA.getValue() != null && cB.getValue() != null
                    && !String.valueOf(cA.getValue()).equals(String.valueOf(cB.getValue()));
        }

        // in vs in：集合無交集 → 互斥
        if (Operators.IN.equals(opA) && Operators.IN.equals(opB)) {
            return !setsIntersect(cA.getValue(), cB.getValue());
        }

        // equals vs in / in vs equals
        if (Operators.EQUALS.equals(opA) && Operators.IN.equals(opB)) {
            return !valueInSet(cA.getValue(), cB.getValue());
        }
        if (Operators.IN.equals(opA) && Operators.EQUALS.equals(opB)) {
            return !valueInSet(cB.getValue(), cA.getValue());
        }

        // equals vs notIn / notIn vs equals
        if (Operators.EQUALS.equals(opA) && Operators.NOT_IN.equals(opB)) {
            return valueInSet(cA.getValue(), cB.getValue());
        }
        if (Operators.NOT_IN.equals(opA) && Operators.EQUALS.equals(opB)) {
            return valueInSet(cB.getValue(), cA.getValue());
        }

        // in vs notIn / notIn vs in
        if (Operators.IN.equals(opA) && Operators.NOT_IN.equals(opB)) {
            return isSubset(cA.getValue(), cB.getValue());
        }
        if (Operators.NOT_IN.equals(opA) && Operators.IN.equals(opB)) {
            return isSubset(cB.getValue(), cA.getValue());
        }

        // 無法判斷 → 不互斥（保守策略）
        return false;
    }

    /**
     * 將條件轉為數值區間 [min, max]。
     * 支援 equals, between, greaterThan(OrEqual), lessThan(OrEqual)。
     * 無法轉換時返回 null。
     */
    public double[] toNumericRange(Condition cond) {
        String op = cond.getOperator();
        Object val = cond.getValue();
        if (op == null || val == null) return null;

        try {
            if (Operators.EQUALS.equals(op)) {
                double v = Double.parseDouble(String.valueOf(val));
                return new double[]{v, v};
            }
            if (Operators.BETWEEN.equals(op) && val instanceof List) {
                List<?> list = (List<?>) val;
                if (list.size() == 2) {
                    double min = Double.parseDouble(String.valueOf(list.get(0)));
                    double max = Double.parseDouble(String.valueOf(list.get(1)));
                    return new double[]{min, max};
                }
            }
            if (Operators.GREATER_THAN.equals(op)) {
                double v = Double.parseDouble(String.valueOf(val));
                return new double[]{v + 0.0001, Double.MAX_VALUE};
            }
            if (Operators.GREATER_THAN_OR_EQUAL.equals(op)) {
                double v = Double.parseDouble(String.valueOf(val));
                return new double[]{v, Double.MAX_VALUE};
            }
            if (Operators.LESS_THAN.equals(op)) {
                double v = Double.parseDouble(String.valueOf(val));
                return new double[]{-Double.MAX_VALUE, v - 0.0001};
            }
            if (Operators.LESS_THAN_OR_EQUAL.equals(op)) {
                double v = Double.parseDouble(String.valueOf(val));
                return new double[]{-Double.MAX_VALUE, v};
            }
        } catch (NumberFormatException ignored) {
            // 非數值，無法轉為區間
        }
        return null;
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /**
     * 判斷條件是否為 anything（萬用匹配）。
     */
    public boolean isAnything(Condition c) {
        return c == null || Operators.ANYTHING.equals(c.getOperator());
    }

    /**
     * 將條件列表轉為 field → Condition 的映射。
     */
    public Map<String, Condition> toConditionMap(List<Condition> conditions) {
        Map<String, Condition> map = new LinkedHashMap<>();
        if (conditions != null) {
            for (Condition c : conditions) {
                if (c.getField() != null) map.put(c.getField(), c);
            }
        }
        return map;
    }

    /**
     * 判斷兩個 in 集合是否有交集。
     */
    public boolean setsIntersect(Object valA, Object valB) {
        if (!(valA instanceof List) || !(valB instanceof List)) return true; // 保守
        Set<String> setA = ((List<?>) valA).stream()
                .map(String::valueOf).collect(Collectors.toSet());
        Set<String> setB = ((List<?>) valB).stream()
                .map(String::valueOf).collect(Collectors.toSet());
        return setA.stream().anyMatch(setB::contains);
    }

    /**
     * 判斷單一值是否在集合中。
     */
    public boolean valueInSet(Object singleValue, Object setValue) {
        if (singleValue == null || !(setValue instanceof List)) return false;
        String target = String.valueOf(singleValue);
        return ((List<?>) setValue).stream()
                .map(String::valueOf)
                .anyMatch(target::equals);
    }

    /**
     * 判斷 setA 是否為 setB 的子集。
     */
    public boolean isSubset(Object valA, Object valB) {
        if (!(valA instanceof List) || !(valB instanceof List)) return false;
        Set<String> setB = ((List<?>) valB).stream()
                .map(String::valueOf).collect(Collectors.toSet());
        return ((List<?>) valA).stream()
                .map(String::valueOf)
                .allMatch(setB::contains);
    }

    // ================================================================
    // JsonNode-based overloads（供 Validator 使用）
    // ================================================================

    /**
     * 將 operator + JsonNode value 轉為數值閉區間 [min, max]。
     * 無法轉換時返回 null。
     * 供 ConsistencyValidator 等使用 JsonNode 的元件呼叫。
     */
    public double[] toNumericRangeFromJson(String operator, JsonNode value) {
        if (operator == null || value == null || value.isNull()) return null;

        try {
            if (Operators.EQUALS.equals(operator) && value.isNumber()) {
                double v = value.asDouble();
                return new double[]{v, v};
            }
            if (Operators.BETWEEN.equals(operator) && value.isArray() && value.size() == 2) {
                double min = value.get(0).asDouble();
                double max = value.get(1).asDouble();
                return new double[]{min, max};
            }
            if (Operators.GREATER_THAN.equals(operator)) {
                double v = value.asDouble();
                return new double[]{v + 0.001, Double.MAX_VALUE};
            }
            if (Operators.GREATER_THAN_OR_EQUAL.equals(operator)) {
                double v = value.asDouble();
                return new double[]{v, Double.MAX_VALUE};
            }
            if (Operators.LESS_THAN.equals(operator)) {
                double v = value.asDouble();
                return new double[]{-Double.MAX_VALUE, v - 0.001};
            }
            if (Operators.LESS_THAN_OR_EQUAL.equals(operator)) {
                double v = value.asDouble();
                return new double[]{-Double.MAX_VALUE, v};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 兩個數值閉區間 [min, max] 的交集。
     * 回傳 null 表示無交集。
     */
    public double[] rangeIntersection(double[] a, double[] b) {
        if (a == null || b == null) return null;
        double lo = Math.max(a[0], b[0]);
        double hi = Math.min(a[1], b[1]);
        return lo <= hi ? new double[]{lo, hi} : null;
    }

    /**
     * 從 JsonNode 中提取值集合（用於 equals / in operator）。
     * 回傳 null 表示無法提取有限集合（如 notEquals, notIn）。
     */
    public Set<String> extractValueSet(String operator, JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (Operators.EQUALS.equals(operator)) {
            return Set.of(value.asText());
        }
        if (Operators.IN.equals(operator) && value.isArray()) {
            Set<String> s = new LinkedHashSet<>();
            value.forEach(v -> s.add(v.asText()));
            return s;
        }
        return null; // notEquals, notIn 是補集
    }
}
