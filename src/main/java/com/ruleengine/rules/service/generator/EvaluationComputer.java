package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.dto.ToolDtos.Operators;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Evaluation 指標計算服務。
 *
 * 計算 RuleEnvelope 的品質評估指標：
 *   - completeness: COMPLETE / INCOMPLETE（基於覆蓋率）
 *   - totalScenarios: 規則條數
 *   - coverageRate: 0.0 – 1.0（基於笛卡爾積估算）
 *   - conflictDetection: NO_CONFLICT / HAS_CONFLICT（FIRST 策略下的重疊偵測）
 *   - recommendedStrategy: FIRST / MULTI
 *
 * v2.0.0: 從 DecisionTableGenerator 抽出，單一職責。
 * v1.3.0 修正：
 *   - anything 全覆蓋維度不計入笛卡爾積
 *   - 基於「分段數」而非 distinctValues 估算 cardinality
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EvaluationComputer {

    private final ConditionOverlapDetector overlapDetector;

    /**
     * 計算並設置 RuleEnvelope 的 evaluation 指標。
     */
    public void computeEvaluation(RuleEnvelope envelope) {
        Rule rule = envelope.getRule();
        if (rule == null) {
            envelope.setEvaluation(buildEmptyEvaluation());
            return;
        }

        int totalRules = rule.getRules() != null ? rule.getRules().size() : 0;
        String hitPolicy = rule.getHitPolicy() != null ? rule.getHitPolicy() : "FIRST";

        // 估算理論最大組合數
        int theoreticalMax = estimateMaxCombinations(rule);

        // 覆蓋率
        double coverage = theoreticalMax > 0
                ? Math.min(1.0, (double) totalRules / theoreticalMax)
                : (totalRules > 0 ? 1.0 : 0.0);
        coverage = Math.round(coverage * 100.0) / 100.0;

        // 衝突偵測（僅 FIRST 策略）
        boolean hasConflict = false;
        if ("FIRST".equals(hitPolicy) && totalRules >= 2) {
            hasConflict = overlapDetector.hasAnyOverlap(rule.getRules());
        }

        envelope.setEvaluation(Evaluation.builder()
                .completeness(coverage >= 1.0 ? "COMPLETE" : "INCOMPLETE")
                .totalScenarios(totalRules)
                .coverageRate(coverage)
                .conflictDetection(hasConflict ? "HAS_CONFLICT" : "NO_CONFLICT")
                .recommendedStrategy(hitPolicy)
                .build());
    }

    // ================================================================
    // 笛卡爾積估算
    // ================================================================

    /**
     * 估算條件組合的理論最大數量。
     *
     * 若某 input 在所有 rules 中都是 anything → 不計入笛卡爾積（乘 1）。
     * 使用「分段數」估算 cardinality。
     */
    private int estimateMaxCombinations(Rule rule) {
        if (rule.getInputs() == null || rule.getRules() == null) return 0;

        int product = 1;
        for (FieldDef input : rule.getInputs()) {
            if (isFieldAlwaysAnything(input.getName(), rule.getRules())) {
                log.debug("estimateMaxCombinations: 欄位 {} 在所有規則中都是 anything，跳過", input.getName());
                continue;
            }
            int cardinality = estimateFieldCardinality(input, rule.getRules());
            product *= cardinality;
            if (product > 10000) return 10000; // 防爆
        }
        return product;
    }

    /**
     * 檢查某欄位在所有 rules 中是否都是 anything（或該欄位不存在於 conditions 中）。
     */
    private boolean isFieldAlwaysAnything(String fieldName, List<RuleRow> rules) {
        for (RuleRow row : rules) {
            if (row.getConditions() == null) continue;
            for (Condition cond : row.getConditions()) {
                if (fieldName.equals(cond.getField())) {
                    if (!overlapDetector.isAnything(cond)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 基於「分段數」估算欄位的 cardinality。
     *
     * 邏輯：
     *   1. BOOLEAN → 2
     *   2. ENUM → allowedValues.size()
     *   3. 數值型（INTEGER/DECIMAL/DATE）：收集分段數
     *   4. STRING → 統計不同值的數量
     */
    private int estimateFieldCardinality(FieldDef input, List<RuleRow> rules) {
        String typeRef = input.getTypeRef();
        if ("BOOLEAN".equals(typeRef)) return 2;
        if ("ENUM".equals(typeRef) && input.getAllowedValues() != null) {
            return Math.max(1, input.getAllowedValues().size());
        }

        // 收集此欄位在所有規則中的條件
        List<Condition> fieldConditions = new ArrayList<>();
        for (RuleRow row : rules) {
            if (row.getConditions() == null) continue;
            for (Condition cond : row.getConditions()) {
                if (input.getName().equals(cond.getField()) && !overlapDetector.isAnything(cond)) {
                    fieldConditions.add(cond);
                }
            }
        }

        if (fieldConditions.isEmpty()) return 1;

        // 對數值型別用分段數估算
        if ("INTEGER".equals(typeRef) || "DECIMAL".equals(typeRef) || "DATE".equals(typeRef)) {
            return estimateNumericSegments(fieldConditions);
        }

        // STRING 或其他型別：統計不同值數量
        Set<String> distinctValues = new HashSet<>();
        for (Condition cond : fieldConditions) {
            if (cond.getValue() != null) {
                String op = cond.getOperator();
                if (Operators.IN.equals(op) && cond.getValue() instanceof List) {
                    for (Object v : (List<?>) cond.getValue()) {
                        distinctValues.add(String.valueOf(v));
                    }
                } else if (Operators.BETWEEN.equals(op) && cond.getValue() instanceof List) {
                    distinctValues.add("range:" + cond.getValue());
                } else {
                    distinctValues.add(op + ":" + cond.getValue());
                }
            }
        }
        return Math.max(1, distinctValues.size());
    }

    /**
     * 估算數值欄位的分段數。
     *
     * 每個不同的 operator+value 組合（去重）算一段。
     * equals 若已落入某 between 範圍內，不額外計算。
     */
    private int estimateNumericSegments(List<Condition> conditions) {
        Set<String> segments = new LinkedHashSet<>();
        List<double[]> betweenRanges = new ArrayList<>();

        for (Condition cond : conditions) {
            String op = cond.getOperator();
            if (op == null || cond.getValue() == null) continue;

            if (Operators.BETWEEN.equals(op) && cond.getValue() instanceof List) {
                List<?> range = (List<?>) cond.getValue();
                if (range.size() == 2) {
                    try {
                        double min = Double.parseDouble(String.valueOf(range.get(0)));
                        double max = Double.parseDouble(String.valueOf(range.get(1)));
                        segments.add("between:" + min + ":" + max);
                        betweenRanges.add(new double[]{min, max});
                    } catch (NumberFormatException ignored) {
                        segments.add("between:" + cond.getValue());
                    }
                }
            } else if (Operators.GREATER_THAN.equals(op) || Operators.GREATER_THAN_OR_EQUAL.equals(op)) {
                try {
                    double threshold = Double.parseDouble(String.valueOf(cond.getValue()));
                    segments.add("gt:" + threshold);
                } catch (NumberFormatException ignored) {
                    segments.add("gt:" + cond.getValue());
                }
            } else if (Operators.LESS_THAN.equals(op) || Operators.LESS_THAN_OR_EQUAL.equals(op)) {
                try {
                    double threshold = Double.parseDouble(String.valueOf(cond.getValue()));
                    segments.add("lt:" + threshold);
                } catch (NumberFormatException ignored) {
                    segments.add("lt:" + cond.getValue());
                }
            } else if (Operators.EQUALS.equals(op)) {
                try {
                    double val = Double.parseDouble(String.valueOf(cond.getValue()));
                    boolean coveredByRange = betweenRanges.stream()
                            .anyMatch(r -> val >= r[0] && val <= r[1]);
                    if (!coveredByRange) {
                        segments.add("eq:" + val);
                    }
                } catch (NumberFormatException ignored) {
                    segments.add("eq:" + cond.getValue());
                }
            } else if (Operators.IN.equals(op) && cond.getValue() instanceof List) {
                for (Object item : (List<?>) cond.getValue()) {
                    try {
                        double val = Double.parseDouble(String.valueOf(item));
                        boolean coveredByRange = betweenRanges.stream()
                                .anyMatch(r -> val >= r[0] && val <= r[1]);
                        if (!coveredByRange) {
                            segments.add("eq:" + val);
                        }
                    } catch (NumberFormatException ignored) {
                        segments.add("in-val:" + item);
                    }
                }
            }
            // 其他 operator（notEquals, notIn 等）不額外增加分段
        }

        return Math.max(1, segments.size());
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private Evaluation buildEmptyEvaluation() {
        return Evaluation.builder()
                .completeness("INCOMPLETE")
                .totalScenarios(0)
                .coverageRate(0.0)
                .conflictDetection("NO_CONFLICT")
                .recommendedStrategy("FIRST")
                .build();
    }
}
