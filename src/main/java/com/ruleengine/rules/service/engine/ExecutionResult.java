package com.ruleengine.rules.service.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Result of evaluating a RuleEnvelope through a {@link RuleEngineRunner}.
 *
 * <p>Demo-sprint shape (DEMO_PLAN_3H §3.3): exposes both the legacy single-hit
 * fields ({@code hitRuleId}, {@code hitNodeId}, {@code hitPath}, {@code results})
 * and a {@code allMatches} list for MULTI hit-policy tables so the dashboard can
 * highlight every matched row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecutionResult {

    /** Whether at least one rule / leaf matched. */
    private boolean matched;

    /**
     * For DecisionTable: the first matched row's ruleId.
     * For DecisionTree: the leaf nodeId.
     * {@code null} when {@link #matched} is false.
     */
    private String hitRuleId;

    /** Tree mode only — the leaf nodeId. {@code null} for tables. */
    private String hitNodeId;

    /**
     * Tree mode: the sequence of nodeIds from root to the matched leaf.
     * Table mode: {@code [hitRuleId]} for FIRST, {@code [ruleId1, ruleId2, ...]}
     * for MULTI. Empty list when nothing matched.
     */
    private List<String> hitPath;

    /**
     * First matched rule's outputs flattened to a {@code field → value} map.
     * Empty map when nothing matched.
     */
    private Map<String, Object> results;

    /** Human-readable evaluation trace — pass-through from the lookup service. */
    private List<String> evaluationPath;

    /**
     * All matched rules — populated for MULTI hit-policy tables.
     * {@code null} for FIRST tables and for tree mode.
     */
    private List<MatchedRule> allMatches;

    /** Identifier of the engine that produced this result (e.g. {@code "mock-group"}). */
    private String engineName;

    /** A single matched rule's summary — used inside {@link #allMatches}. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MatchedRule {
        private String ruleId;
        private Integer priority;
        private Map<String, Object> results;
    }
}
