package com.ruleengine.rules.domain.extensions;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GlobalGuard — 全表級守門條件（v3.14）。
 *
 * 用途：表達「整張 RuleEnvelope 僅在滿足某條件時才適用」的守門邏輯。
 * 範例：受理通路 ∈ {行動保險, 網路投保, 直效線上成交} 才執行此檢核表。
 *
 * 設計重點：
 *   - condition 重用 RuleEnvelope.Condition，因此直接支援既有 12 個 operator
 *     （含 in / equals / valueRef / 相對日期），不再另立守門詞彙。
 *   - onFailure 重用 RuleEnvelope.Result，與其他結果結構一致。
 *   - 所有欄位都帶 @JsonInclude(NON_NULL)，缺值不污染 JSON 輸出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GlobalGuard {

    /** 守門 ID（G01, G02, ...），便於 footnote / status 交叉引用。 */
    private String guardId;

    /** 人類可讀說明（例：「僅適用受理通路 ∈ {行動保險, 網路投保, 直效線上成交}」）。 */
    private String description;

    /**
     * 守門條件本體 — 重用 RuleEnvelope.Condition，因此可直接用既有 operator
     * （含 in / equals / valueRef / 相對日期）。Adapter / engine 不需新詞彙。
     */
    private RuleEnvelope.Condition condition;

    /**
     * 失敗時短路結果（可選，預設「整個 RuleEnvelope 不適用」）。
     * 若提供，executor 在守門失敗時回傳該結果，不再 dispatch rules[]。
     */
    private List<RuleEnvelope.Result> onFailure;
}
