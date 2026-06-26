package com.ruleengine.rules.service.recommender;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.registry.RuleTypeRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 規則型態推薦器（計畫書 §6.3）
 *
 * 分類策略基於語言特徵啟發式 (heuristics)：
 *
 * | 語言特徵                   | 推薦型態       | 原因           |
 * |---------------------------|---------------|----------------|
 * | 「同時滿足」「組合」「對照」 | DecisionTable | 條件平行組合     |
 * | 「先判斷…再看…」「層級」    | DecisionTree  | 條件有先後依賴   |
 * | 「各得…分」「總分」「評分」  | ScoreCard     | 獨立計分再彙總   |
 * | 混合/模糊                  | 建議拆分       | 複合情境需拆解   |
 *
 * v1.3.0 強化：中英文關鍵字擴充 + 語句結構偵測（DECISIONS.md Q27）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleRecommender {

    private final RuleTypeRegistry registry;

    // ========== 語言特徵模式 ==========

    private static final List<FeaturePattern> TABLE_PATTERNS = List.of(
            // 原有中文關鍵字
            pattern("同時", 0.3),
            pattern("組合", 0.3),
            pattern("對照", 0.3),
            pattern("對應", 0.25),
            pattern("費率表", 0.4),
            pattern("折扣規則", 0.35),
            pattern("當.*且.*則", 0.35),
            pattern("條件.*結果", 0.25),
            pattern("若.*同時.*則", 0.3),
            pattern("平行", 0.2),
            pattern("無先後", 0.3),
            pattern("不分順序", 0.3),
            pattern("多條件", 0.25),
            pattern("矩陣", 0.3),
            pattern("表格", 0.25),
            pattern("查表", 0.35),
            // Q27 新增中文關鍵字
            pattern("費率", 0.3),
            pattern("折扣", 0.3),
            pattern("保費", 0.3),
            pattern("核保", 0.4),
            pattern("等級對照", 0.35),
            pattern("分級", 0.25),
            pattern("年齡", 0.2),
            pattern("金額", 0.15),
            // 複合語義：核保/費率搭配條件欄位 → 強 Table 信號
            pattern("(?:年齡|性別|BMI).*(?:核保|保費|費率)", 0.3),
            // Q27 新增英文關鍵字（忽略大小寫）
            patternIgnoreCase("combination", 0.3),
            patternIgnoreCase("matrix", 0.3),
            patternIgnoreCase("lookup", 0.3),
            patternIgnoreCase("rate table", 0.4),
            patternIgnoreCase("discount", 0.3),
            patternIgnoreCase("premium", 0.3),
            patternIgnoreCase("if.*and.*then", 0.35),
            // v1.3.0 追加：英文常用條件→結果句型
            patternIgnoreCase("based on", 0.25),
            patternIgnoreCase("depending on", 0.25),
            patternIgnoreCase("according to", 0.25),
            patternIgnoreCase("membership", 0.2),
            patternIgnoreCase("purchase", 0.2),
            patternIgnoreCase("amount", 0.2),
            // 語句結構偵測：多個 BOOLEAN 描述（是否、有無、是否有）
            pattern("(?:是否|有無|是否有).*(?:是否|有無|是否有)", 0.3),
            // 語句結構偵測：數值區間描述（X-Y歲、X到Y、X以上）
            pattern("\\d+[\\-~到至]\\d+", 0.25),
            pattern("\\d+以上", 0.2),
            // demo 2026-05-18：核保前端檢核 MULTI 慣用語 — 多獨立檢核項 + 拋訊息 + 一次回報
            //   case 2「採 MULTI hit policy / 拋出訊息 / 一次回報所有違規」這類 spec 必為 DecisionTable MULTI
            pattern("MULTI", 0.6),
            patternIgnoreCase("multi.*hit.*polic", 0.6),
            pattern("一次回報", 0.5),
            pattern("多重命中", 0.5),
            pattern("可同時.*多條", 0.5),
            pattern("獨立.*檢核", 0.45),
            pattern("拋訊息", 0.35),
            pattern("拋出訊息", 0.35),
            pattern("拋出錯誤訊息", 0.4),
            pattern("錯誤碼.*\\d+\\.\\d+", 0.4),
            pattern("檢核\\s*\\d+\\.\\d+", 0.4),
            pattern("\\d+\\.\\d+\\s+若", 0.35),
            pattern("檢核範圍", 0.4),
            pattern("違規", 0.3)
    );

    private static final List<FeaturePattern> TREE_PATTERNS = List.of(
            // 原有中文關鍵字
            pattern("先判斷", 0.4),
            pattern("先看", 0.35),
            pattern("再看", 0.35),
            pattern("再判斷", 0.35),
            pattern("若.*則.*否則", 0.3),
            pattern("層級", 0.3),
            pattern("分支", 0.25),
            pattern("先後", 0.3),
            pattern("依序", 0.25),
            pattern("優先", 0.2),
            pattern("如果.*超過.*則.*否則", 0.35),
            pattern("巢狀", 0.3),
            pattern("遞迴", 0.25),
            pattern("流程", 0.2),
            pattern("決策樹", 0.5),
            // Q27 新增中文關鍵字
            pattern("優先判斷", 0.4),
            pattern("嵌套", 0.3),
            pattern("如果.*否則", 0.3),
            pattern("分支判斷", 0.35),
            // Q27 新增英文關鍵字（忽略大小寫）
            patternIgnoreCase("first check", 0.4),
            patternIgnoreCase("then check", 0.35),
            patternIgnoreCase("if.*else", 0.3),
            patternIgnoreCase("priority", 0.2),
            patternIgnoreCase("sequential", 0.25),
            patternIgnoreCase("nested", 0.3),
            // 語句結構偵測：巢狀結構（先…再…、如果…否則…再看）
            pattern("先.*再.*", 0.35),
            pattern("如果.*否則.*再看", 0.35)
    );

    private static final List<FeaturePattern> SCORECARD_PATTERNS = List.of(
            // 原有中文關鍵字
            pattern("計分", 0.4),
            pattern("評分", 0.4),
            pattern("得分", 0.35),
            pattern("總分", 0.4),
            pattern("分數", 0.3),
            pattern("加分", 0.3),
            pattern("扣分", 0.3),
            pattern("各.*分", 0.3),
            pattern("門檻", 0.25),
            pattern("等級", 0.2),
            pattern("信用", 0.2),
            pattern("風險評等", 0.35),
            // Q27 新增中文關鍵字
            pattern("權重", 0.3),
            pattern("加權", 0.35),
            pattern("滿分", 0.3),
            pattern("及格", 0.25),
            pattern("績效", 0.25),
            // Q27 新增英文關鍵字（忽略大小寫）
            patternIgnoreCase("score", 0.35),
            patternIgnoreCase("points", 0.3),
            patternIgnoreCase("weighted", 0.35),
            patternIgnoreCase("threshold", 0.25),
            patternIgnoreCase("rating", 0.25),
            patternIgnoreCase("grade", 0.25),
            // 語句結構偵測：加總描述（各項、加總、合計、累加）
            pattern("(?:各項|加總|合計|累加)", 0.3)
    );

    /**
     * 推薦最適合的規則型態
     */
    public RecommendResponse recommend(String description) {
        if (description == null || description.isBlank()) {
            return RecommendResponse.builder()
                    .recommendedRuleType("DecisionTable")
                    .reason("描述為空，預設推薦決策表")
                    .confidence(0.0)
                    .alternatives(List.of())
                    .build();
        }

        // 計算各型態的匹配分數
        double tableScore = calculateScore(description, TABLE_PATTERNS);
        double treeScore = calculateScore(description, TREE_PATTERNS);
        double scoreCardScore = calculateScore(description, SCORECARD_PATTERNS);

        // 組裝候選
        List<TypeCandidate> candidates = new ArrayList<>();
        candidates.add(TypeCandidate.builder()
                .ruleType(RuleType.DECISION_TABLE.getCode())
                .score(tableScore)
                .reason(buildReason(description, TABLE_PATTERNS, "條件平行組合，無先後依賴"))
                .build());
        candidates.add(TypeCandidate.builder()
                .ruleType(RuleType.DECISION_TREE.getCode())
                .score(treeScore)
                .reason(buildReason(description, TREE_PATTERNS, "條件有層級/先後依賴"))
                .build());
        candidates.add(TypeCandidate.builder()
                .ruleType(RuleType.SCORE_CARD.getCode())
                .score(scoreCardScore)
                .reason(buildReason(description, SCORECARD_PATTERNS, "獨立計分再加總"))
                .build());

        // 排序取最高
        candidates.sort(Comparator.comparingDouble(TypeCandidate::getScore).reversed());
        TypeCandidate best = candidates.get(0);

        // 過濾掉未註冊的型態
        String recommendedType = best.getRuleType();
        try {
            RuleType type = RuleType.fromString(recommendedType);
            if (!registry.isSupported(type)) {
                // 推薦的型態尚未實作，fallback 到已支援的
                for (TypeCandidate c : candidates) {
                    RuleType ct = RuleType.fromString(c.getRuleType());
                    if (registry.isSupported(ct)) {
                        best = c;
                        break;
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {}

        double maxScore = best.getScore();
        double confidence = computeConfidence(maxScore, candidates);

        final TypeCandidate chosen = best;
        List<TypeCandidate> alternatives = candidates.stream()
                .filter(c -> !c.getRuleType().equals(chosen.getRuleType()))
                .toList();

        log.info("Recommend: {} (confidence={}, table={}, tree={}, scorecard={})",
                best.getRuleType(), String.format("%.2f", confidence),
                String.format("%.2f", tableScore),
                String.format("%.2f", treeScore),
                String.format("%.2f", scoreCardScore));

        return RecommendResponse.builder()
                .recommendedRuleType(best.getRuleType())
                .reason(best.getReason())
                .confidence(Math.round(confidence * 100.0) / 100.0)
                .alternatives(alternatives)
                .build();
    }

    // ========== Internal ==========

    /**
     * 計算推薦信心分數。
     *
     * 公式考量三個因素：
     *   1. 絕對分數：最高分越高，越有信心
     *   2. 相對差距：最高分與次高分的差距越大，越有信心（沒有歧義）
     *   3. 無競爭加分：只有一個型態得分 > 0 時額外加分
     *
     * 使用 sigmoid-like 映射：confidence = 1 - 1/(1 + score * k)
     * 確保低分描述也能有合理的 confidence 而非接近 0。
     *
     * @param maxScore   最高分
     * @param candidates 所有候選型態（已排序）
     * @return 0.0 ~ 1.0 的信心分數
     */
    private double computeConfidence(double maxScore, List<TypeCandidate> candidates) {
        if (maxScore <= 0) return 0.3; // 無任何匹配，低信心預設

        // 基礎信心：sigmoid-like 映射，k=3 讓 score=0.5 時 confidence≈0.6
        double baseConfidence = 1.0 - 1.0 / (1.0 + maxScore * 3.0);

        // 差距加分：最高分 vs 次高分的差距
        double secondScore = candidates.size() >= 2 ? candidates.get(1).getScore() : 0.0;
        double gap = maxScore - secondScore;
        double gapBonus = gap > 0 ? Math.min(gap * 0.3, 0.2) : 0.0;

        // 無競爭加分：只有一個型態得分 > 0
        long positiveCount = candidates.stream().filter(c -> c.getScore() > 0).count();
        double exclusiveBonus = (positiveCount == 1) ? 0.1 : 0.0;

        double confidence = Math.min(baseConfidence + gapBonus + exclusiveBonus, 1.0);
        return Math.round(confidence * 100.0) / 100.0;
    }

    private double calculateScore(String description, List<FeaturePattern> patterns) {
        double score = 0;
        for (FeaturePattern p : patterns) {
            if (p.pattern.matcher(description).find()) {
                score += p.weight;
            }
        }
        return Math.round(score * 100.0) / 100.0;
    }

    private String buildReason(String description, List<FeaturePattern> patterns, String defaultReason) {
        List<String> matchedLabels = new ArrayList<>();
        for (FeaturePattern p : patterns) {
            if (p.pattern.matcher(description).find()) {
                matchedLabels.add(p.label);
            }
        }
        if (matchedLabels.isEmpty()) return defaultReason;
        // 去重並限制數量
        List<String> unique = matchedLabels.stream().distinct().limit(5).toList();
        return String.format("描述中包含「%s」等特徵，%s",
                String.join("、", unique), defaultReason);
    }

    // ========== Pattern helper ==========

    private record FeaturePattern(String label, Pattern pattern, double weight) {}

    private static FeaturePattern pattern(String keyword, double weight) {
        return new FeaturePattern(humanLabel(keyword), Pattern.compile(keyword), weight);
    }

    private static FeaturePattern patternIgnoreCase(String keyword, double weight) {
        return new FeaturePattern(humanLabel(keyword), Pattern.compile(keyword, Pattern.CASE_INSENSITIVE), weight);
    }

    /** 將 regex pattern 轉為人類可讀的標籤 */
    private static String humanLabel(String regex) {
        // 常見 regex → 中文標籤對照
        return regex
                .replaceAll("\\\\d\\+\\[.*?\\]\\\\d\\+", "數值區間")
                .replaceAll("\\(\\?:([^)]+)\\)\\.\\*\\(\\?:([^)]+)\\)", "$1×$2")
                .replaceAll("\\(\\?:([^)]+)\\)", "$1")
                .replaceAll("\\|", "/")
                .replaceAll("\\\\[sdwS]", "")
                .replaceAll("[\\\\.*+?^${}()\\[\\]]", "")
                .trim();
    }
}
