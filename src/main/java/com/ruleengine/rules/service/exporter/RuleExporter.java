package com.ruleengine.rules.service.exporter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;

import java.util.List;
import java.util.Map;

/**
 * v3.12 — RuleExporter 介面
 *
 * 定位：把 engine-neutral RuleEnvelope 轉成「特定下游規則引擎」可吃的部署形式。
 * 本系統不直接執行規則；ExporterPort 是 Rules MCP Server 與集團規則引擎之間
 * 的 SPI 邊界。Demo 版以 stub 形式存在，正式接入由各引擎廠商或內部團隊實作。
 *
 * 一個 Exporter 對應一種目標引擎（例如：GroupInternalEngine、Drools、IBM ODM）。
 * 實作必須回報 adapter pre-flight warnings：哪些 envelope 欄位缺 metadata、
 * 哪些 operator 不被引擎原生支援、需要額外 mapping。
 *
 * 設計原則：
 * - Exporter 不該修改 RuleEnvelope（只讀），所有轉換結果放 ExportResult
 * - Warning 是 adapter 與業務之間的契約溝通，比 Exception 重要
 */
public interface RuleExporter {

    /**
     * 目標引擎識別碼（例：group-internal、drools-7、ibm-odm-8）。
     */
    String engineName();

    /**
     * 此 exporter 對應的引擎主要版本（例：0.1-stub、7.x、8.10）。
     */
    String engineVersion();

    /**
     * 將 RuleEnvelope 轉換為目標引擎部署輸入。
     *
     * @param envelope engine-neutral 中介模型
     * @return 轉換結果含 content / warnings / metadata
     */
    ExportResult export(RuleEnvelope envelope);

    // ================================================================
    // ExportResult — 標準化輸出格式
    // ================================================================

    /**
     * Exporter 產出。
     *
     * @param engineName  目標引擎名稱
     * @param format      內容格式（json / xml / drl / yaml）
     * @param content     轉換後內容（adapter 部署用）
     * @param warnings    pre-flight 警告（缺 metadata、operator 不支援、版本問題）
     * @param metadata    部署 metadata（effectiveDate、version、ruleCount 等）
     */
    record ExportResult(
            String engineName,
            String engineVersion,
            String format,
            String content,
            List<String> warnings,
            Map<String, Object> metadata
    ) {}
}
