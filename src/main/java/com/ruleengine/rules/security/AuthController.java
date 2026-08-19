package com.ruleengine.rules.security;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 登入與身分查詢（P1-S4）。
 *
 * <p>
 * 登入失敗一律回同一句「帳號或密碼錯誤」—— 不區分「查無帳號」與「密碼錯」，
 * 避免帳號枚舉（攻擊者用回應差異探測哪些帳號存在）。
 * </p>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;
    private final com.ruleengine.rules.service.audit.AuditService auditService;
    private final io.github.resilience4j.ratelimiter.RateLimiterRegistry rateLimiterRegistry;

    /**
     * 顯式限流而非 @RateLimiter 註解 —— 真機驗證發現註解 AOP 未生效
     * （12 次連續登入無一 429）。安全關鍵路徑不留魔法：手動 acquirePermission，
     * 行為看得見、測得到。（既有 /tools 端點的註解式限流是否同病，記入 backlog 查證。）
     */
    private io.github.resilience4j.ratelimiter.RateLimiter loginLimiter() {
        // 必須雙參指名 config：Registry.of(map) 的 map 是「config 目錄」，
        // 單參 rateLimiter("login") 會用 ofDefaults()（50 permits/500ns = 形同無限流）——
        // 這正是專案既有 /tools 限流從未觸發過的根因（記 backlog 修復）
        return rateLimiterRegistry.rateLimiter("login", "login");
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, String tokenType, Instant expiresAt,
                                String username, List<String> roles) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        // 全域限流（password spraying 防線）：先於一切檢查 —— 超額直接 429
        if (!loginLimiter().acquirePermission()) {
            log.warn("LOGIN RATE-LIMITED | user={}", request.username());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "TOO_MANY_REQUESTS", "message", "登入嘗試過於頻繁，請稍後再試"));
        }
        // 資安收緊②：鎖定中的帳號直接拒絕，連 BCrypt 都不跑（省掉爆破時的 CPU 放大）。
        // 回應與密碼錯完全相同 —— 鎖定狀態本身也是可被枚舉的資訊。
        if (loginAttempts.isLocked(request.username())) {
            log.warn("LOGIN LOCKED | user={} | 鎖定期間的嘗試被拒", request.username());
            auditService.recordEvent("LOGIN", request.username(), "帳號鎖定期間嘗試登入", false);
            return unauthorized();
        }
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            loginAttempts.recordSuccess(request.username());
            JwtService.IssuedToken issued = jwtService.issue(auth);
            log.info("LOGIN OK | user={} | roles={}", issued.username(), issued.roles());
            // 資安收緊⑥：登入是資安事件，進稽核表（落庫、重啟不失），不只進 log
            auditService.recordEvent("LOGIN", issued.username(), "登入成功", true);
            return ResponseEntity.ok(new LoginResponse(
                    issued.token(), "Bearer", issued.expiresAt(), issued.username(), issued.roles()));
        } catch (AuthenticationException e) {
            boolean nowLocked = loginAttempts.recordFailure(request.username());
            log.warn("LOGIN FAIL | user={} | reason={}{}", request.username(),
                    e.getClass().getSimpleName(), nowLocked ? " | 已觸發鎖定" : "");
            auditService.recordEvent("LOGIN", request.username(),
                    nowLocked ? "登入失敗（觸發鎖定）" : "登入失敗", false);
            return unauthorized();
        }
    }

    private ResponseEntity<?> unauthorized() {
        // 統一文案防帳號枚舉；細節只進 server log 與稽核表
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "UNAUTHORIZED", "message", "帳號或密碼錯誤"));
    }

    /**
     * 身分查詢 —— 也是 enforce 行為的「驗證錨點」：
     * 本端點在 SecurityConfig 中永遠 authenticated，即使全域是 permissive 模式，
     * 401/token 解析/角色還原都可在此端到端驗證。
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("username", authentication.getName());
        body.put("authorities", roles);
        if (authentication instanceof JwtAuthenticationToken jwt) {
            body.put("issuedAt", jwt.getToken().getIssuedAt());
            body.put("expiresAt", jwt.getToken().getExpiresAt());
        }
        return body;
    }
}
