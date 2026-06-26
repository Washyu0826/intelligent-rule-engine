package com.ruleengine.rules.domain;

import com.ruleengine.rules.domain.extensions.FieldOrSpec;
import com.ruleengine.rules.domain.extensions.Footnote;
import com.ruleengine.rules.domain.extensions.GlobalGuard;
import com.ruleengine.rules.domain.extensions.RuleGrouping;
import com.ruleengine.rules.domain.extensions.RuleStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RuleEnvelope 的可選擴充區塊（v3.14）。
 *
 * 設計原則：
 *   - 完全可選：null / 缺欄位 / 空集合 都視為「沒有擴充」，行為與 v1.0.0 完全相同。
 *   - 加法式設計：所有子型別都帶 @JsonInclude(NON_NULL)；五個欄位獨立，缺一不影響其他。
 *   - 不重複定義 valueRef：FieldOrSpec / GlobalGuard 內部的條件仍重用 RuleEnvelope.Condition，
 *     跨欄位 / 相對日期參照透過 Condition.valueRef，不再另立詞彙。
 *
 * Jackson 透過 @JsonInclude(NON_NULL) 確保此區塊 null 時不出現在序列化輸出；
 * 與既有 RuleEnvelope.@JsonInclude(NON_NULL) 一致，不破壞向後相容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleEnvelopeExtensions {

    /** 全表級守門條件（AND 串接於所有 rules 之前）。 */
    private List<GlobalGuard> globalGuards;

    /** 欄位級 OR 群組宣告。 */
    private List<FieldOrSpec> fieldOr;

    /** 規則分組（子分組標題 + 成員 ruleId）。 */
    private List<RuleGrouping> groupings;

    /** 註腳清單。 */
    private List<Footnote> footnotes;

    /** 各規則的狀態（ACTIVE / DRAFT / RETIRED）；list 形式，內含 ruleId。 */
    private List<RuleStatus> ruleStatus;
}
