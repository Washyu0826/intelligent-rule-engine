package com.ruleengine.rules.service.glossary;

import com.ruleengine.rules.domain.glossary.GlossaryEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 領域字彙表查詢服務 — v3.14 Phase A 核心服務。
 *
 * 設計依據：docs/v3.14-schema-evolution.md §4。
 *
 * 載入：應用啟動時從 classpath:glossary/glossary-starter.yaml 讀入，存 in-memory map。
 * 查詢：支援 by id / category / 模糊搜尋（中文 / 英文 / 同義詞）。
 *
 * 未來 (Phase A+)：可改成從 DB / 外部系統載入；介面不變。
 */
@Service
@Slf4j
public class GlossaryService {

    private static final String GLOSSARY_RESOURCE = "glossary/glossary-starter.yaml";

    /** 主索引：id → entry（LinkedHashMap 保留 YAML 中的順序）。 */
    private Map<String, GlossaryEntry> byId = Collections.emptyMap();

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 公開的通用詞彙包（classpath），依序載入；後載入的同 id 覆蓋先前的。 */
    @org.springframework.beans.factory.annotation.Value("${rules.glossary.packs:glossary/underwriting-vocabulary.yaml}")
    private List<String> packs = List.of("glossary/underwriting-vocabulary.yaml");

    /** 本地覆寫檔（公司自己的欄位代碼、值域、內部名稱），最後載入、同 id 以它為準；不進公開 repo。 */
    @org.springframework.beans.factory.annotation.Value("${rules.glossary.override-file:}")
    private String overrideFile = "";

    @PostConstruct
    public void load() {
        Map<String, GlossaryEntry> map = new LinkedHashMap<>();
        int starter = mergeFrom(new ClassPathResource(GLOSSARY_RESOURCE), map, false);
        int packed = 0;
        for (String pack : packs) {
            if (pack == null || pack.isBlank()) continue;
            packed += mergeFrom(new ClassPathResource(pack.strip()), map, true);
        }
        int overridden = 0;
        if (overrideFile != null && !overrideFile.isBlank()) {
            overridden = mergeFrom(new org.springframework.core.io.FileSystemResource(overrideFile.strip()), map, true);
        }
        this.byId = Collections.unmodifiableMap(map);
        log.info("Glossary loaded | total={} | starter={} | packs={} | override={}",
                map.size(), starter, packed, overridden);
    }

    /** 讀一個 YAML 詞彙檔併入 map；override=true 時同 id 覆蓋，否則保留先前的。回傳併入筆數。 */
    private int mergeFrom(org.springframework.core.io.Resource res, Map<String, GlossaryEntry> map, boolean override) {
        if (!res.exists()) {
            log.warn("Glossary source not found, skipped: {}", res.getDescription());
            return 0;
        }
        int count = 0;
        try (InputStream is = res.getInputStream()) {
            GlossaryFile file = yamlMapper.readValue(is, GlossaryFile.class);
            if (file.entries == null) return 0;
            for (GlossaryEntry entry : file.entries) {
                if (entry.getId() == null || entry.getId().isBlank()) {
                    log.warn("Skipping glossary entry without id: {}", entry);
                    continue;
                }
                if (map.containsKey(entry.getId()) && !override) {
                    log.warn("Duplicate glossary id '{}' — keeping first occurrence", entry.getId());
                    continue;
                }
                map.put(entry.getId(), entry);
                count++;
            }
        } catch (Exception e) {
            log.error("Failed to load glossary from {}", res.getDescription(), e);
        }
        return count;
    }

    /** 依語意角色取詞彙（核保詞彙包）：decision-outcome / manual-review / sensitive-dimension … */
    public List<GlossaryEntry> findBySemantic(String semantic) {
        if (semantic == null || semantic.isBlank()) return List.of();
        return byId.values().stream().filter(e -> semantic.equalsIgnoreCase(e.getSemantic())).toList();
    }

    /** 取全部 entries（保留 YAML 順序）。 */
    public List<GlossaryEntry> findAll() {
        return List.copyOf(byId.values());
    }

    /** 取單一 entry by id。 */
    public Optional<GlossaryEntry> findById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(byId.get(id));
    }

    /** 取某類別所有 entries（如「核保」、「合規」）。 */
    public List<GlossaryEntry> findByCategory(String category) {
        if (category == null || category.isBlank()) return findAll();
        return byId.values().stream()
                .filter(e -> category.equals(e.getCategory()))
                .toList();
    }

    /**
     * 模糊搜尋：比對 zh_TW / zh_CN / en（lowercase 子字串）/ synonyms / definition。
     * @param query 搜尋字串；null / blank 返回全部
     * @param category 可選的類別過濾；null / blank 不過濾
     */
    public List<GlossaryEntry> search(String query, String category) {
        List<GlossaryEntry> base = (category == null || category.isBlank())
                ? findAll()
                : findByCategory(category);
        if (query == null || query.isBlank()) return base;

        String q = query.trim();
        String qLower = q.toLowerCase();

        return base.stream()
                .filter(e -> matches(e, q, qLower))
                .toList();
    }

    private boolean matches(GlossaryEntry e, String q, String qLower) {
        if (e.getZh_TW() != null && e.getZh_TW().contains(q)) return true;
        if (e.getZh_CN() != null && e.getZh_CN().contains(q)) return true;
        if (e.getEn() != null && e.getEn().toLowerCase().contains(qLower)) return true;
        if (e.getDefinition() != null && e.getDefinition().contains(q)) return true;
        if (e.getSynonyms() != null) {
            for (String syn : e.getSynonyms()) {
                if (syn.contains(q)) return true;
            }
        }
        return false;
    }

    /** 字彙表總數（含 active / deprecated / proposed）。 */
    public int size() {
        return byId.size();
    }

    /** 統計（給 admin / dashboard 用）。 */
    public Stats stats() {
        long active = byId.values().stream().filter(e -> "active".equals(e.getStatus())).count();
        long deprecated = byId.values().stream().filter(e -> "deprecated".equals(e.getStatus())).count();
        long proposed = byId.values().stream().filter(e -> "proposed".equals(e.getStatus())).count();
        Map<String, Long> byCat = new LinkedHashMap<>();
        for (GlossaryEntry e : byId.values()) {
            byCat.merge(e.getCategory() == null ? "其他" : e.getCategory(), 1L, Long::sum);
        }
        return new Stats(byId.size(), (int) active, (int) deprecated, (int) proposed, byCat);
    }

    // ============================================================
    // 內部 DTO：對應 YAML 結構
    // ============================================================
    private static class GlossaryFile {
        public List<GlossaryEntry> entries;
    }

    public record Stats(
            int total,
            int active,
            int deprecated,
            int proposed,
            Map<String, Long> byCategory
    ) {}
}
