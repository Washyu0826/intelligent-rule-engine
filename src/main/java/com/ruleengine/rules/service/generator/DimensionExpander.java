package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.DimensionInfo;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser.ParsedDimensions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DimensionExpander — 後處理維度擴展。
 *
 * 當 LLM 只生成了部分維度的規則時（例如 3/5 inputs），
 * 此組件會找出遺漏的維度，並用笛卡爾積將現有規則擴展，
 * 使所有維度都被涵蓋。
 *
 * 策略：
 * - BOOLEAN 遺漏維度（如 heartDisease）：
 *   false → 繼承原規則的結果
 *   true → 根據風險升級結果（如 承保→人工評估，人工評估→拒保）
 * - ENUM 遺漏維度（如 gender）：
 *   所有取值繼承原規則結果（假設該維度不影響決策）
 * - INTEGER 遺漏維度（如 age range）：
 *   根據 between 運算子展開
 */
@Component
@Slf4j
public class DimensionExpander {

    @Value("${rules.validation.max-table-rows:200}")
    private int maxTableRows;

    /**
     * 核心方法：擴展遺漏的維度。
     */
    public RuleEnvelope expand(RuleEnvelope envelope, ParsedDimensions parsedDims) {
        if (parsedDims == null || parsedDims.inputs().isEmpty()) {
            return envelope;
        }

        Rule rule = envelope.getRule();
        if (rule == null || rule.getRules() == null || rule.getRules().isEmpty()) {
            return envelope;
        }

        // 1. 找出 LLM 已有的 input 欄位名（lowercase）
        Set<String> existingInputNames = rule.getInputs().stream()
                .map(f -> f.getName().toLowerCase())
                .collect(Collectors.toSet());

        // 2. 找出遺漏的 input 維度
        List<DimensionInfo> missingInputs = new ArrayList<>();
        for (DimensionInfo dim : parsedDims.inputs()) {
            String eng = dim.suggestedEnglishName().toLowerCase();
            String engNorm = eng.replace("_", "");
            boolean found = existingInputNames.contains(eng);
            // 模糊匹配（含去底線）
            if (!found) {
                for (String existing : existingInputNames) {
                    String existNorm = existing.replace("_", "");
                    if (existing.contains(eng) || eng.contains(existing)
                            || existNorm.contains(engNorm) || engNorm.contains(existNorm)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                missingInputs.add(dim);
            }
        }

        if (missingInputs.isEmpty()) {
            log.info("DimensionExpander: 無遺漏維度，跳過擴展");
            return envelope;
        }

        // 3. 計算擴展後的規則數，檢查是否超過上限
        int currentRules = rule.getRules().size();
        int expansionFactor = 1;
        for (DimensionInfo dim : missingInputs) {
            int dimSize = dim.values().size();
            if (dimSize > 0) expansionFactor *= dimSize;
        }
        int expectedTotal = currentRules * expansionFactor;

        log.info("DimensionExpander: 遺漏 {} 個維度，擴展倍數 {}x，{} → {} rules{}",
                missingInputs.size(), expansionFactor, currentRules, expectedTotal,
                expectedTotal > maxTableRows ? "（將截斷至 " + maxTableRows + "）" : "");

        // 4. 補上遺漏的 input FieldDef
        List<FieldDef> newInputs = new ArrayList<>(rule.getInputs());
        for (DimensionInfo dim : missingInputs) {
            FieldDef fd = FieldDef.builder()
                    .name(dim.suggestedEnglishName())
                    .typeRef(dim.suggestedTypeRef())
                    .build();
            if ("ENUM".equals(dim.suggestedTypeRef()) && !dim.values().isEmpty()) {
                fd.setAllowedValues(new ArrayList<>(dim.values()));
            }
            newInputs.add(fd);
        }
        rule.setInputs(newInputs);

        // 5. 補上遺漏的 output FieldDef（用更寬鬆的匹配避免重複）
        Set<String> existingOutputNames = rule.getOutputs().stream()
                .map(f -> f.getName().toLowerCase())
                .collect(Collectors.toSet());
        for (DimensionInfo dim : parsedDims.outputs()) {
            String eng = dim.suggestedEnglishName().toLowerCase();
            // 寬鬆匹配：英文名部分包含、或去底線後包含
            boolean found = existingOutputNames.stream()
                    .anyMatch(e -> {
                        String eNorm = e.replace("_", "");
                        String engNorm = eng.replace("_", "");
                        return e.contains(eng) || eng.contains(e)
                                || eNorm.contains(engNorm) || engNorm.contains(eNorm);
                    });
            if (!found) {
                FieldDef fd = FieldDef.builder()
                        .name(dim.suggestedEnglishName())
                        .typeRef(dim.suggestedTypeRef())
                        .build();
                if ("ENUM".equals(dim.suggestedTypeRef()) && !dim.values().isEmpty()) {
                    fd.setAllowedValues(new ArrayList<>(dim.values()));
                }
                rule.getOutputs().add(fd);
                log.info("DimensionExpander: 補上遺漏 output: {}", dim.suggestedEnglishName());
            }
        }

        // 6. 展開規則的笛卡爾積
        List<RuleRow> expandedRules = new ArrayList<>();
        for (RuleRow originalRule : rule.getRules()) {
            List<RuleRow> expanded = expandRule(originalRule, missingInputs);
            expandedRules.addAll(expanded);
            if (expandedRules.size() >= maxTableRows) {
                log.warn("DimensionExpander: 已達上限 {}，停止擴展", maxTableRows);
                break;
            }
        }

        // 截斷
        if (expandedRules.size() > maxTableRows) {
            expandedRules = expandedRules.subList(0, maxTableRows);
        }

        // 7. 重新編號
        for (int i = 0; i < expandedRules.size(); i++) {
            expandedRules.get(i).setRuleId(String.format("R%02d", i + 1));
            expandedRules.get(i).setPriority(i + 1);
        }

        rule.setRules(expandedRules);

        log.info("DimensionExpander: 擴展完成，最終 {} rules（{}個 inputs）",
                expandedRules.size(), rule.getInputs().size());

        return envelope;
    }

    /**
     * 將一條規則按遺漏維度展開為多條。
     */
    private List<RuleRow> expandRule(RuleRow original, List<DimensionInfo> missingDims) {
        List<RuleRow> result = new ArrayList<>();
        result.add(original);

        for (DimensionInfo dim : missingDims) {
            List<RuleRow> nextResult = new ArrayList<>();
            for (RuleRow rule : result) {
                List<String> values = dim.values();
                if (values.isEmpty()) {
                    values = List.of("true", "false"); // 預設 boolean
                }

                for (int vi = 0; vi < values.size(); vi++) {
                    String value = values.get(vi);
                    RuleRow expanded = cloneRuleRow(rule);

                    // 加入新的 condition
                    Condition newCond = buildCondition(dim, value);
                    expanded.getConditions().add(newCond);

                    // 根據維度類型調整 results
                    if ("BOOLEAN".equals(dim.suggestedTypeRef()) && isPositiveValue(value)) {
                        // boolean=true 的疾病/風險 → 加重結果
                        adjustResultsForRisk(expanded);
                    }
                    // ENUM/其他類型：繼承原結果（不調整）

                    nextResult.add(expanded);
                }
            }
            result = nextResult;
        }

        return result;
    }

    private Condition buildCondition(DimensionInfo dim, String value) {
        String typeRef = dim.suggestedTypeRef();

        if ("BOOLEAN".equals(typeRef)) {
            boolean boolVal = isPositiveValue(value);
            return Condition.builder()
                    .field(dim.suggestedEnglishName())
                    .operator("equals")
                    .value(boolVal)
                    .build();
        } else if ("INTEGER".equals(typeRef) && value.matches("\\d+[–\\-~]\\d+.*")) {
            // range value like "18–35"
            String[] parts = value.split("[–\\-~]");
            try {
                int min = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
                int max = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                return Condition.builder()
                        .field(dim.suggestedEnglishName())
                        .operator("between")
                        .value(List.of(min, max))
                        .build();
            } catch (NumberFormatException e) {
                // fallback to equals
            }
        }

        // ENUM or fallback
        return Condition.builder()
                .field(dim.suggestedEnglishName())
                .operator("equals")
                .value(value)
                .build();
    }

    private boolean isPositiveValue(String value) {
        return "是".equals(value) || "true".equalsIgnoreCase(value);
    }

    /**
     * 風險加重：承保→人工評估，人工評估→拒保，保費係數×1.3。
     */
    private void adjustResultsForRisk(RuleRow rule) {
        if (rule.getResults() == null) return;

        for (Result r : rule.getResults()) {
            Object val = r.getValue();
            if (val == null) continue;
            String strVal = String.valueOf(val);

            // 核保決議加重
            if ("承保".equals(strVal)) {
                r.setValue("人工評估");
            } else if ("人工評估".equals(strVal)) {
                r.setValue("拒保");
            } else if ("標準".equals(strVal)) {
                r.setValue("加費10%");
            } else if ("加費10%".equals(strVal)) {
                r.setValue("加費25%");
            } else if ("加費25%".equals(strVal)) {
                r.setValue("加費50%");
            } else if ("加費50%".equals(strVal)) {
                r.setValue("拒保");
            } else if ("核准".equals(strVal)) {
                r.setValue("加費核准");
            } else if ("加費核准".equals(strVal)) {
                r.setValue("附除外條款");
            } else if ("附除外條款".equals(strVal)) {
                r.setValue("拒保");
            }

            // 保費係數：數值型 ×1.3
            if (val instanceof Number) {
                double numVal = ((Number) val).doubleValue();
                if (numVal > 0 && numVal < 10) { // 看起來像保費係數
                    r.setValue(Math.round(numVal * 1.3 * 10.0) / 10.0);
                }
            }
        }
    }

    private RuleRow cloneRuleRow(RuleRow original) {
        List<Condition> conditions = new ArrayList<>();
        if (original.getConditions() != null) {
            for (Condition c : original.getConditions()) {
                conditions.add(Condition.builder()
                        .field(c.getField())
                        .operator(c.getOperator())
                        .value(c.getValue())
                        .build());
            }
        }

        List<Result> results = new ArrayList<>();
        if (original.getResults() != null) {
            for (Result r : original.getResults()) {
                results.add(Result.builder()
                        .field(r.getField())
                        .value(r.getValue())
                        .build());
            }
        }

        return RuleRow.builder()
                .ruleId(original.getRuleId())
                .priority(original.getPriority())
                .conditions(conditions)
                .results(results)
                .build();
    }
}
