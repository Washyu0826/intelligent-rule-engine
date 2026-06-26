package com.ruleengine.rules.domain.extensions;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * FieldOrSpec — 欄位級 OR 群組宣告（v3.14）。
 *
 * 用途：表達「兩個或更多 input 欄位中任一個滿足 predicate 即視為命中」。
 * 範例：[新契約繳費管道] 或 [續期繳費管道] 為 [指定帳戶轉帳]
 *      → fields=[newContractPaymentMethod, renewalPaymentMethod]
 *        predicate.operator=equals, predicate.value="指定帳戶轉帳"
 *
 * 設計重點：
 *   - predicate 命名刻意「不」用 value，避免 EnvelopeNormalizer.unwrapNestedValue
 *     在看到單鍵 {"value": ...} 時誤判（M1 risk register #2）。
 *   - predicate 重用 RuleEnvelope.Condition，繼承既有 operator 與 valueRef 能力。
 *   - appliesToRuleIds 為空或 null 時表示整張表生效。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldOrSpec {

    /** OR 群組 ID（FOR01, FOR02, ...）。 */
    private String orId;

    /** 參與 OR 的 input 欄位名稱（≥2 個）。 */
    private List<String> fields;

    /**
     * OR 群組所要比對的判定 — 同一個 operator 套用到 fields 中任一欄位即可命中。
     * 重用 RuleEnvelope.Condition，但 field 欄位於此忽略（fields 清單已涵蓋）。
     *
     * 範例：operator="equals", value="指定帳戶轉帳" 表示
     *   [newContractPaymentMethod 為 "指定帳戶轉帳"] OR [renewalPaymentMethod 為 "指定帳戶轉帳"]
     */
    private RuleEnvelope.Condition predicate;

    /**
     * 該 OR 在哪幾個 ruleId 中啟用；若為 null 或空，表示對整張表生效。
     */
    private List<String> appliesToRuleIds;
}
