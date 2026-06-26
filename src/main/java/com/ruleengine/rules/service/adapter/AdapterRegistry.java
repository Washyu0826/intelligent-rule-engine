package com.ruleengine.rules.service.adapter;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * M2 — SPI Skeleton: {@link RuleEngineAdapter} 的 Spring 註冊表。
 *
 * <p>
 * 鏡像 {@code com.ruleengine.rules.registry.RuleTypeRegistry} 的設計慣例：
 * 透過建構子注入 {@code List<RuleEngineAdapter>}，於 {@link PostConstruct}
 * 將 adapter 以 {@link RuleEngineAdapter#engineName()} 為鍵索引、並輸出一行
 * 啟動 log（滿足 {@code MISSION.md} §6 M2 接受條件「於啟動時列出已知 adapter 名稱」）。
 * </p>
 *
 * <p>
 * <b>本註冊表不做：</b>
 * </p>
 * <ul>
 *   <li>不依 {@link AdapterFormat} 做查詢（M2 不提供 {@code getByFormat()}；
 *       能力查詢由呼叫端透過 {@link RuleEngineAdapter#supportedFormats()} 處理）。</li>
 *   <li>不與 {@code RuleExporter} 註冊鏈合併（兩個 SPI 各自獨立蒐集 bean）。</li>
 *   <li>不主動呼叫 {@code export()} / {@code importFrom()}；本註冊表是 read-only lookup。</li>
 * </ul>
 */
@Component
@Slf4j
public class AdapterRegistry {

    private final Map<String, RuleEngineAdapter> adapters = new LinkedHashMap<>();
    private final List<RuleEngineAdapter> adapterBeans;

    public AdapterRegistry(List<RuleEngineAdapter> adapterBeans) {
        this.adapterBeans = adapterBeans;
    }

    @PostConstruct
    public void init() {
        for (RuleEngineAdapter adapter : adapterBeans) {
            String name = adapter.engineName();
            if (name == null || name.isBlank()) {
                log.warn("略過未提供 engineName 的 RuleEngineAdapter bean: {}", adapter.getClass().getName());
                continue;
            }
            RuleEngineAdapter existing = adapters.putIfAbsent(name, adapter);
            if (existing != null) {
                log.warn("RuleEngineAdapter engineName 衝突：保留先註冊的 {}，忽略 {}",
                        existing.getClass().getName(), adapter.getClass().getName());
            } else {
                log.info("已註冊 RuleEngineAdapter: {} @ {} | formats={}",
                        name, adapter.engineVersion(), adapter.supportedFormats());
            }
        }

        String summary = adapters.values().stream()
                .map(a -> a.engineName() + "@" + a.engineVersion())
                .collect(Collectors.joining(", "));
        log.info("RuleEngineAdapter Registry 初始化完成 | total={} | registered=[{}]",
                adapters.size(), summary);
    }

    /** 依 engineName 取得 adapter；無註冊回 {@link Optional#empty()}。 */
    public Optional<RuleEngineAdapter> getByName(String engineName) {
        if (engineName == null) return Optional.empty();
        return Optional.ofNullable(adapters.get(engineName));
    }

    /** 列出所有已註冊的 adapter 名稱（依名稱排序，提供決定性順序）。 */
    public List<String> getRegisteredEngineNames() {
        List<String> names = new ArrayList<>(adapters.keySet());
        names.sort(String::compareTo);
        return names;
    }

    /** {@code engineName → engineVersion} 摘要；用於啟動 log、dashboard、MCP Resource。 */
    public Map<String, String> getEngineInfo() {
        Map<String, String> info = new TreeMap<>();
        for (RuleEngineAdapter adapter : adapters.values()) {
            info.put(adapter.engineName(), adapter.engineVersion());
        }
        return info;
    }

    /** 檢查指定 engineName 是否已註冊。 */
    public boolean isRegistered(String engineName) {
        return engineName != null && adapters.containsKey(engineName);
    }
}
