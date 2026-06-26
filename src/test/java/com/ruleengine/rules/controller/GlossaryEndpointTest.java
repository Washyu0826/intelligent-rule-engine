package com.ruleengine.rules.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * /tools/glossary REST 端點測試 — v3.14 Phase A。
 *
 * 涵蓋：
 *   - GET /tools/glossary 全部、含 ?q= / ?category= 過濾
 *   - GET /tools/glossary/{id} 存在 / 不存在
 *   - GET /tools/glossary/stats
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("v3.14 Phase A — Glossary REST 端點")
class GlossaryEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /tools/glossary → 200 + JSON 陣列含 25+ entries")
    void listAll() throws Exception {
        mockMvc.perform(get("/tools/glossary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].zh_TW").exists());
    }

    @Test
    @DisplayName("GET /tools/glossary?category=合規 → 只回傳合規類別")
    void filterByCategory() throws Exception {
        mockMvc.perform(get("/tools/glossary").param("category", "合規"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.category=='合規')]").exists())
                .andExpect(jsonPath("$[?(@.category!='合規')]").doesNotExist());
    }

    @Test
    @DisplayName("GET /tools/glossary?q=保額 → 命中「保額」")
    void searchByQuery() throws Exception {
        mockMvc.perform(get("/tools/glossary").param("q", "保額"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='sum-insured')]").exists());
    }

    @Test
    @DisplayName("GET /tools/glossary?q=保險金額 → 同義詞也能命中 sum-insured")
    void searchBySynonym() throws Exception {
        mockMvc.perform(get("/tools/glossary").param("q", "保險金額"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='sum-insured')]").exists());
    }

    @Test
    @DisplayName("GET /tools/glossary/insured → 200 + 完整 entry")
    void getByIdExisting() throws Exception {
        mockMvc.perform(get("/tools/glossary/insured"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("insured"))
                .andExpect(jsonPath("$.zh_TW").value("被保險人"))
                .andExpect(jsonPath("$.en").value("Insured"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("GET /tools/glossary/{nonExistent} → 404")
    void getByIdNotFound() throws Exception {
        mockMvc.perform(get("/tools/glossary/this-does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /tools/glossary/disability-old → deprecated entry，successor 指向 disability")
    void getDeprecatedEntry() throws Exception {
        mockMvc.perform(get("/tools/glossary/disability-old"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deprecated"))
                .andExpect(jsonPath("$.deprecatedAt").value("2018-06-15"))
                .andExpect(jsonPath("$.successor").value("disability"));
    }

    @Test
    @DisplayName("GET /tools/glossary/stats → 200 + 統計欄位")
    void stats() throws Exception {
        mockMvc.perform(get("/tools/glossary/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.active").isNumber())
                .andExpect(jsonPath("$.deprecated").isNumber())
                .andExpect(jsonPath("$.proposed").isNumber())
                .andExpect(jsonPath("$.byCategory").exists());
    }
}
