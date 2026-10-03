package com.ruleengine.rules.service.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("公平待遇分析：敏感維度結果分布與閾值警示")
class FairnessServiceTest {

    @Autowired FairnessService fairness;
    @Autowired ObjectMapper objectMapper;

    private RuleEnvelope table(String femaleDecision) throws Exception {
        return objectMapper.readValue("""
                {"ruleType":"DecisionTable","reason":"t","rule":{"hitPolicy":"FIRST",
                 "inputs":[{"name":"age","typeRef":"INTEGER"},{"name":"gender","typeRef":"ENUM","allowedValues":["男","女"]},{"name":"smoker","typeRef":"BOOLEAN"}],
                 "outputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","人工評估","拒保"]}],
                 "rules":[
                  {"ruleId":"R1","priority":1,"conditions":[{"field":"age","operator":"lessThanOrEqual","value":60},{"field":"gender","operator":"equals","value":"男"},{"field":"smoker","operator":"anything"}],"results":[{"field":"decision","value":"承保"}]},
                  {"ruleId":"R2","priority":2,"conditions":[{"field":"age","operator":"lessThanOrEqual","value":60},{"field":"gender","operator":"equals","value":"女"},{"field":"smoker","operator":"anything"}],"results":[{"field":"decision","value":"%s"}]},
                  {"ruleId":"R3","priority":3,"conditions":[{"field":"age","operator":"greaterThan","value":60},{"field":"gender","operator":"anything"},{"field":"smoker","operator":"anything"}],"results":[{"field":"decision","value":"人工評估"}]}
                 ]}}""".formatted(femaleDecision), RuleEnvelope.class);
    }

    @Test
    @DisplayName("女性一律拒保 → 性別維度的拒保率差距 100%，超過閾值警示；年齡與性別都被認成敏感維度")
    void flagsGenderGap() throws Exception {
        FairnessService.Report r = fairness.analyze(table("拒保"));
        assertTrue(r.sampleCount() > 0);
        assertTrue(r.dimensions().stream().anyMatch(d -> d.field().equals("gender")));
        assertTrue(r.dimensions().stream().anyMatch(d -> d.field().equals("age")));
        assertTrue(r.dimensions().stream().noneMatch(d -> d.field().equals("smoker")));
        FairnessService.Dimension gender = r.dimensions().stream().filter(d -> d.field().equals("gender")).findFirst().orElseThrow();
        assertTrue(gender.warning(), gender.toString());
        assertEquals("decision", gender.outputField());
        assertTrue(gender.maxGap() > r.threshold(), "gap=" + gender.maxGap());
        assertTrue(r.anyWarning());
    }

    @Test
    @DisplayName("男女同樣承保 → 性別維度無警示")
    void noGapNoWarning() throws Exception {
        FairnessService.Report r = fairness.analyze(table("承保"));
        FairnessService.Dimension gender = r.dimensions().stream().filter(d -> d.field().equals("gender")).findFirst().orElseThrow();
        assertFalse(gender.warning(), gender.toString());
        assertEquals(0.0, gender.maxGap(), 1e-9);
    }
}
