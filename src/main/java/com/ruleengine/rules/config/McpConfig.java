package com.ruleengine.rules.config;

import com.ruleengine.rules.mcp.RulesMcpInfoToolService;
import com.ruleengine.rules.mcp.RulesMcpResourceProvider;
import com.ruleengine.rules.mcp.RulesMcpToolService;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP Server 設定：將 Tool 方法註冊為 MCP 可呼叫的工具。
 * 僅在 spring.ai.mcp.server.enabled=true（預設）時啟用。
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class McpConfig {

    @Bean
    public ToolCallbackProvider ruleToolCallbacks(
            RulesMcpToolService toolService,
            RulesMcpInfoToolService infoToolService
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(toolService, infoToolService)
                .build();
    }

    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> syncResourceSpecifications(
            RulesMcpResourceProvider resourceProvider
    ) {
        return resourceProvider.syncResources();
    }
}
