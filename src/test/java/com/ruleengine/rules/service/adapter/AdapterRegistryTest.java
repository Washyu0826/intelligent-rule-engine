package com.ruleengine.rules.service.adapter;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.adapter.group.GroupAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 — SPI Skeleton 接受測試：驗證 {@link AdapterRegistry} 能透過 Spring
 * 自動發現 {@link GroupAdapter}，並對外暴露引擎名稱、版本與支援格式；
 * 同時確認 stub 階段 {@code export} 一律拋出帶有後續 mission 標記
 * （{@code M4} / {@code M5}）的 {@link UnsupportedOperationException}。
 *
 * <p>
 * 鏡像 {@code ToolsControllerTest} 與 {@code InternalEngineExporterTest}
 * 的慣例：以 {@link SpringBootTest} 載入真實 Spring context（因註冊發生於
 * {@code @PostConstruct}），以 AssertJ 進行斷言。
 * </p>
 *
 * <p>
 * 對應 {@code MISSION.md} §4.M2 outputs（「至少一個單元測試驗證
 * {@code GroupAdapter} 可透過 registry 被發現」）與 §6 M2 row。
 * </p>
 */
@SpringBootTest
@DisplayName("AdapterRegistry - M2 SPI Skeleton 接受測試")
class AdapterRegistryTest {

    @Autowired
    private AdapterRegistry adapterRegistry;

    @Autowired
    private GroupAdapter groupAdapter;

    // ================================================================
    // (a) 註冊表發現性
    // ================================================================

    @Test
    @DisplayName("registry 以 \"group\" 名稱可查得 GroupAdapter")
    void registryContainsGroupAdapterByName() {
        Optional<RuleEngineAdapter> found = adapterRegistry.getByName("group");

        assertThat(found).isPresent();
        assertThat(found.get()).isInstanceOf(GroupAdapter.class);
    }

    @Test
    @DisplayName("getRegisteredEngineNames 列表包含 \"group\"")
    void registryReportsGroupInRegisteredNames() {
        List<String> names = adapterRegistry.getRegisteredEngineNames();

        assertThat(names).contains("group");
    }

    @Test
    @DisplayName("getEngineInfo 對 \"group\" 鍵映射至非 null 版本字串")
    void registryReportsGroupEngineInfo() {
        Map<String, String> info = adapterRegistry.getEngineInfo();

        assertThat(info).containsKey("group");
        assertThat(info.get("group")).isNotNull().isNotBlank();
    }

    // ================================================================
    // (b) Adapter 自身 metadata
    // ================================================================

    @Test
    @DisplayName("GroupAdapter 回報 engineName / engineVersion / supportedFormats")
    void groupAdapterReportsExpectedMetadata() {
        assertThat(groupAdapter.engineName()).isEqualTo("group");
        assertThat(groupAdapter.engineVersion()).isEqualTo("0.1-stub");
        assertThat(groupAdapter.supportedFormats())
                .containsExactlyInAnyOrder(AdapterFormat.JSON, AdapterFormat.XLSX);
    }

    // ================================================================
    // (c) Stub 拋出帶 mission tag 的 UnsupportedOperationException
    // ================================================================

    @Test
    @DisplayName("export(JSON) 拋 UnsupportedOperationException，訊息含 \"M4\"")
    void groupAdapterExportThrowsWithMissionTag() {
        RuleEnvelope envelope = RuleEnvelope.builder().ruleType("DecisionTable").build();

        assertThatThrownBy(() -> groupAdapter.export(envelope, AdapterFormat.JSON))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("M4");
    }

    @Test
    @DisplayName("export(XLSX) 拋 UnsupportedOperationException，訊息含 \"M5\"")
    void groupAdapterXlsxExportThrowsWithM5Tag() {
        RuleEnvelope envelope = RuleEnvelope.builder().ruleType("DecisionTable").build();

        assertThatThrownBy(() -> groupAdapter.export(envelope, AdapterFormat.XLSX))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("M5");
    }
}
