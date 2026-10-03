package com.ruleengine.rules.service.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.ScoreBand;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.ScoringDimension;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.ScoringRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 評分卡分析：分數帶有沒有缺口／重疊、各維度的計分規則有沒有漏掉值。
 *
 * <p>總分可能落在 [各維度最低分加總, 各維度最高分加總]；這段區間沒有任何分數帶覆蓋的部分就是缺口，
 * 兩個分數帶區間相交就是重疊。維度層面：列舉／布林欄位逐值檢查是否有計分規則接住，
 * 數值欄位只看有沒有萬用（anything）規則。</p>
 */
@Component
@RequiredArgsConstructor
public class ScoreCardAnalyzer {

    private final ObjectMapper objectMapper;

    public AnalysisResult analyze(JsonNode envelopeJson) {
        RuleEnvelope envelope;
        try {
            envelope = objectMapper.treeToValue(envelopeJson, RuleEnvelope.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("ScoreCard envelope 解析失敗：" + e.getMessage(), e);
        }
        return analyze(envelope);
    }

    public AnalysisResult analyze(RuleEnvelope envelope) {
        RuleEnvelope.Rule rule = envelope.getRule();
        List<ScoringDimension> dims = rule == null || rule.getScoringDimensions() == null ? List.of() : rule.getScoringDimensions();
        List<ScoreBand> bands = rule == null || rule.getScoreBands() == null ? List.of() : rule.getScoreBands();
        Map<String, FieldDef> inputs = new LinkedHashMap<>();
        if (rule != null && rule.getInputs() != null) {
            for (FieldDef f : rule.getInputs()) inputs.put(f.getName(), f);
        }

        List<AnalysisResult.GapInfo> gaps = new ArrayList<>();
        List<AnalysisResult.OverlapInfo> overlaps = new ArrayList<>();

        // ── 總分可達範圍 ──
        double totalMin = 0, totalMax = 0;
        for (ScoringDimension dim : dims) {
            double w = dim.getWeight() == null ? 1.0 : dim.getWeight();
            double lo = 0, hi = 0;
            boolean catchAll = false;
            if (dim.getScoringRules() != null) {
                for (ScoringRule sr : dim.getScoringRules()) {
                    double s = (sr.getScore() == null ? 0 : sr.getScore()) * w;
                    lo = Math.min(lo, s);
                    hi = Math.max(hi, s);
                    if (isCatchAll(sr.getCondition())) catchAll = true;
                }
            }
            totalMin += lo;
            totalMax += hi;
            checkDimensionCoverage(dim, inputs.get(dim.getField()), catchAll, gaps);
        }

        // ── 分數帶缺口與重疊 ──
        List<ScoreBand> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparing(b -> b.getMinScore() == null ? Integer.MIN_VALUE : b.getMinScore()));
        double coveredLength = 0;
        double cursor = totalMin;
        for (ScoreBand b : sorted) {
            double bMin = b.getMinScore() == null ? Double.NEGATIVE_INFINITY : b.getMinScore();
            double bMax = b.getMaxScore() == null ? Double.POSITIVE_INFINITY : b.getMaxScore();
            if (bMin > cursor && bMin - cursor > 1e-9 && cursor <= totalMax) {
                double gapHi = Math.min(bMin, totalMax + 1e-9);
                if (gapHi > cursor) {
                    gaps.add(AnalysisResult.GapInfo.builder()
                            .conditions(Map.of("totalScore", range(cursor, nextDown(bMin))))
                            .message("總分落在 " + range(cursor, nextDown(bMin)) + " 時沒有對應的分數帶")
                            .volumeRatio(totalMax > totalMin ? (gapHi - cursor) / (totalMax - totalMin) : 0.0)
                            .build());
                }
            }
            double lo = Math.max(bMin, cursor), hi = Math.min(bMax, totalMax);
            if (hi >= lo) coveredLength += (hi - lo);
            cursor = Math.max(cursor, bMax == Double.POSITIVE_INFINITY ? totalMax + 1 : bMax + 1);
        }
        if (cursor <= totalMax) {
            gaps.add(AnalysisResult.GapInfo.builder()
                    .conditions(Map.of("totalScore", range(cursor, totalMax)))
                    .message("總分落在 " + range(cursor, totalMax) + " 時沒有對應的分數帶")
                    .volumeRatio(totalMax > totalMin ? (totalMax - cursor + 1) / (totalMax - totalMin) : 0.0)
                    .build());
        }
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                ScoreBand a = sorted.get(i), b = sorted.get(j);
                double aMin = nz(a.getMinScore(), Double.NEGATIVE_INFINITY), aMax = nz(a.getMaxScore(), Double.POSITIVE_INFINITY);
                double bMin = nz(b.getMinScore(), Double.NEGATIVE_INFINITY), bMax = nz(b.getMaxScore(), Double.POSITIVE_INFINITY);
                double lo = Math.max(aMin, bMin), hi = Math.min(aMax, bMax);
                if (hi >= lo) {
                    overlaps.add(AnalysisResult.OverlapInfo.builder()
                            .ruleIds(List.of(a.getBandId(), b.getBandId()))
                            .intersection(Map.of("totalScore", range(lo, hi)))
                            .message("分數帶 " + a.getBandId() + " 與 " + b.getBandId() + " 在總分 " + range(lo, hi) + " 重疊")
                            .build());
                }
            }
        }

        double span = totalMax - totalMin;
        double coverage = span <= 0 ? (bands.isEmpty() ? 0.0 : 1.0) : Math.min(1.0, coveredLength / span);
        String summary = String.format("總分可達 %s～%s；%d 個分數帶，覆蓋 %.0f%%；缺口 %d、重疊 %d",
                fmt(totalMin), fmt(totalMax), bands.size(), coverage * 100, gaps.size(), overlaps.size());
        return AnalysisResult.builder()
                .coverageRate(coverage)
                .gaps(gaps)
                .overlaps(overlaps)
                .simplifications(List.of())
                .summary(summary)
                .totalRules(bands.size())
                .totalDimensions(dims.size())
                .build();
    }

    private static void checkDimensionCoverage(ScoringDimension dim, FieldDef field, boolean catchAll,
                                               List<AnalysisResult.GapInfo> gaps) {
        if (catchAll || field == null) return;
        String type = field.getTypeRef() == null ? "" : field.getTypeRef().toUpperCase();
        Set<String> covered = new LinkedHashSet<>();
        if (dim.getScoringRules() != null) {
            for (ScoringRule sr : dim.getScoringRules()) {
                Condition c = sr.getCondition();
                if (c == null || c.getValue() == null) continue;
                if ("equals".equals(c.getOperator())) covered.add(c.getValue().toString());
                else if ("in".equals(c.getOperator()) && c.getValue() instanceof List<?> l) l.forEach(v -> covered.add(String.valueOf(v)));
            }
        }
        List<String> expected = switch (type) {
            case "BOOLEAN" -> List.of("true", "false");
            case "ENUM" -> field.getAllowedValues() == null ? List.of() : field.getAllowedValues();
            default -> List.of();
        };
        List<String> missing = expected.stream().filter(v -> !covered.contains(v)).toList();
        if (!missing.isEmpty()) {
            gaps.add(AnalysisResult.GapInfo.builder()
                    .conditions(Map.of(dim.getField(), String.join("／", missing)))
                    .message("維度「" + dim.getField() + "」的值 " + String.join("／", missing) + " 沒有計分規則（會以 0 分計）")
                    .build());
        } else if (expected.isEmpty()) {
            gaps.add(AnalysisResult.GapInfo.builder()
                    .conditions(Map.of(dim.getField(), "其他值"))
                    .message("維度「" + dim.getField() + "」沒有萬用規則，未列出的值會以 0 分計")
                    .build());
        }
    }

    private static boolean isCatchAll(Condition c) {
        return c == null || c.getOperator() == null || "anything".equals(c.getOperator());
    }

    private static double nz(Integer v, double dflt) { return v == null ? dflt : v; }

    private static double nextDown(double v) { return v - 1; }

    private static String range(double lo, double hi) {
        return "[" + fmt(lo) + "," + fmt(hi) + "]";
    }

    private static String fmt(double d) {
        if (Double.isInfinite(d)) return d > 0 ? "∞" : "-∞";
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
