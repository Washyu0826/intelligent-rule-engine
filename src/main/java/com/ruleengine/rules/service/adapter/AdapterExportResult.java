package com.ruleengine.rules.service.adapter;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * M2 — SPI Skeleton: {@link RuleEngineAdapter#export(com.ruleengine.rules.domain.envelope.RuleEnvelope, AdapterFormat)}
 * 的標準化回傳型別。
 *
 * <p>
 * 與 {@code RuleExporter.ExportResult} 並存但不通用：
 * </p>
 * <ul>
 *   <li>{@code content} 採 {@code byte[]} 而非 {@code String}：M5 XLSX 為二進位，
 *       String 無法安全攜帶；JSON 路徑統一以 UTF-8 bytes 表達，呼叫端視需要包成 String。</li>
 *   <li>{@code engineName} / {@code engineVersion} 內嵌入結果中：M7 整合進
 *       {@code GenerateResponse.execution} 區塊時，序列化端不需另外持有 adapter bean。</li>
 * </ul>
 *
 * @param engineName    來源 adapter 的 {@link RuleEngineAdapter#engineName()}
 * @param engineVersion 來源 adapter 的 {@link RuleEngineAdapter#engineVersion()}
 * @param format        實際產出的格式
 * @param content       payload 內容（JSON 為 UTF-8 bytes、XLSX 為二進位）
 * @param warnings      adapter pre-flight 警告（缺 metadata、operator 不被支援等）
 * @param metadata      自由格式 metadata（ruleCount、layoutAlgorithm、sheetName 等）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdapterExportResult(
        String engineName,
        String engineVersion,
        AdapterFormat format,
        byte[] content,
        List<String> warnings,
        Map<String, Object> metadata
) {
}
