package com.ruleengine.rules.service.exporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("DMN 匯出 + Camunda 交叉驗證")
class DmnExportAndCrossCheckTest {

    @Autowired DmnExporter exporter;
    @Autowired DmnCrossCheckService crossCheck;
    @Autowired ObjectMapper objectMapper;

    static final String TABLE = """
            {"ruleType":"DecisionTable","reason":"核保示範",
             "rule":{"hitPolicy":"FIRST",
              "inputs":[{"name":"age","typeRef":"INTEGER"},{"name":"gender","typeRef":"ENUM","allowedValues":["男","女"]},
                        {"name":"smoker","typeRef":"BOOLEAN"},{"name":"bmi","typeRef":"DECIMAL"}],
              "outputs":[{"name":"decision","typeRef":"ENUM","allowedValues":["承保","人工評估","拒保"]},
                         {"name":"factor","typeRef":"DECIMAL"},{"name":"note","typeRef":"STRING"}],
              "rules":[
               {"ruleId":"R1","priority":1,"conditions":[{"field":"age","operator":"between","value":[18,50]},{"field":"gender","operator":"in","value":["男","女"]},{"field":"smoker","operator":"equals","value":false},{"field":"bmi","operator":"lessThan","value":30}],"results":[{"field":"decision","value":"承保"},{"field":"factor","value":1.0},{"field":"note","value":"標準體"}]},
               {"ruleId":"R2","priority":2,"conditions":[{"field":"age","operator":"between","value":[18,50]},{"field":"gender","operator":"anything"},{"field":"smoker","operator":"equals","value":true},{"field":"bmi","operator":"anything"}],"results":[{"field":"decision","value":"人工評估"},{"field":"factor","value":1.5},{"field":"note","value":"吸菸"}]},
               {"ruleId":"R3","priority":3,"conditions":[{"field":"age","operator":"greaterThan","value":50},{"field":"gender","operator":"anything"},{"field":"smoker","operator":"anything"},{"field":"bmi","operator":"anything"}],"results":[{"field":"decision","value":"拒保"},{"field":"factor","value":null},{"field":"note","value":"超齡"}]}
              ]}}""";

    static final String CHECKS = """
            {"ruleType":"DecisionTable","reason":"檢核清單",
             "rule":{"hitPolicy":"MULTI",
              "inputs":[{"name":"idFormat","typeRef":"ENUM","allowedValues":["LOCAL","FOREIGN"]},{"name":"nationality","typeRef":"ENUM","allowedValues":["TW","OTHER"]}],
              "outputs":[{"name":"errorCode","typeRef":"STRING"},{"name":"errorMessage","typeRef":"STRING"}],
              "rules":[
               {"ruleId":"C1","priority":1,"conditions":[{"field":"idFormat","operator":"equals","value":"LOCAL"},{"field":"nationality","operator":"notEquals","value":"TW"}],"results":[{"field":"errorCode","value":"1.1"},{"field":"errorMessage","value":"證號為本國格式，國籍請選本國"}]},
               {"ruleId":"C2","priority":2,"conditions":[{"field":"idFormat","operator":"notEquals","value":"LOCAL"},{"field":"nationality","operator":"equals","value":"TW"}],"results":[{"field":"errorCode","value":"1.2"},{"field":"errorMessage","value":"證號非本國格式，國籍請選非本國"}]}
              ]}}""";

    private RuleEnvelope env(String json) throws Exception {
        return objectMapper.readValue(json, RuleEnvelope.class);
    }

    @Test
    @DisplayName("匯出的 XML 是 DMN 1.3，條件寫成 FEEL unary tests")
    void exportsFeelEntries() throws Exception {
        DmnExporter.DmnExport out = exporter.export(env(TABLE), "underwriting");
        String xml = out.xml();
        assertTrue(xml.contains(DmnExporter.DMN_NS), xml);
        assertTrue(xml.contains("hitPolicy=\"FIRST\""));
        assertTrue(xml.contains("<text>[18..50]</text>"));
        assertTrue(xml.contains("<text>&quot;男&quot;,&quot;女&quot;</text>"));
        assertTrue(xml.contains("<text>false</text>"));
        assertTrue(xml.contains("<text>&lt; 30</text>"));
        assertTrue(xml.contains("<text>&gt; 50</text>"));
        assertTrue(xml.contains("<text>-</text>"));
        assertTrue(xml.contains("typeRef=\"integer\""));
        assertTrue(xml.contains("<text>&quot;承保&quot;</text>"));
        assertTrue(out.warnings().isEmpty(), out.warnings().toString());
    }

    @Test
    @DisplayName("內建引擎與 Camunda DMN 引擎在命中、未命中、多重命中都一致")
    void crossCheckAgrees() throws Exception {
        RuleEnvelope table = env(TABLE);
        for (Map<String, Object> input : List.<Map<String, Object>>of(
                Map.of("age", 30, "gender", "男", "smoker", false, "bmi", 22.5),
                Map.of("age", 30, "gender", "女", "smoker", true, "bmi", 31.0),
                Map.of("age", 66, "gender", "男", "smoker", false, "bmi", 22.5),
                Map.of("age", 10, "gender", "男", "smoker", false, "bmi", 22.5))) {
            DmnCrossCheckService.CrossCheck c = crossCheck.check(table, input);
            assertTrue(c.consistent(), input + " → " + c.differences());
            assertEquals(c.ours().isMatched(), c.dmnMatched(), input.toString());
        }
        DmnCrossCheckService.CrossCheck first = crossCheck.check(table, Map.of("age", 30, "gender", "男", "smoker", false, "bmi", 22.5));
        assertEquals("承保", first.dmnResults().get(0).get("decision"));

        RuleEnvelope checks = env(CHECKS);
        DmnCrossCheckService.CrossCheck multi = crossCheck.check(checks, Map.of("idFormat", "LOCAL", "nationality", "OTHER"));
        assertTrue(multi.consistent(), multi.differences().toString());
        assertEquals(1, multi.dmnResults().size());
        DmnCrossCheckService.CrossCheck none = crossCheck.check(checks, Map.of("idFormat", "LOCAL", "nationality", "TW"));
        assertTrue(none.consistent(), none.differences().toString());
        assertFalse(none.dmnMatched());
    }

    @Test
    @DisplayName("決策樹先攤成表再匯出，並附上警告")
    void treeIsFlattened() throws Exception {
        String tree = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("demo_tree_envelope.json")), java.nio.charset.StandardCharsets.UTF_8);
        DmnExporter.DmnExport out = exporter.export(env(tree), "claims");
        assertTrue(out.xml().contains("<decisionTable"));
        assertTrue(out.warnings().stream().anyMatch(w -> w.contains("攤平")));
        DmnCrossCheckService.CrossCheck c = crossCheck.check(env(tree),
                Map.of("policyStatus", "LAPSED", "incidentType", "ACCIDENT", "isDrunkDriving", false,
                        "exceededWaitingPeriod", true, "claimAmount", 1000));
        assertTrue(c.consistent(), c.differences().toString());
    }
}
