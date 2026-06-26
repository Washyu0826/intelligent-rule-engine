package com.ruleengine.rules.service.engine;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;

import java.util.Map;

/**
 * SPI for rule-engine evaluators. Implementations evaluate a {@link RuleEnvelope}
 * against an input record and return a typed {@link ExecutionResult}. The interface
 * deliberately avoids leaking Group-specific types so future Drools / FICO / ODM
 * engines can plug in without touching callers.
 *
 * <p>Demo-sprint addition (DEMO_PLAN_3H §4.3): the first implementation is
 * {@link MockGroupEngine}, which wraps the existing
 * {@link com.ruleengine.rules.service.RuleLookupService} and enriches its output
 * with hit-path / hit-node fields for the BA-facing UI.
 */
public interface RuleEngineRunner {

    /**
     * Identifier for this engine (e.g. {@code "mock-group"}, {@code "drools"}).
     * Returned verbatim in {@link ExecutionResult#getEngineName()} so callers
     * can attribute results.
     */
    String engineName();

    /**
     * Evaluate the given envelope against the supplied input values.
     *
     * @param envelope    the rule envelope to evaluate
     * @param inputValues field-name → value map; may be empty but should not be null
     * @return an ExecutionResult describing the (possibly empty) match
     */
    ExecutionResult evaluate(RuleEnvelope envelope, Map<String, Object> inputValues);
}
