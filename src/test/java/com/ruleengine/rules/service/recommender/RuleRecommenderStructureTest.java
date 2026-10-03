package com.ruleengine.rules.service.recommender;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.RecommendResponse;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.registry.RuleTypeRegistry;
import com.ruleengine.rules.service.generator.RuleGenerator;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import com.ruleengine.rules.service.validator.RuleValidator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 整段規格的結構訊號 + 低信心 LLM 後備 + 反問。
 * 案例為合成版，欄位與代碼皆為通用寫法。
 */
@DisplayName("RuleRecommender - 結構訊號與後備")
class RuleRecommenderStructureTest {

    static final String NESTED_OUTLINE = """
            1.無症狀/輕症：
              (1)未痊癒-----延期。
              (2)已痊癒：
                    A.無後遺症-----標準體。
                    B.有後遺症
                        (a)無嗅覺喪失---需體檢
                        (b)有嗅覺喪失---需體檢+除外
            2.中症：
              (1)未痊癒（或住院中）-----延期。
              (2)已出院且痊癒<=3個月-----延期。
              (3)已出院且痊癒>3個月：
                   A.無後遺症-----標準體。
                   B.有後遺症
                        I.無嗅覺喪失---需體檢
                        II.有嗅覺喪失---需體檢+除外
            """;

    static final String UNDERWRITING_TABLE = """
            根據被保人年齡（18–35、36–50、51–65、66歲以上）、性別（男/女）、是否有高血壓、糖尿病、心臟病病史，
            輸出三個結果：核保決議（承保／人工評估／拒保）、保費係數（1.0／1.1／1.2／1.5／2.0，拒保時為空值）、備註說明（文字）。
            """;

    static final String NUMBERED_CHECKS = """
            1.若[被保人證號]為[本國證號格式]且[被保人國籍]不為[本國]則拋訊息1.1 證號為本國格式，國籍請選擇本國。
            2.若[被保人證號]不為[本國證號格式]且[被保人國籍]為[本國]則拋訊息1.2 證號非本國格式，國籍請選擇非本國。
            3.若[要保人證號]為[本國證號格式]且[要保人國籍]不為[本國]則拋訊息1.3 證號為本國格式，國籍請選擇本國。
            """;

    static final String CONDITION_CHECKS = """
            檢核範圍：受理通路為[行動投保]或[網路投保]
            條件2.1：若通路為[代理]且[授權編號]為[受理編號]
            檢核2.1：[授權編號]應符合[受理編號規則]，否則拋出訊息
            條件3.1：[繳費管道]為[員工轉帳]
            檢核3.1：[要保人證號]須為[員工]，否則拋出訊息
            條件4.2：所有案件
            檢核4.2：[投保始期]不可小於[被保人生日]，否則拋出訊息
            """;

    private RuleTypeRegistry registry;
    private RuleRecommender recommender;

    @BeforeEach
    void setUp() {
        registry = new RuleTypeRegistry(
                List.of(new G(RuleType.DECISION_TABLE), new G(RuleType.DECISION_TREE), new G(RuleType.SCORE_CARD)),
                List.of(new V(RuleType.DECISION_TABLE), new V(RuleType.DECISION_TREE), new V(RuleType.SCORE_CARD)));
        registry.init();
        recommender = new RuleRecommender(registry);
    }

    @Test
    @DisplayName("多層編號大綱 → 決策樹，理由說到分級層數")
    void nestedOutlineIsTree() {
        RecommendResponse r = recommender.recommend(NESTED_OUTLINE);
        assertEquals("DecisionTree", r.getRecommendedRuleType());
        assertEquals("structure", r.getMethod());
        assertTrue(r.getConfidence() >= 0.55, "confidence=" + r.getConfidence());
        assertTrue(r.getReason().contains("層分級"), r.getReason());
        assertNull(r.getNeedsClarification());
    }

    @Test
    @DisplayName("列舉維度＋輸出 → 決策表，理由說到欄位數")
    void enumeratedDimensionsIsTable() {
        RecommendResponse r = recommender.recommend(UNDERWRITING_TABLE);
        assertEquals("DecisionTable", r.getRecommendedRuleType());
        assertEquals("structure", r.getMethod());
        assertTrue(r.getReason().contains("條件欄位"), r.getReason());
    }

    @Test
    @DisplayName("逐條「若…則拋訊息」 → 決策表（多重命中），不被編號誤判成樹")
    void numberedChecksIsTable() {
        RecommendResponse r = recommender.recommend(NUMBERED_CHECKS);
        assertEquals("DecisionTable", r.getRecommendedRuleType());
        assertTrue(r.getReason().contains("檢核"), r.getReason());
    }

    @Test
    @DisplayName("條件 x.y／檢核 x.y 格式 → 決策表")
    void conditionCheckLinesIsTable() {
        RecommendResponse r = recommender.recommend(CONDITION_CHECKS);
        assertEquals("DecisionTable", r.getRecommendedRuleType());
        assertEquals("structure", r.getMethod());
    }

    @Test
    @DisplayName("沒有任何訊號、沒有 LLM → 預設決策表並反問")
    void noSignalAsksClarification() {
        RecommendResponse r = recommender.recommend("症狀輕微且已痊癒的人可以承保");
        assertEquals("DecisionTable", r.getRecommendedRuleType());
        assertEquals("default", r.getMethod());
        assertEquals(Boolean.TRUE, r.getNeedsClarification());
        assertNotNull(r.getClarifyingQuestion());
        assertEquals(3, r.getClarifyOptions().size());
    }

    @Test
    @DisplayName("低信心時交給 LLM 二選一，採用其答案與理由")
    void lowConfidenceUsesLlm() {
        LlmProvider stub = new StubLlm("{\"ruleType\":\"DecisionTree\",\"reason\":\"先看病史再看痊癒時間\"}");
        recommender.setLlmProviderRegistry(new LlmProviderRegistry(List.of(stub), "stub"));

        RecommendResponse r = recommender.recommend("症狀輕微且已痊癒的人可以承保");
        assertEquals("DecisionTree", r.getRecommendedRuleType());
        assertEquals("llm", r.getMethod());
        assertEquals("先看病史再看痊癒時間", r.getReason());
        assertTrue(r.getConfidence() >= 0.6);
        assertNull(r.getNeedsClarification());
    }

    @Test
    @DisplayName("LLM 回垃圾時保留啟發式結果並反問")
    void llmGarbageFallsBackToHeuristic() {
        recommender.setLlmProviderRegistry(new LlmProviderRegistry(List.of(new StubLlm("not json")), "stub"));
        RecommendResponse r = recommender.recommend("症狀輕微且已痊癒的人可以承保");
        assertEquals("default", r.getMethod());
        assertEquals(Boolean.TRUE, r.getNeedsClarification());
    }

    @Test
    @DisplayName("高信心時不呼叫 LLM")
    void highConfidenceSkipsLlm() {
        StubLlm stub = new StubLlm("{\"ruleType\":\"ScoreCard\",\"reason\":\"x\"}");
        recommender.setLlmProviderRegistry(new LlmProviderRegistry(List.of(stub), "stub"));
        RecommendResponse r = recommender.recommend(NESTED_OUTLINE);
        assertEquals("DecisionTree", r.getRecommendedRuleType());
        assertEquals(0, stub.calls);
    }

    // ---- stubs ----

    static class StubLlm implements LlmProvider {
        final String reply;
        int calls = 0;
        StubLlm(String reply) { this.reply = reply; }
        @Override public String generateRuleJson(String description) { return null; }
        @Override public String classifyRuleType(String prompt) { calls++; return reply; }
        @Override public boolean isAvailable() { return true; }
        @Override public String getProviderName() { return "stub"; }
    }

    record G(RuleType type) implements RuleGenerator {
        @Override public RuleType supportedType() { return type; }
        @Override public JsonNode generate(String d, List<String> f) { return null; }
    }

    record V(RuleType type) implements RuleValidator {
        @Override public RuleType supportedType() { return type; }
        @Override public List<ValidationError> validate(JsonNode p) { return List.of(); }
        @Override public List<ValidationError> checkConsistency(JsonNode p) { return List.of(); }
    }
}
