package com.ruleengine.rules.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LLM Provider 註冊表 — 管理多個 LLM 提供者，支援動態切換。
 *
 * 啟動時收集所有可用的 LlmProvider bean，
 * 提供依名稱選取的能力（claude / ollama / gemini）。
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private final String defaultProviderName;

    public LlmProviderRegistry(
            List<LlmProvider> providerList,
            @Value("${rules.llm.provider:}") String defaultProvider
    ) {
        this.defaultProviderName = defaultProvider;
        for (LlmProvider p : providerList) {
            String name = extractName(p);
            providers.put(name, p);
            log.info("LLM Provider 已註冊: {} (available={})", name, p.isAvailable());
        }
        log.info("LLM Provider Registry 初始化完成 | total={} | default={} | available={}",
                providers.size(), defaultProvider, getAvailableProviderNames());
    }

    /** 取得預設 provider */
    public LlmProvider getDefault() {
        LlmProvider p = providers.get(defaultProviderName);
        if (p != null && p.isAvailable()) return p;
        // fallback: 找第一個可用的
        return providers.values().stream()
                .filter(LlmProvider::isAvailable)
                .findFirst()
                .orElse(null);
    }

    /** 依名稱取得指定 provider */
    public LlmProvider getByName(String name) {
        if (name == null || name.isBlank()) return getDefault();
        LlmProvider p = providers.get(name.toLowerCase());
        if (p != null) return p;
        // 模糊匹配
        for (Map.Entry<String, LlmProvider> entry : providers.entrySet()) {
            if (entry.getKey().contains(name.toLowerCase())) return entry.getValue();
        }
        return getDefault();
    }

    /** 取得所有可用的 provider 名稱 */
    public List<String> getAvailableProviderNames() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, LlmProvider> entry : providers.entrySet()) {
            if (entry.getValue().isAvailable()) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    /** 取得所有已註冊的 provider 資訊 */
    public List<Map<String, Object>> getAllProviderInfo() {
        List<Map<String, Object>> info = new ArrayList<>();
        for (Map.Entry<String, LlmProvider> entry : providers.entrySet()) {
            info.add(Map.of(
                    "name", entry.getKey(),
                    "displayName", entry.getValue().getProviderName(),
                    "available", entry.getValue().isAvailable(),
                    "isDefault", entry.getKey().equals(defaultProviderName)
            ));
        }
        return info;
    }

    private String extractName(LlmProvider p) {
        String className = p.getClass().getSimpleName().toLowerCase();
        // Remove CGLIB proxy suffix
        if (className.contains("$$")) className = className.substring(0, className.indexOf("$$"));
        if (className.contains("claude")) return "claude";
        if (className.contains("gemini")) return "gemini";
        if (className.contains("openai") || className.contains("gpt")) return "openai";
        if (className.contains("ollama")) return "ollama";
        if (className.contains("offline")) return "offline";
        return className.replace("service", "");
    }
}
