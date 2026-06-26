package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioExpandRequest;
import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioExpandResponse;
import com.ruleengine.rules.domain.dto.ToolDtos.ScenarioRow;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.exporter.InternalEngineExporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.12 — 兩個 demo envelope 端到端閉環：
 *   - demo_id_nationality_envelope.json（情境 1：ID 格式 / 國籍）
 *   - demo_policy_issue_envelope.json（情境 2：通路 / 授權書 / 繳費 / 投保始期）
 *
 * 確保 demo 期間：
 *   1. JSON 反序列化成 RuleEnvelope 不噴錯
 *   2. ScenarioExpansionService 展開情境後，PASS / 命中分布符合預期
 *   3. RuleLookupService 對 valueRef 的執行期解析正確
 *   4. InternalEngineExporter 不會對「完整 envelope」回報缺料 warning
 */
class DemoEnvelopeIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleLookupService ruleLookupService = new RuleLookupService();
    private final ScenarioExpansionService scenarioExpansionService =
            new ScenarioExpansionService(ruleLookupService);
    private final InternalEngineExporter exporter = new InternalEngineExporter(objectMapper);

    @Test
    @DisplayName("情境 1：ID/國籍 envelope 載入後 16 情境展開、4 條 PASS、雙重命中存在")
    void idNationalityScenarioExpansion() throws Exception {
        RuleEnvelope envelope = loadEnvelope("demo_id_nationality_envelope.json");

        ScenarioExpandResponse response = scenarioExpansionService.expand(
                ScenarioExpandRequest.builder()
                        .envelope(envelope)
                        .maxScenarios(50)
                        .noMatchLabel("PASS")
                        .build());

        assertThat(envelope.getRule().getRules()).hasSize(4);
        assertThat(response.getTotalPossible()).isEqualTo(16);
        assertThat(response.getReturned()).isEqualTo(16);

        long passCount = response.getScenarios().stream()
                .filter(row -> "PASS".equals(row.getOutcomeLabel()))
                .count();
        assertThat(passCount).isEqualTo(4);

        // 雙重命中：被保人與要保人同時違規（TW_ID + OTHER 國籍）
        ScenarioRow doubleHit = response.getScenarios().stream()
                .filter(row -> row.getInputValues().equals(Map.of(
                        "insuredIdFormat", "TW_ID",
                        "insuredNationality", "OTHER",
                        "applicantIdFormat", "TW_ID",
                        "applicantNationality", "OTHER")))
                .findFirst().orElseThrow();
        assertThat(doubleHit.getMatchedRuleIds()).containsExactly("R01", "R03");
        @SuppressWarnings("unchecked")
        List<Object> errorCodes = (List<Object>) doubleHit.getResults().get("errorCode");
        assertThat(errorCodes).containsExactly("1.1", "1.3");
    }

    @Test
    @DisplayName("情境 1：完整 metadata envelope 透過 InternalEngineExporter 不報缺料")
    void idNationalityExporterCleanup() throws Exception {
        RuleEnvelope envelope = loadEnvelope("demo_id_nationality_envelope.json");
        var result = exporter.export(envelope);
        assertThat(result.warnings())
                .as("情境 1 envelope 應已補齊 fieldCode / externalCodes / audit metadata")
                .isEmpty();
        assertThat(result.metadata()).containsEntry("ruleCount", 4);
    }

    @Test
    @DisplayName("情境 2：投保始期 < 被保人生日 → 命中 R07")
    void policyIssueCrossFieldDate() throws Exception {
        RuleEnvelope envelope = loadEnvelope("demo_policy_issue_envelope.json");

        // 投保始期 1990 早於生日 2000：命中 R07
        Map<String, Object> inputs = Map.of(
                "channel", "DIRECT",
                "authorizationDoc", true,
                "paymentMethod", "CASH",
                "applicantIsCardHolder", true,
                "policyStartDate", "1990-06-15",
                "birthday", "2000-06-15");

        var lookup = ruleLookupService.lookup(envelope, inputs);
        assertThat(lookup.matched()).isTrue();
        assertThat(lookup.matchedRules().stream()
                .map(RuleLookupService.MatchedRule::ruleId)
                .collect(Collectors.toList()))
                .contains("R07");
    }

    @Test
    @DisplayName("情境 2：投保始期 > 隔日 → 命中 R06；今日 → 不命中 R05/R06")
    void policyIssueRelativeDate() throws Exception {
        RuleEnvelope envelope = loadEnvelope("demo_policy_issue_envelope.json");

        String today = LocalDate.now().toString();
        String tooFar = LocalDate.now().plusDays(10).toString();

        Map<String, Object> tooFarInputs = Map.of(
                "channel", "DIRECT",
                "authorizationDoc", true,
                "paymentMethod", "CASH",
                "applicantIsCardHolder", true,
                "policyStartDate", tooFar,
                "birthday", "1990-06-15");

        var tooFarLookup = ruleLookupService.lookup(envelope, tooFarInputs);
        assertThat(tooFarLookup.matchedRules().stream()
                .map(RuleLookupService.MatchedRule::ruleId)
                .collect(Collectors.toList()))
                .contains("R06");

        Map<String, Object> todayInputs = Map.of(
                "channel", "DIRECT",
                "authorizationDoc", true,
                "paymentMethod", "CASH",
                "applicantIsCardHolder", true,
                "policyStartDate", today,
                "birthday", "1990-06-15");

        var todayLookup = ruleLookupService.lookup(envelope, todayInputs);
        var ids = todayLookup.matchedRules().stream()
                .map(RuleLookupService.MatchedRule::ruleId)
                .collect(Collectors.toList());
        assertThat(ids).doesNotContain("R05", "R06");
    }

    @Test
    @DisplayName("情境 2：保代通路缺授權書 + 信用卡非持卡人 → 同時命中 R01 與 R03（MULTI）")
    void policyIssueMultiRuleHit() throws Exception {
        RuleEnvelope envelope = loadEnvelope("demo_policy_issue_envelope.json");

        Map<String, Object> inputs = Map.of(
                "channel", "AGENT",
                "authorizationDoc", false,
                "paymentMethod", "CREDIT_CARD",
                "applicantIsCardHolder", false,
                "policyStartDate", LocalDate.now().plusDays(1).toString(),
                "birthday", "1990-06-15");

        var lookup = ruleLookupService.lookup(envelope, inputs);
        var ids = lookup.matchedRules().stream()
                .map(RuleLookupService.MatchedRule::ruleId)
                .collect(Collectors.toList());
        assertThat(ids).contains("R01", "R03");
    }

    @Test
    @DisplayName("情境 2：valueRef 條件透過 InternalEngineExporter 產生 adapter warning")
    void policyIssueExporterFlagsValueRef() throws Exception {
        RuleEnvelope envelope = loadEnvelope("demo_policy_issue_envelope.json");
        var result = exporter.export(envelope);
        assertThat(result.warnings())
                .as("情境 2 含 valueRef 條件，exporter 應提醒下游 adapter 需編排執行期解析")
                .anyMatch(w -> w.contains("valueRef"));
    }

    private RuleEnvelope loadEnvelope(String relativePath) throws Exception {
        Path path = Path.of(relativePath).toAbsolutePath();
        if (!Files.exists(path)) {
            // SpringBoot test 工作目錄通常是專案 root；保守 fallback
            path = Path.of("..", relativePath).toAbsolutePath().normalize();
        }
        String json = Files.readString(path);
        return objectMapper.readValue(json, RuleEnvelope.class);
    }
}
