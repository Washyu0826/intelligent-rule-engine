package com.ruleengine.rules.service.engine;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.RuleLookupService;
import com.ruleengine.rules.service.RuleLookupService.LookupResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock implementation of {@link RuleEngineRunner} that delegates the actual
 * matching to the existing {@link RuleLookupService} and adds path-formatting /
 * hit-node identification on top.
 *
 * <p>This class does <strong>not</strong> reimplement matching logic — every
 * condition evaluation, FIRST/MULTI hit-policy choice, and tree traversal is
 * performed by {@code RuleLookupService}. The work performed here is purely
 * presentational: shaping {@link LookupResponse} into the
 * {@link ExecutionResult} contract documented in DEMO_PLAN_3H §3.3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MockGroupEngine implements RuleEngineRunner {

    private static final String ENGINE_NAME = "mock-group";

    /** Matches an Nxx nodeId anywhere inside a tree-mode evaluationPath entry. */
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("N\\d+");

    private final RuleLookupService lookupService;

    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    @Override
    public ExecutionResult evaluate(RuleEnvelope envelope, Map<String, Object> inputValues) {
        // 1. Delegate the heavy lifting to RuleLookupService.
        LookupResponse lookup = lookupService.lookup(envelope, inputValues);

        boolean isTree = envelope != null
                && "DecisionTree".equalsIgnoreCase(envelope.getRuleType());

        List<String> evaluationPath = lookup.evaluationPath() != null
                ? lookup.evaluationPath()
                : List.of();
        List<RuleLookupService.MatchedRule> matchedRules = lookup.matchedRules() != null
                ? lookup.matchedRules()
                : List.of();

        // 2. No match — return an empty (but non-null) shell.
        if (!lookup.matched() || matchedRules.isEmpty()) {
            return ExecutionResult.builder()
                    .matched(false)
                    .hitRuleId(null)
                    .hitNodeId(null)
                    .hitPath(List.of())
                    .results(Map.of())
                    .evaluationPath(evaluationPath)
                    .allMatches(null)
                    .engineName(ENGINE_NAME)
                    .build();
        }

        // 3. First matched rule is the canonical "hit" for back-compat callers.
        RuleLookupService.MatchedRule firstHit = matchedRules.get(0);
        String hitRuleId = firstHit.ruleId();
        Map<String, Object> firstResults = firstHit.results() != null
                ? firstHit.results()
                : Map.of();

        // 4. Path derivation differs by ruleType.
        List<String> hitPath;
        String hitNodeId;
        List<ExecutionResult.MatchedRule> allMatches;

        if (isTree) {
            // Tree mode: walk the evaluationPath, extract the nodeId sequence,
            // and append the leaf nodeId (== first matched rule's id) as the
            // terminal step.
            hitPath = buildTreeHitPath(evaluationPath, hitRuleId);
            hitNodeId = hitRuleId;
            allMatches = null; // tree mode always returns a single leaf
        } else {
            // Table mode: hitPath is the list of matched ruleIds. For FIRST
            // this is a single-element list; for MULTI it carries every match.
            hitPath = matchedRules.stream()
                    .map(RuleLookupService.MatchedRule::ruleId)
                    .toList();
            hitNodeId = null;

            boolean isMulti = envelope != null
                    && envelope.getRule() != null
                    && "MULTI".equalsIgnoreCase(envelope.getRule().getHitPolicy());

            if (isMulti && matchedRules.size() > 1) {
                allMatches = matchedRules.stream()
                        .map(this::toExecutionMatchedRule)
                        .toList();
            } else {
                allMatches = null;
            }
        }

        return ExecutionResult.builder()
                .matched(true)
                .hitRuleId(hitRuleId)
                .hitNodeId(hitNodeId)
                .hitPath(hitPath)
                .results(firstResults)
                .evaluationPath(evaluationPath)
                .allMatches(allMatches)
                .engineName(ENGINE_NAME)
                .build();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Parse the lookup service's tree-mode evaluationPath into the ordered
     * sequence of nodeIds (root → ... → leaf), de-duplicating any consecutive
     * repeat and guaranteeing the leaf nodeId appears as the last element.
     *
     * <p>RuleLookupService formats tree entries as either
     * {@code "field=value → N01"} or {@code "NO_MATCH → N01"} (see
     * {@code RuleLookupService.lookupDecisionTree}). We extract every
     * {@code N\d+} token; insertion-ordered LinkedHashSet preserves the
     * walk order while collapsing duplicates.
     */
    private List<String> buildTreeHitPath(List<String> evaluationPath, String leafNodeId) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (evaluationPath != null) {
            for (String step : evaluationPath) {
                if (step == null) continue;
                Matcher m = NODE_ID_PATTERN.matcher(step);
                while (m.find()) {
                    ordered.add(m.group());
                }
            }
        }
        if (leafNodeId != null && !leafNodeId.isBlank()) {
            ordered.add(leafNodeId);
        }
        return new ArrayList<>(ordered);
    }

    private ExecutionResult.MatchedRule toExecutionMatchedRule(RuleLookupService.MatchedRule src) {
        return ExecutionResult.MatchedRule.builder()
                .ruleId(src.ruleId())
                .priority(src.priority())
                .results(src.results() != null ? src.results() : Collections.emptyMap())
                .build();
    }
}
