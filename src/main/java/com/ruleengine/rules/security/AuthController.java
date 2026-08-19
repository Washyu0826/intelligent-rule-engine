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

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, String tokenType, Instant expiresAt,
                                String username, List<String> roles) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            JwtService.IssuedToken issued = jwtService.issue(auth);
            log.info("LOGIN OK | user={} | roles={}", issued.username(), issued.roles());
            return ResponseEntity.ok(new LoginResponse(
                    issued.token(), "Bearer", issued.expiresAt(), issued.username(), issued.roles()));
        } catch (AuthenticationException e) {
            // 統一文案防帳號枚舉；細節只進 server log
            log.warn("LOGIN FAIL | user={} | reason={}", request.username(), e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "UNAUTHORIZED", "message", "帳號或密碼錯誤"));
        }
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
