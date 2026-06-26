package com.ruleengine.rules.service.adapter.group;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.adapter.AdapterExportResult;
import com.ruleengine.rules.service.adapter.AdapterFormat;
import com.ruleengine.rules.service.adapter.RuleEngineAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * M2 — SPI Skeleton: Group 集團規則引擎之 {@link RuleEngineAdapter} stub。
 *
 * <p>
 * M2 階段為「可被發現」的空殼：{@link #engineName()}、{@link #engineVersion()}、
 * {@link #supportedFormats()} 回傳真實值，讓 {@code AdapterRegistry} 與單元測試
 * 可觀察到註冊；而 {@link #export} / {@link #importFrom} 一律拋出
 * {@link UnsupportedOperationException}，訊息明示由哪個 mission 接手實作。
 * </p>
 *
 * <p>
 * <b>engineName 採 {@code "group"} 而非 {@code "group-internal-engine"}：</b>
 * 後者為既有 {@code InternalEngineExporter.ENGINE_NAME} 常數（隸屬
 * {@code RuleExporter} SPI 命名空間）。雖然兩個 SPI 各有獨立 bean map，技術衝突
 * 風險為零，但為避免「兩個字串是否同義」的人因疑慮，並讓未來透過
 * {@code RuleEngineAdapter} 發布的引擎遵循一致短稱（{@code drools}、{@code fico}、
 * {@code ibm-odm}），這裡採單字 {@code "group"}。
 * </p>
 *
 * <p>
 * <b>實作排程：</b>
 * </p>
 * <ul>
 *   <li>M4 — Group JSON Adapter：填入 {@code export(JSON)} 與 {@code importFrom(JSON)}</li>
 *   <li>M5 — Group XLSX Adapter：填入 {@code export(XLSX)}（{@code importFrom(XLSX)}
 *       依 {@code MISSION.md} §1.2 不在 mission 範圍內）</li>
 * </ul>
 */
@Component
@Slf4j
public class GroupAdapter implements RuleEngineAdapter {

    static final String ENGINE_NAME = "group";
    static final String ENGINE_VERSION = "0.1-stub";

    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    @Override
    public String engineVersion() {
        return ENGINE_VERSION;
    }

    @Override
    public Set<AdapterFormat> supportedFormats() {
        // M2 階段先宣告完整能力，使 dashboard / 註冊表測試可不待 M4/M5 即驗證。
        return Set.of(AdapterFormat.JSON, AdapterFormat.XLSX);
    }

    @Override
    public AdapterExportResult export(RuleEnvelope envelope, AdapterFormat format) {
        if (format == null) {
            throw new UnsupportedOperationException(
                    "GroupAdapter.export(null) is not valid; specify JSON or XLSX");
        }
        switch (format) {
            case JSON:
                throw new UnsupportedOperationException(
                        "GroupAdapter.export(JSON) will be implemented in M4 — Group JSON Adapter");
            case XLSX:
                throw new UnsupportedOperationException(
                        "GroupAdapter.export(XLSX) will be implemented in M5 — Group XLSX Adapter");
            default:
                throw new UnsupportedOperationException(
                        "GroupAdapter does not support format " + format);
        }
    }

    @Override
    public RuleEnvelope importFrom(byte[] payload, AdapterFormat format) {
        if (format == null) {
            throw new UnsupportedOperationException(
                    "GroupAdapter.importFrom(null) is not valid; specify JSON or XLSX");
        }
        switch (format) {
            case JSON:
                throw new UnsupportedOperationException(
                        "GroupAdapter.importFrom(JSON) will be implemented in M4 — Group JSON Adapter");
            case XLSX:
                throw new UnsupportedOperationException(
                        "GroupAdapter.importFrom(XLSX) is out of scope; XLSX import is not in this mission. "
                                + "See MISSION.md §1.2.");
            default:
                throw new UnsupportedOperationException(
                        "GroupAdapter does not support format " + format);
        }
    }
}
