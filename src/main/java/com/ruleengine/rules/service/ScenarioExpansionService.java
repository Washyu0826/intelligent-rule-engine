package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioDomain;
import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioExpandRequest;
import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioExpandResponse;
import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioRow;
import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expands a compact RuleEnvelope into a deterministic scenario matrix.
 *
 * The formal rules stay untouched. This service only creates analysis/test
 * rows from declared input domains, then runs the existing lookup evaluator.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScenarioExpansionService {

    private static final int DEFAULT_MAX_SCENARIOS = 256;
    private static final int HARD_MAX_SCENARIOS = 1000;
    private static final String DEFAULT_NO_MATCH_LABEL = "PASS";

    private final RuleLookupService ruleLookupService;

    public ScenarioExpandResponse expand(ScenarioExpandRequest request) {
        RuleEnvelope envelope = request != null ? request.getEnvelope() : null;
        if (envelope == null || envelope.getRule() == null) {
            return ScenarioExpandResponse.builder()
                    .totalPossible(0)
                    .returned(0)
                    .truncated(false)
                    .noMatchLabel(resolveNoMatchLabel(request))
                    .domains(List.of())
                    .scenarios(List.of())
                    .warnings(List.of("Envelope or rule is missing."))
                    .build();
        }

        int maxScenarios = clampMax(request.getMaxScenarios());
        String noMatchLabel = resolveNoMatchLabel(request);
        List<String> warnings = new ArrayList<>();
        List<Condition> conditions = collectConditions(envelope.getRule());
        List<FieldDef> inputs = envelope.getRule().getInputs() != null
                ? envelope.getRule().getInputs()
                : List.of();

        List<ScenarioDomain> domains = new ArrayList<>();
        for (FieldDef input : inputs) {
            ScenarioDomain domain = buildDomain(input, conditions, warnings);
            domains.add(domain);
        }

        int totalPossible = computeScenarioCount(domains);
        List<Map<String, Object>> inputMatrix = buildInputMatrix(domains, maxScenarios);
        List<ScenarioRow> scenarios = new ArrayList<>();

        for (int i = 0; i < inputMatrix.size(); i++) {
            Map<String, Object> inputValues = inputMatrix.get(i);
            RuleLookupService.LookupResponse lookup = ruleLookupService.lookup(envelope, inputValues);
            List<String> matchedRuleIds = lookup.matchedRules().stream()
                    .map(RuleLookupService.MatchedRule::ruleId)
                    .toList();
            Map<String, Object> results = combineResults(lookup.matchedRules());

            scenarios.add(ScenarioRow.builder()
                    .scenarioId("S" + String.format("%03d", i + 1))
                    .inputValues(inputValues)
                    .matched(lookup.matched())
                    .matchedRuleIds(matchedRuleIds)
                    .results(results)
                    .outcomeLabel(lookup.matched() ? "MATCHED" : noMatchLabel)
                    .evaluationPath(lookup.evaluationPath())
                    .build());
        }

        boolean truncated = totalPossible > scenarios.size();
        if (truncated) {
            warnings.add("Scenario matrix was truncated at " + maxScenarios + " rows.");
        }

        log.info("scenario-expand END | totalPossible={} | returned={} | truncated={}",
                totalPossible, scenarios.size(), truncated);

        return ScenarioExpandResponse.builder()
                .totalPossible(totalPossible)
                .returned(scenarios.size())
                .truncated(truncated)
                .noMatchLabel(noMatchLabel)
                .domains(domains)
                .scenarios(scenarios)
                .warnings(warnings)
                .build();
    }

    private int clampMax(Integer requested) {
        int max = requested != null ? requested : DEFAULT_MAX_SCENARIOS;
        if (max < 1) return DEFAULT_MAX_SCENARIOS;
        return Math.min(max, HARD_MAX_SCENARIOS);
    }

    private String resolveNoMatchLabel(ScenarioExpandRequest request) {
        if (request != null && request.getNoMatchLabel() != null
                && !request.getNoMatchLabel().isBlank()) {
            return request.getNoMatchLabel().trim();
        }
        return DEFAULT_NO_MATCH_LABEL;
    }

    private List<Condition> collectConditions(Rule rule) {
        List<Condition> conditions = new ArrayList<>();
        if (rule == null) return conditions;

        if (rule.getRules() != null) {
            for (RuleRow row : rule.getRules()) {
                if (row.getConditions() != null) {
                    conditions.addAll(row.getConditions());
                }
            }
        }

        collectTreeConditions(rule.getRoot(), conditions);
        return conditions;
    }

    private void collectTreeConditions(TreeNode node, List<Condition> conditions) {
        if (node == null) return;
        if (node.getCondition() != null) {
            conditions.add(node.getCondition());
        }
        if (node.getBranches() != null) {
            for (Branch branch : node.getBranches()) {
                if (branch.getCondition() != null) {
                    conditions.add(branch.getCondition());
                }
                collectTreeConditions(branch.getChild(), conditions);
            }
        }
        collectTreeConditions(node.getTrueBranch(), conditions);
        collectTreeConditions(node.getFalseBranch(), conditions);
    }

    private ScenarioDomain buildDomain(FieldDef input, List<Condition> conditions, List<String> warnings) {
        String fieldName = input.getName();
        String typeRef = input.getTypeRef() != null ? input.getTypeRef() : TypeRefs.STRING;
        List<Object> values = new ArrayList<>();
        boolean finite = true;
        String source = "allowedValues";

        if (input.getAllowedValues() != null && !input.getAllowedValues().isEmpty()) {
            values.addAll(input.getAllowedValues());
        } else if (TypeRefs.BOOLEAN.equals(typeRef)) {
            values.add(Boolean.TRUE);
            values.add(Boolean.FALSE);
            source = "typeRef";
        } else {
            finite = false;
            source = "conditionSamples";
            values.addAll(deriveSamples(fieldName, typeRef, conditions));
            if (values.isEmpty()) {
                source = "typeDefault";
                values.add(defaultSample(typeRef));
            }
            warnings.add("Field " + fieldName
                    + " has no finite allowedValues; sampled values were used.");
        }

        values = distinct(values);
        return ScenarioDomain.builder()
                .field(fieldName)
                .typeRef(typeRef)
                .values(values)
                .finite(finite)
                .source(source)
                .build();
    }

    private List<Object> deriveSamples(String fieldName, String typeRef, List<Condition> conditions) {
        Set<Object> samples = new LinkedHashSet<>();
        for (Condition condition : conditions) {
            if (condition == null || !fieldName.equals(condition.getField())) {
                continue;
            }

            String operator = condition.getOperator();
            Object value = condition.getValue();
            // v3.12: valueRef 條件無法靜態取樣（依執行期 input / 當天日期），略過。
            // 此類欄位請於 FieldDef.allowedValues 顯式宣告測試用樣本。
            if ((value == null) && condition.getValueRef() != null) {
                continue;
            }
            if (Operators.EQUALS.equals(operator)) {
                samples.add(value);
            } else if (Operators.IN.equals(operator) && value instanceof List<?> list) {
                samples.addAll(list);
            } else if (Operators.BETWEEN.equals(operator) && value instanceof List<?> range && range.size() == 2) {
                samples.add(coerceForType(typeRef, range.get(0)));
                samples.add(coerceForType(typeRef, range.get(1)));
            } else if (isNumeric(typeRef) && value instanceof Number number) {
                addNumericSamples(samples, typeRef, operator, number);
            } else if (TypeRefs.DATE.equals(typeRef) && value != null) {
                samples.add(value);
            }
        }
        return new ArrayList<>(samples);
    }

    private void addNumericSamples(Set<Object> samples, String typeRef, String operator, Number number) {
        double value = number.doubleValue();
        if (Operators.GREATER_THAN.equals(operator)) {
            samples.add(numericForType(typeRef, value + 1));
        } else if (Operators.GREATER_THAN_OR_EQUAL.equals(operator)) {
            samples.add(numericForType(typeRef, value));
        } else if (Operators.LESS_THAN.equals(operator)) {
            samples.add(numericForType(typeRef, value - 1));
        } else if (Operators.LESS_THAN_OR_EQUAL.equals(operator)) {
            samples.add(numericForType(typeRef, value));
        } else if (Operators.NOT_EQUALS.equals(operator)) {
            samples.add(defaultSample(typeRef));
            samples.add(numericForType(typeRef, value));
        }
    }

    private Object coerceForType(String typeRef, Object value) {
        if (value instanceof Number number && isNumeric(typeRef)) {
            return numericForType(typeRef, number.doubleValue());
        }
        return value;
    }

    private Object numericForType(String typeRef, double value) {
        if (TypeRefs.INTEGER.equals(typeRef)) {
            return (int) Math.round(value);
        }
        return value;
    }

    private boolean isNumeric(String typeRef) {
        return TypeRefs.INTEGER.equals(typeRef) || TypeRefs.DECIMAL.equals(typeRef);
    }

    private Object defaultSample(String typeRef) {
        return switch (typeRef) {
            case TypeRefs.INTEGER -> 0;
            case TypeRefs.DECIMAL -> 0.0;
            case TypeRefs.BOOLEAN -> Boolean.TRUE;
            case TypeRefs.DATE -> LocalDate.of(2026, 1, 1).toString();
            default -> "sample";
        };
    }

    private List<Object> distinct(List<Object> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private int computeScenarioCount(List<ScenarioDomain> domains) {
        long total = 1;
        for (ScenarioDomain domain : domains) {
            int size = domain.getValues() != null ? domain.getValues().size() : 0;
            total *= Math.max(size, 1);
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    /**
     * v3.12 — 維度均勻抽樣：
     * <ul>
     *   <li>若 totalPossible &lt;= maxScenarios → 全展開（行為與舊版一致）</li>
     *   <li>否則用 stride sampling（混合進制 index 等距取樣），保證每個維度都被
     *       覆蓋到，避免舊版 break-on-first-fill 偏向第一個維度的問題。</li>
     * </ul>
     * 取樣為確定性（不引入隨機種子），同樣 envelope 重跑會得到同樣 scenarios。
     */
    private List<Map<String, Object>> buildInputMatrix(List<ScenarioDomain> domains, int maxScenarios) {
        if (domains.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(new LinkedHashMap<>());
            return rows;
        }

        // 把 domains 攤成 (field, values) — 對 empty 用 typeDefault 補一個 sample
        List<Object[]> dimValues = new ArrayList<>();
        for (ScenarioDomain domain : domains) {
            List<Object> values = (domain.getValues() != null && !domain.getValues().isEmpty())
                    ? domain.getValues()
                    : List.of(defaultSample(domain.getTypeRef()));
            dimValues.add(values.toArray());
        }

        long totalPossible = 1L;
        for (Object[] vals : dimValues) {
            totalPossible *= vals.length;
            if (totalPossible >= Integer.MAX_VALUE) {
                totalPossible = Integer.MAX_VALUE;
                break;
            }
        }

        int targetSize = (int) Math.min(totalPossible, (long) maxScenarios);
        List<Map<String, Object>> rows = new ArrayList<>(targetSize);

        if (totalPossible <= maxScenarios) {
            // 全展開：等同於 cartesian，但用 index 解碼避免巢狀循環
            for (int i = 0; i < (int) totalPossible; i++) {
                rows.add(decodeIndex(domains, dimValues, i));
            }
            return rows;
        }

        // 兩階段抽樣：
        // Phase 1（確保覆蓋）：round-robin 走 max(dim sizes) 列，
        //   每維度各值各自輪一次 → 滿足「每個維度的每個值都至少出現一次」
        // Phase 2（確保多樣性）：用 stride sampling 從 cartesian 中均勻補剩下的列
        //   stride = totalPossible / remaining，避開 Phase 1 已用過的小 index
        int maxDimSize = 0;
        for (Object[] vals : dimValues) {
            if (vals.length > maxDimSize) maxDimSize = vals.length;
        }
        int phase1 = Math.min(maxDimSize, maxScenarios);
        java.util.Set<Integer> usedIndices = new java.util.LinkedHashSet<>();
        for (int i = 0; i < phase1; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int d = 0; d < domains.size(); d++) {
                row.put(domains.get(d).getField(), dimValues.get(d)[i % dimValues.get(d).length]);
            }
            rows.add(row);
            usedIndices.add(encodeIndex(dimValues, row, domains));
        }

        int remaining = maxScenarios - phase1;
        if (remaining > 0) {
            long range = totalPossible - phase1;
            long step = Math.max(1L, range / remaining);
            long idx = phase1; // 從 phase1 之後的 index 起跳
            int safety = 0;
            while (rows.size() < maxScenarios && safety < remaining * 4L && idx < totalPossible) {
                int decoded = (int) (idx % totalPossible);
                if (!usedIndices.contains(decoded)) {
                    rows.add(decodeIndex(domains, dimValues, decoded));
                    usedIndices.add(decoded);
                }
                idx += step;
                safety++;
            }
        }
        return rows;
    }

    /**
     * 把已組好的 row 反算成 cartesian index（給 Phase 1 用以避免 Phase 2 重複取樣）。
     */
    private int encodeIndex(List<Object[]> dimValues, Map<String, Object> row,
                             List<ScenarioDomain> domains) {
        int idx = 0;
        for (int d = 0; d < domains.size(); d++) {
            Object[] vals = dimValues.get(d);
            Object current = row.get(domains.get(d).getField());
            int posInDim = 0;
            for (int k = 0; k < vals.length; k++) {
                if (java.util.Objects.equals(vals[k], current)) {
                    posInDim = k;
                    break;
                }
            }
            idx = idx * vals.length + posInDim;
        }
        return idx;
    }

    /**
     * 把 0..totalPossible-1 範圍的整數 index 解成各維度的值組合（混合進制）。
     */
    private Map<String, Object> decodeIndex(List<ScenarioDomain> domains,
                                             List<Object[]> dimValues, int index) {
        Map<String, Object> row = new LinkedHashMap<>();
        // 由最後一個維度開始解，保留與 cartesian 順序一致（first dim 為最慢變動）
        int remaining = index;
        int[] dimIdx = new int[domains.size()];
        for (int d = domains.size() - 1; d >= 0; d--) {
            int size = dimValues.get(d).length;
            dimIdx[d] = remaining % size;
            remaining = remaining / size;
        }
        for (int d = 0; d < domains.size(); d++) {
            row.put(domains.get(d).getField(), dimValues.get(d)[dimIdx[d]]);
        }
        return row;
    }

    private Map<String, Object> combineResults(List<RuleLookupService.MatchedRule> matchedRules) {
        Map<String, Object> combined = new LinkedHashMap<>();
        for (RuleLookupService.MatchedRule matchedRule : matchedRules) {
            for (Map.Entry<String, Object> entry : matchedRule.results().entrySet()) {
                appendResult(combined, entry.getKey(), entry.getValue());
            }
        }
        return combined;
    }

    @SuppressWarnings("unchecked")
    private void appendResult(Map<String, Object> combined, String field, Object value) {
        if (!combined.containsKey(field)) {
            combined.put(field, value);
            return;
        }

        Object existing = combined.get(field);
        if (existing instanceof List<?> list) {
            List<Object> updated = new ArrayList<>((List<Object>) list);
            updated.add(value);
            combined.put(field, updated);
        } else {
            List<Object> values = new ArrayList<>();
            values.add(existing);
            values.add(value);
            combined.put(field, values);
        }
    }
}
