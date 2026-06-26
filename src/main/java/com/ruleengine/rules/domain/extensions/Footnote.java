package com.ruleengine.rules.domain.extensions;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Footnote — 註腳（v3.14）。
 *
 * 用途：附加文字註解到單一 ruleId（規則註腳）或整張表（表尾註腳），
 * 對應核保規格慣用的「註1：...」格式。
 *
 * 設計重點：
 *   - appliesToRuleId 為 null/空 時，視為整張表的尾註。
 *   - altersApplicability=true 表示這條註腳會改變規則適用性，
 *     例如「在試算上傳中，保代通路不會進行檢核」會關閉某些 rule。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Footnote {

    /** 註腳編號標籤（例：「註1」、「†」、「*」）。 */
    private String marker;

    /** 註腳內文（必填，非空白）。 */
    private String text;

    /**
     * 註腳掛載對象：
     *   - 指定 ruleId 字串：該規則的腳註
     *   - null / 空字串：整張表的腳註（出現在表尾）
     */
    private String appliesToRuleId;

    /**
     * 註腳是否會改變規則套用性（true = applicability note，
     * 例「在試算上傳中，保代通路不會進行檢核」會關閉某些 rule）。
     * 預設 false（純解說）。
     */
    private Boolean altersApplicability;
}
