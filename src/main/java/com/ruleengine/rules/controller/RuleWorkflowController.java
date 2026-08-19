package com.ruleengine.rules.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import com.ruleengine.rules.service.rulestore.ReviewWorkflowService;
import com.ruleengine.rules.service.rulestore.RuleStoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 規則治理入口（P2-S4）：maker-checker 審核工作流的 REST 介面。
 *
 * <p>
 * 授權兩層：路徑層（SecurityConfig：/rules/** 一律 authenticated，新端點新規矩）
 * + 方法層（@PreAuthorize：MAKER 才能送審、CHECKER 才能核准 ——
 * P1-S4 埋的 @EnableMethodSecurity 在這裡兌現）。
 * 「同人不可自審自核」是第三層，在 ReviewWorkflowService 裡按「件」檢查。
 * </p>
 */
@RestController
@RequestMapping("/rules")
@RequiredArgsConstructor
@Slf4j
public class RuleWorkflowController {

    private final RuleStoreService store;
    private final ReviewWorkflowService workflow;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ================================================================
    // 版本建立與查詢
    // ================================================================

    public record CreateDraftRequest(@NotBlank String ruleKey, @NotNull JsonNode envelope) {}

    @PostMapping
    @PreAuthorize("hasRole('MAKER')")
    public ResponseEntity<VersionView> createDraft(@Valid @RequestBody CreateDraftRequest request,
                                                   Authentication auth) {
        RuleEnvelope envelope;
        try {
            envelope = objectMapper.treeToValue(request.envelope(), RuleEnvelope.class);
        } catch (Exception e) {
            throw new RuleStoreService.RuleStoreException("envelope 不是合法的 RuleEnvelope JSON：" + e.getMessage());
        }
        var v = store.createDraft(request.ruleKey(), envelope, auth.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(VersionView.of(v));
    }

    @GetMapping("/keys")
    public List<String> keys() {
        return store.allRuleKeys();
    }

    @GetMapping("/{ruleKey}/history")
    public List<VersionView> history(@PathVariable String ruleKey) {
        return store.history(ruleKey).stream().map(VersionView::of).toList();
    }

    @GetMapping("/review-queue")
    @PreAuthorize("hasRole('CHECKER')")
    public List<VersionView> reviewQueue() {
        return workflow.reviewQueue().stream().map(VersionView::of).toList();
    }

    // ================================================================
    // 狀態轉移（maker 側）
    // ================================================================

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('MAKER')")
    public VersionView submit(@PathVariable Long id, Authentication auth) {
        return VersionView.of(workflow.submit(id, auth.getName()));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasRole('MAKER')")
    public VersionView withdraw(@PathVariable Long id, Authentication auth) {
        return VersionView.of(workflow.withdraw(id, auth.getName()));
    }

    @PostMapping("/{id}/revise")
    @PreAuthorize("hasRole('MAKER')")
    public VersionView revise(@PathVariable Long id, Authentication auth) {
        return VersionView.of(workflow.revise(id, auth.getName()));
    }

    // ================================================================
    // 狀態轉移（checker 側）
    // ================================================================

    public record ReviewRequest(String comment) {}

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('CHECKER')")
    public VersionView approve(@PathVariable Long id,
                               @RequestBody(required = false) ReviewRequest request,
                               Authentication auth) {
        return VersionView.of(workflow.approve(id, auth.getName(),
                request != null ? request.comment() : null));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('CHECKER')")
    public VersionView reject(@PathVariable Long id,
                              @RequestBody ReviewRequest request,
                              Authentication auth) {
        return VersionView.of(workflow.reject(id, auth.getName(), request.comment()));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('CHECKER')")
    public VersionView activate(@PathVariable Long id, Authentication auth) {
        return VersionView.of(workflow.activate(id, auth.getName()));
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize("hasRole('ADMIN')")
    public VersionView retire(@PathVariable Long id,
                              @RequestBody(required = false) ReviewRequest request,
                              Authentication auth) {
        return VersionView.of(workflow.retire(id, auth.getName(),
                request != null ? request.comment() : null));
    }

    // ================================================================
    // 回應模型（不直接曝 entity —— envelope 完整 JSON 太肥，摘要即可；
    // 需要完整內容走 /rules/{key}/history 的單版查詢，之後 UI 需要時再加）
    // ================================================================

    public record VersionView(Long id, String ruleKey, int versionNo, String ruleType,
                              String status, Long previousVersionId,
                              String createdBy, String submittedBy, String reviewedBy,
                              String reviewComment, OffsetDateTime updatedAt) {
        static VersionView of(RuleVersionEntity v) {
            return new VersionView(v.getId(), v.getRuleKey(), v.getVersionNo(), v.getRuleType(),
                    v.getStatus().name(), v.getPreviousVersionId(),
                    v.getCreatedBy(), v.getSubmittedBy(), v.getReviewedBy(),
                    v.getReviewComment(), v.getUpdatedAt());
        }
    }

    // ================================================================
    // 錯誤映射：狀態機違規 409（衝突）、找不到 404、格式錯 400
    // ================================================================

    @ExceptionHandler(ReviewWorkflowService.WorkflowViolationException.class)
    public ResponseEntity<Map<String, Object>> handleViolation(
            ReviewWorkflowService.WorkflowViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "WORKFLOW_VIOLATION", "message", e.getMessage()));
    }

    @ExceptionHandler(ReviewWorkflowService.VersionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            ReviewWorkflowService.VersionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(RuleStoreService.RuleStoreException.class)
    public ResponseEntity<Map<String, Object>> handleStoreError(RuleStoreService.RuleStoreException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
