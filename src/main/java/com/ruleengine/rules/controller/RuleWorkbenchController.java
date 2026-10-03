package com.ruleengine.rules.controller;

import com.ruleengine.rules.service.rulestore.ReviewWorkflowService;
import com.ruleengine.rules.service.rulestore.RuleStoreService;
import com.ruleengine.rules.service.rulestore.WorkbenchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 審核工作台的讀寫入口：目錄與標籤、審核單、AI 改規則建議。
 * 路徑在 /rules/** 之下，沿用 SecurityConfig 的「一律要 token」；角色由 @PreAuthorize 管。
 * 狀態轉移（送審、核准⋯）仍在 {@link RuleWorkflowController}。
 */
@RestController
@RequestMapping("/rules")
@RequiredArgsConstructor
public class RuleWorkbenchController {

    private final WorkbenchService workbench;

    /** 目錄樹與標籤；query 參數的 key 為標籤維度、value 為標籤，例如 {@code ?分類=車險}。 */
    @GetMapping("/tree")
    public WorkbenchService.TreeView tree(@RequestParam Map<String, String> tagFilter) {
        return workbench.tree(tagFilter);
    }

    public record PlacementRequest(String path, Map<String, List<String>> tags) {}

    @PutMapping("/{ruleKey}/placement")
    @PreAuthorize("hasAnyRole('MAKER','ADMIN')")
    public WorkbenchService.TreeEntry place(@PathVariable String ruleKey,
                                            @RequestBody PlacementRequest request,
                                            Authentication auth) {
        return workbench.place(ruleKey, request.path(), request.tags(), auth.getName());
    }

    @GetMapping("/{id}/review-sheet")
    public WorkbenchService.ReviewSheet reviewSheet(@PathVariable Long id) {
        return workbench.reviewSheet(id);
    }

    public record SuggestChangeRequest(@NotBlank String instruction) {}

    @PostMapping("/{id}/suggest-change")
    @PreAuthorize("hasRole('MAKER')")
    public WorkbenchService.ChangeSuggestion suggestChange(@PathVariable Long id,
                                                           @Valid @RequestBody SuggestChangeRequest request) {
        return workbench.suggestChange(id, request.instruction());
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
