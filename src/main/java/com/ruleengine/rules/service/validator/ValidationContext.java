package com.ruleengine.rules.service.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.*;

/**
 * 驗證上下文 — 跨層共享的解析結果。
 *
 * 由 Layer 2（FieldDefinitionValidator）解析欄位定義後填入，
 * 供 Layer 3（RuleSemanticValidator）和 Layer 4（ConsistencyValidator）使用。
 */
@Data
public class ValidationContext {

    /** 是否應該繼續驗證（Layer 1 發現嚴重結構問題時設為 false） */
    private boolean shouldContinue = true;

    /** rule 節點（由 Layer 1 解析後設定） */
    private JsonNode ruleNode;

    /** hitPolicy（由 Layer 1 解析後設定） */
    private String hitPolicy = "";

    // ===== Layer 2 填入 =====

    /** input 欄位名稱 → typeRef */
    private Map<String, String> inputTypes = new LinkedHashMap<>();

    /** input 欄位名稱 → allowedValues（僅 ENUM） */
    private Map<String, List<String>> inputAllowed = new LinkedHashMap<>();

    /** output 欄位名稱 → typeRef */
    private Map<String, String> outputTypes = new LinkedHashMap<>();

    /** output 欄位名稱 → allowedValues（僅 ENUM） */
    private Map<String, List<String>> outputAllowed = new LinkedHashMap<>();

    // ===== Layer 3 填入 =====

    /** 解析後的規則（用於 Layer 4 衝突偵測） */
    private List<ParsedRule> parsedRules = new ArrayList<>();

    /** 被引用的 input 欄位名稱集合 */
    private Set<String> referencedInputs = new HashSet<>();

    // ================================================================
    // 內部資料結構
    // ================================================================

    /** 條件資訊（用於衝突偵測） */
    public record ConditionInfo(String field, String operator, JsonNode value) {}

    /** 解析後的規則行（ruleId + 條件映射） */
    public record ParsedRule(String ruleId, Map<String, ConditionInfo> conditions) {}
}
