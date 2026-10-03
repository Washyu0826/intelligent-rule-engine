package com.ruleengine.rules.service.glossary;

import com.ruleengine.rules.domain.glossary.GlossaryEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("核保詞彙包 + 本地覆寫")
class GlossaryPackTest {

    private GlossaryService service(String overrideFile) {
        GlossaryService s = new GlossaryService();
        ReflectionTestUtils.setField(s, "packs", List.of("glossary/underwriting-vocabulary.yaml"));
        ReflectionTestUtils.setField(s, "overrideFile", overrideFile == null ? "" : overrideFile);
        s.load();
        return s;
    }

    @Test
    @DisplayName("通用詞彙包載入：決議值、人工評估、敏感維度都有語意標籤")
    void packLoaded() {
        GlossaryService s = service(null);
        assertTrue(s.findBySemantic("decision-outcome").size() >= 5);
        List<GlossaryEntry> manual = s.findBySemantic("manual-review");
        assertEquals(1, manual.size());
        assertTrue(manual.get(0).getSynonyms().contains("照會"));
        assertTrue(s.findBySemantic("sensitive-dimension").stream().anyMatch(e -> "性別".equals(e.getZh_TW())));
        assertTrue(s.findById("insured").isPresent(), "starter 的 entry 仍在");
    }

    @Test
    @DisplayName("本地覆寫檔：同 id 覆蓋通用層（欄位代碼、值域），新 id 併入")
    void overrideWins(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("override.yaml");
        Files.writeString(f, """
                entries:
                  - id: premium-factor
                    zh_TW: 保費係數
                    en: Premium factor
                    fieldCode: UW_PREM_FACTOR
                    valueRange: "[1.0, 1.8]"
                    semantic: premium-factor
                  - id: company-channel-code
                    zh_TW: 通路代碼
                    en: Channel code
                    semantic: check-message
                """, StandardCharsets.UTF_8);
        GlossaryService s = service(f.toString());
        GlossaryEntry pf = s.findById("premium-factor").orElseThrow();
        assertEquals("UW_PREM_FACTOR", pf.getFieldCode());
        assertEquals("[1.0, 1.8]", pf.getValueRange());
        assertTrue(s.findById("company-channel-code").isPresent());
    }

    @Test
    @DisplayName("覆寫檔不存在：略過，不影響通用層")
    void missingOverrideIsSkipped() {
        GlossaryService s = service("Z:/does/not/exist.yaml");
        assertTrue(s.findById("premium-factor").isPresent());
    }
}
