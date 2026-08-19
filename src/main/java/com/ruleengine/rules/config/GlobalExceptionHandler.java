package com.ruleengine.rules.config;

import com.ruleengine.rules.exception.LlmUnavailableException;
import com.ruleengine.rules.exception.RuleException;
import com.ruleengine.rules.exception.RuleGenerationException;
import com.ruleengine.rules.exception.RuleValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全域例外處理器。
 *
 * 統一錯誤回傳格式：
 * {
 *   "error": "錯誤類型",
 *   "message": "詳細描述",
 *   "timestamp": "2026-03-27T...",
 *   "suggestion": "建議操作"
 * }
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(RuleGenerationException.class)
    public ResponseEntity<Map<String, Object>> handleRuleGeneration(RuleGenerationException e) {
        log.warn("規則生成失敗：{}", e.getMessage());
        return ResponseEntity.unprocessableEntity().body(errorBody(
                "RULE_GENERATION_ERROR",
                e.getMessage(),
                "請檢查輸入描述或 JSON 格式是否正確"));
    }

    @ExceptionHandler(RuleValidationException.class)
    public ResponseEntity<Map<String, Object>> handleRuleValidation(RuleValidationException e) {
        log.warn("規則驗證過程錯誤：{}", e.getMessage());
        return ResponseEntity.unprocessableEntity().body(errorBody(
                "RULE_VALIDATION_ERROR",
                e.getMessage(),
                "請確認 ruleJson 為合法的 RuleEnvelope JSON"));
    }

    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleLlmUnavailable(LlmUnavailableException e) {
        log.warn("LLM 服務不可用：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(
                "LLM_UNAVAILABLE",
                e.getMessage(),
                "LLM 服務暫時不可用，請稍後重試或使用 JSON 直接輸入模式"));
    }

    @ExceptionHandler(RuleException.class)
    public ResponseEntity<Map<String, Object>> handleRuleException(RuleException e) {
        log.warn("規則服務錯誤：{}", e.getMessage());
        return ResponseEntity.badRequest().body(errorBody(
                "RULE_ERROR",
                e.getMessage(),
                "請檢查輸入參數是否正確"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("請求參數驗證失敗：{}", details);
        return ResponseEntity.badRequest().body(errorBody(
                "VALIDATION_ERROR",
                details,
                "請檢查請求參數是否符合格式要求"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        // 完整錯誤寫到 server log，但不暴露給前端避免洩漏內部細節
        log.warn("請求 body 無法解析：{}", e.getMessage());
        return ResponseEntity.badRequest().body(errorBody(
                "INVALID_REQUEST_BODY",
                "請求格式不正確，請提供合法的 JSON",
                "請確認請求 body 為合法的 JSON 格式且符合 API 規格"));
    }

    @ExceptionHandler(io.github.resilience4j.ratelimiter.RequestNotPermitted.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(
            io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
        // 端點未提供 fallbackMethod 時，Resilience4j 限流會拋此例外 → 統一回 429
        log.warn("限流觸發：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(errorBody(
                        "RATE_LIMITED",
                        "請求過於頻繁，請稍後再試",
                        "此端點有每分鐘呼叫上限；請降低呼叫頻率或稍候重試"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(errorBody(
                "BAD_REQUEST",
                e.getMessage(),
                "請檢查輸入參數是否正確"));
    }

    /**
     * 方法層授權失敗（@PreAuthorize）→ 403。
     * 錯誤#9：AccessDeniedException 從 controller 方法拋出會先被本 advice 的萬用
     * Exception handler 攔到變 500 —— security 的例外必須有專屬出口。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody(
                "FORBIDDEN",
                "權限不足：此操作需要的角色與你的帳號不符",
                "請確認登入帳號的角色（制單=MAKER / 審核=CHECKER / 管理=ADMIN）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        // 完整 stack trace 進 server log；對外只回固定文案避免洩漏實作細節
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(
                "INTERNAL_ERROR",
                "系統發生未預期錯誤",
                "請稍後重試；若持續發生請聯絡系統管理員"));
    }

    private Map<String, Object> errorBody(String error, String message, String suggestion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        body.put("suggestion", suggestion);
        return body;
    }
}
