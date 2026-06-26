package com.ruleengine.rules.service.optimizer.v2;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * OptimizeConfigV2 — DecisionTree v2 optimizer 的可調參數。
 *
 * <p>所有欄位皆有預設值；API 呼叫方可只指定想調的子集（其他欄位由 {@link #defaults()} 補齊）。
 *
 * <p>主要參數：
 * <ul>
 *   <li><b>leafWeight (λ)</b>：每多 1 葉節點的 objective 懲罰，預設 0.01</li>
 *   <li><b>depthWeight (μ)</b>：每多 1 層深度的 objective 懲罰，預設 0.005</li>
 *   <li><b>coverageFloor</b>：coverage 硬下限，低於即拒絕該 pass 的變動，預設 0.95</li>
 *   <li><b>maxReconstructDepth</b>：Pass 6 局部重構容許的最大子樹深度，預設 4</li>
 *   <li><b>enablePass4/5/6</b>：個別 pass 開關（除錯 / A-B 比較用）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OptimizeConfigV2 {

    /** 每多 1 葉的 objective 懲罰 λ */
    private Double leafWeight;

    /** 每多 1 層深度的 objective 懲罰 μ */
    private Double depthWeight;

    /** coverage 硬下限，低於即拒絕 */
    private Double coverageFloor;

    /** Pass 6 局部重構的最大子樹深度 */
    private Integer maxReconstructDepth;

    /** Pass 4：跨位置同構子樹去重 */
    private Boolean enablePass4;

    /** Pass 5：祖先蘊含的冗餘條件消除 */
    private Boolean enablePass5;

    /** Pass 6：局部重構（借用 ID3 重構小子樹） */
    private Boolean enablePass6;

    /**
     * 預設設定：GOSDT 風格參數，略偏向稀疏（但保護 coverage）。
     */
    public static OptimizeConfigV2 defaults() {
        return OptimizeConfigV2.builder()
                .leafWeight(0.01)
                .depthWeight(0.005)
                .coverageFloor(0.95)
                .maxReconstructDepth(4)
                .enablePass4(true)
                .enablePass5(true)
                .enablePass6(true)
                .build();
    }

    /**
     * 合併使用者傳入與預設：null 值由預設補齊，不修改原物件。
     */
    public OptimizeConfigV2 withDefaults() {
        OptimizeConfigV2 d = defaults();
        return OptimizeConfigV2.builder()
                .leafWeight(leafWeight == null ? d.leafWeight : leafWeight)
                .depthWeight(depthWeight == null ? d.depthWeight : depthWeight)
                .coverageFloor(coverageFloor == null ? d.coverageFloor : coverageFloor)
                .maxReconstructDepth(maxReconstructDepth == null ? d.maxReconstructDepth : maxReconstructDepth)
                .enablePass4(enablePass4 == null ? d.enablePass4 : enablePass4)
                .enablePass5(enablePass5 == null ? d.enablePass5 : enablePass5)
                .enablePass6(enablePass6 == null ? d.enablePass6 : enablePass6)
                .build();
    }
}
