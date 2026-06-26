package com.ruleengine.rules.service.glossary;

import com.ruleengine.rules.domain.glossary.GlossaryEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlossaryService 整合測試 — v3.14 Phase A。
 *
 * 涵蓋：
 *   - YAML 載入：starter 25 條 entries 全載入
 *   - findById：基本存取、null/不存在 handling
 *   - findByCategory：類別過濾
 *   - search：中文 / 英文 / 同義詞 / 定義 模糊搜尋
 *   - stats：active / deprecated / proposed 計數
 *   - 殘廢 → 失能 deprecation 案例
 */
@SpringBootTest
@DisplayName("v3.14 Phase A — GlossaryService 整合測試")
class GlossaryServiceTest {

    @Autowired
    private GlossaryService glossary;

    @Test
    @DisplayName("YAML 載入：starter 25 條全部就位")
    void loadsAll25Entries() {
        assertTrue(glossary.size() >= 25, "Expected >= 25 entries, got " + glossary.size());
    }

    @Test
    @DisplayName("findById：取「被保險人」entry")
    void findInsuredById() {
        Optional<GlossaryEntry> e = glossary.findById("insured");
        assertTrue(e.isPresent());
        assertEquals("被保險人", e.get().getZh_TW());
        assertEquals("Insured", e.get().getEn());
        assertNotNull(e.get().getSynonyms());
        assertTrue(e.get().getSynonyms().contains("被保人"));
        assertEquals("active", e.get().getStatus());
    }

    @Test
    @DisplayName("findById：不存在 id 回 empty")
    void findByIdNotFound() {
        assertTrue(glossary.findById("non-existent").isEmpty());
        assertTrue(glossary.findById(null).isEmpty());
    }

    @Test
    @DisplayName("findByCategory：合規類別至少 5 條")
    void findByCompliance() {
        List<GlossaryEntry> compliance = glossary.findByCategory("合規");
        assertTrue(compliance.size() >= 5, "Expected >= 5 entries in 合規, got " + compliance.size());
        assertTrue(compliance.stream().anyMatch(e -> "kyc".equals(e.getId())));
        assertTrue(compliance.stream().anyMatch(e -> "fatca".equals(e.getId())));
    }

    @Test
    @DisplayName("findByCategory：null/blank 回全部")
    void findByCategoryNullReturnsAll() {
        assertEquals(glossary.size(), glossary.findByCategory(null).size());
        assertEquals(glossary.size(), glossary.findByCategory("").size());
    }

    @Test
    @DisplayName("search：中文「保額」命中")
    void searchByChineseTerm() {
        List<GlossaryEntry> results = glossary.search("保額", null);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(e -> "sum-insured".equals(e.getId())));
    }

    @Test
    @DisplayName("search：英文「premium」（不分大小寫）命中")
    void searchByEnglishCaseInsensitive() {
        List<GlossaryEntry> results = glossary.search("PREMIUM", null);
        assertTrue(results.stream().anyMatch(e -> "premium".equals(e.getId())));

        List<GlossaryEntry> lower = glossary.search("premium", null);
        assertTrue(lower.stream().anyMatch(e -> "premium".equals(e.getId())));
    }

    @Test
    @DisplayName("search：同義詞「保險金額」也能找到「保額」")
    void searchBySynonym() {
        List<GlossaryEntry> results = glossary.search("保險金額", null);
        assertTrue(results.stream().anyMatch(e -> "sum-insured".equals(e.getId())),
                "「保險金額」應命中 sum-insured（同義詞）");
    }

    @Test
    @DisplayName("search：定義內文「給付」命中")
    void searchByDefinitionText() {
        List<GlossaryEntry> results = glossary.search("給付", null);
        assertFalse(results.isEmpty(), "「給付」在多條定義中出現，應命中");
    }

    @Test
    @DisplayName("search：搭配 category 過濾（搜「保」於商品類別）")
    void searchWithCategoryFilter() {
        List<GlossaryEntry> all = glossary.search("保", null);
        List<GlossaryEntry> productOnly = glossary.search("保", "商品");
        assertTrue(productOnly.size() <= all.size());
        assertTrue(productOnly.stream().allMatch(e -> "商品".equals(e.getCategory())));
    }

    @Test
    @DisplayName("殘廢 → 失能 deprecation 案例：兩條都載入、successor 連結正確")
    void disabilityDeprecationCase() {
        Optional<GlossaryEntry> oldEntry = glossary.findById("disability-old");
        Optional<GlossaryEntry> newEntry = glossary.findById("disability");

        assertTrue(oldEntry.isPresent(), "「殘廢」entry 應載入");
        assertTrue(newEntry.isPresent(), "「失能」entry 應載入");

        assertEquals("deprecated", oldEntry.get().getStatus());
        assertEquals("2018-06-15", oldEntry.get().getDeprecatedAt());
        assertEquals("disability", oldEntry.get().getSuccessor());

        assertEquals("active", newEntry.get().getStatus());
        assertEquals("2018-06-15", newEntry.get().getEffectiveFrom());
    }

    @Test
    @DisplayName("Stats：總數、各狀態與類別計數")
    void statsAggregation() {
        GlossaryService.Stats s = glossary.stats();
        assertEquals(glossary.size(), s.total());
        assertEquals(s.total(), s.active() + s.deprecated() + s.proposed());
        assertTrue(s.active() >= 20, "Expected most entries active");
        assertTrue(s.deprecated() >= 1, "Expected at least 1 deprecated (殘廢)");
        assertNotNull(s.byCategory());
        assertTrue(s.byCategory().containsKey("合規"));
    }
}
