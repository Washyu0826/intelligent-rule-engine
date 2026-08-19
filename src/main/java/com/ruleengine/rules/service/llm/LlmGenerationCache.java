package com.ruleengine.rules.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;

/**
 * LLM 生成結果快取的唯一入口（{@code llmGenerate} cache）。
 *
 * <p>
 * <b>為什麼要獨立成一個 bean：</b>
 * v3.16.3 前，{@code @Cacheable} 直接標在各 provider 的
 * {@code doGenerateRuleJson(...)} 上，而該方法是被同類別的
 * {@code generateRuleJson(...)} 以 {@code this.} 內部呼叫的。Spring Cache 預設是
 * proxy 模式（{@code CacheConfig} 未啟用 AspectJ），內部呼叫不經過 proxy，
 * 因此那些註解<b>從未生效過</b> —— 快取的命中率一直是 0，而且沒有任何測試覆蓋，
 * 所以問題長期沒被發現。把快取行為移到獨立 bean，呼叫必定跨 proxy 邊界，註解才會作用。
 * </p>
 *
 * <p>
 * <b>快取鍵：</b>{@code provider|mode|ruleType|sha256(description)}。
 * 舊實作用 {@code description.hashCode()}（32-bit），不同描述碰撞時會回傳
 * <b>另一個請求的規則 JSON</b>；改用 SHA-256 消除這個風險，同時讓鍵長度固定，
 * 不會因為 50KB 的描述而讓快取鍵本身佔記憶體。
 * </p>
 *
 * <p>
 * <b>不快取失敗：</b>{@code unless = "#result == null"} —— provider 失敗時回傳 null，
 * 若把 null 也存進去，一次暫時性的 API 失敗會被固定 30 分鐘。
 * </p>
 */
@Component
@Slf4j
public class LlmGenerationCache {

    /**
     * 取快取；未命中才執行 {@code loader}。
     *
     * @param cacheKey 由 {@link #key} 產生的鍵
     * @param loader   實際的 LLM 呼叫（僅在未命中時執行）
     * @return LLM 回應字串，失敗時 null（null 不會進快取）
     */
    @Cacheable(value = "llmGenerate", key = "#cacheKey", unless = "#result == null")
    public String getOrGenerate(String cacheKey, Supplier<String> loader) {
        log.debug("llmGenerate cache MISS | key={}", cacheKey);
        return loader.get();
    }

    /**
     * 組出快取鍵。description 以 SHA-256 摘要，避免碰撞與過長的鍵。
     */
    public static String key(String provider, String description, String mode, String ruleType) {
        return provider + '|' + mode + '|' + ruleType + '|' + sha256(description);
    }

    private static String sha256(String text) {
        if (text == null) return "null";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 規範要求每個實作都必須支援的演算法，這裡不可能走到
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
