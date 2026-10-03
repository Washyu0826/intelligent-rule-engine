package com.ruleengine.rules.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.dto.ToolDtos;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.RuleLookupService;
import com.ruleengine.rules.service.RuleService;
import com.ruleengine.rules.service.analyzer.AnalysisResult;
import com.ruleengine.rules.service.analyzer.ScoreCardAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("評分卡：執行、查詢、分數帶分析")
class ScoreCardExecutionTest {

    @Autowired RuleExecutionEngine engine;
    @Autowired RuleLookupService lookup;
    @Autowired ScoreCardAnalyzer analyzer;
    @Autowired RuleService ruleService;
    @Autowired ObjectMapper objectMapper;

    static final String CARD = """
            {"ruleType":"ScoreCard","reason":"核保風險評分",
             "rule":{"inputs":[{"name":"age","typeRef":"INTEGER"},{"name":"smoker","typeRef":"BOOLEAN"},{"name":"bmi","typeRef":"ENUM","allowedValues":["正常","過重","肥胖"]}],
              "outputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","加費","人工評估"]}],
              "scoringDimensions":[
               {"field":"age","weight":1.0,"scoringRules":[
                 {"ruleId":"S01","condition":{"field":"age","operator":"lessThan","value":40},"score":0},
                 {"ruleId":"S02","condition":{"field":"age","operator":"between","value":[40,59]},"score":10},
                 {"ruleId":"S03","condition":{"field":"age","operator":"anything"},"score":25}]},
               {"field":"smoker","weight":2.0,"scoringRules":[
                 {"ruleId":"S04","condition":{"field":"smoker","operator":"equals","value":true},"score":10}]},
               {"field":"bmi","weight":1.0,"scoringRules":[
                 {"ruleId":"S05","condition":{"field":"bmi","operator":"equals","value":"過重"},"score":5},
                 {"ruleId":"S06","condition":{"field":"bmi","operator":"equals","value":"肥胖"},"score":15}]}],
              "scoreBands":[
               {"bandId":"B01","minScore":0,"maxScore":14,"results":[{"field":"decision","value":"承保"}]},
               {"bandId":"B02","minScore":15,"maxScore":34,"results":[{"field":"decision","value":"加費"}]},
               {"bandId":"B03","minScore":40,"maxScore":100,"results":[{"field":"decision","value":"人工評估"}]}]}}""";

    private RuleEnvelope card() throws Exception {
        return objectMapper.readValue(CARD, RuleEnvelope.class);
    }

    @Test
    @DisplayName("執行：各維度首條成立規則 × 權重加總，落入分數帶；FULL trace 記每個維度")
    void executes() throws Exception {
        ExecutionResult r = engine.execute(card(), Map.of("age", 45, "smoker", true, "bmi", "正常"), TraceLevel.FULL);
        assertTrue(r.isMatched());
        assertEquals("B02", r.getMatchedRules().get(0).getRuleId());
        assertEquals(30L, r.getOutputs().get("totalScore"));   // 10 + 10×2 + 0
        assertEquals("加費", r.getOutputs().get("decision"));
        assertEquals(3, r.getTrace().getSteps().size());

        ExecutionResult low = engine.execute(card(), Map.of("age", 30, "smoker", false, "bmi", "正常"), TraceLevel.NONE);
        assertEquals("承保", low.getOutputs().get("decision"));
        assertEquals(0L, low.getOutputs().get("totalScore"));
    }

    @Test
    @DisplayName("總分落在分數帶缺口（35–39）→ 未命中、不擲例外；40 落入 B03")
    void gapScoreIsUnmatched() throws Exception {
        ExecutionResult gap = engine.execute(card(), Map.of("age", 45, "smoker", true, "bmi", "過重"), TraceLevel.SUMMARY);
        assertFalse(gap.isMatched());   // 10 + 10×2 + 5 = 35
        assertTrue(gap.getOutputs().isEmpty());

        ExecutionResult edge = engine.execute(card(), Map.of("age", 70, "smoker", false, "bmi", "肥胖"), TraceLevel.SUMMARY);
        assertTrue(edge.isMatched());   // 25 + 0 + 15 = 40
        assertEquals("B03", edge.getMatchedRules().get(0).getRuleId());
        assertEquals("人工評估", edge.getOutputs().get("decision"));
    }

    @Test
    @DisplayName("/tools/execute 走的 lookup 也支援評分卡，路徑逐維度列出")
    void lookupWorks() throws Exception {
        RuleLookupService.LookupResponse r = lookup.lookup(card(), Map.of("age", 62, "smoker", false, "bmi", "正常"));
        assertTrue(r.matched());
        assertEquals("B02", r.matchedRules().get(0).ruleId());
        assertEquals(25L, r.matchedRules().get(0).results().get("totalScore"));
        assertTrue(r.evaluationPath().stream().anyMatch(p -> p.startsWith("age: S03")));
    }

    @Test
    @DisplayName("分析：分數帶 35–39 是缺口；smoker=false 沒計分規則；/tools/analyze 走評分卡分析器")
    void analyzesBands() throws Exception {
        AnalysisResult a = analyzer.analyze(card());
        assertTrue(a.getGaps().stream().anyMatch(g -> g.getMessage().contains("[35,39]")), a.getGaps().toString());
        assertTrue(a.getGaps().stream().anyMatch(g -> g.getMessage().contains("smoker") && g.getMessage().contains("false")));
        assertTrue(a.getOverlaps().isEmpty());
        assertTrue(a.getCoverageRate() > 0.8 && a.getCoverageRate() < 1.0, "coverage=" + a.getCoverageRate());

        ToolDtos.AnalyzeResponse resp = ruleService.analyze(ToolDtos.AnalyzeRequest.builder()
                .ruleJson(objectMapper.readTree(CARD)).ruleType("ScoreCard").build());
        assertFalse(resp.getGaps().isEmpty());
    }
}
