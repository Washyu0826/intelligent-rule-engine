package com.ruleengine.rules.service.diff;

import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.service.analyzer.DmnAnalyzer;
import com.ruleengine.rules.service.analyzer.DmnAnalyzer.GeometryModel;
import com.ruleengine.rules.service.analyzer.HyperRectangle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * DecisionTable 雙表行為比對（方向③ L2 — 超矩形集合差）。
 *
 * <p>把舊版與新版規則表各自建成 N 維超矩形集合（共用統一座標系），對值域空間取樣，
 * 找出三類行為差異：</p>
 * <ul>
 *   <li><b>LOST_COVERAGE（回歸）</b> —— 舊版有對應規則、新版漏掉的輸入組合（最該警示）</li>
 *   <li><b>CHANGED_DECISION</b> —— 新舊都命中但決策結果不同（最危險）</li>
 *   <li><b>NEW_COVERAGE</b> —— 新版新增涵蓋的輸入組合</li>
 * </ul>
 *
 * <p>幾何模型（enum 映射、維度邊界、離散旗標）委派 {@link DmnAnalyzer#buildGeometry}，
 * 與單表分析語意一致；超矩形建構與點包含複用 {@link HyperRectangle}。理論依據
 * Calvanese et al. (BPM 2016) 的「行為等價」概念，見 {@code design/tech-research-three-directions.md} §方向③。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleDiffService {

    private final DmnAnalyzer dmnAnalyzer;
    private final ObjectMapper objectMapper;

    private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);
    private static final long MAX_CELLS = 60_000;
    private static final int SAMPLES = 20_000;
    private static final int MAX_REGIONS = 40;

    public static final String LOST_COVERAGE = "LOST_COVERAGE";
    public static final String NEW_COVERAGE = "NEW_COVERAGE";
    public static final String CHANGED_DECISION = "CHANGED_DECISION";

    public record RegionDiff(String type, Map<String, String> point,
                             String oldDecision, String newDecision, String message) {}

    public record TableDiffResult(
            boolean comparable,
            int lostCount, int newCount, int changedCount,
            int sampledPoints, boolean approximate,
            List<RegionDiff> regions, String summary,
            /** 條件組合超過展開上限的規則 — 非空時 approximate=true，截斷區域的比對可能漏報或誤報 */
            List<String> truncatedRuleIds) {}

    // ===== 結構 diff（規則列對齊，依條件簽章；不靠 ruleId）=====
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String MODIFIED = "MODIFIED";

    public record RuleChange(String type, String ruleId, Map<String, String> conditions,
                             String oldDecision, String newDecision, String detail) {}

    public record RuleSetDiffResult(boolean comparable, int added, int removed, int modified, int unchanged,
                                    List<RuleChange> changes, String summary) {}

    // ====================================================================

    public TableDiffResult diff(RuleEnvelope before, RuleEnvelope after) {
        JsonNode beforeRule = ruleNode(before);
        JsonNode afterRule = ruleNode(after);
        if (beforeRule == null || afterRule == null) {
            return empty("其中一方不是可比對的 DecisionTable（缺 inputs/rules）");
        }

        List<FieldDef> beforeInputs = dmnAnalyzer.parseInputs(beforeRule.get("inputs"));
        List<FieldDef> afterInputs = dmnAnalyzer.parseInputs(afterRule.get("inputs"));
        String incompatibility = incompatibleInputTypes(beforeInputs, afterInputs);
        if (incompatibility != null) {
            return empty(incompatibility);
        }
        List<FieldDef> inputs = unifyInputs(beforeInputs, afterInputs);
        if (inputs.isEmpty()) {
            return empty("找不到輸入欄位，無法比對");
        }

        // 合併兩表 rules 餵入共用幾何模型 → 統一座標系（enum 索引、維度邊界一致）
        ArrayNode combined = objectMapper.createArrayNode();
        beforeRule.get("rules").forEach(combined::add);
        afterRule.get("rules").forEach(combined::add);
        GeometryModel geom = dmnAnalyzer.buildGeometry(inputs, combined);

        SideModel oldModel = buildSide(beforeRule.get("rules"), inputs, geom);
        SideModel newModel = buildSide(afterRule.get("rules"), inputs, geom);

        // 代表點取樣
        List<double[]> points = enumeratePoints(inputs, geom, oldModel, newModel);
        boolean approximate = points.isEmpty();
        if (approximate) {
            points = samplePoints(inputs, geom);
        }

        // 逐點分類，先收原始差異點（之後合併成區段）
        List<Raw> raw = new ArrayList<>();
        boolean rawTruncated = false;
        for (double[] pt : points) {
            String oldDec = decisionAt(pt, oldModel);
            String newDec = decisionAt(pt, newModel);

            String type;
            if (oldDec != null && newDec == null) type = LOST_COVERAGE;
            else if (oldDec == null && newDec != null) type = NEW_COVERAGE;
            else if (oldDec != null && !oldDec.equals(newDec)) type = CHANGED_DECISION;
            else continue;

            if (raw.size() < RAW_CAP) {
                raw.add(new Raw(type, oldDec, newDec, pt));
            } else {
                rawTruncated = true;
            }
        }
        approximate = approximate || rawTruncated;

        // 把相鄰差異點合併成可讀區段（複用 HyperRectangle 幾何合併）
        List<RegionDiff> regions = mergeRegions(raw, inputs, geom);

        int lost = (int) regions.stream().filter(r -> r.type().equals(LOST_COVERAGE)).count();
        int changed = (int) regions.stream().filter(r -> r.type().equals(CHANGED_DECISION)).count();
        int added = (int) regions.stream().filter(r -> r.type().equals(NEW_COVERAGE)).count();

        // 優先序：CHANGED（最危險）→ LOST（回歸）→ NEW
        regions.sort(Comparator.comparingInt(r -> priority(r.type())));
        if (regions.size() > MAX_REGIONS) regions = new ArrayList<>(regions.subList(0, MAX_REGIONS));

        String summary = String.format(
                "比對 %d 個輸入組合%s：回歸(漏覆蓋) %d 區段、決策改變 %d 區段、新增覆蓋 %d 區段",
                points.size(), approximate ? "（隨機取樣估計）" : "",
                lost, changed, added);

        // 任一側有規則被截斷 → 被丟棄的組合會讓 decisionAt 在該區域回 null，
        // 既可能漏報、也可能產生幻影 LOST/NEW_COVERAGE — 必須設 approximate 並結構化回報
        LinkedHashSet<String> truncated = new LinkedHashSet<>(oldModel.truncatedRuleIds());
        truncated.addAll(newModel.truncatedRuleIds());
        if (!truncated.isEmpty()) {
            approximate = true;
            summary += HyperRectangle.truncationNote(truncated, "比對");
        }
        log.info("rule-diff | {}", summary);

        return new TableDiffResult(true, lost, added, changed, points.size(), approximate, regions, summary,
                List.copyOf(truncated));
    }

    // ====================================================================
    //  區段合併（相鄰差異點 → 區間，複用 HyperRectangle.isAdjacentOn / mergeOn）
    // ====================================================================

    private record Raw(String type, String oldDec, String newDec, double[] pt) {}

    /** 收集原始差異點上限（超過則放棄合併、逐點呈現以免 O(n²) 過久）。 */
    private static final int RAW_CAP = 2000;
    private static final int MERGE_LIMIT = 800;

    private List<RegionDiff> mergeRegions(List<Raw> raw, List<FieldDef> inputs, GeometryModel geom) {
        List<String> dimNames = inputs.stream().map(FieldDef::getName).toList();
        boolean[] discrete = geom.discrete();

        // 依 (type, oldDec, newDec) 分組
        LinkedHashMap<String, List<Raw>> groups = new LinkedHashMap<>();
        for (Raw r : raw) {
            groups.computeIfAbsent(r.type() + "" + r.oldDec + "" + r.newDec, k -> new ArrayList<>()).add(r);
        }

        List<RegionDiff> out = new ArrayList<>();
        for (List<Raw> group : groups.values()) {
            Raw sample = group.get(0);
            List<HyperRectangle> rects = new ArrayList<>();
            for (Raw r : group) rects.add(degenerate(dimNames, r.pt()));

            if (group.size() <= MERGE_LIMIT) {
                rects = greedyMerge(rects, discrete);
            }
            for (HyperRectangle rect : rects) {
                out.add(new RegionDiff(sample.type(), rectConditions(rect, inputs, geom),
                        sample.oldDec(), sample.newDec(), message(sample.type(), sample.oldDec(), sample.newDec())));
            }
        }
        return out;
    }

    /** 反覆把「除某維外完全相同、且在該維相鄰」的超矩形合併。 */
    private List<HyperRectangle> greedyMerge(List<HyperRectangle> rects, boolean[] discrete) {
        List<HyperRectangle> list = new ArrayList<>(rects);
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    for (int d = 0; d < discrete.length; d++) {
                        if (list.get(i).isAdjacentOn(list.get(j), d, discrete[d])) {
                            HyperRectangle m = list.get(i).mergeOn(list.get(j), d);
                            list.remove(j);
                            list.set(i, m);
                            merged = true;
                            break outer;
                        }
                    }
                }
            }
        }
        return list;
    }

    private HyperRectangle degenerate(List<String> dimNames, double[] pt) {
        double[][] iv = new double[pt.length][2];
        for (int d = 0; d < pt.length; d++) { iv[d][0] = pt[d]; iv[d][1] = pt[d]; }
        return new HyperRectangle("pt", dimNames, iv);
    }

    private Map<String, String> rectConditions(HyperRectangle rect, List<FieldDef> inputs, GeometryModel geom) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int d = 0; d < inputs.size(); d++) {
            double lo = rect.getMin(d), hi = rect.getMax(d);
            FieldDef f = inputs.get(d);
            if (lo == hi) {
                m.put(f.getName(), fmtVal(lo, f, geom));
            } else {
                m.put(f.getName(), fmtVal(lo, f, geom) + " ~ " + fmtVal(hi, f, geom));
            }
        }
        return m;
    }

    private static int priority(String type) {
        return switch (type) {
            case CHANGED_DECISION -> 0;
            case LOST_COVERAGE -> 1;
            default -> 2;
        };
    }

    private String message(String type, String oldDec, String newDec) {
        return switch (type) {
            case LOST_COVERAGE -> "此輸入組合舊版會命中（" + oldDec + "），新版無任何規則涵蓋（回歸風險）";
            case CHANGED_DECISION -> "此輸入組合的決策由「" + oldDec + "」變為「" + newDec + "」";
            default -> "此輸入組合為新版新增涵蓋（" + newDec + "）";
        };
    }

    // ====================================================================
    //  幾何模型建構
    // ====================================================================

    private record RuleGeo(String ruleId, String decision, List<HyperRectangle> rects) {}
    private record SideModel(List<RuleGeo> rules, List<String> truncatedRuleIds) {}

    private SideModel buildSide(JsonNode rulesNode, List<FieldDef> inputs, GeometryModel geom) {
        List<RuleGeo> rules = new ArrayList<>();
        List<String> truncatedRuleIds = new ArrayList<>();
        int i = 0;
        for (JsonNode row : rulesNode) {
            String ruleId = row.has("ruleId") ? row.get("ruleId").asText() : "rule-" + i;
            // v3.16.1: 用 WithInfo 取得截斷訊號 — IN 笛卡爾積超限時 diff 在截斷區會漏算，必須回報
            HyperRectangle.Expansion expansion = HyperRectangle.fromJsonRuleWithInfo(
                    ruleId, row.get("conditions"), inputs, geom.enumMappings(), geom.dimBounds());
            if (expansion.truncated()) {
                truncatedRuleIds.add(ruleId);
            }
            rules.add(new RuleGeo(ruleId, decisionOf(row), expansion.rects()));
            i++;
        }
        return new SideModel(rules, truncatedRuleIds);
    }

    /** FIRST-hit：依規則順序，回傳第一條包含此點的規則決策；皆未命中回 null。 */
    private String decisionAt(double[] pt, SideModel side) {
        for (RuleGeo rg : side.rules()) {
            for (HyperRectangle hr : rg.rects()) {
                if (contains(hr, pt)) return rg.decision();
            }
        }
        return null;
    }

    private static boolean contains(HyperRectangle hr, double[] pt) {
        for (int d = 0; d < pt.length; d++) {
            if (pt[d] < hr.getMin(d) || pt[d] > hr.getMax(d)) return false;
        }
        return true;
    }

    private String decisionOf(JsonNode row) {
        JsonNode res = row.get("results");
        if (res == null || !res.isArray() || res.isEmpty()) return "(無結果)";
        List<String> parts = new ArrayList<>();
        for (JsonNode r : res) {
            if (r.has("field")) {
                String v = r.has("value") ? r.get("value").asText() : "";
                parts.add(r.get("field").asText() + "=" + v);
            }
        }
        return parts.stream().sorted().collect(Collectors.joining(","));
    }

    // ====================================================================
    //  代表點列舉 / 取樣
    // ====================================================================

    private List<double[]> enumeratePoints(List<FieldDef> inputs, GeometryModel geom,
                                           SideModel oldModel, SideModel newModel) {
        int dims = inputs.size();
        List<List<Double>> perDim = new ArrayList<>();
        long total = 1;
        for (int d = 0; d < dims; d++) {
            List<Double> vals = repValues(d, inputs.get(d), geom, oldModel, newModel);
            if (vals.isEmpty()) return List.of();
            perDim.add(vals);
            total *= vals.size();
            if (total > MAX_CELLS) return List.of(); // 太大 → 改用取樣
        }
        List<double[]> points = new ArrayList<>();
        cartesian(perDim, 0, new double[dims], points);
        return points;
    }

    private void cartesian(List<List<Double>> perDim, int d, double[] cur, List<double[]> out) {
        if (d == perDim.size()) { out.add(cur.clone()); return; }
        for (double v : perDim.get(d)) {
            cur[d] = v;
            cartesian(perDim, d + 1, cur, out);
        }
    }

    /** 某維度的代表值：離散→每個整數值；連續→規則邊界切點之間的中點。 */
    private List<Double> repValues(int d, FieldDef field, GeometryModel geom,
                                   SideModel oldModel, SideModel newModel) {
        double lo = geom.dimBounds()[d][0], hi = geom.dimBounds()[d][1];
        List<Double> vals = new ArrayList<>();
        if (geom.discrete()[d]) {
            double start = Math.ceil(lo);
            double end = Math.floor(hi);
            if (end - start + 1 > 5000) {
                return List.of(); // large discrete domain -> deterministic random sampling
            }
            for (double v = start; v <= end; v += 1.0) {
                vals.add(v);
            }
        } else {
            // Continuous behavior can only change at rule boundaries. Include
            // every boundary plus each interval midpoint so narrow regressions
            // are not missed by the old global lo/mid/hi sampling.
            TreeSet<Double> cuts = new TreeSet<>();
            cuts.add(lo);
            cuts.add(hi);
            addContinuousCuts(cuts, d, lo, hi, oldModel);
            addContinuousCuts(cuts, d, lo, hi, newModel);
            List<Double> ordered = new ArrayList<>(cuts);
            for (int i = 0; i < ordered.size(); i++) {
                vals.add(ordered.get(i));
                if (i + 1 < ordered.size() && ordered.get(i + 1) > ordered.get(i)) {
                    vals.add((ordered.get(i) + ordered.get(i + 1)) / 2.0);
                }
            }
        }
        return vals;
    }

    private void addContinuousCuts(Set<Double> cuts, int dimension, double lo, double hi, SideModel side) {
        for (RuleGeo rule : side.rules()) {
            for (HyperRectangle rect : rule.rects()) {
                cuts.add(Math.max(lo, Math.min(hi, rect.getMin(dimension))));
                cuts.add(Math.max(lo, Math.min(hi, rect.getMax(dimension))));
            }
        }
    }

    private List<double[]> samplePoints(List<FieldDef> inputs, GeometryModel geom) {
        Random rand = new Random(42);
        int dims = inputs.size();
        List<double[]> points = new ArrayList<>();
        for (int s = 0; s < SAMPLES; s++) {
            double[] pt = new double[dims];
            for (int d = 0; d < dims; d++) {
                double lo = geom.dimBounds()[d][0], hi = geom.dimBounds()[d][1];
                if (geom.discrete()[d]) {
                    int range = (int) (hi - lo) + 1;
                    pt[d] = lo + rand.nextInt(Math.max(range, 1));
                } else {
                    pt[d] = lo + rand.nextDouble() * (hi - lo);
                }
            }
            points.add(pt);
        }
        return points;
    }

    // ====================================================================
    //  顯示
    // ====================================================================

    private String fmtVal(double v, FieldDef field, GeometryModel geom) {
        String t = field.getTypeRef();
        if (TypeRefs.BOOLEAN.equals(t)) return v >= 0.5 ? "true" : "false";
        if (TypeRefs.INTEGER.equals(t)) return String.valueOf((long) v);
        if (TypeRefs.DATE.equals(t)) return EPOCH.plusDays((long) v).toString();
        if (TypeRefs.ENUM.equals(t) && field.getAllowedValues() != null) {
            int idx = (int) v;
            if (idx >= 0 && idx < field.getAllowedValues().size()) return field.getAllowedValues().get(idx);
        }
        if (TypeRefs.ENUM.equals(t) || TypeRefs.STRING.equals(t)) {
            String label = geom.reverseEnumMap().get((int) v);
            if (label != null) return label;
        }
        return (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ====================================================================
    //  輸入解析 / 聯集
    // ====================================================================

    private JsonNode ruleNode(RuleEnvelope env) {
        if (env == null) return null;
        if (env.getRuleType() != null && !"DecisionTable".equalsIgnoreCase(env.getRuleType())) return null;
        JsonNode rule = dmnAnalyzer.resolveRule(objectMapper.valueToTree(env));
        if (rule == null) return null;
        if (rule.get("inputs") == null || !rule.get("inputs").isArray()) return null;
        if (rule.get("rules") == null || !rule.get("rules").isArray()) return null;
        return rule;
    }

    /** 聯集兩表 input 欄位（依名稱）；typeRef 取 before，allowedValues 取聯集（before 先、after 補）。 */
    private List<FieldDef> unifyInputs(List<FieldDef> a, List<FieldDef> b) {
        LinkedHashMap<String, FieldDef> byName = new LinkedHashMap<>();
        for (FieldDef f : a) byName.put(f.getName(), cloneField(f));
        for (FieldDef f : b) {
            FieldDef existing = byName.get(f.getName());
            if (existing == null) {
                byName.put(f.getName(), cloneField(f));
            } else if (f.getAllowedValues() != null) {
                List<String> merged = new ArrayList<>(
                        existing.getAllowedValues() != null ? existing.getAllowedValues() : List.of());
                for (String v : f.getAllowedValues()) if (!merged.contains(v)) merged.add(v);
                existing.setAllowedValues(merged);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private FieldDef cloneField(FieldDef f) {
        FieldDef c = FieldDef.builder().name(f.getName()).typeRef(f.getTypeRef()).build();
        if (f.getAllowedValues() != null) c.setAllowedValues(new ArrayList<>(f.getAllowedValues()));
        return c;
    }

    private TableDiffResult empty(String reason) {
        return new TableDiffResult(false, 0, 0, 0, 0, false, List.of(), reason, List.of());
    }

    // ====================================================================
    //  結構 diff：規則列對齊（依條件簽章，不靠 ruleId）
    // ====================================================================

    private record RuleInfo(String ruleId, String sig, Map<String, String> conditions, String decision) {}

    public RuleSetDiffResult structuralDiff(RuleEnvelope before, RuleEnvelope after) {
        JsonNode b = ruleNode(before);
        JsonNode a = ruleNode(after);
        if (b == null || a == null) {
            return new RuleSetDiffResult(false, 0, 0, 0, 0, List.of(),
                    "其中一方不是可比對的 DecisionTable（缺 inputs/rules）");
        }

        String incompatibility = incompatibleInputTypes(
                dmnAnalyzer.parseInputs(b.get("inputs")),
                dmnAnalyzer.parseInputs(a.get("inputs")));
        if (incompatibility != null) {
            return new RuleSetDiffResult(false, 0, 0, 0, 0, List.of(), incompatibility);
        }

        Map<String, List<RuleInfo>> beforeBySig = groupBySig(ruleInfos(b.get("rules")));
        Map<String, List<RuleInfo>> afterBySig = groupBySig(ruleInfos(a.get("rules")));
        Set<String> signatures = new LinkedHashSet<>(beforeBySig.keySet());
        signatures.addAll(afterBySig.keySet());

        int added = 0, removed = 0, modified = 0, unchanged = 0;
        List<RuleChange> changes = new ArrayList<>();

        for (String signature : signatures) {
            List<RuleInfo> beforeRemaining = new ArrayList<>(
                    beforeBySig.getOrDefault(signature, List.of()));
            List<RuleInfo> afterRemaining = new ArrayList<>(
                    afterBySig.getOrDefault(signature, List.of()));

            // Match identical decisions first so duplicate signatures are
            // treated as a multiset rather than collapsed to the first row.
            for (int i = beforeRemaining.size() - 1; i >= 0; i--) {
                RuleInfo bi = beforeRemaining.get(i);
                int same = indexOfDecision(afterRemaining, bi.decision());
                if (same >= 0) {
                    unchanged++;
                    beforeRemaining.remove(i);
                    afterRemaining.remove(same);
                }
            }

            int pairs = Math.min(beforeRemaining.size(), afterRemaining.size());
            for (int i = 0; i < pairs; i++) {
                RuleInfo bi = beforeRemaining.get(i);
                RuleInfo ai = afterRemaining.get(i);
                modified++;
                changes.add(new RuleChange(MODIFIED, bi.ruleId(), bi.conditions(),
                        bi.decision(), ai.decision(),
                        String.format("規則 %s（%s）決策由「%s」改為「%s」",
                                bi.ruleId(), condStr(bi.conditions()), bi.decision(), ai.decision())));
            }
            for (int i = pairs; i < beforeRemaining.size(); i++) {
                RuleInfo bi = beforeRemaining.get(i);
                removed++;
                changes.add(new RuleChange(REMOVED, bi.ruleId(), bi.conditions(),
                        bi.decision(), null,
                        String.format("刪除規則 %s（%s → %s）", bi.ruleId(), condStr(bi.conditions()), bi.decision())));
            }
            for (int i = pairs; i < afterRemaining.size(); i++) {
                RuleInfo ai = afterRemaining.get(i);
                added++;
                changes.add(new RuleChange(ADDED, ai.ruleId(), ai.conditions(),
                        null, ai.decision(),
                        String.format("新增規則 %s（%s → %s）", ai.ruleId(), condStr(ai.conditions()), ai.decision())));
            }
        }

        // 優先序：MODIFIED（決策改變）→ REMOVED → ADDED
        changes.sort(Comparator.comparingInt(c -> switch (c.type()) {
            case MODIFIED -> 0;
            case REMOVED -> 1;
            default -> 2;
        }));

        String summary = String.format("規則結構比對：新增 %d、刪除 %d、修改 %d；%d 條不變",
                added, removed, modified, unchanged);
        log.info("rule-structural-diff | {}", summary);
        return new RuleSetDiffResult(true, added, removed, modified, unchanged, changes, summary);
    }

    private List<RuleInfo> ruleInfos(JsonNode rulesNode) {
        List<RuleInfo> list = new ArrayList<>();
        int i = 0;
        for (JsonNode row : rulesNode) {
            String ruleId = row.has("ruleId") ? row.get("ruleId").asText() : "rule-" + i;
            JsonNode conds = row.get("conditions");
            List<String> sigParts = new ArrayList<>();
            Map<String, String> disp = new LinkedHashMap<>();
            if (conds != null && conds.isArray()) {
                for (JsonNode c : conds) {
                    String f = c.path("field").asText("");
                    String op = c.path("operator").asText("");
                    boolean hasValueRef = c.hasNonNull("valueRef") && !c.get("valueRef").asText().isBlank();
                    String v = hasValueRef ? "<" + c.get("valueRef").asText() + ">" : valText(c.get("value"));
                    sigParts.add(f + "|" + op + "|" + (hasValueRef ? "ref:" : "value:") + v);
                    disp.put(f, (op + " " + v).trim());
                }
            }
            sigParts.sort(Comparator.naturalOrder());
            list.add(new RuleInfo(ruleId, String.join(" && ", sigParts), disp, decisionOf(row)));
            i++;
        }
        return list;
    }

    private Map<String, List<RuleInfo>> groupBySig(List<RuleInfo> infos) {
        Map<String, List<RuleInfo>> m = new LinkedHashMap<>();
        for (RuleInfo ri : infos) {
            m.computeIfAbsent(ri.sig(), ignored -> new ArrayList<>()).add(ri);
        }
        return m;
    }

    private int indexOfDecision(List<RuleInfo> infos, String decision) {
        for (int i = 0; i < infos.size(); i++) {
            if (infos.get(i).decision().equals(decision)) return i;
        }
        return -1;
    }

    private String valText(JsonNode v) {
        if (v == null || v.isNull()) return "";
        if (v.isContainerNode()) return v.toString();
        return v.asText();
    }

    private String incompatibleInputTypes(List<FieldDef> before, List<FieldDef> after) {
        Map<String, String> beforeTypes = new HashMap<>();
        for (FieldDef field : before) beforeTypes.put(field.getName(), field.getTypeRef());
        for (FieldDef field : after) {
            String previous = beforeTypes.get(field.getName());
            if (previous != null && !previous.equals(field.getTypeRef())) {
                return String.format("輸入欄位 %s 的 typeRef 不一致（%s → %s），無法可靠比對",
                        field.getName(), previous, field.getTypeRef());
            }
        }
        return null;
    }

    private String condStr(Map<String, String> conditions) {
        if (conditions.isEmpty()) return "無條件";
        return conditions.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining("、"));
    }
}
