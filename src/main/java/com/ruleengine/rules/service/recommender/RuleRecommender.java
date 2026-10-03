package com.ruleengine.rules.service.recommender;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.registry.RuleTypeRegistry;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 啟發式信心低於此值才呼叫 LLM 二選一 */
    private static final double LLM_FALLBACK_THRESHOLD = 0.55;
    /** LLM 之後仍低於此值就反問使用者 */
    private static final double CLARIFY_THRESHOLD = 0.45;
    private static final int LLM_PROMPT_MAX_CHARS = 4000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RuleTypeRegistry registry;

    private LlmProviderRegistry llmProviderRegistry;

    @Autowired(required = false)
    public void setLlmProviderRegistry(LlmProviderRegistry registry) {
        this.llmProviderRegistry = registry;
    }

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

        // 計算各型態的匹配分數：字詞特徵 + 整段規格的結構訊號
        StructureSignals structure = analyzeStructure(description);
        double tableScore = calculateScore(description, TABLE_PATTERNS) + structure.tableBoost();
        double treeScore = calculateScore(description, TREE_PATTERNS) + structure.treeBoost();
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

        String method = maxScore <= 0 ? "default"
                : structureDecided(best.getRuleType(), structure) ? "structure" : "keyword";
        RecommendResponse response = RecommendResponse.builder()
                .recommendedRuleType(best.getRuleType())
                .reason(plainReason(best.getRuleType(), method, structure, best.getReason()))
                .confidence(Math.round(confidence * 100.0) / 100.0)
                .alternatives(alternatives)
                .method(method)
                .build();

        if (response.getConfidence() < LLM_FALLBACK_THRESHOLD) {
            applyLlmClassification(description, response);
        }
        if (response.getConfidence() < CLARIFY_THRESHOLD) {
            response.setNeedsClarification(true);
            response.setClarifyingQuestion("這些條件是同時比對（每一列是一種組合），還是有先後順序（先看一個條件，再依結果看下一個）？");
            response.setClarifyOptions(List.of(
                    ClarifyOption.builder().label("同時比對，像對照表").ruleType("DecisionTable").build(),
                    ClarifyOption.builder().label("有先後順序，像流程圖").ruleType("DecisionTree").build(),
                    ClarifyOption.builder().label("各項目分別計分再加總").ruleType("ScoreCard").build()));
        }
        return response;
    }

    // ========== 結構訊號（整段規格） ==========

    private static final List<Pattern> OUTLINE_MARKERS = List.of(
            Pattern.compile("^\\s*\\d+[.．、](?!\\d)"),
            Pattern.compile("^\\s*[（(]\\d+[）)]"),
            Pattern.compile("^\\s*[A-Za-z][.．、]"),
            Pattern.compile("^\\s*[（(][a-z][）)]"),
            Pattern.compile("^\\s*(?:I|II|III|IV|V|VI|VII|VIII|IX|X)[.．、]"),
            Pattern.compile("^\\s*[一二三四五六七八九十]+[、.．]"),
            Pattern.compile("^\\s*[甲乙丙丁戊己庚辛][、.．]")
    );
    private static final Pattern LEAF_RESULT = Pattern.compile("-{2,}\\s*\\S+\\s*[。．]?\\s*$");
    private static final Pattern ENUM_DIMENSION = Pattern.compile("[（(][^（）()]*[／/、][^（）()]*[）)]");
    private static final Pattern NUMBERED_RULE_LINE = Pattern.compile("^\\s*\\d+[.．、]?\\s*若.*則");
    private static final Pattern CHECK_LINE = Pattern.compile("^\\s*(?:條件|檢核)\\s*\\d+(?:\\.\\d+)?\\s*[：:]");
    private static final Pattern OUTPUT_WORD = Pattern.compile("輸出|結果|回傳");

    record StructureSignals(double tableBoost, double treeBoost, int outlineDepth,
                            int dimensionCount, int ruleLines, int checkLines) {}

    static StructureSignals analyzeStructure(String description) {
        Set<Integer> markerKinds = new HashSet<>();
        Set<Integer> indents = new HashSet<>();
        int leafResults = 0, ruleLines = 0, checkLines = 0;
        for (String line : description.split("\\R")) {
            if (line.isBlank()) continue;
            for (int k = 0; k < OUTLINE_MARKERS.size(); k++) {
                if (OUTLINE_MARKERS.get(k).matcher(line).find()) {
                    markerKinds.add(k);
                    indents.add(leadingWidth(line));
                    break;
                }
            }
            if (LEAF_RESULT.matcher(line).find()) leafResults++;
            if (NUMBERED_RULE_LINE.matcher(line).find()) ruleLines++;
            if (CHECK_LINE.matcher(line).find()) checkLines++;
        }
        int depth = Math.max(markerKinds.size(), indents.size());
        int dimensions = 0;
        var m = ENUM_DIMENSION.matcher(description);
        while (m.find()) dimensions++;

        double tree = 0, table = 0;
        if (depth >= 3) tree += 1.0;
        else if (depth == 2) tree += 0.5;
        if (depth >= 2 && leafResults >= 3) tree += 0.2;

        if (dimensions >= 2 && OUTPUT_WORD.matcher(description).find()) table += 0.6;
        if (ruleLines >= 2) table += 0.5;
        if (checkLines >= 2) table += 0.4;

        return new StructureSignals(table, tree, depth, dimensions, ruleLines, checkLines);
    }

    private static int leadingWidth(String line) {
        int w = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') w++;
            else if (c == '\t') w += 4;
            else if (c == '　') w += 2;
            else break;
        }
        return w;
    }

    private static boolean structureDecided(String ruleType, StructureSignals s) {
        return (RuleType.DECISION_TREE.getCode().equals(ruleType) && s.treeBoost() > 0)
                || (RuleType.DECISION_TABLE.getCode().equals(ruleType) && s.tableBoost() > 0);
    }

    /** 給業務使用者看的一句話理由（Q5：不列分數細節）。 */
    private static String plainReason(String ruleType, String method, StructureSignals s, String keywordReason) {
        if ("default".equals(method)) {
            return "描述裡看不出條件是同時比對還是有先後，先以決策表呈現。";
        }
        if (RuleType.DECISION_TREE.getCode().equals(ruleType)) {
            return s.treeBoost() > 0
                    ? "規格有 " + s.outlineDepth() + " 層分級，後面的條件要先看前面的結果，適合用決策樹。"
                    : "條件有先後順序，後面的判斷依前面的結果而定，適合用決策樹。";
        }
        if (RuleType.DECISION_TABLE.getCode().equals(ruleType)) {
            if (s.checkLines() >= 2 || s.ruleLines() >= 2) {
                return "是一條條獨立的檢核，各自判斷、各自拋訊息，適合用決策表（多重命中）。";
            }
            if (s.dimensionCount() >= 2) {
                return "有 " + s.dimensionCount() + " 個條件欄位平行組合、對應固定的輸出，適合用決策表。";
            }
            return "多個條件平行組合對應結果，沒有先後，適合用決策表。";
        }
        if (RuleType.SCORE_CARD.getCode().equals(ruleType)) {
            return "各項目分別給分再加總，依分數帶決定結果，適合用評分卡。";
        }
        return keywordReason;
    }

    // ========== LLM 二選一（低信心才用） ==========

    private void applyLlmClassification(String description, RecommendResponse response) {
        LlmProvider provider = llmProviderRegistry != null ? llmProviderRegistry.getDefault() : null;
        if (provider == null || !provider.isAvailable()) return;
        try {
            String raw = provider.classifyRuleType(buildClassifyPrompt(description));
            if (raw == null || raw.isBlank()) return;
            String cleaned = raw.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode node = JSON.readTree(cleaned);
            RuleType type = RuleType.fromString(node.path("ruleType").asText(""));
            if (!registry.isSupported(type)) return;
            String reason = node.path("reason").asText("").strip();
            response.setRecommendedRuleType(type.getCode());
            response.setReason(reason.isEmpty() ? plainReason(type.getCode(), "llm", analyzeStructure(description), "") : reason);
            response.setConfidence(Math.max(response.getConfidence(), 0.6));
            response.setMethod("llm");
            log.info("Recommend: LLM fallback via {} → {}", provider.getProviderName(), type.getCode());
        } catch (Exception e) {
            log.warn("Recommend: LLM fallback failed, keeping heuristic result: {}", e.getMessage());
        }
    }

    static String buildClassifyPrompt(String description) {
        String body = description.length() > LLM_PROMPT_MAX_CHARS
                ? description.substring(0, LLM_PROMPT_MAX_CHARS) + "…" : description;
        return """
                判斷下面這段業務規格最適合用哪一種規則型態，只回 JSON：
                {"ruleType":"DecisionTable 或 DecisionTree 或 ScoreCard","reason":"一句話，30 字內，用業務口吻"}

                定義：
                - DecisionTable：多個條件平行比對，每一列是一種條件組合對應一組輸出；條件之間沒有先後。
                - DecisionTree：條件有先後或層級，後面的條件只在前面某個分支下才需要看；像流程圖或分級大綱。
                - ScoreCard：各項目分別給分，加總後依分數帶決定結果。

                規格：
                """ + body;
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
