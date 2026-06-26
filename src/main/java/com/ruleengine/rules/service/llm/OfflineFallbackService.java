package com.ruleengine.rules.service.llm;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * OfflineFallbackService -- DECISIONS.md Q28
 *
 * Loads classpath:offline/*.json on startup.
 * When Gemini API is unavailable, matches description keywords to the closest scenario.
 */
@Component
@Slf4j
public class OfflineFallbackService {

    private record ScenarioEntry(
            String scenarioId,
            List<String> keywords,
            String jsonContent
    ) {}

    private final List<ScenarioEntry> scenarios = new ArrayList<>();

    private static final Map<String, List<String>> SCENARIO_KEYWORDS = new LinkedHashMap<>();
    static {
        SCENARIO_KEYWORDS.put("scenario-01-insurance", List.of(
                "insurance", "underwriting", "premium",
                "\u4fdd\u96aa", "\u58fd\u96aa", "\u6838\u4fdd", "\u627f\u4fdd", "\u62d2\u4fdd", "\u4fdd\u8cbb",
                "\u9ad8\u8840\u58d3", "\u7cd6\u5c3f\u75c5", "BMI", "\u5065\u5eb7", "\u5e74\u9f61"
        ));
        SCENARIO_KEYWORDS.put("scenario-02-discount", List.of(
                "discount", "promotion", "coupon",
                "\u6298\u6263", "\u4fc3\u92b7", "\u512a\u60e0", "\u6eff\u984d",
                "VIP", "\u6703\u54e1", "\u91d1\u984d", "\u8cfc\u7269", "\u6d88\u8cbb"
        ));
        SCENARIO_KEYWORDS.put("scenario-03-health-reward", List.of(
                "health", "reward", "exercise", "sleep", "MULTI",
                "\u5065\u5eb7\u734e\u52f5", "\u904b\u52d5", "\u7761\u7720", "BMI",
                "\u734e\u52f5", "\u7d2f\u52a0", "\u6298\u6e1b"
        ));
        SCENARIO_KEYWORDS.put("scenario-04-conflict", List.of(
                "conflict", "overlap", "inconsistent",
                "\u885d\u7a81", "\u91cd\u758a", "\u77db\u76fe"
        ));
        SCENARIO_KEYWORDS.put("scenario-05-enum-error", List.of(
                "ENUM", "enum", "allowedValues", "error", "invalid",
                "\u5217\u8209", "\u932f\u8aa4"
        ));
        SCENARIO_KEYWORDS.put("scenario-06-credit", List.of(
                "credit", "risk", "score", "loan",
                "\u4fe1\u7528", "\u98a8\u96aa", "\u8a55\u7d1a", "\u8a55\u5206",
                "\u984d\u5ea6", "\u8cb8\u6b3e", "\u4fe1\u8cb8", "\u5be9\u6838"
        ));
        SCENARIO_KEYWORDS.put("scenario-07-logistics", List.of(
                "logistics", "shipping", "freight", "delivery",
                "\u7269\u6d41", "\u904b\u8cbb", "\u8cbb\u7387", "\u914d\u9001",
                "\u91cd\u91cf", "\u8ddd\u96e2", "\u5009\u5eab"
        ));
        SCENARIO_KEYWORDS.put("scenario-08-tree-insurance", List.of(
                "DecisionTree", "tree", "\u6c7a\u7b56\u6a39", "\u5148\u5224\u65b7", "\u5148\u770b",
                "\u518d\u770b", "\u5c64\u7d1a", "\u5206\u652f", "\u968e\u5c64",
                "\u6162\u6027\u75c5", "BMI", "\u6838\u4fdd",
                "\u58fd\u96aa", "\u5e74\u9f61\u8d85\u904e"
        ));
        SCENARIO_KEYWORDS.put("scenario-09-tree-loan", List.of(
                "DecisionTree", "tree", "\u6c7a\u7b56\u6a39",
                "\u8cb8\u6b3e", "\u4fe1\u7528\u8a55\u5206", "\u8ca0\u50b5\u6bd4",
                "\u5148\u770b\u4fe1\u7528", "\u6838\u51c6", "\u62d2\u7d55",
                "credit_score", "debt_ratio", "loan"
        ));
        // === 案例 10-13：已移除離線場景關鍵字，強制走 LLM ===
    }

    @PostConstruct
    public void init() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:offline/scenario-*.json");
            log.info("Scanning offline scenario JSON | found {} files", resources.length);

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;

                String scenarioId = filename.replace(".json", "");
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                List<String> keywords = SCENARIO_KEYWORDS.getOrDefault(scenarioId, List.of());
                scenarios.add(new ScenarioEntry(scenarioId, keywords, content));

                log.debug("Loaded offline scenario | id={} | keywords={} | size={}",
                        scenarioId, keywords.size(), content.length());
            }

            log.info("Offline scenarios loaded | total={}", scenarios.size());
        } catch (IOException e) {
            log.warn("Failed to load offline scenarios (non-fatal): {}", e.getMessage());
        }
    }

    public String tryOffline(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        if (scenarios.isEmpty()) {
            return null;
        }

        String descLower = description.toLowerCase();

        ScenarioEntry bestMatch = null;
        int bestScore = 0;

        for (ScenarioEntry entry : scenarios) {
            int score = 0;
            for (String keyword : entry.keywords()) {
                if (descLower.contains(keyword.toLowerCase())) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestMatch = entry;
            }
        }

        if (bestMatch != null && bestScore >= 2) {
            log.info("Offline mode matched | scenario={} | score={}", bestMatch.scenarioId(), bestScore);
            return bestMatch.jsonContent();
        }

        return null;
    }

    public List<String> listScenarios() {
        return scenarios.stream()
                .map(ScenarioEntry::scenarioId)
                .toList();
    }
}
