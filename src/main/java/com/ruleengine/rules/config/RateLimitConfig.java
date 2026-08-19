package com.ruleengine.rules.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * API 限流設定 — 使用 Resilience4j RateLimiter。
 *
 * - generate: 每分鐘 10 次（LLM API 保護）
 * - validate/analyze: 每分鐘 60 次
 * - default: 每分鐘 30 次
 */
@Configuration
public class RateLimitConfig {

    @org.springframework.beans.factory.annotation.Value("${rules.security.login.rate-limit-per-minute:10}")
    private int loginLimitPerMinute;

    @org.springframework.beans.factory.annotation.Value("${rules.rate-limit.generate-per-minute:10}")
    private int generateLimitPerMinute;

    @org.springframework.beans.factory.annotation.Value("${rules.rate-limit.diff-per-minute:60}")
    private int diffLimitPerMinute;

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        // LLM 生成限流：每分鐘 10 次（可配置：測試環境放寬避免互撞，見 test application.yml）
        RateLimiterConfig generateConfig = RateLimiterConfig.custom()
                .limitForPeriod(generateLimitPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build();

        // 驗證/分析限流：每分鐘 60 次
        RateLimiterConfig validateConfig = RateLimiterConfig.custom()
                .limitForPeriod(60)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(2))
                .build();

        // diff / tree-paths 等純符號 CPU-bound 端點：每分鐘 60 次、不等待（額滿即時 429）。
        // 單次成本已由各服務的節點/取樣上限約束；此限流為對抗高頻濫用的防禦縱深。
        RateLimiterConfig diffConfig = RateLimiterConfig.custom()
                .limitForPeriod(diffLimitPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        // 登入全域限流（資安收緊②的第二層）：擋 password spraying —— 
        // 帳號級鎖定（LoginAttemptService）擋單帳號爆破，這裡擋「換帳號名狂試」。
        // 預設 10 次/分對真人綽綽有餘（登入是低頻操作），對腳本是硬牆。
        // 可配置：測試環境放寬（大量測試共享一個 registry），限流專屬測試再收窄。
        RateLimiterConfig loginConfig = RateLimiterConfig.custom()
                .limitForPeriod(loginLimitPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.of(
                java.util.Map.of(
                        "generate", generateConfig,
                        "validate", validateConfig,
                        "diff", diffConfig,
                        "login", loginConfig
                )
        );
        // review 修復輪的關鍵：Registry.of(map) 只登記「config 目錄」，不建 instance。
        // @RateLimiter 註解 aspect 與任何單參 rateLimiter(name) 查無 instance 時會用
        // ofDefaults()（50 permits/500ns = 形同無限流）—— 這就是 /tools 限流
        // 自 v3.0 以來從未真正觸發過的根因。預先以「同名 config」實例化，
        // 之後所有單參查詢都命中這些既存 instance，限流才真的存在。
        registry.rateLimiter("generate", "generate");
        registry.rateLimiter("validate", "validate");
        registry.rateLimiter("diff", "diff");
        registry.rateLimiter("login", "login");
        return registry;
    }

    @Bean
    public RateLimiter generateRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("generate");
    }

    @Bean
    public RateLimiter validateRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("validate");
    }
}
