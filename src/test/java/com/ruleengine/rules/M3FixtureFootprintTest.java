package com.ruleengine.rules;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.14 — M3 fixture footprint 防呆測試。
 *
 * 既有 src/test/resources/fixtures/ 下的 *.json fixture **不應** 含 extensions 區塊。
 * 它們是 v1.0.0 時代的測試素材，用來驗證 11 個既有錯誤碼。
 * 如果某個既有 fixture 不小心被改動而引入了 extensions，就會：
 *   - 改變 validator 的執行路徑（多走 Layer 5）
 *   - 可能讓既有測試的錯誤計數異動
 *
 * 此測試在 mvn test 階段防止「fixture 被誤動」的回歸。
 */
@DisplayName("M3 - 既有 fixtures 不應自帶 extensions")
class M3FixtureFootprintTest {

    private static final Path FIXTURES_DIR =
            Paths.get("src", "test", "resources", "fixtures");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("src/test/resources/fixtures 下所有 *.json 都 getExtensions() == null（既有 DT/Tree）")
    void existingFixtures_doNotContainExtensionsKey() throws IOException {
        assertThat(FIXTURES_DIR).exists().isDirectory();

        try (Stream<Path> walk = Files.walk(FIXTURES_DIR)) {
            List<Path> jsons = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .toList();

            assertThat(jsons).as("fixtures 目錄應含 *.json fixture").isNotEmpty();

            for (Path p : jsons) {
                String json = Files.readString(p, StandardCharsets.UTF_8);
                JsonNode node;
                try {
                    node = mapper.readTree(json);
                } catch (JsonProcessingException ex) {
                    // 部分 fixture 可能不是合法 JSON（仍可能被 validator 處理為 MISSING_FIELD）
                    continue;
                }
                // 直接以樹狀結構檢查（即使 RuleEnvelope.fromJson 對某些 fixture 失敗仍應有效）
                assertThat(node.has("extensions"))
                        .as("fixture %s 不應自帶 extensions 區塊", p.getFileName())
                        .isFalse();

                // 若整個 envelope 能 deserialize 成 RuleEnvelope，再 double-check
                if (node.has("ruleType") && node.has("rule")) {
                    try {
                        RuleEnvelope envelope = mapper.treeToValue(node, RuleEnvelope.class);
                        assertThat(envelope.getExtensions())
                                .as("fixture %s deserialize 後 extensions 應為 null", p.getFileName())
                                .isNull();
                    } catch (JsonProcessingException ignored) {
                        // 有些 negative-case fixture 故意結構錯誤；不算回歸
                    }
                }
            }
        }
    }
}
