package com.ruleengine.rules.persistence.rulestore;

import java.util.Map;
import java.util.Set;

/**
 * 規則版本生命週期狀態（P2-S1；與 V1 migration 的 CHECK constraint 對齊）。
 *
 * <p>
 * 轉移圖（maker-checker，含文獻調查補強的「可逆性」轉移）：
 * </p>
 * <pre>
 *   DRAFT ──submit──► REVIEW ──approve──► APPROVED ──activate──► ACTIVE ──retire──► RETIRED
 *     ▲                 │ │                                        │
 *     │◄───withdraw─────┘ └──reject──► REJECTED ──revise──► DRAFT  │
 *     └──────────────────── (新版本 supersede 舊 ACTIVE) ◄─────────┘
 * </pre>
 *
 * <p>
 * 合法轉移集中定義在這裡（enum 自持），狀態機服務（P2-S4）只查表不寫 if ——
 * 新增轉移改一處。DB 層 CHECK 只擋非法「值」，轉移的合法性由應用層強制。
 * </p>
 */
public enum RuleStatus {

    DRAFT,
    REVIEW,
    APPROVED,
    ACTIVE,
    RETIRED,
    REJECTED;

    /** 合法轉移表：from → 允許的 to 集合。 */
    private static final Map<RuleStatus, Set<RuleStatus>> ALLOWED = Map.of(
            DRAFT,    Set.of(REVIEW),
            REVIEW,   Set.of(APPROVED, REJECTED, DRAFT),   // DRAFT = maker 撤回（可逆性）
            APPROVED, Set.of(ACTIVE, REVIEW),              // REVIEW = 生效前發現問題退回重審
            ACTIVE,   Set.of(RETIRED),
            REJECTED, Set.of(DRAFT),                       // 改後重送（可逆性）
            RETIRED,  Set.of()                             // 終態：退役不可復活，要用新版本
    );

    public boolean canTransitionTo(RuleStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public Set<RuleStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Set.of());
    }
}
