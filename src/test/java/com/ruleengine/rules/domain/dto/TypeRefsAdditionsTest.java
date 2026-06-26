package com.ruleengine.rules.domain.dto;

import com.ruleengine.rules.domain.dto.ToolDtos.TypeRefs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.14 — TypeRefs.ALL 擴增測試。
 *
 * 對應 MISSION.md §4.M3：「兩個新型別 (TIMESTAMP / VARIABLE) 加在 enum 最末以保留 ordinal 穩定性」。
 * 也對應 design §4.1 與 §10 reviewer checklist「Are TIMESTAMP and VARIABLE appended at the END」。
 *
 * 此測試刻意 cover 三個面向：
 *   1. 新值真的存在於 ALL 中
 *   2. 既有 6 個值仍在（向後相容）
 *   3. ALL.size() = 8（既有 6 + 新 2）
 */
@DisplayName("TypeRefs - v3.14 新增 TIMESTAMP / VARIABLE")
class TypeRefsAdditionsTest {

    @Test
    @DisplayName("TypeRefs.ALL 包含 TIMESTAMP 與 VARIABLE")
    void typeRefsAllContainsTimestampAndVariable() {
        assertThat(TypeRefs.ALL).contains(TypeRefs.TIMESTAMP);
        assertThat(TypeRefs.ALL).contains(TypeRefs.VARIABLE);
        assertThat(TypeRefs.ALL).contains("TIMESTAMP");
        assertThat(TypeRefs.ALL).contains("VARIABLE");
    }

    @Test
    @DisplayName("TypeRefs.ALL 大小恰為 8（既有 6 + 新 2）")
    void typeRefsAllSizeIs8() {
        assertThat(TypeRefs.ALL).hasSize(8);
    }

    @Test
    @DisplayName("既有 6 個 typeRef 仍存在於 ALL 中（向後相容）")
    void legacySixTypeRefsStillPresent() {
        assertThat(TypeRefs.ALL).containsAll(Set.of(
                TypeRefs.INTEGER,
                TypeRefs.DECIMAL,
                TypeRefs.BOOLEAN,
                TypeRefs.STRING,
                TypeRefs.ENUM,
                TypeRefs.DATE
        ));
    }

    @Test
    @DisplayName("TIMESTAMP / VARIABLE 常數字面值即為其字串值")
    void timestampAndVariableConstantsHaveCorrectStringValues() {
        assertThat(TypeRefs.TIMESTAMP).isEqualTo("TIMESTAMP");
        assertThat(TypeRefs.VARIABLE).isEqualTo("VARIABLE");
    }

    @Test
    @DisplayName("TypeRefs.ALL 不應包含過去版本未支援的值（防回歸）")
    void unknownTypeRefsAreNotAccidentallyPresent() {
        assertThat(TypeRefs.ALL).doesNotContain(
                "DATETIME",  // 必須透過 normalizer alias 為 TIMESTAMP，不直接收
                "VAR",
                "INT",
                "BOOL",
                "STR"
        );
    }
}
