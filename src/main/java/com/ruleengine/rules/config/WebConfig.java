package com.ruleengine.rules.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置 — CORS 設定。
 *
 * 允許前端（預設 localhost:5173）跨域存取後端 API。
 * 可透過 rules.cors.allowed-origins 配置允許的來源。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;

    @Value("${rules.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    /**
     * 開發模式 pattern：允許任意 localhost port（Vite dev server 會在 5173 被佔時自動遞增到 5174/5175/...）。
     * 正式環境請用 rules.cors.allowed-origins 明確列白名單。
     */
    @Value("${rules.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOriginPatterns;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/tools/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/tools/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedOriginPatterns(allowedOriginPatterns.split(","))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-API-Key")
                .maxAge(3600);

        registry.addMapping("/actuator/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedOriginPatterns(allowedOriginPatterns.split(","))
                .allowedMethods("GET", "OPTIONS")
                .maxAge(3600);
    }
}
