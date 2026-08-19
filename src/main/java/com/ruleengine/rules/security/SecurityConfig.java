package com.ruleengine.rules.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 設定（P1-S4）：自發 JWT 的 resource-server 模式。
 *
 * <p>
 * <b>架構：</b>登入（{@code /auth/login}）用 DaoAuthenticationProvider + BCrypt
 * 驗帳密 → {@link JwtService} 簽 HS256 JWT；之後每個請求由官方
 * {@code BearerTokenAuthenticationFilter} 驗簽/驗過期並填 SecurityContext ——
 * 不手寫 token filter。
 * </p>
 *
 * <p>
 * <b>兩段式上線（enforce | permissive）：</b>前端登入頁在 P1-S5 才落地，
 * 立刻強制 {@code /tools/**} 會讓現有 UI 全面 401。因此：
 * token 解析<b>永遠開</b>（帶 token 就有身分，稽核 userId 可歸因），
 * 而「無 token 是否放行」由 {@code rules.security.jwt.mode} 控制。
 * {@code /auth/me} 從第一天就強制 —— 作為 enforce 行為的驗證錨點。
 * 這不是權宜：feature-flag 漸進上線正是避免大爆炸切換的標準做法。
 * </p>
 *
 * <p>
 * <b>與既有 X-API-Key 的關係：</b>API key（{@code ApiKeyInterceptor}）是
 * 機器對機器的通道（MCP client / 整合方），JWT 是人的通道（UI 操作者）——
 * 兩者並存、各管各的，interceptor 不動。
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // P2 審核端點將用 @PreAuthorize("hasRole('CHECKER')")
@Slf4j
public class SecurityConfig {

    /** enforce = /tools/** 無 token 回 401；permissive = 放行但仍解析 token（過渡期）。 */
    @Value("${rules.security.jwt.mode:permissive}")
    private String mode;

    /**
     * HS256 secret。HS256 規範要求至少 256 bits（32 bytes）——
     * 太短 Nimbus 會直接拒絕（KeyLengthException），這是好事，不要繞過。
     * dev 預設值僅供本機；prod 由 RULES_JWT_SECRET 注入（application-prod.yml）。
     */
    @Value("${rules.security.jwt.secret:local-dev-only-secret-change-me-0123456789abcdef}")
    private String secret;

    private SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    // ── JWT 編解碼（HS256 對稱；切 RS256 時只動這兩個 bean）──

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey()).build();
    }

    /** roles claim（["MAKER"]）→ ROLE_MAKER authorities，讓 hasRole('MAKER') 直接可用。 */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var granted = new JwtGrantedAuthoritiesConverter();
        granted.setAuthoritiesClaimName(JwtService.ROLES_CLAIM);
        granted.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(granted);
        return converter;
    }

    // ── 帳密驗證（僅 /auth/login 用）──

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(DbUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        // Security 6.4 的單參建構子收 PasswordEncoder（不是 UserDetailsService —— 6.5 才加那個），
        // UserDetailsService 走 setter
        var provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(userDetailsService);
        return provider::authenticate;
    }

    // ── Filter chain ──

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationConverter jwtAuthConverter) throws Exception {
        boolean enforce = "enforce".equalsIgnoreCase(mode);
        log.info("Security 初始化 | jwt.mode={} | /tools/** {}", mode,
                enforce ? "需要 Bearer token" : "過渡期放行（token 仍會被解析）");

        http
            // JWT 是 stateless —— 不建 session、不需要 CSRF token（CSRF 攻擊面來自
            // cookie 自動附帶；Bearer header 不會被瀏覽器自動帶出去）
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth
                    // 登入本身與健康檢查、靜態資源（SPA）、API 文件永遠開放
                    .requestMatchers("/auth/login", "/actuator/health/**", "/health").permitAll()
                    .requestMatchers("/", "/index.html", "/assets/**", "/logo.svg", "/vite.svg",
                            "/demo.html", "/favicon.ico").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    // 驗證錨點：從第一天就強制，enforce 行為在 permissive 期也可被驗證
                    .requestMatchers("/auth/me").authenticated()
                    // 資安收緊①：/engine/** 是「規則的營運」寫入面，新端點無相容包袱 ——
                    // 不分 mode 一律要身分（permissive 只保護「既有」端點的過渡）
                    .requestMatchers("/engine/**").authenticated()
                    // /rules/** 同理（P2-S4 審核工作流）—— 角色細分在方法層 @PreAuthorize
                    .requestMatchers("/rules/**").authenticated()
                    // 資安收緊⑤：actuator 除 health 外（metrics/prometheus 洩漏內部拓撲與
                    // 流量特徵）需要 ADMIN；生產另有網路層隔離，這是 app 層的縱深
                    .requestMatchers("/actuator/**").hasRole("ADMIN");
                if (enforce) {
                    auth.anyRequest().authenticated();
                } else {
                    auth.anyRequest().permitAll();
                }
            })
            // resource-server 模式：有 Bearer token 就驗簽/驗過期並填 SecurityContext。
            // permissive 模式下它照樣工作 —— 這就是「放行但仍解析」的機制來源
            .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)))
            // 未帶 token 打受保護端點 → 401 JSON（而非預設的重導向登入頁 —— 這是 API 不是網站）
            .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                res.setStatus(HttpStatus.UNAUTHORIZED.value());
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"需要登入（Bearer token）\"}");
            }));

        return http.build();
    }
}
