package com.ruleengine.rules.service;

import com.ruleengine.rules.service.generator.ConditionOverlapDetector;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.DimensionInfo;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.ParsedDimensions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 描述層邏輯預檢（pre-flight Layer B）。
 *
 * <p>在呼叫 LLM 生成「之前」，對 {@link DescriptionDimensionParser} 解析出的維度做
 * <strong>純符號</strong>的自洽性檢查，抓出矛盾區間、互斥/重複值、空值域、笛卡爾積爆炸等
 * 問題。目標：在花任何 token 前就攔下明顯有問題的描述。</p>
 *
 * <p>本服務不呼叫任何 LLM、不持有狀態、不碰 {@link DescriptionDimensionParser} 的
 * {@code ThreadLocal}（用獨立 {@code parse()} 呼叫），典型延遲 &lt; 5ms。設計見
 * {@code design/input-preflight-gate.md} §5.1。</p>
 *
 * <p>區間交集邏輯複用 {@link ConditionOverlapDetector#rangeIntersection(double[], double[])}，
 * 與 Validator / DmnAnalyzer 的衝突偵測語意一致。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecLintService {

    private final DescriptionDimensionParser parser;
    private final ConditionOverlapDetector overlapDetector;

    // ===== severity =====
    public static final String ERROR = "ERROR";
    public static final String WARNING = "WARNING";
    public static final String INFO = "INFO";

    // ===== lint codes =====
    /** 數值區間 min &gt; max，永遠不可能成立 —— 真正的邏輯錯誤。 */
    public static final String CONTRADICTORY_RANGE = "CONTRADICTORY_RANGE";
    /** 同一維度多個數值區間互相重疊，生成後極可能產生衝突規則。 */
    public static final String OVERLAPPING_RANGE = "OVERLAPPING_RANGE";
    /** 偵測為 ENUM 的維度卻沒列任何允許值，LLM 只能瞎猜值域。 */
    public static final String EMPTY_VALUE_DOMAIN = "EMPTY_VALUE_DOMAIN";
    /** 同一維度出現重複值。 */
    public static final String DUPLICATE_VALUE = "DUPLICATE_VALUE";
    /** 偵測到輸入維度但完全沒有輸出欄位，命中後不知道要回什麼。 */
    public static final String NO_OUTPUT_ON_HIT = "NO_OUTPUT_ON_HIT";
    /** 估算規則數超過上限，笛卡爾積可能爆炸。 */
    public static final String UNBOUNDED_CARTESIAN = "UNBOUNDED_CARTESIAN";

    /** 笛卡爾積規則數警示上限（之後可移到 application.yml）。 */
    private static final int MAX_CARTESIAN = 500;

    /** 匹配「a-b」/「a–b」/「a~b」數值區間（半形/全形破折號、波浪號）。 */
    private static final Pattern NUMERIC_RANGE = Pattern.compile(
            "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[-–~]\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    // ========================================================================

    public record LintFinding(String code, String severity, String message, String dimension) {}

    public record LintReport(List<LintFinding> findings, boolean hasError) {}

    /**
     * 對自然語言描述做純符號預檢。
     *
     * @param description 使用者的規則描述
     * @return 檢查發現清單；{@code hasError=true} 表示存在 severity=ERROR 的致命問題
     */
    public LintReport lint(String description) {
        List<LintFinding> findings = new ArrayList<>();
        if (description == null || description.isBlank()) {
            return new LintReport(findings, false);
        }

        // 獨立 parse —— 不碰 ThreadLocal（避免污染後續生成階段，見設計 §9 R2）
        ParsedDimensions dims = parser.parse(description);

        for (DimensionInfo dim : dims.inputs()) {
            checkRanges(dim, findings);
            checkEmptyEnumDomain(dim, findings);
            checkDuplicateValues(dim, findings);
        }

        // 有輸入但完全沒輸出
        if (!dims.inputs().isEmpty() && dims.outputs().isEmpty()) {
            findings.add(new LintFinding(NO_OUTPUT_ON_HIT, WARNING,
                    "偵測到輸入條件但沒有任何輸出欄位，命中規則後不知道要回傳什麼結果（建議補上決議／錯誤碼等輸出）",
                    null));
        }

        // 笛卡爾積爆炸
        if (dims.estimatedCartesian() > MAX_CARTESIAN) {
            findings.add(new LintFinding(UNBOUNDED_CARTESIAN, INFO,
                    String.format("估算規則數約 %d 條，超過建議上限 %d，請確認是否所有組合皆有意義或考慮拆分維度",
                            dims.estimatedCartesian(), MAX_CARTESIAN),
                    null));
        }

        boolean hasError = findings.stream().anyMatch(f -> ERROR.equals(f.severity()));
        log.info("spec-lint 完成 | findings={} | hasError={}", findings.size(), hasError);
        return new LintReport(findings, hasError);
    }

    // ========================================================================
    //  個別檢查
    // ========================================================================

    /**
     * 檢查單一維度的數值區間：min&gt;max（矛盾）以及多區間互相重疊。
     */
    private void checkRanges(DimensionInfo dim, List<LintFinding> findings) {
        List<double[]> ranges = new ArrayList<>();
        for (String v : dim.values()) {
            Matcher m = NUMERIC_RANGE.matcher(v);
            if (!m.matches()) continue;
            double lo = Double.parseDouble(m.group(1));
            double hi = Double.parseDouble(m.group(2));
            if (lo > hi) {
                findings.add(new LintFinding(CONTRADICTORY_RANGE, ERROR,
                        String.format("維度「%s」的區間「%s」起點大於終點，永遠不可能成立", dim.chineseName(), v),
                        dim.chineseName()));
            } else {
                ranges.add(new double[]{lo, hi});
            }
        }

        // 兩兩比對是否重疊（複用 ConditionOverlapDetector 的閉區間交集）
        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                if (overlapDetector.rangeIntersection(ranges.get(i), ranges.get(j)) != null) {
                    findings.add(new LintFinding(OVERLAPPING_RANGE, WARNING,
                            String.format("維度「%s」存在重疊的數值區間 [%s, %s] 與 [%s, %s]，生成後可能產生衝突規則",
                                    dim.chineseName(),
                                    fmt(ranges.get(i)[0]), fmt(ranges.get(i)[1]),
                                    fmt(ranges.get(j)[0]), fmt(ranges.get(j)[1])),
                            dim.chineseName()));
                }
            }
        }
    }

    /**
     * ENUM 維度卻沒列任何允許值。
     */
    private void checkEmptyEnumDomain(DimensionInfo dim, List<LintFinding> findings) {
        if ("ENUM".equals(dim.suggestedTypeRef()) && dim.values().isEmpty()) {
            findings.add(new LintFinding(EMPTY_VALUE_DOMAIN, WARNING,
                    String.format("維度「%s」看起來是選項型欄位，但描述中沒有列出允許值（例如：A／B／C）", dim.chineseName()),
                    dim.chineseName()));
        }
    }

    /**
     * 同一維度出現重複值。
     */
    private void checkDuplicateValues(DimensionInfo dim, List<LintFinding> findings) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> dupes = new LinkedHashSet<>();
        for (String v : dim.values()) {
            if (!seen.add(v.trim())) dupes.add(v.trim());
        }
        if (!dupes.isEmpty()) {
            findings.add(new LintFinding(DUPLICATE_VALUE, WARNING,
                    String.format("維度「%s」出現重複值：%s", dim.chineseName(), String.join("、", dupes)),
                    dim.chineseName()));
        }
    }

    /** 整數值不帶小數點顯示，其餘保留。 */
    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
