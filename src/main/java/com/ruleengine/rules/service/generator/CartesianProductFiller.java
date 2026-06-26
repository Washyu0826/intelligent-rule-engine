package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.DimensionInfo;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.ParsedDimensions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CartesianProductFiller — Two-Pass 生成的 Pass 2。
 *
 * 在 Pass 1 完成後，計算所有條件組合的笛卡爾積，
 * 找出尚未覆蓋的組合，分批送 LLM 只填結果值。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CartesianProductFiller {

    private static final int MAX_COMBINATIONS = 200;

    // 預編譯值樣式（buildConditionFromValue 會對每個維度值呼叫，避免重複編譯 regex）
    private static final java.util.regex.Pattern RANGE_PATTERN =
            java.util.regex.Pattern.compile(".*\\d+\\s*[–\\-~]\\s*\\d+.*");
    private static final java.util.regex.Pattern INT_PATTERN =
            java.util.regex.Pattern.compile("-?\\d+");
    private static final java.util.regex.Pattern DECIMAL_PATTERN =
            java.util.regex.Pattern.compile("-?\\d+\\.\\d+");
    private static final java.util.regex.Pattern ABOVE_PATTERN =
            java.util.regex.Pattern.compile("\\d+.*以上");

    @Value("${rules.validation.max-table-rows:200}")
    private int maxTableRows;

    /**
     * 主方法：填充笛卡爾積缺口。
     */
    public RuleEnvelope fill(RuleEnvelope envelope, ParsedDimensions parsedDims) {
        Rule rule = envelope.getRule();
        if (rule == null || rule.getInputs() == null) return envelope;

        // 1. 計算所有條件組合
        List<List<Condition>> allCombinations = computeAllCombinations(rule.getInputs(), parsedDims);
        if (allCombinations.isEmpty()) {
            log.info("CartesianProductFiller: 無法計算笛卡爾積，跳過");
            return envelope;
        }

        // 截斷到上限
        if (allCombinations.size() > MAX_COMBINATIONS) {
            log.info("CartesianProductFiller: 笛卡爾積 {} 超過上限 {}，截斷",
                    allCombinations.size(), MAX_COMBINATIONS);
            allCombinations = allCombinations.subList(0, MAX_COMBINATIONS);
        }

        // 2. 找出尚未覆蓋的組合
        List<RuleRow> existingRules = rule.getRules() != null ? rule.getRules() : new ArrayList<>();
        Set<String> existingFingerprints = existingRules.stream()
                .map(this::fingerprint)
                .collect(Collectors.toSet());

        List<List<Condition>> missingCombinations = new ArrayList<>();
        for (List<Condition> combo : allCombinations) {
            String fp = fingerprintConditions(combo);
            if (!existingFingerprints.contains(fp)) {
                missingCombinations.add(combo);
            }
        }

        log.info("CartesianProductFiller: 笛卡爾積={}, 已有={}, 缺少={}",
                allCombinations.size(), existingRules.size(), missingCombinations.size());

        if (missingCombinations.isEmpty()) {
            return envelope;
        }

        // 3. 純程式化啟發式填充（不呼叫 LLM，速度極快）
        List<RuleRow> newRules = new ArrayList<>(existingRules);
        fillWithHeuristics(missingCombinations, newRules, rule.getOutputs(), existingRules);

        // 5. 截斷並重新編號
        if (newRules.size() > maxTableRows) {
            newRules = new ArrayList<>(newRules.subList(0, maxTableRows));
        }
        for (int i = 0; i < newRules.size(); i++) {
            newRules.get(i).setRuleId(String.format("R%02d", i + 1));
            newRules.get(i).setPriority(i + 1);
        }

        rule.setRules(newRules);
        log.info("CartesianProductFiller: 完成 | rules {} → {}",
                existingRules.size(), newRules.size());

        return envelope;
    }

    // ========================================================================
    // 笛卡爾積計算
    // ========================================================================

    private List<List<Condition>> computeAllCombinations(List<FieldDef> inputs, ParsedDimensions parsedDims) {
        // 收集每個 input 的所有可能值
        List<List<Condition>> dimensionValues = new ArrayList<>();

        for (FieldDef input : inputs) {
            List<Condition> valuesForThisDim = new ArrayList<>();
            DimensionInfo dimInfo = findMatchingDim(input.getName(), parsedDims.inputs());

            if (dimInfo != null && !dimInfo.values().isEmpty()) {
                for (String val : dimInfo.values()) {
                    valuesForThisDim.add(buildConditionFromValue(input, dimInfo, val));
                }
            } else if (input.getAllowedValues() != null && !input.getAllowedValues().isEmpty()) {
                for (String val : input.getAllowedValues()) {
                    valuesForThisDim.add(Condition.builder()
                            .field(input.getName())
                            .operator("equals")
                            .value(val)
                            .build());
                }
            } else if ("BOOLEAN".equalsIgnoreCase(input.getTypeRef())) {
                valuesForThisDim.add(Condition.builder().field(input.getName()).operator("equals").value(true).build());
                valuesForThisDim.add(Condition.builder().field(input.getName()).operator("equals").value(false).build());
            } else {
                log.warn("CartesianProductFiller: 無法確定 {} 的取值，跳過此維度", input.getName());
                continue;
            }

            if (!valuesForThisDim.isEmpty()) {
                dimensionValues.add(valuesForThisDim);
            }
        }

        if (dimensionValues.isEmpty()) return List.of();

        // 計算笛卡爾積
        return cartesianProduct(dimensionValues);
    }

    private Condition buildConditionFromValue(FieldDef input, DimensionInfo dimInfo, String val) {
        String typeRef = dimInfo.suggestedTypeRef();

        // 「是」/「否」→ boolean
        if ("BOOLEAN".equalsIgnoreCase(typeRef) || "是".equals(val) || "否".equals(val)) {
            boolean boolVal = "是".equals(val) || "true".equalsIgnoreCase(val);
            return Condition.builder()
                    .field(input.getName()).operator("equals").value(boolVal).build();
        }

        // 含「–」或「-」的範圍值 → between
        if (RANGE_PATTERN.matcher(val).matches()) {
            String[] parts = val.split("[–\\-~]");
            try {
                int min = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
                int max = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                return Condition.builder()
                        .field(input.getName()).operator("between").value(List.of(min, max)).build();
            } catch (NumberFormatException ignored) {}
        }

        // 純數字 → equals number
        if (INT_PATTERN.matcher(val).matches()) {
            return Condition.builder()
                    .field(input.getName()).operator("equals").value(Integer.parseInt(val)).build();
        }
        if (DECIMAL_PATTERN.matcher(val).matches()) {
            return Condition.builder()
                    .field(input.getName()).operator("equals").value(Double.parseDouble(val)).build();
        }

        // 含「以上」→ greaterThan
        if (ABOVE_PATTERN.matcher(val).matches()) {
            int num = Integer.parseInt(val.replaceAll("[^0-9]", ""));
            return Condition.builder()
                    .field(input.getName()).operator("greaterThan").value(num).build();
        }

        // 預設 equals string
        return Condition.builder()
                .field(input.getName()).operator("equals").value(val).build();
    }

    private List<List<Condition>> cartesianProduct(List<List<Condition>> dimensions) {
        List<List<Condition>> result = new ArrayList<>();
        result.add(new ArrayList<>());

        for (List<Condition> dim : dimensions) {
            List<List<Condition>> newResult = new ArrayList<>();
            for (List<Condition> existing : result) {
                for (Condition val : dim) {
                    List<Condition> combo = new ArrayList<>(existing);
                    combo.add(val);
                    newResult.add(combo);
                    if (newResult.size() > MAX_COMBINATIONS) return newResult;
                }
            }
            result = newResult;
        }
        return result;
    }

    // ========================================================================
    // 指紋（用於比對已有/缺少的組合）
    // ========================================================================

    private String fingerprint(RuleRow rule) {
        if (rule.getConditions() == null) return "";
        return fingerprintConditions(rule.getConditions());
    }

    private String fingerprintConditions(List<Condition> conditions) {
        return conditions.stream()
                .sorted(Comparator.comparing(Condition::getField))
                .map(c -> c.getField() + ":" + c.getOperator() + ":" + c.getValue())
                .collect(Collectors.joining("|"));
    }

    // ========================================================================
    // 啟發式填充
    // ========================================================================

    private void fillWithHeuristics(List<List<Condition>> batch, List<RuleRow> newRules,
                                     List<FieldDef> outputs, List<RuleRow> existingRules) {
        // 用第一條已有規則的結果作為基礎
        List<Result> baseResults = existingRules.isEmpty() ? new ArrayList<>()
                : existingRules.get(0).getResults();

        for (List<Condition> combo : batch) {
            if (newRules.size() >= maxTableRows) break;

            // 計算風險因子數量
            int riskFactors = countRiskFactors(combo);

            // 複製基礎結果並根據風險因子調整
            List<Result> results = new ArrayList<>();
            for (Result base : baseResults) {
                Result adjusted = Result.builder()
                        .field(base.getField())
                        .value(base.getValue())
                        .build();
                // 每多一個風險因子就升級一次
                for (int r = 0; r < riskFactors; r++) {
                    escalateResult(adjusted);
                }
                results.add(adjusted);
            }

            // 如果 outputs 中有基礎結果沒覆蓋的，補上預設值
            Set<String> coveredFields = results.stream()
                    .map(Result::getField).collect(Collectors.toSet());
            for (FieldDef out : outputs) {
                if (!coveredFields.contains(out.getName())) {
                    results.add(Result.builder().field(out.getName()).value("待確認").build());
                }
            }

            newRules.add(RuleRow.builder()
                    .ruleId(String.format("R%02d", newRules.size() + 1))
                    .priority(newRules.size() + 1)
                    .conditions(combo)
                    .results(results)
                    .build());
        }
    }

    private int countRiskFactors(List<Condition> conditions) {
        int count = 0;
        for (Condition c : conditions) {
            if (c.getValue() instanceof Boolean && (Boolean) c.getValue()) count++;
            if ("是".equals(c.getValue())) count++;
            // 高年齡、高風險等級
            String field = c.getField().toLowerCase();
            if (c.getValue() instanceof String) {
                String val = (String) c.getValue();
                if (val.contains("高") || val.contains("肥胖") || val.contains("以上")) count++;
            }
            if (c.getValue() instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> range = (List<Object>) c.getValue();
                if (range.size() == 2 && range.get(0) instanceof Number) {
                    int min = ((Number) range.get(0)).intValue();
                    if (field.contains("age") && min >= 50) count++;
                }
            }
        }
        return count;
    }

    private void escalateResult(Result r) {
        if (r.getValue() == null) return;
        String val = String.valueOf(r.getValue());

        // 決議升級
        if ("承保".equals(val) || "核准".equals(val) || "標準".equals(val)) {
            r.setValue("人工評估".equals(val) ? "拒保" : "人工評估");
            return;
        }
        if ("人工評估".equals(val) || "附除外條款".equals(val) || "加費核准".equals(val)) {
            r.setValue("拒保");
            return;
        }

        // 數值升級
        if (r.getValue() instanceof Number) {
            double num = ((Number) r.getValue()).doubleValue();
            if (num > 0 && num < 10) {
                r.setValue(Math.round(num * 1.2 * 10.0) / 10.0);
            }
        }
    }

    private DimensionInfo findMatchingDim(String fieldName, List<DimensionInfo> dims) {
        String lower = fieldName.toLowerCase();
        for (DimensionInfo dim : dims) {
            String eng = dim.suggestedEnglishName().toLowerCase();
            if (eng.equals(lower) || eng.contains(lower) || lower.contains(eng)) {
                return dim;
            }
        }
        return null;
    }
}
