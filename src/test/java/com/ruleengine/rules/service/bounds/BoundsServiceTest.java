package com.ruleengine.rules.service.bounds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("精算邊界：送審前逐列檢查")
class BoundsServiceTest {

    @Autowired BoundsService bounds;
    @Autowired ObjectMapper objectMapper;

    private RuleEnvelope table(String rows) throws Exception {
        return objectMapper.readValue("""
                {"ruleType":"DecisionTable","reason":"t","rule":{"hitPolicy":"FIRST",
                 "inputs":[{"name":"age","typeRef":"INTEGER"},{"name":"smoker","typeRef":"BOOLEAN"}],
                 "outputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","人工評估","拒保"]},{"name":"factor","typeRef":"DECIMAL"}],
                 "rules":[%s]}}""".formatted(rows), RuleEnvelope.class);
    }

    @Test
    @DisplayName("示範邊界檔載入：至少有係數上限與 66 歲禁止自動承保")
    void sampleLoaded() {
        assertTrue(bounds.isEnabled());
        assertTrue(bounds.current().getConstraints().size() >= 3);
        assertTrue(bounds.current().getConstraints().stream().anyMatch(c -> "premium-factor-max".equals(c.getId())));
    }

    @Test
    @DisplayName("係數 2.5 超過上限 2.2 → 違規；66–80 歲承保 → 違規；30–50 歲承保 → 通過")
    void detectsViolations() throws Exception {
        RuleEnvelope env = table("""
                {"ruleId":"R1","priority":1,"conditions":[{"field":"age","operator":"between","value":[30,50]},{"field":"smoker","operator":"equals","value":false}],"results":[{"field":"decision","value":"承保"},{"field":"factor","value":1.0}]},
                {"ruleId":"R2","priority":2,"conditions":[{"field":"age","operator":"between","value":[51,65]},{"field":"smoker","operator":"equals","value":true}],"results":[{"field":"decision","value":"人工評估"},{"field":"factor","value":2.5}]},
                {"ruleId":"R3","priority":3,"conditions":[{"field":"age","operator":"between","value":[66,80]},{"field":"smoker","operator":"anything"}],"results":[{"field":"decision","value":"承保"},{"field":"factor","value":1.2}]},
                {"ruleId":"R4","priority":4,"conditions":[{"field":"age","operator":"greaterThan","value":60},{"field":"smoker","operator":"anything"}],"results":[{"field":"decision","value":"人工評估"},{"field":"factor","value":1.5}]}
                """);
        BoundsService.BoundsReport r = bounds.check(env);
        assertTrue(r.checked());
        assertEquals(2, r.violations().size(), r.violations().toString());
        assertTrue(r.violations().stream().anyMatch(v -> "R2".equals(v.ruleId()) && "premium-factor-max".equals(v.constraintId())));
        assertTrue(r.violations().stream().anyMatch(v -> "R3".equals(v.ruleId()) && "no-auto-accept-over-65".equals(v.constraintId())));
    }

    @Test
    @DisplayName("greaterThan 60 且承保：與 66+ 有交集 → 違規；lessThan 66 承保 → 通過")
    void rangeOverlap() throws Exception {
        RuleEnvelope env = table("""
                {"ruleId":"R1","priority":1,"conditions":[{"field":"age","operator":"greaterThan","value":60},{"field":"smoker","operator":"anything"}],"results":[{"field":"decision","value":"承保"},{"field":"factor","value":1.0}]},
                {"ruleId":"R2","priority":2,"conditions":[{"field":"age","operator":"lessThan","value":66},{"field":"smoker","operator":"anything"}],"results":[{"field":"decision","value":"承保"},{"field":"factor","value":1.0}]}
                """);
        BoundsService.BoundsReport r = bounds.check(env);
        assertEquals(1, r.violations().size(), r.violations().toString());
        assertEquals("R1", r.violations().get(0).ruleId());
    }
}
