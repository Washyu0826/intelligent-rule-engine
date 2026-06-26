package com.ruleengine.rules.service.adapter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;

import java.util.Set;

/**
 * M2 — SPI Skeleton: 下游規則引擎雙向轉換介面。
 *
 * <p>
 * 與既有 {@code com.ruleengine.rules.service.exporter.RuleExporter} <b>並存但不取代</b>：
 * 兩者於三個維度上互補（詳見 {@code design/M2_SPI_DESIGN.md} §2）。
 * </p>
 *
 * <ul>
 *   <li><b>方向：</b>本介面為 bidirectional（{@code RuleEnvelope ↔ payload}）；
 *       {@code RuleExporter} 僅為 forward-only。</li>
 *   <li><b>內容：</b>本介面以 {@code byte[]} 攜帶 payload，可同時容納 JSON（UTF-8 bytes）
 *       與 XLSX（binary）。</li>
 *   <li><b>格式：</b>單一實作可宣告多個 {@link AdapterFormat}；{@code RuleExporter}
 *       一個實作對應單一格式。</li>
 * </ul>
 *
 * <p>
 * 介面簽章僅依賴 {@code java.lang.*} / {@code java.util.*} / 既有
 * {@link RuleEnvelope}，<b>不洩漏</b> 任何下游引擎（Group / Drools / FICO / IBM ODM）
 * 的具體型別，以符合 {@code MISSION.md} §4.M6 接受條件。
 * </p>
 *
 * <p>
 * 由 {@link AdapterRegistry} 透過 Spring {@code List<RuleEngineAdapter>} 自動蒐集
 * 並以 {@link #engineName()} 為索引。
 * </p>
 */
public interface RuleEngineAdapter {

    /**
     * 下游引擎的短識別字串（例：{@code "group"}、{@code "drools"}、{@code "fico"}、
     * {@code "ibm-odm"}）。必須與任何 {@code RuleExporter} bean 已發佈的值不同名，
     * 並做為 {@link AdapterRegistry} 的查詢鍵。
     */
    String engineName();

    /**
     * Adapter 對下游 payload schema 的相容版本（例：M2 為 {@code "0.1-stub"}，
     * M4/M5 落地後可升為 {@code "1.0"}）。會被 {@link AdapterRegistry} 於啟動時記錄。
     */
    String engineVersion();

    /**
     * 此 adapter 可處理的格式集合（同時涵蓋 {@link #export} 與 {@link #importFrom}
     * 兩個方向）。呼叫端可據此做能力查詢（例：dashboard 僅在 {@link AdapterFormat#XLSX}
     * 出現時才顯示「匯出 xlsx」按鈕）。
     */
    Set<AdapterFormat> supportedFormats();

    /**
     * 將 engine-neutral envelope 轉成下游引擎指定格式的 payload。
     *
     * @param envelope 來源 envelope
     * @param format   目標格式
     * @return 含 payload、warnings、metadata 的轉換結果
     * @throws AdapterException 當 {@code format ∉ supportedFormats()} 或轉換失敗時
     *                          （M2 stub 階段改拋 {@link UnsupportedOperationException}，
     *                          訊息標註 M4/M5 接手）
     */
    AdapterExportResult export(RuleEnvelope envelope, AdapterFormat format);

    /**
     * 將下游引擎 payload 解析回 {@link RuleEnvelope}。
     *
     * <p>
     * 參數採 {@code byte[]}：XLSX 為二進位、JSON 為 UTF-8 bytes
     * （呼叫端以 {@code String.getBytes(StandardCharsets.UTF_8)} 包裝文字）。
     * </p>
     *
     * @param payload 下游 payload
     * @param format  payload 的格式
     * @return 解析後 envelope
     * @throws AdapterException 當 {@code format} 不被支援或解析失敗時
     *                          （M2 stub 階段改拋 {@link UnsupportedOperationException}）
     */
    RuleEnvelope importFrom(byte[] payload, AdapterFormat format);
}
