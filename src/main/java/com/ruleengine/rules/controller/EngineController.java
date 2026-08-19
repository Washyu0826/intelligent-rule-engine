package com.ruleengine.rules.controller;

import com.ruleengine.rules.persistence.execution.DecisionTraceEntity;
import com.ruleengine.rules.service.execution.EngineExecutionService;
import com.ruleengine.rules.service.execution.TraceLevel;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 規則引擎執行入口（P2-S3）。
 *
 * <p>路徑取 {@code /engine/**}（非 /tools/**）：tools 是「規則的產製」，
 * engine 是「規則的營運」—— 之後權限也分開（執行給系統整合方，產製給 BA）。</p>
 *
 * <p>executedBy 取自 JWT（permissive 期無 token 時記 anonymous）——
 * 這就是 P1 說的「token 解析永遠開，稽核可歸因」的兌現。</p>
 */
@RestController
@RequestMapping("/engine")
@RequiredArgsConstructor
@Slf4j
public class EngineController {

    private final EngineExecutionService service;

    public record ExecuteRequest(
            @NotBlank String ruleKey,
            Map<String, Object> input,
            /** NONE / SUMMARY / FULL，預設 SUMMARY。 */
            String traceLevel) {}

    @PostMapping("/execute")
    public ResponseEntity<EngineExecutionService.ExecutionOutcome> execute(
            @org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid ExecuteRequest request,
            Authentication authentication) {
        TraceLevel level = parseLevel(request.traceLevel());
        String user = authentication != null ? authentication.getName() : null;
        log.info("POST /engine/execute | key={} | level={} | by={}", request.ruleKey(), level, user);
        return ResponseEntity.ok(service.executeActive(request.ruleKey(), request.input(), level, user));
    }

    @PostMapping("/replay/{traceId}")
    public ResponseEntity<EngineExecutionService.ReplayReport> replay(@PathVariable Long traceId) {
        log.info("POST /engine/replay/{}", traceId);
        return ResponseEntity.ok(service.replay(traceId));
    }

    @GetMapping("/trace/{id}")
    public ResponseEntity<DecisionTraceEntity> trace(@PathVariable Long id) {
        return ResponseEntity.ok(service.getTrace(id));
    }

    @GetMapping("/traces")
    public ResponseEntity<java.util.List<DecisionTraceEntity>> traces(
            @RequestParam(required = false) String ruleKey,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(service.recentTraces(ruleKey, limit));
    }

    private TraceLevel parseLevel(String raw) {
        if (raw == null || raw.isBlank()) return TraceLevel.SUMMARY;
        try {
            return TraceLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new EngineExecutionService.EngineServiceException(
                    "traceLevel 只接受 NONE / SUMMARY / FULL，收到：" + raw);
        }
    }

    /** 業務性失敗（無 ACTIVE 版、trace 不存在、參數不合法）→ 404/400 而非 500。 */
    @ExceptionHandler(EngineExecutionService.EngineServiceException.class)
    public ResponseEntity<Map<String, Object>> handleEngineError(
            EngineExecutionService.EngineServiceException e) {
        boolean notFound = e.getMessage() != null && e.getMessage().contains("不存在");
        return ResponseEntity.status(notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST)
                .body(Map.of("error", notFound ? "NOT_FOUND" : "BAD_REQUEST",
                        "message", e.getMessage()));
    }
}
