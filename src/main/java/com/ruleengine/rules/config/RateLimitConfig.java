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

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        // LLM 生成限流：每分鐘 10 次
        RateLimiterConfig generateConfig = RateLimiterConfig.custom()
                .limitForPeriod(10)
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
                .limitForPeriod(60)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        return RateLimiterRegistry.of(
                java.util.Map.of(
                        "generate", generateConfig,
                        "validate", validateConfig,
                        "diff", diffConfig
                )
        );
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
