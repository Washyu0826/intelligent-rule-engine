package com.ruleengine.rules.domain;

import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.generator.ConditionOverlapDetector;
import com.ruleengine.rules.service.validator.ConsistencyValidator;
import com.ruleengine.rules.service.validator.DecisionTableValidator;
import com.ruleengine.rules.service.validator.ExtensionsValidator;
import com.ruleengine.rules.service.validator.FieldDefinitionValidator;
import com.ruleengine.rules.service.validator.RuleSemanticValidator;
import com.ruleengine.rules.service.validator.StructureValidator;
import com.ruleengine.rules.service.validator.ValidationLayer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.14 — RuleEnvelope 向後相容性測試。
 *
 * 對應 MISSION.md §4.M3 acceptance 第 1 條：
 *   「old envelopes (any with no extensions block) deserialise and validate exactly as before」
 *
 * 此測試直接從 golden-tests/sample-A / sample-B/ 載入既有 fixture（projet root 路徑），
 * 確保：
 *   1. extensions=null 的 envelope deserialize 後 getExtensions() == null
 *   2. round-trip 不會「冒出」extensions 鍵（JsonInclude(NON_NULL) 生效）
 *   3. DecisionTableValidator + 5-layer 對既有 fixture 仍回傳 0 error
 *
 * sample-B 在本 mission 內已 additively 加入 extensions 區塊（F1a path），
 * 所以 part1 / part2 也應該驗證通過。
 */
@DisplayName("RuleEnvelope - Backward Compatibility (v3.14)")
class RuleEnvelopeBackwardCompatibilityTest {

    private static final Path SAMPLE_A =
            Paths.get("golden-tests", "sample-A", "expected-envelope.json");
    private static final Path SAMPLE_B_PART1 =
            Paths.get("golden-tests", "sample-B", "expected-envelope-part1.json");
    private static final Path SAMPLE_B_PART2 =
            Paths.get("golden-tests", "sample-B", "expected-envelope-part2.json");

    private ObjectMapper mapper;
    private DecisionTableValidator validator;

    @BeforeEach
    void setUp() {
        // Sample-B fixtures 內含 "comment" 欄位（人類可讀註解，非 domain 欄位）；
        // 反序列化時必須容忍未知欄位，否則 RuleEnvelope→fromJson 會炸。
        mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        List<ValidationLayer> layers = List.of(
                new StructureValidator(),
                new FieldDefinitionValidator(),
                new RuleSemanticValidator(),
                new ConsistencyValidator(new ConditionOverlapDetector()),
                new ExtensionsValidator()
        );
        validator = new DecisionTableValidator(layers);
    }

    // ================================================================
    // sample-A：完全無 extensions 區塊
    // ================================================================

    @Test
    @DisplayName("sample-A envelope 不應在反序列化後出現 extensions 區塊")
    void loadSampleAEnvelope_doesNotHaveExtensions() throws Exception {
        String json = Files.readString(SAMPLE_A);
        RuleEnvelope envelope = mapper.readValue(json, RuleEnvelope.class);
        assertThat(envelope.getExtensions())
                .as("sample-A 原本就無 extensions；新增的 nullable field 應 = null")
                .isNull();
    }

    @Test
    @DisplayName("sample-A round-trip 不會「冒出」extensions 鍵（@JsonInclude(NON_NULL) 生效）")
    void loadSampleAEnvelope_reserialized_doesNotEmitExtensionsKey() throws Exception {
        String original = Files.readString(SAMPLE_A);
        RuleEnvelope envelope = mapper.readValue(original, RuleEnvelope.class);
        String reserialized = mapper.writeValueAsString(envelope);
        assertThat(reserialized).doesNotContain("\"extensions\"");
    }

    @Test
    @DisplayName("sample-A 經過 5-layer DecisionTableValidator 仍 0 error")
    void validateSampleAEnvelope_stillPasses() throws Exception {
        String json = Files.readString(SAMPLE_A);
        JsonNode node = mapper.readTree(json);
        List<ValidationError> errs = validator.validate(node);
        assertThat(errs).as("sample-A 應 0 error；實際：" + errs).isEmpty();
    }

    // ================================================================
    // sample-B part1：v3.14 已 additively 加入 extensions
    // ================================================================

    @Test
    @DisplayName("sample-B part1 經過 5-layer DecisionTableValidator 仍 0 error（含新加的 extensions）")
    void validateSampleBPart1_stillPasses() throws Exception {
        String json = Files.readString(SAMPLE_B_PART1);
        JsonNode node = mapper.readTree(json);
        List<ValidationError> errs = validator.validate(node);
        assertThat(errs).as("sample-B part1 應 0 error；實際：" + errs).isEmpty();
    }

    @Test
    @DisplayName("sample-B part1 deserialize 後 extensions 不為 null（F1a 已加入）")
    void sampleBPart1_hasExtensionsAfterEnrichment() throws Exception {
        String json = Files.readString(SAMPLE_B_PART1);
        RuleEnvelope envelope = mapper.readValue(json, RuleEnvelope.class);
        assertThat(envelope.getExtensions())
                .as("sample-B part1 應在 F1a 中被加入 extensions 區塊")
                .isNotNull();
    }

    @Test
    @DisplayName("sample-B part1 含 globalGuards（MISSION.md §6 M3 row「global guards parse from sample B」）")
    void sampleBPart1_hasGlobalGuard() throws Exception {
        String json = Files.readString(SAMPLE_B_PART1);
        RuleEnvelope envelope = mapper.readValue(json, RuleEnvelope.class);
        assertThat(envelope.getExtensions().getGlobalGuards())
                .as("sample-B part1 應含至少 1 個 globalGuard")
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    @DisplayName("sample-B part1 含 fieldOr（MISSION.md §6 M3 row「field-OR conditions recognised in sample B」）")
    void sampleBPart1_hasFieldOr() throws Exception {
        String json = Files.readString(SAMPLE_B_PART1);
        RuleEnvelope envelope = mapper.readValue(json, RuleEnvelope.class);
        assertThat(envelope.getExtensions().getFieldOr())
                .as("sample-B part1 應含至少 1 個 fieldOr 群組")
                .isNotNull()
                .isNotEmpty();
        // 第一個 fieldOr 必須是兩個繳費管道
        assertThat(envelope.getExtensions().getFieldOr().get(0).getFields())
                .as("sample-B part1 的 fieldOr 應指向兩個繳費管道欄位")
                .containsExactly("newContractPaymentChannel", "renewalPaymentChannel");
    }

    @Test
    @DisplayName("sample-B part1 含 footnotes")
    void sampleBPart1_hasFootnotes() throws Exception {
        String json = Files.readString(SAMPLE_B_PART1);
        RuleEnvelope envelope = mapper.readValue(json, RuleEnvelope.class);
        assertThat(envelope.getExtensions().getFootnotes())
                .as("sample-B part1 應含至少 1 個 footnote")
                .isNotNull()
                .isNotEmpty();
    }

    // ================================================================
    // sample-B part2：v3.14 已 additively 加入 footnotes
    // ================================================================

    @Test
    @DisplayName("sample-B part2 經過 5-layer DecisionTableValidator 仍 0 error")
    void validateSampleBPart2_stillPasses() throws Exception {
        String json = Files.readString(SAMPLE_B_PART2);
        JsonNode node = mapper.readTree(json);
        List<ValidationError> errs = validator.validate(node);
        assertThat(errs).as("sample-B part2 應 0 error；實際：" + errs).isEmpty();
    }

    @Test
    @DisplayName("sample-B part2 deserialize 後 extensions 不為 null（F1a 已加入 footnotes）")
    void sampleBPart2_hasFootnotesAfterEnrichment() throws Exception {
        String json = Files.readString(SAMPLE_B_PART2);
        RuleEnvelope envelope = mapper.readValue(json, RuleEnvelope.class);
        assertThat(envelope.getExtensions()).isNotNull();
        assertThat(envelope.getExtensions().getFootnotes())
                .as("sample-B part2 在 F1a 加入了 footnotes")
                .isNotNull()
                .isNotEmpty();
    }

    // ================================================================
    // 加值 round-trip：sample-B 全 round-trip 不失真
    // ================================================================

    @Test
    @DisplayName("sample-B part1 完整 round-trip（含 extensions）：deserialize → reserialize → deserialize 仍對等")
    void sampleBPart1_roundTripPreservesExtensions() throws Exception {
        String original = Files.readString(SAMPLE_B_PART1);
        RuleEnvelope first = mapper.readValue(original, RuleEnvelope.class);
        String reserialized = mapper.writeValueAsString(first);
        RuleEnvelope second = mapper.readValue(reserialized, RuleEnvelope.class);

        assertThat(second.getExtensions()).isEqualTo(first.getExtensions());
        // 既有欄位也未變動
        assertThat(second.getRule().getRules()).hasSameSizeAs(first.getRule().getRules());
        assertThat(second.getRuleType()).isEqualTo(first.getRuleType());
    }
}
