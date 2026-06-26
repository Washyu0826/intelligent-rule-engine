package com.ruleengine.rules.service;

import com.ruleengine.rules.service.InputSuggestionService.MissingDimension;
import com.ruleengine.rules.service.InputSuggestionService.SuggestResponse;
import com.ruleengine.rules.service.SpecLintService.LintFinding;
import com.ruleengine.rules.service.SpecLintService.LintReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成前輸入偵測閘（pre-flight gate）。
 *
 * <p>在 {@link RuleService#generateFull} 呼叫 LLM「之前」執行，整合：</p>
 * <ul>
 *   <li><b>Layer A 完整性</b> —— 委派 {@link InputSuggestionService}（維度/領域/品質分數/缺失維度）</li>
 *   <li><b>Layer B 邏輯</b> —— 委派 {@link SpecLintService}（純符號矛盾/重疊/空值域偵測）</li>
 * </ul>
 *
 * <p>並依設定把「完整性不足」升級為阻擋條件（blocker）。三種模式：</p>
 * <ul>
 *   <li><b>off</b> —— 完全跳過（{@link #evaluate} 回 null），{@code generateFull} 行為與未導入前位元級相同</li>
 *   <li><b>warn</b>（預設）—— 計算並附加報告，但永不阻擋生成</li>
 *   <li><b>block</b> —— 出現 ERROR 級問題時短路、不呼叫 LLM（0 token）</li>
 * </ul>
 *
 * <p>全程純符號、0 token、不呼叫 LLM；不碰 {@link com.ruleengine.rules.service.llm.DescriptionDimensionParser}
 * 的 ThreadLocal（兩個 delegate 都用獨立 parse）。設計見 {@code design/input-preflight-gate.md}。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreflightService {

    private final InputSuggestionService suggestionService;
    private final SpecLintService specLintService;

    @Value("${rules.preflight.enabled:true}")
    private boolean enabled;

    /** off | warn | block（預設 warn）。 */
    @Value("${rules.preflight.mode:warn}")
    private String defaultMode;

    /** 品質分數低於此值列為 blocker。 */
    @Value("${rules.preflight.min-quality:0.4}")
    private double minQuality;

    /** 是否把「無任何輸出欄位」列為 blocker。 */
    @Value("${rules.preflight.require-outputs:true}")
    private boolean requireOutputs;

    /** 描述短於此長度列為 blocker。 */
    @Value("${rules.preflight.min-length:15}")
    private int minLength;

    // ===== Layer A blocker lint codes =====
    public static final String LOW_QUALITY = "LOW_QUALITY";
    public static final String NO_OUTPUTS = "NO_OUTPUTS";
    public static final String TOO_SHORT = "TOO_SHORT";

    /**
     * Pre-flight 報告。
     *
     * @param blocked            是否阻擋生成（僅 block 模式 + 有 ERROR 時為 true）
     * @param hasError           是否存在 ERROR 級 finding（與模式無關）
     * @param qualityScore       Layer A 完整性分數 0.0–1.0
     * @param findings           Layer A blocker + Layer B lint 的合併清單
     * @param missing            建議補充的維度（Layer A）
     * @param suggestions        中文建議文字（Layer A）
     * @param estimatedRuleCount 估算規則數
     */
    public record PreflightReport(
            boolean blocked,
            boolean hasError,
            double qualityScore,
            List<LintFinding> findings,
            List<MissingDimension> missing,
            List<String> suggestions,
            int estimatedRuleCount
    ) {}

    /**
     * 供 {@code generateFull} 呼叫：尊重 {@code enabled} 與 {@code mode}。
     * off 或 disabled 回 {@code null}（呼叫端不附加，回應與未導入前相同）。
     */
    public PreflightReport evaluate(String description, String modeOverride) {
        String mode = effectiveMode(modeOverride);
        if (!enabled || "off".equals(mode)) {
            return null;
        }
        return compute(description, "block".equals(mode));
    }

    /**
     * 供 {@code POST /tools/preflight} 呼叫：永遠計算、永不阻擋（informational），
     * 前端依 {@code hasError} / {@code findings} 顯示紅黃綠燈。
     */
    public PreflightReport report(String description) {
        return compute(description, false);
    }

    // ========================================================================

    private String effectiveMode(String override) {
        if (override != null && !override.isBlank()) {
            return override.trim().toLowerCase();
        }
        return defaultMode != null ? defaultMode.trim().toLowerCase() : "warn";
    }

    private PreflightReport compute(String description, boolean blockMode) {
        SuggestResponse s = suggestionService.analyze(description);
        LintReport lint = specLintService.lint(description);

        List<LintFinding> findings = new ArrayList<>(lint.findings());

        // Layer A blocker 1：完全沒有輸出欄位
        if (requireOutputs && s.detectedOutputs().isEmpty()) {
            // 去重：SpecLint 的 NO_OUTPUT_ON_HIT（WARNING）由此 ERROR 取代
            findings.removeIf(f -> SpecLintService.NO_OUTPUT_ON_HIT.equals(f.code()));
            findings.add(new LintFinding(NO_OUTPUTS, SpecLintService.ERROR,
                    "未偵測到任何輸出欄位，LLM 無從得知規則命中後要回傳什麼（請以「輸出：」標記列出結果欄位）",
                    null));
        }

        // Layer A blocker 2：完整度過低
        if (s.qualityScore() < minQuality) {
            findings.add(new LintFinding(LOW_QUALITY, SpecLintService.ERROR,
                    String.format("描述完整度僅 %.0f%%（低於 %.0f%% 門檻），建議補齊條件欄位、允許值與輸出",
                            s.qualityScore() * 100, minQuality * 100),
                    null));
        }

        // Layer A blocker 3：描述過短
        if (description != null && description.strip().length() < minLength) {
            findings.add(new LintFinding(TOO_SHORT, SpecLintService.ERROR,
                    String.format("描述僅 %d 字，過於精簡，難以生成有意義的規則", description.strip().length()),
                    null));
        }

        boolean hasError = findings.stream().anyMatch(f -> SpecLintService.ERROR.equals(f.severity()));
        boolean blocked = blockMode && hasError;

        if (blocked) {
            log.info("pre-flight BLOCK | quality={} | findings={} | errors={}",
                    s.qualityScore(), findings.size(),
                    findings.stream().filter(f -> SpecLintService.ERROR.equals(f.severity())).count());
        }

        return new PreflightReport(
                blocked, hasError, s.qualityScore(),
                findings, s.missingDimensions(), s.suggestions(), s.estimatedRuleCount());
    }
}
