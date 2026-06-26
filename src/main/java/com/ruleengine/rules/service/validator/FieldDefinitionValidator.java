package com.ruleengine.rules.service.validator;

import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.ruleengine.rules.domain.dto.ToolDtos.ErrorCodes.*;

/**
 * Layer 2 — 欄位定義正確性驗證。
 *
 * 檢查 inputs / outputs 的欄位定義：
 *   - name 必填
 *   - typeRef 必填且合法
 *   - ENUM 必須有 allowedValues (ENUM_VALUE_MISSING)
 *   - 欄位名稱不重複 (DUPLICATE_ID)
 *   - inputs 和 outputs 之間名稱不重複 (INCONSISTENT_TABLE)
 *
 * 同時建立 name → typeRef / allowedValues 索引，供後續層使用。
 */
@Component
@Order(2)
public class FieldDefinitionValidator implements ValidationLayer {

    @Override
    public List<ValidationError> validate(JsonNode envelope, ValidationContext context) {
        List<ValidationError> errors = new ArrayList<>();

        if (!context.isShouldContinue()) return errors;

        JsonNode ruleNode = context.getRuleNode();
        JsonNode inputsNode = ruleNode.get("inputs");
        JsonNode outputsNode = ruleNode.get("outputs");

        // 解析欄位定義並填入 context
        parseFieldDefs(inputsNode, "inputs", context.getInputTypes(), context.getInputAllowed(), errors);
        parseFieldDefs(outputsNode, "outputs", context.getOutputTypes(), context.getOutputAllowed(), errors);

        // inputs 和 outputs 之間名稱不能重複
        Set<String> overlappingNames = new LinkedHashSet<>(context.getInputTypes().keySet());
        overlappingNames.retainAll(context.getOutputTypes().keySet());
        for (String name : overlappingNames) {
            errors.add(err(INCONSISTENT_TABLE,
                    "欄位名稱 \"" + name + "\" 同時出現在 inputs 和 outputs 中。"
                            + "input 和 output 的欄位名稱不可重複"));
        }

        return errors;
    }

    private void parseFieldDefs(JsonNode fieldsNode, String section,
                                 Map<String, String> typeMap,
                                 Map<String, List<String>> allowedMap,
                                 List<ValidationError> errors) {
        Set<String> seenNames = new HashSet<>();

        for (int i = 0; i < fieldsNode.size(); i++) {
            JsonNode field = fieldsNode.get(i);
            String label = section + "[" + i + "]";

            // name 必填
            if (!field.has("name") || field.get("name").asText().isBlank()) {
                errors.add(err(MISSING_FIELD, label + " 缺少 name"));
                continue;
            }
            String name = field.get("name").asText();
            label = section + "[" + name + "]";

            // 重複名稱偵測
            if (!seenNames.add(name)) {
                errors.add(err(DUPLICATE_ID,
                        section + " 中欄位名稱 \"" + name + "\" 重複出現。"
                                + "每個欄位名稱在 " + section + " 中只能定義一次"));
                continue;
            }

            // typeRef 必填
            if (!field.has("typeRef") || field.get("typeRef").asText().isBlank()) {
                errors.add(err(MISSING_FIELD, label + " 缺少 typeRef"));
                continue;
            }
            String typeRef = field.get("typeRef").asText();

            // typeRef 合法性
            if (!TypeRefs.ALL.contains(typeRef)) {
                errors.add(err(TYPE_MISMATCH,
                        label + " typeRef \"" + typeRef + "\" 不是合法型別。"
                                + "合法型別：INTEGER, DECIMAL, BOOLEAN, STRING, ENUM, DATE"));
                continue;
            }

            typeMap.put(name, typeRef);

            // ENUM_VALUE_MISSING
            if (TypeRefs.ENUM.equals(typeRef)) {
                if (!field.has("allowedValues")
                        || !field.get("allowedValues").isArray()
                        || field.get("allowedValues").isEmpty()) {
                    errors.add(err(ENUM_VALUE_MISSING,
                            label + " typeRef=ENUM 但缺少 allowedValues（ENUM 必須定義允許的值清單）"));
                } else {
                    allowedMap.put(name, toStringList(field.get("allowedValues")));
                }
            }
        }
    }

    private List<String> toStringList(JsonNode arrayNode) {
        return StreamSupport.stream(arrayNode.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toList());
    }

    private ValidationError err(String code, String message) {
        return ValidationError.builder().code(code).message(message).build();
    }
}
