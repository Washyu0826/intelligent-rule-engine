package com.ruleengine.rules.domain.extensions;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RuleGrouping — 規則分組（v3.14）。
 *
 * 用途：為一串 ruleId 加上子分組標題，模擬列印版核保規格的
 * §2.1 / §2.2 / §2.3 排版習慣，方便 BA 在 dashboard 上閱讀。
 *
 * 設計重點：
 *   - groupId 使用 "RG" 前綴與 GlobalGuard.guardId（"G" 前綴）分開命名空間。
 *   - memberRuleIds 保留順序，下游 renderer 可依此印出「分組標題 → 規則列表」版面。
 *   - level 為可選欄位，預設視為 1（一級標題）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleGrouping {

    /** 分組 ID（RG01, RG02, ...）。 */
    private String groupId;

    /** 分組標題（例：「2.3 特約通路檢核」）。 */
    private String title;

    /** 分組說明（可選）。 */
    private String description;

    /**
     * 所屬規則 ID 清單；順序維持，使下游 renderer 可印出
     * 「分組標題 → 該分組內的 rule 列表」這種人類友善版面。
     */
    private List<String> memberRuleIds;

    /** 可選：分組層級（1 = 一級標題、2 = 子標題……），預設 1。 */
    private Integer level;
}
