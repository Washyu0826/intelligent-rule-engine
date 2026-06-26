package com.ruleengine.rules.domain;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.extensions.FieldOrSpec;
import com.ruleengine.rules.domain.extensions.Footnote;
import com.ruleengine.rules.domain.extensions.GlobalGuard;
import com.ruleengine.rules.domain.extensions.RuleGrouping;
import com.ruleengine.rules.domain.extensions.RuleStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.14 — RuleEnvelopeExtensions round-trip 測試。
 *
 * 對應 MISSION.md §6 M3 row 第 2 條 acceptance 條件：
 *   "新 envelopes with each construct serialise, deserialise, and validate without loss"
 *
 * 每個 sub-type 至少一個 round-trip 測試；最末加上「全 5 sub-type 同時填滿」的綜合測試。
 * 此處只覆蓋 Jackson 序列化/反序列化的對稱性（語義驗證另由 ExtensionsValidatorTest 負責）。
 */
@DisplayName("RuleEnvelopeExtensions - Jackson Round-Trip (v3.14)")
class RuleEnvelopeExtensionsRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ================================================================
    // 空 extensions
    // ================================================================

    @Test
    @DisplayName("空 RuleEnvelopeExtensions 序列化為 {}，所有 sub-list 仍為 null")
    void roundTrip_emptyExtensionsBlock() throws Exception {
        RuleEnvelopeExtensions empty = RuleEnvelopeExtensions.builder().build();

        String json = mapper.writeValueAsString(empty);
        // @JsonInclude(NON_NULL) 讓所有 null 欄位都不出現 → 空物件 "{}"
        assertThat(json).isEqualTo("{}");

        RuleEnvelopeExtensions back = mapper.readValue(json, RuleEnvelopeExtensions.class);
        assertThat(back).isNotNull();
        assertThat(back.getGlobalGuards()).isNull();
        assertThat(back.getFieldOr()).isNull();
        assertThat(back.getGroupings()).isNull();
        assertThat(back.getFootnotes()).isNull();
        assertThat(back.getRuleStatus()).isNull();
    }

    // ================================================================
    // GlobalGuard
    // ================================================================

    @Test
    @DisplayName("GlobalGuard：單一 in operator + onFailure round-trip 不失真")
    void roundTrip_globalGuardOnly() throws Exception {
        GlobalGuard g1 = GlobalGuard.builder()
                .guardId("G01")
                .description("僅適用受理通路 ∈ {行動保險, 網路投保, 直效線上成交}")
                .condition(Condition.builder()
                        .field("acceptanceChannel")
                        .operator("in")
                        .value(new ArrayList<>(Arrays.asList("行動保險", "網路投保", "直效線上成交")))
                        .build())
                .onFailure(new ArrayList<>(List.of(
                        Result.builder().field("errorMessage").value("本檢核不適用此受理通路").build())))
                .build();
        GlobalGuard g2 = GlobalGuard.builder()
                .guardId("G02")
                .description("僅適用要保人非未成年")
                .condition(Condition.builder()
                        .field("policyholderAge").operator("greaterThanOrEqual").value(20).build())
                .build();

        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .globalGuards(new ArrayList<>(List.of(g1, g2)))
                .build();

        String json = mapper.writeValueAsString(ext);
        // 只應出現 globalGuards 鍵；其他 4 個 sub-list 為 null 不出現
        assertThat(json).contains("\"globalGuards\"");
        assertThat(json).doesNotContain("\"fieldOr\"");
        assertThat(json).doesNotContain("\"groupings\"");
        assertThat(json).doesNotContain("\"footnotes\"");
        assertThat(json).doesNotContain("\"ruleStatus\"");

        RuleEnvelopeExtensions back = mapper.readValue(json, RuleEnvelopeExtensions.class);
        assertThat(back).isEqualTo(ext);
        assertThat(back.getGlobalGuards()).hasSize(2);
        assertThat(back.getGlobalGuards().get(0).getCondition().getOperator()).isEqualTo("in");
        assertThat(back.getGlobalGuards().get(0).getOnFailure()).hasSize(1);
        assertThat(back.getGlobalGuards().get(1).getOnFailure()).isNull(); // 未提供 → null
    }

    // ================================================================
    // FieldOrSpec
    // ================================================================

    @Test
    @DisplayName("FieldOrSpec：兩個欄位 + predicate equals round-trip 不失真")
    void roundTrip_fieldOrOnly() throws Exception {
        FieldOrSpec spec = FieldOrSpec.builder()
                .orId("FOR01")
                .fields(new ArrayList<>(List.of("newContractPaymentMethod", "renewalPaymentMethod")))
                .predicate(Condition.builder().operator("equals").value("指定帳戶轉帳").build())
                .appliesToRuleIds(new ArrayList<>(List.of("R31", "R32")))
                .build();

        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .fieldOr(new ArrayList<>(List.of(spec)))
                .build();

        String json = mapper.writeValueAsString(ext);
        // 確認 predicate 鍵（非 value）— 避免 unwrapNestedValue 誤判
        assertThat(json).contains("\"predicate\"");
        assertThat(json).contains("\"fields\"");
        assertThat(json).contains("\"newContractPaymentMethod\"");
        assertThat(json).contains("\"renewalPaymentMethod\"");

        RuleEnvelopeExtensions back = mapper.readValue(json, RuleEnvelopeExtensions.class);
        assertThat(back).isEqualTo(ext);
        assertThat(back.getFieldOr()).hasSize(1);
        assertThat(back.getFieldOr().get(0).getFields())
                .containsExactly("newContractPaymentMethod", "renewalPaymentMethod");
        assertThat(back.getFieldOr().get(0).getPredicate().getOperator()).isEqualTo("equals");
        assertThat(back.getFieldOr().get(0).getPredicate().getValue()).isEqualTo("指定帳戶轉帳");
    }

    // ================================================================
    // RuleGrouping
    // ================================================================

    @Test
    @DisplayName("RuleGrouping：3 個成員 ruleId round-trip 不失真")
    void roundTrip_groupingsOnly() throws Exception {
        RuleGrouping grp = RuleGrouping.builder()
                .groupId("RG03")
                .title("2.3 特約通路檢核")
                .description("授權書編號不可等於受理編號（針對特約通路）")
                .memberRuleIds(new ArrayList<>(List.of("R21", "R22", "R23")))
                .level(1)
                .build();

        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .groupings(new ArrayList<>(List.of(grp)))
                .build();

        String json = mapper.writeValueAsString(ext);
        assertThat(json).contains("\"groupings\"");
        assertThat(json).contains("\"memberRuleIds\"");

        RuleEnvelopeExtensions back = mapper.readValue(json, RuleEnvelopeExtensions.class);
        assertThat(back).isEqualTo(ext);
        assertThat(back.getGroupings()).hasSize(1);
        assertThat(back.getGroupings().get(0).getMemberRuleIds())
                .containsExactly("R21", "R22", "R23");
        assertThat(back.getGroupings().get(0).getLevel()).isEqualTo(1);
    }

    // ================================================================
    // Footnote
    // ================================================================

    @Test
    @DisplayName("Footnote：規則級 + 表級兩種掛載 round-trip 不失真")
    void roundTrip_footnotesOnly() throws Exception {
        Footnote ruleLevel = Footnote.builder()
                .marker("註1")
                .text("在試算上傳中，保代通路不會進行檢核")
                .appliesToRuleId("R02")
                .altersApplicability(true)
                .build();
        Footnote tableLevel = Footnote.builder()
                .marker("*")
                .text("整段程式已移除，保留供稽核")
                // appliesToRuleId 故意不設 → null（表級）
                .altersApplicability(false)
                .build();

        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .footnotes(new ArrayList<>(List.of(ruleLevel, tableLevel)))
                .build();

        String json = mapper.writeValueAsString(ext);
        assertThat(json).contains("\"footnotes\"");

        RuleEnvelopeExtensions back = mapper.readValue(json, RuleEnvelopeExtensions.class);
        assertThat(back).isEqualTo(ext);
        assertThat(back.getFootnotes()).hasSize(2);
        assertThat(back.getFootnotes().get(0).getAppliesToRuleId()).isEqualTo("R02");
        assertThat(back.getFootnotes().get(1).getAppliesToRuleId()).isNull();
        assertThat(back.getFootnotes().get(0).getAltersApplicability()).isTrue();
        assertThat(back.getFootnotes().get(1).getAltersApplicability()).isFalse();
    }

    // ================================================================
    // RuleStatus
    // ================================================================

    @Test
    @DisplayName("RuleStatus：ACTIVE / DRAFT / RETIRED 序列化為 enum 常數名稱")
    void roundTrip_ruleStatusOnly() throws Exception {
        RuleStatus active = RuleStatus.builder()
                .ruleId("R01").status(RuleStatus.Lifecycle.ACTIVE).build();
        RuleStatus draft = RuleStatus.builder()
                .ruleId("R02").status(RuleStatus.Lifecycle.DRAFT).since("2026-04-01").build();
        RuleStatus retired = RuleStatus.builder()
                .ruleId("R23").status(RuleStatus.Lifecycle.RETIRED)
                .since("2026-04-01").reason("業務需求廢止；保留供稽核").build();

        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .ruleStatus(new ArrayList<>(List.of(active, draft, retired)))
                .build();

        String json = mapper.writeValueAsString(ext);
        // Jackson 預設將 enum 序列化為常數名稱（不是 ordinal）
        assertThat(json).contains("\"ACTIVE\"");
        assertThat(json).contains("\"DRAFT\"");
        assertThat(json).contains("\"RETIRED\"");
        assertThat(json).doesNotContain("\"0\"");
        assertThat(json).doesNotContain("\"2\""); // RETIRED 的 ordinal 是 2，不應出現

        RuleEnvelopeExtensions back = mapper.readValue(json, RuleEnvelopeExtensions.class);
        assertThat(back).isEqualTo(ext);
        assertThat(back.getRuleStatus()).hasSize(3);
        assertThat(back.getRuleStatus().get(0).getStatus()).isEqualTo(RuleStatus.Lifecycle.ACTIVE);
        assertThat(back.getRuleStatus().get(1).getStatus()).isEqualTo(RuleStatus.Lifecycle.DRAFT);
        assertThat(back.getRuleStatus().get(2).getStatus()).isEqualTo(RuleStatus.Lifecycle.RETIRED);
        assertThat(back.getRuleStatus().get(2).getReason()).isEqualTo("業務需求廢止；保留供稽核");
    }

    // ================================================================
    // 5 個 sub-type 全部填滿
    // ================================================================

    @Test
    @DisplayName("五個 sub-type 同時填滿時 round-trip 不失真")
    void roundTrip_allFiveSubTypesPopulated() throws Exception {
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .globalGuards(new ArrayList<>(List.of(
                        GlobalGuard.builder()
                                .guardId("G01")
                                .description("guard")
                                .condition(Condition.builder()
                                        .field("ch").operator("equals").value("X").build())
                                .build())))
                .fieldOr(new ArrayList<>(List.of(
                        FieldOrSpec.builder()
                                .orId("FOR01")
                                .fields(new ArrayList<>(List.of("a", "b")))
                                .predicate(Condition.builder().operator("equals").value("yes").build())
                                .build())))
                .groupings(new ArrayList<>(List.of(
                        RuleGrouping.builder()
                                .groupId("RG01")
                                .title("title")
                                .memberRuleIds(new ArrayList<>(List.of("R01")))
                                .build())))
                .footnotes(new ArrayList<>(List.of(
                        Footnote.builder().marker("*").text("note").build())))
                .ruleStatus(new ArrayList<>(List.of(
                        RuleStatus.builder().ruleId("R01")
                                .status(RuleStatus.Lifecycle.ACTIVE).build())))
                .build();

        String json = mapper.writeValueAsString(ext);

        RuleEnvelopeExtensions back = mapper.readValue(json, RuleEnvelopeExtensions.class);
        assertThat(back).isEqualTo(ext);
        assertThat(back.getGlobalGuards()).hasSize(1);
        assertThat(back.getFieldOr()).hasSize(1);
        assertThat(back.getGroupings()).hasSize(1);
        assertThat(back.getFootnotes()).hasSize(1);
        assertThat(back.getRuleStatus()).hasSize(1);
    }

    // ================================================================
    // RuleEnvelope（外層）級別的向後相容 round-trip
    // ================================================================

    @Test
    @DisplayName("RuleEnvelope.extensions=null 序列化結果不含 \"extensions\" 鍵")
    void nullExtensions_doesNotAppearInSerialization() throws Exception {
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .reason("test")
                .schemaVersion("1.0.0")
                .promptVersion("p1.0.0")
                .build();
        assertThat(envelope.getExtensions()).isNull();

        String json = mapper.writeValueAsString(envelope);
        assertThat(json).doesNotContain("\"extensions\"");
    }

    @Test
    @DisplayName("RuleEnvelope 內附帶 extensions round-trip 不失真")
    void envelopeWithExtensions_roundTrips() throws Exception {
        RuleEnvelopeExtensions ext = RuleEnvelopeExtensions.builder()
                .footnotes(new ArrayList<>(List.of(
                        Footnote.builder().marker("註1").text("hello").build())))
                .build();
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .reason("test")
                .schemaVersion("1.0.0")
                .promptVersion("p1.0.0")
                .extensions(ext)
                .build();

        String json = mapper.writeValueAsString(envelope);
        assertThat(json).contains("\"extensions\"");
        assertThat(json).contains("\"footnotes\"");

        RuleEnvelope back = mapper.readValue(json, RuleEnvelope.class);
        assertThat(back.getExtensions()).isNotNull();
        assertThat(back.getExtensions().getFootnotes()).hasSize(1);
        assertThat(back.getExtensions().getFootnotes().get(0).getText()).isEqualTo("hello");
    }
}
