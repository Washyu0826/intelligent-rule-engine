package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RuleLookupService — takes a RuleEnvelope and a set of input values,
 * then finds which rule(s) match.
 *
 * Supports DecisionTable (FIRST / MULTI hit policies) and DecisionTree traversal.
 */
@Service
@Slf4j
public class RuleLookupService {

    // ========================================
    // Response records
    // ========================================

    public record LookupResponse(
            boolean matched,
            List<MatchedRule> matchedRules,
            List<String> evaluationPath,
            List<String> unmatchedInputs
    ) {}

    public record MatchedRule(
            String ruleId,
            Integer priority,
            Map<String, Object> results
    ) {}

    // ========================================
    // Main lookup entry point
    // ========================================

    /**
     * Evaluate the given input values against the rules defined in the envelope.
     *
     * @param envelope    the rule envelope containing the rule definition
     * @param inputValues a map of field-name to actual value
     * @return LookupResponse with matched rules, evaluation path, and unmatched inputs
     */
    public LookupResponse lookup(RuleEnvelope envelope, Map<String, Object> inputValues) {
        if (envelope == null || envelope.getRule() == null) {
            log.warn("lookup called with null envelope or null rule");
            return new LookupResponse(false, List.of(), List.of(), List.of());
        }

        Map<String, Object> safeInputs = inputValues != null ? inputValues : Map.of();
        String ruleType = envelope.getRuleType();

        if ("DecisionTree".equalsIgnoreCase(ruleType)) {
            return lookupDecisionTree(envelope.getRule(), safeInputs);
        }
        // Default to DecisionTable
        return lookupDecisionTable(envelope.getRule(), safeInputs);
    }

    // ========================================
    // DecisionTable lookup
    // ========================================

    private LookupResponse lookupDecisionTable(Rule rule, Map<String, Object> inputValues) {
        List<RuleRow> rows = rule.getRules();
        if (rows == null || rows.isEmpty()) {
            log.debug("DecisionTable has no rules");
            return new LookupResponse(false, List.of(), List.of(), findUnmatchedInputs(rule, inputValues));
        }

        String hitPolicy = rule.getHitPolicy() != null ? rule.getHitPolicy().toUpperCase() : "FIRST";
        List<MatchedRule> matched = new ArrayList<>();
        List<String> evaluationPath = new ArrayList<>();

        for (RuleRow row : rows) {
            boolean allMatch = evaluateAllConditions(row.getConditions(), inputValues);
            String ruleId = row.getRuleId() != null ? row.getRuleId() : "unknown";

            if (allMatch) {
                evaluationPath.add(ruleId + " → MATCHED");
                matched.add(toMatchedRule(row));

                if ("FIRST".equals(hitPolicy)) {
                    break;
                }
            } else {
                evaluationPath.add(ruleId + " → SKIPPED");
            }
        }

        List<String> unmatched = matched.isEmpty() ? findUnmatchedInputs(rule, inputValues) : List.of();
        return new LookupResponse(!matched.isEmpty(), matched, evaluationPath, unmatched);
    }

    // ========================================
    // DecisionTree lookup
    // ========================================

    private LookupResponse lookupDecisionTree(Rule rule, Map<String, Object> inputValues) {
        TreeNode root = rule.getRoot();
        if (root == null) {
            log.debug("DecisionTree has no root node");
            return new LookupResponse(false, List.of(), List.of(), findUnmatchedInputs(rule, inputValues));
        }

        List<String> evaluationPath = new ArrayList<>();
        TreeNode current = root;

        while (current != null) {
            // Leaf node — has results, no further branching
            if (current.getResults() != null && !current.getResults().isEmpty()) {
                Map<String, Object> resultMap = current.getResults().stream()
                        .collect(Collectors.toMap(
                                Result::getField,
                                Result::getValue,
                                (a, b) -> b,
                                LinkedHashMap::new
                        ));
                MatchedRule matched = new MatchedRule(current.getNodeId(), null, resultMap);
                return new LookupResponse(true, List.of(matched), evaluationPath, List.of());
            }

            // N-ary branches (normalized format)
            if (current.getBranches() != null && !current.getBranches().isEmpty()) {
                TreeNode next = null;
                for (Branch branch : current.getBranches()) {
                    Condition branchCondition = branch.getCondition();
                    if (branchCondition == null) {
                        // A branch without a condition acts as a default/fallback
                        if (next == null) {
                            next = branch.getChild();
                        }
                        continue;
                    }
                    Object actualValue = inputValues.get(branchCondition.getField());
                    if (matchesCondition(branchCondition, actualValue, inputValues)) {
                        String field = branchCondition.getField() != null ? branchCondition.getField() : "?";
                        String nodeId = current.getNodeId() != null ? current.getNodeId() : "?";
                        evaluationPath.add(field + "=" + actualValue + " → " + nodeId);
                        next = branch.getChild();
                        break;
                    }
                }

                if (next == null) {
                    // No branch matched
                    String nodeId = current.getNodeId() != null ? current.getNodeId() : "?";
                    evaluationPath.add("NO_MATCH → " + nodeId);
                    return new LookupResponse(false, List.of(), evaluationPath,
                            findUnmatchedInputs(rule, inputValues));
                }
                current = next;
                continue;
            }

            // Legacy binary tree (trueBranch / falseBranch)
            if (current.getCondition() != null) {
                Condition cond = current.getCondition();
                Object actualValue = inputValues.get(cond.getField());
                boolean result = matchesCondition(cond, actualValue, inputValues);
                String field = cond.getField() != null ? cond.getField() : "?";
                String nodeId = current.getNodeId() != null ? current.getNodeId() : "?";
                evaluationPath.add(field + "=" + actualValue + " → " + nodeId);

                current = result ? current.getTrueBranch() : current.getFalseBranch();
                continue;
            }

            // Node has no results, no branches, no condition — dead end
            log.warn("DecisionTree node {} has no branches, no condition, and no results", current.getNodeId());
            break;
        }

        return new LookupResponse(false, List.of(), evaluationPath, findUnmatchedInputs(rule, inputValues));
    }

    // ========================================
    // Condition matching
    // ========================================

    /**
     * Core matching logic — evaluates a single Condition against the actual value from input.
     *
     * @param condition   the condition to evaluate
     * @param actualValue the actual value from inputValues (may be null)
     * @return true if the condition is satisfied
     */
    public boolean matchesCondition(Condition condition, Object actualValue) {
        return matchesCondition(condition, actualValue, null);
    }

    /**
     * Core matching logic with full input context — supports valueRef cross-field
     * comparison and relative date references (v3.12).
     *
     * @param condition    the condition to evaluate
     * @param actualValue  the actual value of {@code condition.field} from inputValues
     * @param inputContext the full input map; used to resolve {@code condition.valueRef}
     * @return true if the condition is satisfied
     */
    public boolean matchesCondition(Condition condition, Object actualValue, Map<String, Object> inputContext) {
        if (condition == null) {
            return true;
        }

        String operator = condition.getOperator();
        if (operator == null) {
            log.warn("Condition has null operator for field '{}', treating as non-match", condition.getField());
            return false;
        }

        // A missing input is not the same as an explicitly supplied null. Only
        // wildcard conditions can match when the caller did not provide the field.
        if (inputContext != null
                && condition.getField() != null
                && !inputContext.containsKey(condition.getField())
                && !Operators.ANYTHING.equals(operator)) {
            return false;
        }

        if (!hasResolvableValueRef(condition, inputContext)) {
            return false;
        }

        Object expectedValue = resolveExpected(condition, inputContext);

        try {
            return switch (operator) {
                case Operators.ANYTHING -> true;
                case Operators.IS_NULL -> actualValue == null;
                case Operators.IS_NOT_NULL -> actualValue != null;
                case Operators.EQUALS -> equalsCheck(expectedValue, actualValue);
                case Operators.NOT_EQUALS -> !equalsCheck(expectedValue, actualValue);
                case Operators.GREATER_THAN -> compareValues(actualValue, expectedValue) > 0;
                case Operators.GREATER_THAN_OR_EQUAL -> compareValues(actualValue, expectedValue) >= 0;
                case Operators.LESS_THAN -> compareValues(actualValue, expectedValue) < 0;
                case Operators.LESS_THAN_OR_EQUAL -> compareValues(actualValue, expectedValue) <= 0;
                case Operators.BETWEEN -> betweenCheck(actualValue, expectedValue);
                case Operators.IN -> inCheck(actualValue, expectedValue);
                case Operators.NOT_IN -> !inCheck(actualValue, expectedValue);
                default -> {
                    log.warn("Unknown operator '{}' for field '{}'", operator, condition.getField());
                    yield false;
                }
            };
        } catch (Exception e) {
            log.debug("Condition evaluation failed for field '{}': {}", condition.getField(), e.getMessage());
            return false;
        }
    }

    /**
     * Resolve the expected value for comparison, honoring {@code condition.valueRef}
     * before falling back to {@code condition.value}.
     */
    private Object resolveExpected(Condition condition, Map<String, Object> inputContext) {
        String ref = condition.getValueRef();
        if (ref == null || ref.isBlank()) {
            return condition.getValue();
        }
        return resolveValueRef(ref, inputContext);
    }

    private boolean hasResolvableValueRef(Condition condition, Map<String, Object> inputContext) {
        String ref = condition.getValueRef();
        if (ref == null || ref.isBlank()) return true;

        String trimmed = ref.trim();
        if ("$today".equalsIgnoreCase(trimmed)) return true;
        if (trimmed.regionMatches(true, 0, "$today", 0, 6)) {
            return RELATIVE_DATE.matcher(trimmed).matches();
        }
        return inputContext != null && inputContext.containsKey(trimmed);
    }

    private static final Pattern RELATIVE_DATE = Pattern.compile(
            "^\\$today\\s*([+-])\\s*(\\d+)\\s*([dmyDMY])$");

    /**
     * Resolve a valueRef token to a runtime value:
     * <ul>
     *   <li>{@code "$today"} → today's date as ISO yyyy-MM-dd</li>
     *   <li>{@code "$today+1d"}, {@code "$today-30d"}, {@code "$today+1m"},
     *       {@code "$today-1y"} → relative date</li>
     *   <li>any other token → look up in {@code inputContext}</li>
     * </ul>
     * Returns null when the input field is missing or the token is malformed
     * (caller treats null expected as non-match for ordering operators).
     */
    // P2-S2 起升為 public：RuleExecutionEngine 的 trace 需要「解析後的期望值」
    // （回放紀錄裡 $today 必須是當時的具體日期，不是符號）
    public Object resolveValueRef(String ref, Map<String, Object> inputContext) {
        if (ref == null) return null;
        String trimmed = ref.trim();
        if ("$today".equalsIgnoreCase(trimmed)) {
            return LocalDate.now().toString();
        }
        if (trimmed.regionMatches(true, 0, "$today", 0, 6)) {
            Matcher m = RELATIVE_DATE.matcher(trimmed);
            if (!m.matches()) {
                log.warn("Malformed relative-date valueRef '{}'", ref);
                return null;
            }
            int sign = "+".equals(m.group(1)) ? 1 : -1;
            int amount = Integer.parseInt(m.group(2)) * sign;
            char unit = Character.toLowerCase(m.group(3).charAt(0));
            LocalDate base = LocalDate.now();
            return switch (unit) {
                case 'd' -> base.plusDays(amount).toString();
                case 'm' -> base.plusMonths(amount).toString();
                case 'y' -> base.plusYears(amount).toString();
                default -> null;
            };
        }
        if (inputContext == null) return null;
        return inputContext.get(trimmed);
    }

    // ========================================
    // Comparison helpers
    // ========================================

    /**
     * Equality check with type coercion:
     * - null == null is true
     * - String "true"/"false" == Boolean
     * - Numeric cross-type comparison (Integer vs Double, etc.)
     * - Fallback to toString comparison
     */
    private boolean equalsCheck(Object expected, Object actual) {
        if (expected == null && actual == null) return true;
        if (expected == null || actual == null) return false;
        if (expected.equals(actual)) return true;

        // Boolean coercion: String <-> Boolean
        if (expected instanceof Boolean && actual instanceof String) {
            Boolean parsed = parseStrictBoolean((String) actual);
            return parsed != null && expected.equals(parsed);
        }
        if (actual instanceof Boolean && expected instanceof String) {
            Boolean parsed = parseStrictBoolean((String) expected);
            return parsed != null && actual.equals(parsed);
        }

        // Numeric coercion
        if (isNumeric(expected) && isNumeric(actual)) {
            return toDouble(expected) == toDouble(actual);
        }

        // String coercion: compare numeric string to number
        if (isNumeric(expected) && actual instanceof String) {
            try {
                return toDouble(expected) == Double.parseDouble((String) actual);
            } catch (NumberFormatException ignored) {}
        }
        if (isNumeric(actual) && expected instanceof String) {
            try {
                return toDouble(actual) == Double.parseDouble((String) expected);
            } catch (NumberFormatException ignored) {}
        }

        // Fallback: toString comparison
        return expected.toString().equals(actual.toString());
    }

    /**
     * v3.12: Generalized comparison that handles numerics first, then ISO date
     * strings (yyyy-MM-dd, lex order ≡ chronological order), then falls back to
     * lexicographic Comparable. Supports cross-field & relative-date comparisons
     * via {@link #resolveValueRef(String, Map)}.
     */
    private int compareValues(Object actual, Object expected) {
        if (actual == null || expected == null) {
            throw new IllegalArgumentException("null comparison");
        }
        if (actual instanceof Number && expected instanceof Number) {
            return Double.compare(((Number) actual).doubleValue(), ((Number) expected).doubleValue());
        }
        // Try numeric on strings (existing behavior for legacy rules)
        try {
            double a = toDouble(actual);
            double e = toDouble(expected);
            return Double.compare(a, e);
        } catch (Exception ignored) {
            // Fall through to date / lexical
        }
        // ISO date strings: yyyy-MM-dd lex order matches chronological order;
        // validate parseable to avoid silent acceptance of garbage.
        if (actual instanceof String aS && expected instanceof String eS) {
            if (looksLikeIsoDate(aS) && looksLikeIsoDate(eS)) {
                try {
                    LocalDate.parse(aS);
                    LocalDate.parse(eS);
                    return aS.compareTo(eS);
                } catch (DateTimeParseException ignored) {
                    // fall through
                }
            }
            return aS.compareTo(eS);
        }
        return actual.toString().compareTo(expected.toString());
    }

    private boolean looksLikeIsoDate(String s) {
        return s != null && s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-';
    }

    /**
     * Between check: expectedValue must be a List of 2 elements [min, max].
     * Checks min <= actual <= max (inclusive).
     */
    private boolean betweenCheck(Object actual, Object expected) {
        if (actual == null) return false;
        if (!(expected instanceof List<?> range) || range.size() != 2) {
            log.warn("'between' operator expects a list of 2 elements, got: {}", expected);
            return false;
        }
        return compareValues(actual, range.get(0)) >= 0
                && compareValues(actual, range.get(1)) <= 0;
    }

    /**
     * In check: expectedValue must be a List. Checks if actualValue is in the list,
     * using equalsCheck for each element to handle type coercion.
     */
    private boolean inCheck(Object actual, Object expected) {
        if (!(expected instanceof List<?> list)) {
            log.warn("'in' operator expects a list, got: {}", expected);
            return false;
        }
        for (Object item : list) {
            if (equalsCheck(item, actual)) {
                return true;
            }
        }
        return false;
    }

    // ========================================
    // Utility helpers
    // ========================================

    private boolean isNumeric(Object obj) {
        return obj instanceof Number;
    }

    private Boolean parseStrictBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return null;
    }

    private double toDouble(Object obj) {
        if (obj instanceof Number n) {
            return n.doubleValue();
        }
        if (obj instanceof String s) {
            return Double.parseDouble(s);
        }
        throw new IllegalArgumentException("Cannot convert to double: " + obj);
    }

    private boolean evaluateAllConditions(List<Condition> conditions, Map<String, Object> inputValues) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Condition condition : conditions) {
            String field = condition.getField();
            Object actualValue = field != null ? inputValues.get(field) : null;
            if (!matchesCondition(condition, actualValue, inputValues)) {
                return false;
            }
        }
        return true;
    }

    private MatchedRule toMatchedRule(RuleRow row) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        if (row.getResults() != null) {
            for (Result r : row.getResults()) {
                resultMap.put(r.getField(), r.getValue());
            }
        }
        return new MatchedRule(row.getRuleId(), row.getPriority(), resultMap);
    }

    /**
     * Identify input fields that were expected by the rule definition but missing from inputValues.
     */
    private List<String> findUnmatchedInputs(Rule rule, Map<String, Object> inputValues) {
        if (rule.getInputs() == null) return List.of();
        return rule.getInputs().stream()
                .map(FieldDef::getName)
                .filter(name -> name != null && !inputValues.containsKey(name))
                .toList();
    }
}
