package com.ruleengine.rules.service;

import com.ruleengine.rules.service.PreflightService.PreflightReport;
import com.ruleengine.rules.service.generator.ConditionOverlapDetector;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PreflightService 單元測試。
 *
 * 用 {@link ReflectionTestUtils} 注入 {@code @Value} 設定欄位，
 * 串接真實 {@link InputSuggestionService} + {@link SpecLintService}。
 */
@DisplayName("PreflightService - 生成前輸入偵測閘")
class PreflightServiceTest {

    private PreflightService preflight;

    /** 一段完整且自洽的描述 —— 不應有任何 ERROR。 */
    private static final String GOOD = "年齡（18-30／31-50／51-99）。輸出：核保決議（核准／婉拒／加費）";
    /** 含矛盾區間 —— Layer B 邏輯 ERROR。 */
    private static final String CONTRADICTORY = "年齡（60-30）。輸出：核保決議（核准／婉拒）";
    /** 模糊描述 —— Layer A 完整性 ERROR（低品質 + 無輸出）。 */
    private static final String VAGUE = "幫我檢查一下這個資料對不對好嗎謝謝";

    @BeforeEach
    void setUp() {
        InputSuggestionService suggestion = new InputSuggestionService(new DescriptionDimensionParser());
        SpecLintService specLint = new SpecLintService(new DescriptionDimensionParser(), new ConditionOverlapDetector());
        preflight = new PreflightService(suggestion, specLint);
        ReflectionTestUtils.setField(preflight, "enabled", true);
        ReflectionTestUtils.setField(preflight, "defaultMode", "warn");
        ReflectionTestUtils.setField(preflight, "minQuality", 0.4);
        ReflectionTestUtils.setField(preflight, "requireOutputs", true);
        ReflectionTestUtils.setField(preflight, "minLength", 15);
    }

    private void setMode(String m) { ReflectionTestUtils.setField(preflight, "defaultMode", m); }

    private static boolean has(PreflightReport r, String code) {
        return r.findings().stream().anyMatch(f -> code.equals(f.code()));
    }

    // ================================================================
    // mode = off
    // ================================================================

    @Test
    @DisplayName("mode=off → evaluate 回 null（行為不變）")
    void offReturnsNull() {
        setMode("off");
        assertNull(preflight.evaluate(VAGUE, null));
    }

    @Test
    @DisplayName("enabled=false → evaluate 回 null")
    void disabledReturnsNull() {
        ReflectionTestUtils.setField(preflight, "enabled", false);
        assertNull(preflight.evaluate(VAGUE, null));
    }

    // ================================================================
    // mode = warn
    // ================================================================

    @Test
    @DisplayName("mode=warn + 模糊描述 → 不阻擋但 hasError、附 findings")
    void warnNeverBlocks() {
        PreflightReport r = preflight.evaluate(VAGUE, null);
        assertNotNull(r);
        assertFalse(r.blocked(), "warn 模式永不阻擋");
        assertTrue(r.hasError(), "模糊描述應有 ERROR");
    }

    @Test
    @DisplayName("mode=warn + 完整描述 → 無 finding、不阻擋")
    void warnCleanInput() {
        PreflightReport r = preflight.evaluate(GOOD, null);
        assertNotNull(r);
        assertFalse(r.blocked());
        assertFalse(r.hasError(), "乾淨描述不應有 ERROR，實得：" + r.findings());
    }

    // ================================================================
    // mode = block
    // ================================================================

    @Test
    @DisplayName("mode=block + 模糊描述 → blocked（完整性不足）")
    void blockVague() {
        setMode("block");
        PreflightReport r = preflight.evaluate(VAGUE, null);
        assertTrue(r.blocked());
        assertTrue(has(r, PreflightService.LOW_QUALITY) || has(r, PreflightService.NO_OUTPUTS));
    }

    @Test
    @DisplayName("mode=block + 矛盾區間 → blocked（邏輯錯誤，非完整性）")
    void blockContradictory() {
        setMode("block");
        PreflightReport r = preflight.evaluate(CONTRADICTORY, null);
        assertTrue(r.blocked());
        assertTrue(has(r, SpecLintService.CONTRADICTORY_RANGE));
    }

    @Test
    @DisplayName("mode=block + 完整描述 → 不阻擋")
    void blockCleanInputPasses() {
        setMode("block");
        PreflightReport r = preflight.evaluate(GOOD, null);
        assertFalse(r.blocked());
        assertFalse(r.hasError());
    }

    // ================================================================
    // modeOverride
    // ================================================================

    @Test
    @DisplayName("modeOverride=block 覆寫預設 warn → 模糊描述被擋")
    void overrideToBlock() {
        // 預設 warn，但單次請求指定 block
        PreflightReport r = preflight.evaluate(VAGUE, "block");
        assertTrue(r.blocked());
    }

    @Test
    @DisplayName("modeOverride=off 覆寫預設 → 回 null")
    void overrideToOff() {
        PreflightReport r = preflight.evaluate(VAGUE, "off");
        assertNull(r);
    }

    // ================================================================
    // NO_OUTPUTS 取代 NO_OUTPUT_ON_HIT（去重）
    // ================================================================

    @Test
    @DisplayName("有輸入無輸出 → NO_OUTPUTS(ERROR) 取代 SpecLint 的 NO_OUTPUT_ON_HIT(WARNING)")
    void noOutputsDedup() {
        PreflightReport r = preflight.report("年齡（18-30／31-50）");
        assertTrue(has(r, PreflightService.NO_OUTPUTS), "應升級為 ERROR 版 NO_OUTPUTS");
        assertFalse(has(r, SpecLintService.NO_OUTPUT_ON_HIT), "WARNING 版應被去重移除");
    }

    // ================================================================
    // report() —— 永遠計算、永不阻擋
    // ================================================================

    @Test
    @DisplayName("report() 永不阻擋但回報 hasError")
    void reportInformationalOnly() {
        PreflightReport r = preflight.report(CONTRADICTORY);
        assertFalse(r.blocked(), "report() 永遠 blocked=false");
        assertTrue(r.hasError());
    }
}
