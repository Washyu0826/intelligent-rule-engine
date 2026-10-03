package com.ruleengine.rules.service.rulestore;

import com.ruleengine.rules.persistence.rulestore.RuleStatus;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import com.ruleengine.rules.persistence.rulestore.RuleVersionRepository;
import com.ruleengine.rules.service.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * maker-checker 審核狀態機（P2-S4）—— 狀態轉移的唯一裁判。
 *
 * <p>
 * 職責邊界：{@link RuleStoreService} 管版本的誕生與讀取，本類管狀態的變遷。
 * 每個轉移做四件事：①查轉移表（{@link RuleStatus#canTransitionTo}）
 * ②驗身分規則（誰可以做這件事）③改狀態+記歸因欄位 ④落稽核事件。
 * </p>
 *
 * <p>
 * <b>maker ≠ checker（四眼原則的硬檢查）：</b>核准/退回時強制
 * {@code reviewer != submittedBy} —— 即使某帳號同時有 MAKER 與 CHECKER 角色
 * （如 admin），也不能審自己送的件。角色（@PreAuthorize）管「這類人能不能做這類事」，
 * 本檢查管「這個人能不能審這一件」—— 兩層缺一不可。
 * </p>
 *
 * <p>
 * <b>activate 的原子性讓位：</b>新版生效與舊版退役在同一交易 ——
 * 中途失敗全回滾，不存在「兩個 ACTIVE」或「零個 ACTIVE」的中間態外洩；
 * 資料庫的部分唯一索引（單一 ACTIVE）是最後兜底。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewWorkflowService {

    private final RuleVersionRepository repository;
    private final AuditService auditService;

    /** 狀態機違規（非法轉移、身分規則違反）—— 呼叫端應轉 409/403。 */
    public static class WorkflowViolationException extends RuntimeException {
        public WorkflowViolationException(String message) { super(message); }
    }

    public static class VersionNotFoundException extends RuntimeException {
        public VersionNotFoundException(Long id) { super("規則版本 " + id + " 不存在"); }
        public VersionNotFoundException(String message) { super(message); }
    }

    // ================================================================
    // 轉移操作（maker 側）
    // ================================================================

    /** DRAFT → REVIEW：maker 送審。 */
    @Transactional
    public RuleVersionEntity submit(Long versionId, String maker) {
        RuleVersionEntity v = transition(versionId, RuleStatus.REVIEW, maker, "SUBMIT", null);
        v.setSubmittedBy(maker);
        v.setSubmittedAt(OffsetDateTime.now());
        return repository.saveAndFlush(v);
    }

    /** REVIEW → DRAFT：maker 撤回（可逆性）。僅原送審人或 ADMIN 隱含於 controller 角色檢查。 */
    @Transactional
    public RuleVersionEntity withdraw(Long versionId, String maker) {
        RuleVersionEntity v = load(versionId);
        if (v.getSubmittedBy() != null && !v.getSubmittedBy().equals(maker)) {
            throw new WorkflowViolationException(
                    "只有原送審人（" + v.getSubmittedBy() + "）可以撤回這個版本");
        }
        return transitionAndSave(v, RuleStatus.DRAFT, maker, "WITHDRAW", null);
    }

    /** REJECTED → DRAFT：maker 改後重送前的重置（可逆性）。 */
    @Transactional
    public RuleVersionEntity revise(Long versionId, String maker) {
        return transitionAndSave(load(versionId), RuleStatus.DRAFT, maker, "REVISE", null);
    }

    // ================================================================
    // 轉移操作（checker 側）
    // ================================================================

    /** REVIEW → APPROVED：checker 核准。強制 checker ≠ 送審人。 */
    @Transactional
    public RuleVersionEntity approve(Long versionId, String checker, String comment) {
        RuleVersionEntity v = load(versionId);
        assertNotSelfReview(v, checker);
        v.setReviewedBy(checker);
        v.setReviewedAt(OffsetDateTime.now());
        v.setReviewComment(comment);
        return transitionAndSave(v, RuleStatus.APPROVED, checker, "APPROVE", comment);
    }

    /** REVIEW → REJECTED：checker 退回。強制 checker ≠ 送審人；退回必須給理由。 */
    @Transactional
    public RuleVersionEntity reject(Long versionId, String checker, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new WorkflowViolationException("退回必須附審核意見 —— maker 要據此修改");
        }
        RuleVersionEntity v = load(versionId);
        assertNotSelfReview(v, checker);
        v.setReviewedBy(checker);
        v.setReviewedAt(OffsetDateTime.now());
        v.setReviewComment(comment);
        return transitionAndSave(v, RuleStatus.REJECTED, checker, "REJECT", comment);
    }

    /**
     * APPROVED → ACTIVE：生效。同一交易內把同 key 的舊 ACTIVE 轉 RETIRED（原子性讓位）。
     */
    @Transactional
    public RuleVersionEntity activate(Long versionId, String checker) {
        RuleVersionEntity v = load(versionId);
        // 先讓位再生效 —— 順序反過來會先撞單一 ACTIVE 的部分唯一索引
        repository.findByRuleKeyAndStatus(v.getRuleKey(), RuleStatus.ACTIVE)
                .ifPresent(old -> {
                    transitionAndSave(old, RuleStatus.RETIRED, checker, "SUPERSEDE",
                            "由 v" + v.getVersionNo() + " 取代");
                    log.info("WORKFLOW supersede | key={} v{} RETIRED（讓位給 v{}）",
                            old.getRuleKey(), old.getVersionNo(), v.getVersionNo());
                });
        return transitionAndSave(v, RuleStatus.ACTIVE, checker, "ACTIVATE", null);
    }

    /** ACTIVE → RETIRED：管理性下架（不由新版取代的主動退役）。 */
    @Transactional
    public RuleVersionEntity retire(Long versionId, String admin, String reason) {
        return transitionAndSave(load(versionId), RuleStatus.RETIRED, admin, "RETIRE", reason);
    }

    // ================================================================
    // 查詢
    // ================================================================

    /** 審核工作台的待審佇列（舊的先審）。 */
    @Transactional(readOnly = true)
    public List<RuleVersionEntity> reviewQueue() {
        return repository.findByStatusOrderByUpdatedAtAsc(RuleStatus.REVIEW);
    }

    // ================================================================
    // 內部
    // ================================================================

    private RuleVersionEntity load(Long id) {
        return repository.findById(id).orElseThrow(() -> new VersionNotFoundException(id));
    }

    private void assertNotSelfReview(RuleVersionEntity v, String reviewer) {
        if (v.getSubmittedBy() != null && v.getSubmittedBy().equals(reviewer)) {
            throw new WorkflowViolationException(
                    "四眼原則：送審人（" + reviewer + "）不可審核自己送的版本 —— 請由其他 CHECKER 處理");
        }
    }

    /** 查轉移表 → 改狀態 → 落稽核。所有轉移的共同路徑。 */
    private RuleVersionEntity transition(Long versionId, RuleStatus target,
                                         String actor, String operation, String comment) {
        return doTransition(load(versionId), target, actor, operation, comment);
    }

    private RuleVersionEntity transitionAndSave(RuleVersionEntity v, RuleStatus target,
                                                String actor, String operation, String comment) {
        doTransition(v, target, actor, operation, comment);
        return repository.saveAndFlush(v);
    }

    private RuleVersionEntity doTransition(RuleVersionEntity v, RuleStatus target,
                                           String actor, String operation, String comment) {
        RuleStatus from = v.getStatus();
        if (!from.canTransitionTo(target)) {
            throw new WorkflowViolationException(
                    "非法狀態轉移：" + from + " → " + target
                            + "（" + from + " 只能轉移到 " + from.allowedTargets() + "）");
        }
        v.setStatus(target);
        v.setUpdatedAt(OffsetDateTime.now());
        auditService.recordEvent(operation, actor,
                "%s | key=%s v%d | %s→%s%s".formatted(
                        operation, v.getRuleKey(), v.getVersionNo(), from, target,
                        comment != null ? " | " + comment : ""),
                true);
        log.info("WORKFLOW {} | key={} v{} | {}→{} | by={}",
                operation, v.getRuleKey(), v.getVersionNo(), from, target, actor);
        return v;
    }
}
