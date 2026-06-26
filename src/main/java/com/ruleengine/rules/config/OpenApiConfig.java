package com.ruleengine.rules.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger UI 配置。
 *
 * Swagger UI: http://localhost:8080/swagger-ui.html
 * OpenAPI JSON: http://localhost:8080/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Value("${rules.schema-version:1.0.0}")
    private String schemaVersion;

    @Bean
    public OpenAPI rulesOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Rules MCP Server API")
                        .description("業務規則治理與交付前處理服務 — 自然語言 → engine-neutral RuleEnvelope JSON (DecisionTable / DecisionTree)，再由 exporter/adapter 轉給下游規則引擎")
                        .version(schemaVersion)
                        .contact(new Contact()
                                .name("Group Financial Holdings")
                                .url("https://github.com/ruleengine-rules")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development"),
                        new Server().url("https://rules-mcp.group.com").description("Production")
                ));
    }
}
