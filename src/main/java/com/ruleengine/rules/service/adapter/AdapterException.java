package com.ruleengine.rules.service.adapter;

/**
 * M2 — SPI Skeleton: {@link RuleEngineAdapter} 轉換失敗時拋出的例外。
 *
 * <p>
 * 採 unchecked（{@link RuntimeException} subclass），與 codebase 既有風格一致
 * （{@code RuleGenerationException}、{@code RuleValidationException}、
 * {@code EnvelopeNormalizationException} 均為 unchecked，於 {@code RuleService}
 * 由 {@code @RestControllerAdvice} 邊界統一捕捉）。
 * </p>
 *
 * <p>
 * 不重用 {@code RuleGenerationException}：後者語意屬 LLM 生成階段，與 adapter
 * 轉換階段分屬不同錯誤類別，混用會稀釋 {@code execution} 區塊的錯誤回報訊號。
 * </p>
 *
 * <p>
 * <b>M2 stub 注意：</b>{@link RuleEngineAdapter#export} / {@link RuleEngineAdapter#importFrom}
 * 在 stub 階段拋出 {@link UnsupportedOperationException}（語意：「功能尚未實作」），
 * 而非本例外（語意：「此 engine 不支援此格式」/「轉換期失敗」）。M4/M5 實作後再切換。
 * </p>
 */
public class AdapterException extends RuntimeException {

    private final String engineName;
    private final AdapterFormat format;

    public AdapterException(String engineName, AdapterFormat format, String message) {
        super(message);
        this.engineName = engineName;
        this.format = format;
    }

    public AdapterException(String engineName, AdapterFormat format, String message, Throwable cause) {
        super(message, cause);
        this.engineName = engineName;
        this.format = format;
    }

    public String getEngineName() {
        return engineName;
    }

    public AdapterFormat getFormat() {
        return format;
    }
}
