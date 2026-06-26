package com.ruleengine.rules.domain.extensions;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RuleStatus — 單一規則的生命週期狀態（v3.14）。
 *
 * 用途：追蹤每條 rule 的狀態（ACTIVE / DRAFT / RETIRED），
 * 讓 executor 跳過退役規則，同時保留供稽核。
 * 對應 sample B 母規格中的「整段程式已移除」標記。
 *
 * 設計重點：
 *   - status 採 Java enum，Jackson 預設序列化為 enum 常數名稱（"ACTIVE" / "DRAFT" / "RETIRED"），
 *     無需客製化 (de)serializer。
 *   - since / reason 為可選稽核欄位。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleStatus {

    /** 規則 ID（必填，必須對應 rule.rules[].ruleId）。 */
    private String ruleId;

    /** 狀態：ACTIVE | DRAFT | RETIRED；缺值預設 ACTIVE。 */
    private Lifecycle status;

    /** 狀態變更日期（ISO-8601 yyyy-MM-dd，可選）。 */
    private String since;

    /** 狀態變更原因（例：「業務需求廢止」），可選。 */
    private String reason;

    public enum Lifecycle {
        /** 正式生效 */
        ACTIVE,
        /** 草稿，executor 不執行 */
        DRAFT,
        /** 退役，executor 不執行，但保留於 envelope 供稽核 */
        RETIRED
    }
}
