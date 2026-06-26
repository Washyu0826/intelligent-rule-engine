package com.ruleengine.rules.service.exporter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.AuditMetadata;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InternalEngineExporterTest {

    private final InternalEngineExporter exporter = new InternalEngineExporter(new ObjectMapper());

    @Test
    @DisplayName("envelope 完整時 export 不報缺料 warning")
    void exportsCleanlyWhenMetadataComplete() {
        RuleEnvelope envelope = envelopeWithFullMetadata();

        var result = exporter.export(envelope);

        assertThat(result.engineName()).isEqualTo("group-internal-engine");
        assertThat(result.format()).isEqualTo("json");
        assertThat(result.content()).contains("\"engineName\"");
        assertThat(result.content()).contains("\"INSURED_NATIONALITY\"");
        // 沒有任何 fieldCode 缺料 / externalCodes 缺料 / audit 缺料 警告
        assertThat(result.warnings()).isEmpty();
        assertThat(result.metadata()).containsEntry("ruleType", "DecisionTable")
                .containsEntry("effectiveDate", "2026-06-01")
                .containsEntry("businessOwner", "uw-lead@group.com.tw");
    }

    @Test
    @DisplayName("缺 fieldCode / externalCodes / effectiveDate 時，回報具體 warning 給 adapter 接入方")
    void warnsWhenAdapterMetadataMissing() {
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .rule(Rule.builder()
                        .hitPolicy("MULTI")
                        .inputs(List.of(
                                FieldDef.builder().name("nationality").typeRef("ENUM")
                                        .allowedValues(List.of("TW", "OTHER")).build(),
                                FieldDef.builder().name("premium").typeRef("DECIMAL").build()))
                        .outputs(List.of(
                                FieldDef.builder().name("errorCode").typeRef("STRING").build()))
                        .rules(List.of(RuleRow.builder()
                                .ruleId("R01").priority(1)
                                .conditions(List.of(Condition.builder()
                                        .field("nationality").operator("equals").value("TW").build()))
                                .results(List.of(Result.builder().field("errorCode").value("X").build()))
                                .build()))
                        .build())
                .build();

        var result = exporter.export(envelope);

        assertThat(result.warnings()).anyMatch(w -> w.contains("nationality") && w.contains("fieldCode"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("nationality") && w.contains("externalCodes"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("premium") && w.contains("scale"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("audit"));
    }

    @Test
    @DisplayName("valueRef 跨欄位條件 → warning 提醒 adapter 需編排執行期解析")
    void warnsOnValueRefConditions() {
        RuleEnvelope envelope = RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .audit(AuditMetadata.builder()
                        .effectiveDate("2026-06-01")
                        .businessOwner("uw-lead@group.com.tw")
                        .build())
                .rule(Rule.builder()
                        .hitPolicy("MULTI")
                        .inputs(List.of(
                                FieldDef.builder().name("policyStartDate").typeRef("DATE")
                                        .fieldCode("POLICY_START_DT").nullable(false).build(),
                                FieldDef.builder().name("birthday").typeRef("DATE")
                                        .fieldCode("INSURED_BIRTH_DT").nullable(false).build()))
                        .outputs(List.of(
                                FieldDef.builder().name("errorCode").typeRef("STRING").build()))
                        .rules(List.of(RuleRow.builder()
                                .ruleId("R01").priority(1)
                                .conditions(List.of(Condition.builder()
                                        .field("policyStartDate").operator("lessThan")
                                        .valueRef("birthday").build()))
                                .results(List.of(Result.builder().field("errorCode").value("E1").build()))
                                .build()))
                        .build())
                .build();

        var result = exporter.export(envelope);

        assertThat(result.warnings()).anyMatch(w -> w.contains("valueRef") && w.contains("birthday"));
        assertThat(result.content()).contains("\"valueRef\"");
    }

    private RuleEnvelope envelopeWithFullMetadata() {
        return RuleEnvelope.builder()
                .ruleType("DecisionTable")
                .schemaVersion("3.0")
                .audit(AuditMetadata.builder()
                        .effectiveDate("2026-06-01")
                        .businessOwner("uw-lead@group.com.tw")
                        .businessDomain("核保前端檢核")
                        .build())
                .rule(Rule.builder()
                        .hitPolicy("MULTI")
                        .inputs(List.of(
                                FieldDef.builder()
                                        .name("nationality").typeRef("ENUM")
                                        .allowedValues(List.of("TW", "OTHER"))
                                        .fieldCode("INSURED_NATIONALITY")
                                        .nullable(false)
                                        .externalCodes(Map.of("TW", "158", "OTHER", "999"))
                                        .build(),
                                FieldDef.builder()
                                        .name("premium").typeRef("DECIMAL")
                                        .fieldCode("PREMIUM_AMOUNT")
                                        .unit("TWD").scale(2).nullable(false)
                                        .build()))
                        .outputs(List.of(
                                FieldDef.builder().name("errorCode").typeRef("STRING").build()))
                        .rules(List.of(RuleRow.builder()
                                .ruleId("R01").priority(1)
                                .conditions(List.of(Condition.builder()
                                        .field("nationality").operator("equals").value("TW").build()))
                                .results(List.of(Result.builder().field("errorCode").value("X").build()))
                                .build()))
                        .build())
                .build();
    }
}
