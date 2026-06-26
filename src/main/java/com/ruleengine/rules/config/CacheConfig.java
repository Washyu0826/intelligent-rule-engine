package com.ruleengine.rules.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 快取配置。
 *
 * 使用 Caffeine 作為快取引擎，為計算密集型和 API 呼叫提供 TTL 快取。
 *
 * 快取策略（各快取獨立設定）：
 *   - dmnAnalysis: 最多 100 筆，5 分鐘 TTL（避免相同規則重複 O(n²) 計算）
 *   - treeAnalysis: 最多 100 筆，5 分鐘 TTL（DecisionTree 路徑分析）
 *   - llmGenerate: 最多 50 筆，30 分鐘 TTL（LLM 回應快取，節省 API 成本）
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                buildCache("dmnAnalysis", 100, 5),
                buildCache("treeAnalysis", 100, 5),
                buildCache("llmGenerate", 50, 30)));
        return manager;
    }

    private CaffeineCache buildCache(String name, int maxSize, int ttlMinutes) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build());
    }
}
