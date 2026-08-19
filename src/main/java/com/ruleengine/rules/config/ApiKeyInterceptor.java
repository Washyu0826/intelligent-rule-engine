package com.ruleengine.rules.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API Key 認證攔截器。
 *
 * 當 rules.security.api-key-enabled=true 時啟用，
 * 要求所有 /tools/** 請求必須帶 X-API-Key header。
 *
 * 預設關閉（開發環境不需要），生產環境在 application-prod.yml 啟用。
 */
@Component
@Slf4j
public class ApiKeyInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Value("${rules.security.api-key-enabled:false}")
    private boolean enabled;

    @Value("${rules.security.api-key:}")
    private String expectedApiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 未啟用時直接放行
        if (!enabled) return true;

        // OPTIONS 預檢請求放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        // 健康檢查放行
        String uri = request.getRequestURI();
        if (uri.startsWith("/actuator")) return true;

        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null || providedKey.isBlank()) {
            log.warn("API Key 認證失敗：未提供 {} header | uri={} | ip={}",
                    API_KEY_HEADER, uri, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"UNAUTHORIZED\",\"message\":\"Missing X-API-Key header\"}");
            return false;
        }

        // 常數時間比較（資安收緊④）：String.equals 首字元不符即返回，
        // 理論上可由回應時間逐字元推測 key（timing attack）。
        // MessageDigest.isEqual 自 JDK6u17 起保證恆定時間。
        if (!java.security.MessageDigest.isEqual(
                providedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedApiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            log.warn("API Key 認證失敗：無效的 key | uri={} | ip={}",
                    uri, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"FORBIDDEN\",\"message\":\"Invalid API key\"}");
            return false;
        }

        return true;
    }
}
