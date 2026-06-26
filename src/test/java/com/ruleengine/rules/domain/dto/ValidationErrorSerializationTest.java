package com.ruleengine.rules.domain.dto;

import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.7.0 — ValidationError 結構化 witness 欄位的 JSON 序列化行為測試。
 *
 * 重點確認：
 *   - witness 有值時出現在 JSON
 *   - witness 為 null 時 @JsonInclude(NON_NULL) 會從 JSON 中隱藏（不出現 "witness": null）
 *
 * IEEE 2024 verification pattern — structured counter-example 欄位存在與否
 * 應能清楚反映錯誤是否附帶可機器解析的反例。
 */
class ValidationErrorSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void witnessPresent_appearsInJson() throws Exception {
        Map<String, String> witness = new LinkedHashMap<>();
        witness.put("age", "25");
        witness.put("hasHypertension", "true");

        ValidationError e = ValidationError.builder()
                .code("INCONSISTENT_TABLE")
                .message("R01 與 R02 可同時命中。觸發條件範例：age=25, hasHypertension=true")
                .witness(witness)
                .build();

        String json = mapper.writeValueAsString(e);

        assertThat(json).contains("\"witness\"");
        assertThat(json).contains("\"age\":\"25\"");
        assertThat(json).contains("\"hasHypertension\":\"true\"");
    }

    @Test
    void witnessNull_absentFromJson() throws Exception {
        ValidationError e = ValidationError.builder()
                .code("UNKNOWN_OPERATOR")
                .message("\"like\" 不是合法 operator")
                .build();  // witness 未設，維持 null

        String json = mapper.writeValueAsString(e);

        assertThat(json).doesNotContain("witness");
        assertThat(json).doesNotContain("null");
    }

    @Test
    void witnessEmpty_stillSerializes() throws Exception {
        // 空 map 不應被 @JsonInclude(NON_NULL) 隱藏，NON_NULL 只過濾 null 不過濾 empty
        ValidationError e = ValidationError.builder()
                .code("INCONSISTENT_TABLE")
                .message("test")
                .witness(new LinkedHashMap<>())
                .build();

        String json = mapper.writeValueAsString(e);

        assertThat(json).contains("\"witness\":{}");
    }
}
