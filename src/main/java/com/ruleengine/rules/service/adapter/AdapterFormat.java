package com.ruleengine.rules.service.adapter;

/**
 * M2 — SPI Skeleton: 下游引擎 payload 格式列舉。
 *
 * 為 {@link RuleEngineAdapter} 宣告其可處理的轉換格式。
 * M2 僅納入 JSON 與 XLSX 兩個值；其他格式（XML / YAML / DMN）為 mission §1.2
 * 明確排除的範圍。新增列舉值是 additive 變更：既有 adapter 透過
 * {@link RuleEngineAdapter#supportedFormats()} 宣告能力，不會被打破。
 */
public enum AdapterFormat {

    /**
     * 文字 payload，以 UTF-8 bytes 攜帶。
     * 由 M4 {@code GroupJsonExporter} 產生、{@code GroupJsonImporter} 消費。
     */
    JSON,

    /**
     * 二進位 payload（Office Open XML 試算表）。
     * 由 M5 {@code GroupXlsxExporter} 產生；XLSX import 不在本 mission 範圍內。
     */
    XLSX
}
