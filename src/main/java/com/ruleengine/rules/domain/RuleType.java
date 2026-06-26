package com.ruleengine.rules.domain;

/**
 * 規則型態光譜（計畫書 §3.1）
 * MVP 必做：DECISION_TABLE, DECISION_TREE
 * 彈性目標：SCORE_CARD
 * 未來擴展：LOOKUP_TABLE, RULE_SET_CHAIN, CEP
 */
public enum RuleType {

    DECISION_TABLE("DecisionTable", "決策表", "多條件平行組合→單一結果，條件之間無先後依賴"),
    DECISION_TREE("DecisionTree", "決策樹", "條件有層級/先後依賴的分支判斷，「先看A，再看B」的邏輯"),
    SCORE_CARD("ScoreCard", "評分卡", "各條件獨立計分→加總後依總分對應結果");

    private final String code;
    private final String label;
    private final String description;

    RuleType(String code, String label, String description) {
        this.code = code;
        this.label = label;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }

    /**
     * 從字串解析 RuleType（大小寫不敏感，支援 code 和 enum name）
     */
    public static RuleType fromString(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        for (RuleType type : values()) {
            if (type.code.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支援的規則型態：" + value
                + "。目前支援：DecisionTable, DecisionTree, ScoreCard");
    }
}
