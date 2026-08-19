package com.ruleengine.rules.service.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 決策軌跡（P2-S2）—— 一次規則執行的完整可回放紀錄。
 *
 * <p>
 * 結構取法 GoRules ZEN 的 per-node trace（每步的 input/output/耗時），
 * 並補上金融回放三要素（文獻調查結論）：
 * <b>①不可變的規則版本</b>（{@code ruleVersionId}，S3 落庫時填）
 * <b>②當時的輸入快照</b>（{@code inputSnapshot}）
 * <b>③引擎版本</b>（{@code engineVersion}）—— 三者齊備才能宣稱
 * 「重放這筆決策會得到 bit 級相同的結果」。
 * </p>
 *
 * <p>
 * 全部欄位不可變（@Value + List.copyOf）—— trace 是證據，不是工作區。
 * </p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecisionTrace {

    /** 引擎版本（回放三要素③）：引擎語意改變時 bump，舊 trace 註明由哪版產生。 */
    String engineVersion;

    /** 落庫後回填的規則版本 id（回放三要素①；S2 階段直接執行 envelope 時為 null）。 */
    Long ruleVersionId;

    String ruleType;

    /** DecisionTable 專用：FIRST / MULTI。 */
    String hitPolicy;

    /** 輸入快照（回放三要素②）—— 執行當下的完整輸入，防呼叫端事後改 map。 */
    Map<String, Object> inputSnapshot;

    /** 逐規則（table）/ 逐節點（tree）的評估紀錄；SUMMARY 級只含命中者。 */
    List<StepTrace> steps;

    List<String> matchedRuleIds;

    long totalNanos;

    TraceLevel level;

    /** 單一規則列（table）或單一節點（tree）的評估紀錄。 */
    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StepTrace {
        /** RuleRow.ruleId 或 TreeNode.nodeId。 */
        String id;
        /** table：規則 priority；tree：節點深度。 */
        Integer order;
        boolean matched;
        /** FULL 級：逐條件的比對明細。 */
        List<ConditionTrace> conditions;
        /** tree 專用：實際走的分支 label。 */
        String branchTaken;
        long elapsedNanos;
    }

    /** 單一條件的比對明細 —— 「為什麼命中/不命中」的最小解釋單位。 */
    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConditionTrace {
        String field;
        String operator;
        /** 解析後的期望值（valueRef 已展開 —— 回放時能看到當時 $today 解析成什麼）。 */
        Object expected;
        /** 輸入中的實際值。 */
        Object actual;
        boolean matched;
    }
}
