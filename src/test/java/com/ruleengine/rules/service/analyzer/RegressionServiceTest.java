package com.ruleengine.rules.service.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("合成資料批次回歸")
class RegressionServiceTest {

    @Autowired RegressionService regression;
    @Autowired ObjectMapper objectMapper;

    private RuleEnvelope table(int threshold, String decisionOver) throws Exception {
        return objectMapper.readValue("""
                {"ruleType":"DecisionTable","reason":"t","rule":{"hitPolicy":"FIRST",
                 "inputs":[{"name":"age","typeRef":"INTEGER"},{"name":"smoker","typeRef":"BOOLEAN"},{"name":"gender","typeRef":"ENUM","allowedValues":["男","女"]}],
                 "outputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","人工評估","拒保"]}],
                 "rules":[
                  {"ruleId":"R1","priority":1,"conditions":[{"field":"age","operator":"lessThanOrEqual","value":%d},{"field":"smoker","operator":"anything"},{"field":"gender","operator":"anything"}],"results":[{"field":"decision","value":"承保"}]},
                  {"ruleId":"R2","priority":2,"conditions":[{"field":"age","operator":"greaterThan","value":%d},{"field":"smoker","operator":"anything"},{"field":"gender","operator":"anything"}],"results":[{"field":"decision","value":"%s"}]}
                 ]}}""".formatted(threshold, threshold, decisionOver), RuleEnvelope.class);
    }

    @Test
    @DisplayName("門檻 50→45：45 到 50 之間的合成案件結果改變，其餘不變")
    void detectsChangedCases() throws Exception {
        RuleEnvelope before = table(50, "人工評估");
        RuleEnvelope after = table(45, "人工評估");
        RegressionService.Report r = regression.run(before, after);
        assertTrue(r.sampleCount() > 0);
        assertTrue(r.changedCount() > 0, "門檻改變應有案件結果不同");
        assertTrue(r.changedCount() < r.sampleCount());
        assertEquals(r.changedCount(), r.outputChanged());
        assertFalse(r.examples().isEmpty());
        var ex = r.examples().get(0);
        long age = ((Number) ex.input().get("age")).longValue();
        assertTrue(age > 45 && age <= 50, "改變的案件年齡應落在 46–50：" + age);
        assertEquals("承保", ex.before().outputs().get("decision"));
        assertEquals("人工評估", ex.after().outputs().get("decision"));
    }

    @Test
    @DisplayName("完全相同的兩版 → 0 筆改變")
    void identicalVersionsNoChange() throws Exception {
        RegressionService.Report r = regression.run(table(50, "拒保"), table(50, "拒保"));
        assertEquals(0, r.changedCount());
        assertEquals(0.0, r.changeRate());
    }
}
