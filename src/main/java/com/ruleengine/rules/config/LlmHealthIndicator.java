package com.ruleengine.rules.config;

import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

/**
 * LLM 服務健康指標。
 *
 * 在 /actuator/health 中顯示所有 LLM 提供者的可用狀態。
 */
@Component
public class LlmHealthIndicator extends AbstractHealthIndicator {

    private final LlmProviderRegistry registry;

    public LlmHealthIndicator(LlmProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        var available = registry.getAvailableProviderNames();
        var all = registry.getAllProviderInfo();
        if (!available.isEmpty()) {
            builder.up()
                    .withDetail("availableProviders", available)
                    .withDetail("totalProviders", all.size());
        } else {
            builder.up()
                    .withDetail("availableProviders", available)
                    .withDetail("warning", "無可用的 LLM 提供者，自然語言生成功能不可用");
        }
    }
}
