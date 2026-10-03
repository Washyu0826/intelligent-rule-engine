package com.ruleengine.rules.controller;

import com.ruleengine.rules.service.rulestore.ReviewWorkflowService;
import com.ruleengine.rules.service.rulestore.RuleChainService;
import com.ruleengine.rules.service.rulestore.RuleStoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 線性規則串接：
 * <pre>
 *   GET    /rules/chains                → 全部流程
 *   PUT    /rules/chains/{key}          → 建立／更新（MAKER／ADMIN）
 *   DELETE /rules/chains/{key}          → 刪除（ADMIN）
 *   POST   /engine/chain/{key}/execute  → 逐步執行各規則的 ACTIVE 版
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class RuleChainController {

    private final RuleChainService chains;

    public record SaveChainRequest(String name, @NotEmpty List<RuleChainService.Step> steps) {}

    public record ExecuteChainRequest(Map<String, Object> input) {}

    @GetMapping("/rules/chains")
    public List<RuleChainService.ChainView> list() {
        return chains.list();
    }

    @GetMapping("/rules/chains/{key}")
    public RuleChainService.ChainView get(@PathVariable String key) {
        return chains.get(key);
    }

    @PutMapping("/rules/chains/{key}")
    @PreAuthorize("hasAnyRole('MAKER','ADMIN')")
    public RuleChainService.ChainView save(@PathVariable String key,
                                           @Valid @RequestBody SaveChainRequest request,
                                           Authentication auth) {
        return chains.save(key, request.name(), request.steps(), auth.getName());
    }

    @DeleteMapping("/rules/chains/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        chains.delete(key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/engine/chain/{key}/execute")
    public RuleChainService.ChainOutcome execute(@PathVariable String key,
                                                 @RequestBody ExecuteChainRequest request) {
        return chains.execute(key, request.input() == null ? Map.of() : request.input());
    }

    @ExceptionHandler(ReviewWorkflowService.VersionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ReviewWorkflowService.VersionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(RuleStoreService.RuleStoreException.class)
    public ResponseEntity<Map<String, Object>> handleBad(RuleStoreService.RuleStoreException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
