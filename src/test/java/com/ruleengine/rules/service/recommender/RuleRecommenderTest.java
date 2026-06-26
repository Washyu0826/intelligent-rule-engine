package com.ruleengine.rules.service.recommender;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.RecommendResponse;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.registry.RuleTypeRegistry;
import com.ruleengine.rules.service.generator.RuleGenerator;
import com.ruleengine.rules.service.validator.RuleValidator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RuleRecommender 測試 — 計畫書 §九 KPI：
 * Recommend 分類準確率 ≥ 80%（以案例測）
 *
 * v1.3.0 強化：新增英文 / 中英夾雜測試（DECISIONS.md Q27）
 */
@DisplayName("RuleRecommender - 分類準確率")
class RuleRecommenderTest {

    private RuleRecommender recommender;

    @BeforeEach
    void setUp() {
        // 建立 stub generator/validator 讓 registry 認為三種型態都已註冊
        RuleGenerator tableGen = new StubGenerator(RuleType.DECISION_TABLE);
        RuleGenerator treeGen = new StubGenerator(RuleType.DECISION_TREE);
        RuleGenerator scoreGen = new StubGenerator(RuleType.SCORE_CARD);
        RuleValidator tableVal = new StubValidator(RuleType.DECISION_TABLE);
        RuleValidator treeVal = new StubValidator(RuleType.DECISION_TREE);
        RuleValidator scoreVal = new StubValidator(RuleType.SCORE_CARD);

        RuleTypeRegistry registry = new RuleTypeRegistry(
                List.of(tableGen, treeGen, scoreGen),
                List.of(tableVal, treeVal, scoreVal)
        );
        registry.init();
        recommender = new RuleRecommender(registry);
    }

    // ================================================================
    // DecisionTable 案例（中文）
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "根據客戶年齡和性別的組合對照不同的壽險費率係數, DecisionTable",
            "當 20-30 歲男性規律運動滿 7500 步且同時符合睡眠條件可獲折減, DecisionTable",
            "訂單金額 0-999 免運 1000-2999 折扣 5% 3000 以上折扣 10% 會員再多 3%, DecisionTable",
            "根據地區和車齡組合對應不同保費係數的費率表, DecisionTable",
            "員工業績達標且出勤率 95% 以上同時滿足兩項條件為 A 等, DecisionTable"
    })
    @DisplayName("DecisionTable 分類（中文）")
    void shouldRecommendDecisionTable(String description, String expected) {
        RecommendResponse resp = recommender.recommend(description);
        assertEquals(expected, resp.getRecommendedRuleType(),
                "「" + description + "」→ " + resp.getRecommendedRuleType()
                        + "（原因：" + resp.getReason() + "）");
    }

    // ================================================================
    // DecisionTable 案例（英文）— Q27 新增
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "discount based on membership level and purchase amount, DecisionTable",
            "premium rate table by age and gender combination, DecisionTable",
            "if region is Asia and order amount > 100 then free shipping, DecisionTable"
    })
    @DisplayName("DecisionTable 分類（英文）")
    void shouldRecommendDecisionTableEnglish(String description, String expected) {
        RecommendResponse resp = recommender.recommend(description);
        assertEquals(expected, resp.getRecommendedRuleType(),
                "\"" + description + "\" → " + resp.getRecommendedRuleType()
                        + " (reason: " + resp.getReason() + ")");
    }

    // ================================================================
    // DecisionTree 案例（中文）
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "先判斷年齡是否超過 60 歲若超過則拒絕否則再看 BMI, DecisionTree",
            "貸款審核先看是否有信用不良紀錄有的話直接拒絕沒有再看年收入, DecisionTree",
            "客訴處理先判斷是否為 VIP 客戶是的話轉主管否則再看金額, DecisionTree",
            "醫療理賠先看是否住院是住院再看天數門診則看是否有轉診單, DecisionTree",
            "風險評估先判斷投資金額是否超過 100 萬超過再看客戶年齡, DecisionTree"
    })
    @DisplayName("DecisionTree 分類（中文）")
    void shouldRecommendDecisionTree(String description, String expected) {
        RecommendResponse resp = recommender.recommend(description);
        assertEquals(expected, resp.getRecommendedRuleType(),
                "「" + description + "」→ " + resp.getRecommendedRuleType()
                        + "（原因：" + resp.getReason() + "）");
    }

    // ================================================================
    // DecisionTree 案例（英文）— Q27 新增
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "first check credit history then check annual income if else reject, DecisionTree",
            "sequential evaluation: first check age then check BMI nested conditions, DecisionTree"
    })
    @DisplayName("DecisionTree 分類（英文）")
    void shouldRecommendDecisionTreeEnglish(String description, String expected) {
        RecommendResponse resp = recommender.recommend(description);
        assertEquals(expected, resp.getRecommendedRuleType(),
                "\"" + description + "\" → " + resp.getRecommendedRuleType()
                        + " (reason: " + resp.getReason() + ")");
    }

    // ================================================================
    // ScoreCard 案例（中文）
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "健康管理獎勵每日步數達 8000 步得 10 分 BMI 正常得 20 分總分 60 以上為金牌, ScoreCard",
            "信用評等還款紀錄 0-30 分負債比率 0-25 分總分 80 以上為優等, ScoreCard"
    })
    @DisplayName("ScoreCard 分類（中文）")
    void shouldRecommendScoreCard(String description, String expected) {
        RecommendResponse resp = recommender.recommend(description);
        assertEquals(expected, resp.getRecommendedRuleType(),
                "「" + description + "」→ " + resp.getRecommendedRuleType());
    }

    // ================================================================
    // ScoreCard 案例（英文）— Q27 新增
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "credit score based on weighted points from income and debt ratio with threshold 700, ScoreCard"
    })
    @DisplayName("ScoreCard 分類（英文）")
    void shouldRecommendScoreCardEnglish(String description, String expected) {
        RecommendResponse resp = recommender.recommend(description);
        assertEquals(expected, resp.getRecommendedRuleType(),
                "\"" + description + "\" → " + resp.getRecommendedRuleType()
                        + " (reason: " + resp.getReason() + ")");
    }

    // ================================================================
    // 中英夾雜案例 — Q27 新增
    // ================================================================

    @ParameterizedTest
    @CsvSource({
            "年齡+高血壓核保 lookup table 對照保費, DecisionTable",
            "VIP 客戶 first check 信用紀錄再看 income level, DecisionTree"
    })
    @DisplayName("中英夾雜分類")
    void shouldRecommendMixedLanguage(String description, String expected) {
        RecommendResponse resp = recommender.recommend(description);
        assertEquals(expected, resp.getRecommendedRuleType(),
                "「" + description + "」→ " + resp.getRecommendedRuleType()
                        + "（原因：" + resp.getReason() + "）");
    }

    // ================================================================
    // 語句結構偵測案例 — Q27 新增
    // ================================================================

    @Test
    @DisplayName("多個 BOOLEAN 描述 → +Table")
    void featurePatternBooleanDescriptions() {
        RecommendResponse resp = recommender.recommend("是否吸菸、有無慢性病、是否有家族病史來決定費率");
        assertEquals("DecisionTable", resp.getRecommendedRuleType());
        assertTrue(resp.getConfidence() >= 0.3);
    }

    @Test
    @DisplayName("數值區間描述 → +Table")
    void featurePatternNumericRange() {
        RecommendResponse resp = recommender.recommend("18-35歲費率A，36-55歲費率B，56以上費率C");
        assertEquals("DecisionTable", resp.getRecommendedRuleType());
        assertTrue(resp.getConfidence() >= 0.3);
    }

    @Test
    @DisplayName("加總描述 → +ScoreCard")
    void featurePatternSummation() {
        RecommendResponse resp = recommender.recommend("各項得分加總合計後依總分門檻決定信用等級");
        assertEquals("ScoreCard", resp.getRecommendedRuleType());
        assertTrue(resp.getConfidence() >= 0.5);
    }

    // ================================================================
    // confidence 門檻驗證 — 驗收標準
    // ================================================================

    @Test
    @DisplayName("年齡+高血壓核保 → confidence >= 0.5 且推薦 DecisionTable")
    void confidenceThresholdChinese() {
        RecommendResponse resp = recommender.recommend("年齡+高血壓核保");
        assertEquals("DecisionTable", resp.getRecommendedRuleType());
        assertTrue(resp.getConfidence() >= 0.5,
                "confidence=" + resp.getConfidence() + " 應 >= 0.5");
    }

    @Test
    @DisplayName("英文 discount 描述 → 推薦 DecisionTable")
    void confidenceThresholdEnglish() {
        RecommendResponse resp = recommender.recommend(
                "discount based on membership level and purchase amount");
        assertEquals("DecisionTable", resp.getRecommendedRuleType());
        assertTrue(resp.getConfidence() > 0.0);
    }

    // ================================================================
    // 邊界案例
    // ================================================================

    @Test
    @DisplayName("空描述 → 預設 DecisionTable")
    void emptyDescription() {
        RecommendResponse resp = recommender.recommend("");
        assertEquals("DecisionTable", resp.getRecommendedRuleType());
        assertEquals(0.0, resp.getConfidence());
    }

    @Test
    @DisplayName("null 描述 → 預設 DecisionTable")
    void nullDescription() {
        RecommendResponse resp = recommender.recommend(null);
        assertEquals("DecisionTable", resp.getRecommendedRuleType());
    }

    @Test
    @DisplayName("模糊描述 → 仍能給出推薦")
    void ambiguousDescription() {
        RecommendResponse resp = recommender.recommend("天氣好就出門");
        assertNotNull(resp.getRecommendedRuleType());
        assertNotNull(resp.getReason());
    }

    @Test
    @DisplayName("alternatives 包含其他候選型態")
    void alternativesPresent() {
        RecommendResponse resp = recommender.recommend("先判斷年齡再看BMI");
        assertNotNull(resp.getAlternatives());
        assertFalse(resp.getAlternatives().isEmpty());
    }

    // ================================================================
    // 整體準確率（含中文/英文/中英夾雜，共 18 筆明確案例）
    // ================================================================

    @Test
    @DisplayName("18 筆明確案例的整體準確率 ≥ 80%")
    void overallAccuracy() {
        String[][] cases = {
                // 中文 DecisionTable（5 筆）
                {"根據客戶年齡和性別組合對照費率", "DecisionTable"},
                {"同時滿足運動和睡眠條件可獲折減", "DecisionTable"},
                {"訂單金額對照折扣表查表", "DecisionTable"},
                {"地區和車齡組合費率表", "DecisionTable"},
                {"業績達標且出勤率同時滿足為A等", "DecisionTable"},
                // 中文 DecisionTree（5 筆）
                {"先判斷年齡是否超過60歲再看BMI", "DecisionTree"},
                {"先看信用紀錄有不良直接拒絕否則看年收入", "DecisionTree"},
                {"先判斷VIP再看客訴金額", "DecisionTree"},
                {"先看住院再看天數再判斷理賠等級", "DecisionTree"},
                {"先判斷投資金額超過100萬再看年齡", "DecisionTree"},
                // 中文 ScoreCard（2 筆）
                {"各得分項加總總分對應等級評分", "ScoreCard"},
                {"還款紀錄得分加上負債得分總分評等計分", "ScoreCard"},
                // 英文（3 筆）
                {"discount based on membership level and purchase amount", "DecisionTable"},
                {"first check credit history then check income if else reject", "DecisionTree"},
                {"credit score based on weighted points with threshold 700", "ScoreCard"},
                // 中英夾雜（2 筆）
                {"年齡+高血壓核保 lookup table 對照保費", "DecisionTable"},
                {"VIP 客戶 first check 信用紀錄再看 income", "DecisionTree"},
                // 語句結構偵測（1 筆）
                {"各項得分加總合計後依總分門檻決定信用等級", "ScoreCard"}
        };

        int correct = 0;
        StringBuilder report = new StringBuilder();
        for (String[] c : cases) {
            RecommendResponse resp = recommender.recommend(c[0]);
            boolean match = c[1].equals(resp.getRecommendedRuleType());
            if (match) correct++;
            report.append(match ? "✓" : "✗").append(" ")
                    .append(c[1]).append(" → ").append(resp.getRecommendedRuleType())
                    .append(" (").append(String.format("%.2f", resp.getConfidence())).append(")")
                    .append(" | ").append(c[0], 0, Math.min(c[0].length(), 40))
                    .append("\n");
        }
        double accuracy = (double) correct / cases.length;
        String msg = String.format("準確率 %.0f%% (%d/%d)\n%s",
                accuracy * 100, correct, cases.length, report);
        System.out.println(msg);
        assertTrue(accuracy >= 0.8, msg);
    }

    // ================================================================
    // Stubs（純單元測試，不需 Spring）
    // ================================================================

    private record StubGenerator(RuleType type) implements RuleGenerator {
        @Override public RuleType supportedType() { return type; }
        @Override public JsonNode generate(String d, List<String> f) { return null; }
    }

    private record StubValidator(RuleType type) implements RuleValidator {
        @Override public RuleType supportedType() { return type; }
        @Override public List<ValidationError> validate(JsonNode p) { return List.of(); }
        @Override public List<ValidationError> checkConsistency(JsonNode p) { return List.of(); }
    }
}
