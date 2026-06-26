package com.ruleengine.rules.domain.glossary;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 領域字彙表 entry — v3.14 Phase A 核心資料結構。
 *
 * 設計依據：docs/v3.14-schema-evolution.md §4。
 * 每個業務名詞在系統內有單一定義來源，跨 5 層分層引用同一 entry。
 *
 * 同義詞處理：synonyms 列出別名（如「保額」/「保險金額」），
 * 未來模板辨識引擎（v3.14 Phase B）將以此作為 alias 對映依據。
 *
 * 歷史變遷：deprecated entry 透過 successor 指向新名詞（如「殘廢」→「失能」2018-06-15）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GlossaryEntry {

    /** 唯一鍵（kebab-case）。 */
    private String id;

    /** 中文（繁）。 */
    private String zh_TW;

    /** 中文（簡）— 可選，用於繁簡對應。 */
    private String zh_CN;

    /** 英文。 */
    private String en;

    /** 同義詞 / 別名（中文）。 */
    private List<String> synonyms;

    /** 一句話業務定義（給 BA 看）。 */
    private String definition;

    /** 1-2 個情境句範例。 */
    private List<String> examples;

    /** 法源 / 公會 / 公司內規出處。 */
    private String source;

    /** 業務分類：核保 / 商品 / 理賠 / 通路 / 繳費 / 合規 / 精算。 */
    private String category;

    /** 法規層級：law / guild / fsc / company。 */
    private String regulatoryLayer;

    /** 對接核心系統的欄位代碼（可選）。 */
    private String fieldCode;

    /** 資料型別：Money / Enum / Date / Boolean / String / Integer / Decimal。 */
    private String dataType;

    /** 單位：TWD / 年 / % / 日 等。 */
    private String unit;

    /** 值域："[min, max]" 字串。 */
    private String valueRange;

    /** 狀態：active / deprecated / proposed。 */
    private String status;

    /** 生效起日 (yyyy-MM-dd)。 */
    private String effectiveFrom;

    /** 棄用日 (yyyy-MM-dd)，僅 deprecated 時填。 */
    private String deprecatedAt;

    /** 後繼名詞 id（deprecated 時必填）。 */
    private String successor;

    /** 關聯名詞 id 列表。 */
    private List<String> relatedTerms;

    /** 業務負責人 email。 */
    private String businessOwner;

    /** 技術負責人 email。 */
    private String techOwner;

    /** 此 entry 版本。 */
    private String version;
}
