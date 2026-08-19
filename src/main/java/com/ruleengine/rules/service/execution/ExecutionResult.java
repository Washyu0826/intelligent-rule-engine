package com.ruleengine.rules.service.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 一次規則執行的結果（P2-S2）。
 *
 * <p>
 * <b>FIRST / tree：</b>{@code outputs} 是命中那條規則（或葉節點）的結果。
 * <b>MULTI：</b>語意上是「收集所有命中」（對應 DMN COLLECT）——
 * {@code matchedRules} 逐條保留各自的 outputs，頂層 {@code outputs} 取第一個命中
 * （方便只關心單值的呼叫端），完整資訊在 {@code matchedRules}。
 * </p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecutionResult {

    boolean matched;

    /** 命中結果（見類別 javadoc 的 FIRST/MULTI 語意）。無命中時為空 map。 */
    Map<String, Object> outputs;

    /** 所有命中的規則（FIRST 至多一筆；MULTI 可多筆；tree 為葉節點）。 */
    List<MatchedRule> matchedRules;

    /** {@code TraceLevel.NONE} 時為 null。 */
    DecisionTrace trace;

    @Value
    @Builder
    public static class MatchedRule {
        String ruleId;
        Integer priority;
        Map<String, Object> outputs;
    }
}
