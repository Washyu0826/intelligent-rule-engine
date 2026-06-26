package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.StreamSupport;

import static com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes.*;

/**
 * ScoreCard 驗證器。
 *
 * 驗證項目：
 * 1. 結構檢查：ruleType、inputs、outputs、scoringDimensions、scoreBands
 * 2. 計分規則語意：field 存在、score 非 null、operator 合法
 * 3. 分數區間連續性：scoreBands 是否覆蓋所有可能分數
 * 4. 區間重疊偵測：不允許 scoreBands 重疊
 * 5. 結果完整性：每個 scoreBand 的 results 涵蓋所有 outputs
 */
@Component
@Slf4j
public class ScoreCardValidator implements RuleValidator {

    @Override
    public RuleType supportedType() {
        return RuleType.SCORE_CARD;
    }

    @Override
    public List<ValidationError> validate(JsonNode envelope) {
        List<ValidationError> errors = new ArrayList<>();

        // Phase 1: 結構檢查
        if (envelope == null || envelope.isNull()) {
            errors.add(err(MISSING_FIELD, "輸入為 null"));
            return errors;
        }

        if (!envelope.has("ruleType")) {
            errors.add(err(MISSING_FIELD, "缺少 ruleType"));
        }

        if (!envelope.has("rule") || envelope.get("rule").isNull()) {
            errors.add(err(MISSING_FIELD, "缺少 rule"));
            return errors;
        }

        JsonNode rule = envelope.get("rule");

        // inputs/outputs
        if (!rule.has("inputs") || !rule.get("inputs").isArray()) {
            errors.add(err(MISSING_FIELD, "缺少 inputs"));
        }
        if (!rule.has("outputs") || !rule.get("outputs").isArray()) {
            errors.add(err(MISSING_FIELD, "缺少 outputs"));
        }

        // scoringDimensions
        if (!rule.has("scoringDimensions") || !rule.get("scoringDimensions").isArray()) {
            errors.add(err(MISSING_FIELD, "ScoreCard 缺少 scoringDimensions"));
            return errors;
        }

        // scoreBands
        if (!rule.has("scoreBands") || !rule.get("scoreBands").isArray()) {
            errors.add(err(MISSING_FIELD, "ScoreCard 缺少 scoreBands"));
            return errors;
        }

        // Phase 2: 解析欄位
        Set<String> inputNames = new HashSet<>();
        if (rule.has("inputs") && rule.get("inputs").isArray()) {
            for (JsonNode inp : rule.get("inputs")) {
                if (inp.has("name")) inputNames.add(inp.get("name").asText());
            }
        }

        Set<String> outputNames = new HashSet<>();
        if (rule.has("outputs") && rule.get("outputs").isArray()) {
            for (JsonNode out : rule.get("outputs")) {
                if (out.has("name")) outputNames.add(out.get("name").asText());
            }
        }

        // Phase 3: 驗證 scoringDimensions
        JsonNode dimensions = rule.get("scoringDimensions");
        Set<String> dimFields = new HashSet<>();
        Set<String> seenRuleIds = new HashSet<>();

        for (int i = 0; i < dimensions.size(); i++) {
            JsonNode dim = dimensions.get(i);
            String path = "scoringDimensions[" + i + "]";

            if (!dim.has("field") || dim.get("field").asText().isBlank()) {
                errors.add(err(MISSING_FIELD, path + " 缺少 field"));
                continue;
            }

            String field = dim.get("field").asText();

            if (!inputNames.contains(field)) {
                errors.add(err(UNKNOWN_FIELD, path + " field \"" + field + "\" 不在 inputs 中"));
            }

            if (!dimFields.add(field)) {
                errors.add(err(DUPLICATE_ID, path + " field \"" + field + "\" 重複出現"));
            }

            // 驗證 scoringRules
            if (!dim.has("scoringRules") || !dim.get("scoringRules").isArray()) {
                errors.add(err(MISSING_FIELD, path + " 缺少 scoringRules"));
                continue;
            }

            JsonNode rules = dim.get("scoringRules");
            for (int j = 0; j < rules.size(); j++) {
                JsonNode sr = rules.get(j);
                String srPath = path + ".scoringRules[" + j + "]";

                // ruleId 重複
                if (sr.has("ruleId") && !sr.get("ruleId").asText().isBlank()) {
                    if (!seenRuleIds.add(sr.get("ruleId").asText())) {
                        errors.add(err(DUPLICATE_ID, srPath + " ruleId \"" + sr.get("ruleId").asText() + "\" 重複"));
                    }
                }

                // score 必填
                if (!sr.has("score") || sr.get("score").isNull()) {
                    errors.add(err(MISSING_FIELD, srPath + " 缺少 score"));
                } else if (!sr.get("score").isNumber()) {
                    errors.add(err(TYPE_MISMATCH, srPath + " score 必須是數值"));
                }

                // condition 驗證
                if (!sr.has("condition") || sr.get("condition").isNull()) {
                    errors.add(err(MISSING_FIELD, srPath + " 缺少 condition"));
                } else {
                    JsonNode cond = sr.get("condition");
                    if (!cond.has("operator") || cond.get("operator").asText().isBlank()) {
                        errors.add(err(MISSING_FIELD, srPath + ".condition 缺少 operator"));
                    } else if (!Operators.ALL.contains(cond.get("operator").asText())) {
                        errors.add(err(UNKNOWN_OPERATOR, srPath + ".condition operator \"" + cond.get("operator").asText() + "\" 不合法"));
                    }
                }
            }
        }

        // Phase 4: 驗證 scoreBands
        JsonNode bands = rule.get("scoreBands");
        List<int[]> bandRanges = new ArrayList<>();

        for (int i = 0; i < bands.size(); i++) {
            JsonNode band = bands.get(i);
            String bPath = "scoreBands[" + i + "]";

            if (!band.has("minScore") || !band.get("minScore").isNumber()) {
                errors.add(err(MISSING_FIELD, bPath + " 缺少 minScore 或格式不正確"));
            }
            if (!band.has("maxScore") || !band.get("maxScore").isNumber()) {
                errors.add(err(MISSING_FIELD, bPath + " 缺少 maxScore 或格式不正確"));
            }

            if (band.has("minScore") && band.has("maxScore")
                    && band.get("minScore").isNumber() && band.get("maxScore").isNumber()) {
                int min = band.get("minScore").asInt();
                int max = band.get("maxScore").asInt();

                if (min > max) {
                    errors.add(err(TYPE_MISMATCH, bPath + " minScore(" + min + ") > maxScore(" + max + ")"));
                }
                bandRanges.add(new int[]{min, max});
            }

            // results 完整性
            if (!band.has("results") || !band.get("results").isArray() || band.get("results").isEmpty()) {
                errors.add(err(MISSING_RESULTS, bPath + " 缺少 results"));
            } else {
                Set<String> coveredOutputs = new HashSet<>();
                for (JsonNode res : band.get("results")) {
                    if (res.has("field")) coveredOutputs.add(res.get("field").asText());
                }
                for (String outputName : outputNames) {
                    if (!coveredOutputs.contains(outputName)) {
                        errors.add(err(MISSING_RESULTS, bPath + " 缺少 output 欄位 \"" + outputName + "\" 的結果"));
                    }
                }
            }
        }

        // Phase 5: 區間重疊偵測
        bandRanges.sort(Comparator.comparingInt(a -> a[0]));
        for (int i = 1; i < bandRanges.size(); i++) {
            if (bandRanges.get(i)[0] <= bandRanges.get(i - 1)[1]) {
                errors.add(err(INCONSISTENT_TABLE,
                        "scoreBands 區間重疊：[" + bandRanges.get(i - 1)[0] + "," + bandRanges.get(i - 1)[1]
                                + "] 與 [" + bandRanges.get(i)[0] + "," + bandRanges.get(i)[1] + "]"));
            }
        }

        log.info("ScoreCard 驗證完成：{} 個錯誤", errors.size());
        return errors;
    }

    @Override
    public List<ValidationError> checkConsistency(JsonNode payload) {
        return validate(payload);
    }

    private ValidationError err(String code, String message) {
        return ValidationError.builder().code(code).message(message).build();
    }
}
