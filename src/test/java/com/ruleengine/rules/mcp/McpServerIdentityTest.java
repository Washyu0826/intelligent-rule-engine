package com.ruleengine.rules.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP server 身分設定的結構回歸測試。
 *
 * <p>
 * <b>為什麼需要這個測試：</b>{@code spring.ai.mcp.server} 整個區塊曾因為在它前面
 * 插入一個頂層 {@code audit:} 鍵而被縮排到 {@code audit.ai.mcp.server}，
 * 導致 server 的 name / version / instructions / capabilities 全部沒有生效 ——
 * MCP client 連進來看到的是 Spring AI 的預設身分。同一次縮排也順帶弄掉了
 * {@code spring.web.resources.static-locations}（Dockerfile 依賴其中的
 * {@code file:./static/}）與 {@code spring.lifecycle.timeout-per-shutdown-phase}。
 * </p>
 *
 * <p>
 * <b>為什麼要直接讀檔而不是 @SpringBootTest 讀 Environment：</b>
 * {@code src/test/resources/application.yml} 與 main 的檔案同名，在測試 classpath 上
 * <b>整份遮蔽</b>了 main 的設定 —— 這正是當初那個 bug 三個月沒被任何測試抓到的原因。
 * 因此這裡以檔案為對象驗證結構，繞過遮蔽。
 * </p>
 */
@DisplayName("MCP server 身分設定（application.yml 結構）")
class McpServerIdentityTest {

    private static final Path MAIN_YAML = Path.of("src", "main", "resources", "application.yml");

    private static PropertySource<?> mainApplicationYaml() throws Exception {
        assertThat(Files.exists(MAIN_YAML))
                .as("找不到 %s —— 測試預期由 Maven 在專案根目錄執行", MAIN_YAML)
                .isTrue();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("main-application-yml", new FileSystemResource(MAIN_YAML.toFile()));
        assertThat(loaded).as("application.yml 應為單一 YAML 文件").hasSize(1);
        return loaded.get(0);
    }

    @Test
    @DisplayName("spring.ai.mcp.server 在 spring 底下，不是被縮進 audit 底下")
    void mcpServerBlockIsUnderSpring() throws Exception {
        PropertySource<?> yaml = mainApplicationYaml();

        assertThat(yaml.getProperty("spring.ai.mcp.server.name")).isEqualTo("rules-mcp-server");
        assertThat(yaml.getProperty("spring.ai.mcp.server.version")).isNotNull();
        assertThat(yaml.getProperty("spring.ai.mcp.server.capabilities.tool")).isEqualTo(true);
        assertThat(yaml.getProperty("spring.ai.mcp.server.capabilities.resource")).isEqualTo(true);

        // 反向斷言：這是縮排 bug 實際造成的鍵，出現即代表 bug 回來了
        assertThat(yaml.getProperty("audit.ai.mcp.server.name"))
                .as("spring.ai 被縮進 audit 底下 —— MCP server 身分與 instructions 會失效")
                .isNull();
    }

    @Test
    @DisplayName("instructions 內容與目前支援範圍一致，並宣告 maker-checker 邊界")
    void instructionsMatchCurrentCapabilities() throws Exception {
        Object raw = mainApplicationYaml().getProperty("spring.ai.mcp.server.instructions");
        assertThat(raw).as("instructions 是 AI 選用工具的依據，不可缺").isNotNull();
        String instructions = raw.toString();

        // 正式支援兩種型態（DecisionTree 自 v3.0 起即為正式功能）
        assertThat(instructions).contains("DecisionTable", "DecisionTree");
        // ScoreCard 必須被標示為 stub，避免 AI 拿去產正式輸出
        assertThat(instructions).contains("ScoreCard", "stub");
        // 過期說法：曾寫「正式支援的規則型態只有 DecisionTable」
        assertThat(instructions).doesNotContain("只有 DecisionTable", "尚未正式對外承諾");
        // MCP 的暴露邊界＝maker-checker 的邊界：AI 能寫到送審，核准必須是人
        assertThat(instructions).contains("CHECKER");
    }

    @Test
    @DisplayName("同一次縮排連帶影響的兩個 spring 設定也在正確位置")
    void neighbouringSpringKeysAreNotOrphaned() throws Exception {
        PropertySource<?> yaml = mainApplicationYaml();

        // Dockerfile 把前端 build 產物 copy 到 ./static/，靠這個設定才會被服務
        assertThat(String.valueOf(yaml.getProperty("spring.web.resources.static-locations")))
                .contains("file:./static/");
        assertThat(yaml.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isNotNull();

        // audit 命名空間只該有自己的鍵（repository），不該藏 Spring 的設定
        assertThat(yaml.getProperty("audit.repository")).isEqualTo("jpa");
        assertThat(yaml.getProperty("audit.web.resources.static-locations")).isNull();
        assertThat(yaml.getProperty("audit.lifecycle.timeout-per-shutdown-phase")).isNull();
    }
}
