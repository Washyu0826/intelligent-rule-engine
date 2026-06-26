package com.ruleengine.rules.service.adapter.group;

import com.ruleengine.rules.domain.RuleEnvelopeExtensions;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.extensions.FieldOrSpec;
import com.ruleengine.rules.domain.extensions.Footnote;
import com.ruleengine.rules.domain.extensions.GlobalGuard;
import com.ruleengine.rules.domain.extensions.RuleGrouping;
import com.ruleengine.rules.domain.extensions.RuleStatus;
import com.ruleengine.rules.domain.glossary.GlossaryEntry;
import com.ruleengine.rules.service.glossary.GlossaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Demo sprint (2026-05-18) — Group 格式定義 xlsx exporter（sub-agent B）。
 *
 * <p>把 {@link RuleEnvelope#getRule()} 的 {@code inputs[]} / {@code outputs[]} 攤平成
 * 集團壽險業務部熟悉的 7 欄欄位定義表（sheet「格式定義」）。typeRef 對應依
 * {@code design/M3_EXTENSIONS_DESIGN.md} §4.3 mapping table。</p>
 *
 * <p>Demo 範疇：單向 envelope → xlsx；不做反向 import（M5 完整版才做）。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GroupXlsxExporter {

    private static final String SHEET_NAME = "格式定義";
    private static final String[] HEADERS = {
            "欄位類型", "欄位名稱", "欄位中文", "資料型態",
            "代碼表清單", "是否必填", "欄位說明"
    };
    private static final DateTimeFormatter FILENAME_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GlossaryService glossaryService;
    private final GroupLabelResolver labelResolver;

    /**
     * 將 envelope 的 inputs/outputs 寫成 xlsx，回傳二進位內容、warning 與檔名。
     *
     * @param envelope 規則信封（不應為 null；若 rule/inputs/outputs 為 null 會回 warning）
     * @return xlsx bytes + warnings + filename
     */
    public XlsxExportResult export(RuleEnvelope envelope) {
        List<String> warnings = new ArrayList<>();

        if (envelope == null) {
            warnings.add("envelope is null; producing header-only xlsx");
            return buildResult(null, Collections.emptyList(), Collections.emptyList(), warnings);
        }

        RuleEnvelope.Rule rule = envelope.getRule();
        List<RuleEnvelope.FieldDef> inputs = (rule == null || rule.getInputs() == null)
                ? Collections.emptyList() : rule.getInputs();
        List<RuleEnvelope.FieldDef> outputs = (rule == null || rule.getOutputs() == null)
                ? Collections.emptyList() : rule.getOutputs();

        if (inputs.isEmpty() && outputs.isEmpty()) {
            warnings.add("envelope.rule has no inputs or outputs; producing header-only xlsx");
            log.warn("GroupXlsxExporter: envelope.rule empty (inputs={} outputs={})",
                    inputs.size(), outputs.size());
        }

        return buildResult(envelope, inputs, outputs, warnings);
    }

    // ============================================================
    // Internals
    // ============================================================

    private XlsxExportResult buildResult(RuleEnvelope envelope,
                                         List<RuleEnvelope.FieldDef> inputs,
                                         List<RuleEnvelope.FieldDef> outputs,
                                         List<String> warnings) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            CellStyle headerStyle = buildHeaderStyle(wb);
            CellStyle bodyStyle = buildBodyStyle(wb);

            // ─── Sheet 1: 格式定義 (per-envelope) ───────────────────
            XSSFSheet sheet = wb.createSheet(SHEET_NAME);

            // Row 0 header
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(HEADERS[c]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (RuleEnvelope.FieldDef f : inputs) {
                writeFieldRow(sheet, rowIdx++, f, 0, bodyStyle, warnings, true);
            }
            for (RuleEnvelope.FieldDef f : outputs) {
                writeFieldRow(sheet, rowIdx++, f, 1, bodyStyle, warnings, false);
            }

            // Auto-size columns (after data is written so widths are sensible)
            for (int c = 0; c < HEADERS.length; c++) {
                try {
                    sheet.autoSizeColumn(c);
                } catch (Exception ex) {
                    // headless / no-font environments occasionally throw; fall back to fixed width
                    sheet.setColumnWidth(c, 4000);
                }
            }

            // ─── Sheet 2: 使用說明 (static legend) ──────────────────
            writeLegendSheet(wb, headerStyle, bodyStyle);

            // ─── Sheet 3: 格式異常範例 (static canonical reference) ──
            writeAnomalyExamplesSheet(wb, headerStyle, bodyStyle);

            // ─── Sheet 4: 決策表 (dynamic, per envelope) ───────────
            writeDecisionTableSheet(wb, envelope, headerStyle, bodyStyle);

            // ─── Sheet 5: 擴充規則 (dynamic, only if extensions present) ──
            if (envelope != null && envelope.getExtensions() != null) {
                writeExtensionsSheet(wb, envelope.getExtensions(), headerStyle, bodyStyle);
            }

            wb.write(bos);
            byte[] content = bos.toByteArray();

            String filename = "field-spec-" + LocalDateTime.now().format(FILENAME_TS) + ".xlsx";
            log.info("GroupXlsxExporter: produced {} bytes ({} inputs, {} outputs, {} warnings, {} sheets) -> {}",
                    content.length, inputs.size(), outputs.size(), warnings.size(),
                    wb.getNumberOfSheets(), filename);
            return new XlsxExportResult(content, warnings, filename);
        } catch (IOException ioe) {
            log.error("GroupXlsxExporter: failed to write xlsx", ioe);
            throw new IllegalStateException("Failed to build Group xlsx: " + ioe.getMessage(), ioe);
        }
    }

    private void writeFieldRow(XSSFSheet sheet,
                               int rowIdx,
                               RuleEnvelope.FieldDef field,
                               int columnType,
                               CellStyle bodyStyle,
                               List<String> warnings,
                               boolean isInput) {
        Row row = sheet.createRow(rowIdx);
        for (int c = 0; c < HEADERS.length; c++) {
            row.createCell(c).setCellStyle(bodyStyle);
        }

        String name = safe(field.getName());
        String typeRef = field.getTypeRef() == null ? "" : field.getTypeRef().trim().toUpperCase();

        // col 0 — 欄位類型 (0=input, 1=output)
        row.getCell(0).setCellValue(String.valueOf(columnType));

        // col 1 — 欄位名稱 (英文 / canonical name)
        row.getCell(1).setCellValue(name);

        // col 2 + col 6 — 欄位中文 / 欄位說明 (resolved via GroupLabelResolver:
        // direct glossary hit → whole-name shim → camelCase token-by-token).
        String chinese = labelResolver.resolveFieldChinese(name);
        String description = labelResolver.resolveFieldDescription(name);
        if (description == null || description.isBlank()) {
            description = field.getUnit() == null ? "" : field.getUnit();
        }
        if (!labelResolver.hasResolution(name)) {
            warnings.add("field '" + name
                    + "' has no glossary / shim match; 欄位中文/說明 fall back to English");
        }
        row.getCell(2).setCellValue(chinese);

        // col 3 — 資料型態 (M3 §4.3 mapping)
        String dataType = mapDataType(typeRef);
        row.getCell(3).setCellValue(dataType);

        if ("VARIABLE".equals(typeRef) && isInput) {
            warnings.add("input field '" + name
                    + "' uses VARIABLE typeRef which Group xlsx forbids on columnType=0 rows");
        }

        // col 4 — 代碼表清單 (List<map> JSON for ENUM, else empty)
        String codeList = "";
        if ("ENUM".equals(typeRef)) {
            List<String> allowed = field.getAllowedValues();
            if (allowed == null || allowed.isEmpty()) {
                warnings.add("ENUM field '" + name + "' has no allowedValues; 代碼表清單 left empty");
            } else {
                codeList = formatCodeList(allowed);
            }
        }
        row.getCell(4).setCellValue(codeList);

        // col 5 — 是否必填 (true unless nullable explicitly true)
        boolean required = !(Boolean.TRUE.equals(field.getNullable()));
        row.getCell(5).setCellValue(required ? "true" : "false");

        // col 6 — 欄位說明 (glossary definition, fallback to field.unit)
        row.getCell(6).setCellValue(description);
    }

    /**
     * Map RuleEnvelope typeRef → Group xlsx 資料型態 (M3 design §4.3).
     */
    static String mapDataType(String typeRefUpper) {
        if (typeRefUpper == null || typeRefUpper.isEmpty()) {
            return "string";
        }
        return switch (typeRefUpper) {
            case "STRING" -> "string";
            case "DECIMAL" -> "bigdecimal";
            case "BOOLEAN" -> "boolean";
            case "DATE" -> "date";
            case "TIMESTAMP" -> "timestamp";
            case "INTEGER" -> "integer";
            case "ENUM" -> "code";
            case "VARIABLE" -> "variable";
            default -> typeRefUpper.toLowerCase();
        };
    }

    /**
     * 把 allowedValues 轉成 `[{"1":"v1"},{"2":"v2"}]` 樣式（簡化版 List&lt;map&gt;）。
     * 不引入額外 JSON library — 直接手寫，confirming the legend shape on sheet「使用說明」.
     */
    static String formatCodeList(List<String> allowedValues) {
        List<Map<String, String>> shape = new ArrayList<>();
        for (int i = 0; i < allowedValues.size(); i++) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put(String.valueOf(i + 1), allowedValues.get(i));
            shape.add(entry);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < shape.size(); i++) {
            if (i > 0) sb.append(",");
            Map.Entry<String, String> e = shape.get(i).entrySet().iterator().next();
            sb.append("{\"").append(escape(e.getKey()))
                    .append("\":\"").append(escape(e.getValue())).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private CellStyle buildHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle buildBodyStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle buildMultilineBodyStyle(XSSFWorkbook wb) {
        CellStyle style = buildBodyStyle(wb);
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }

    // ============================================================
    // Sheet 2 — 使用說明 (legend, static)
    // ============================================================

    /**
     * 寫入「使用說明」sheet — 與 Group {@code 格式定義_sample.xlsx} sheet 2 內容對齊。
     * 第一列為 7 個 column header（與「格式定義」相同），第二列為每欄的 legend 說明。
     */
    private void writeLegendSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle bodyStyle) {
        XSSFSheet sheet = wb.createSheet("使用說明");
        CellStyle multilineStyle = buildMultilineBodyStyle(wb);

        Row header = sheet.createRow(0);
        for (int c = 0; c < HEADERS.length; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(HEADERS[c]);
            cell.setCellStyle(headerStyle);
        }

        String[] legend = {
                "0 : 條件欄位\n1 : 結果欄位",
                "欄位英文名稱\n僅開放英文、數字、下底線 _，且不可重複",
                "欄位中文名稱",
                "1.string\n2.bigdecimal\n3.boolean\n4.date\n5.timestamp\n6.integer\n7.code - 代碼表\n8.variable - 區域變數 (條件欄位不開放)",
                "List<map> 轉 Json 格式 (key & value 皆為 string)",
                "是 : true\n否 : false",
                ""
        };
        Row row = sheet.createRow(1);
        row.setHeightInPoints(140f);
        for (int c = 0; c < legend.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(legend[c]);
            cell.setCellStyle(multilineStyle);
        }
        // Fix column widths instead of auto-size (multiline content makes autosize unreliable)
        int[] widths = {3500, 8000, 5000, 8500, 8500, 3500, 4500};
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, widths[c]);
        }
    }

    // ============================================================
    // Sheet 3 — 格式異常範例 (static canonical reference)
    // ============================================================

    /**
     * 寫入「格式異常範例」sheet — 與 Group {@code 格式定義_sample.xlsx} sheet 3 內容對齊。
     * 8 欄表頭（多一欄「錯誤原因」），9 列範例：其中 2 列示範錯誤格式。
     */
    private void writeAnomalyExamplesSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle bodyStyle) {
        XSSFSheet sheet = wb.createSheet("格式異常範例");
        String[] cols = {"欄位類型", "欄位名稱", "欄位中文", "資料型態", "代碼表清單", "是否必填", "欄位說明", "錯誤原因"};

        Row header = sheet.createRow(0);
        for (int c = 0; c < cols.length; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(cols[c]);
            cell.setCellStyle(headerStyle);
        }

        // Canonical reference rows from 格式定義_sample.xlsx sheet 3
        String[][] rows = {
                {"0", "PROD_CAT", "商品種類", "code", "{\"1\":\"壽險\",\"2\":\"意外險\"}", "true", "", "代碼清單設定有誤"},
                {"0", "PROD_CLASSIFY", "產品通算分類", "stringDDDD", "", "true", "", "無此資料型態"},
                {"0", "CUSTOMER_TYPE", "客戶類型", "string", "", "true", "", ""},
                {"0", "PEERS_CHANNEL", "同業通路", "string", "", "true", "", ""},
                {"0", "HEALTH_INS_TYPE", "健康保險型態分類", "string", "", "false", "", ""},
                {"1", "WITHIN_TOT_AMT", "通算代號(內)", "variable", "", "true", "", ""},
                {"1", "PEERS_TOT_AMT", "同業累算(外)", "variable", "", "true", "", ""},
                {"1", "MAX_AMT_WITHIN", "投保上限(內)", "integer", "", "false", "", ""},
                {"1", "MAX_AMT_ALL", "投保上限(內 + 外)", "integer", "", "false", "", ""}
        };

        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < rows[r].length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(rows[r][c]);
                cell.setCellStyle(bodyStyle);
            }
        }

        for (int c = 0; c < cols.length; c++) {
            try {
                sheet.autoSizeColumn(c);
            } catch (Exception ex) {
                sheet.setColumnWidth(c, 4500);
            }
        }
    }

    // ============================================================
    // Sheet 4 — 決策表 (dynamic, per envelope)
    // ============================================================

    /**
     * 寫入「決策表」sheet — 1 row / 1 rule、欄位 = inputs + outputs。
     * 第 0 列：中文 header；第 1 列：English header；第 2 列起：每條規則一列。
     * anything → "-"; between [a,b] → "[a, b]"; ENUM/數值/字串直接顯示。
     */
    private void writeDecisionTableSheet(XSSFWorkbook wb, RuleEnvelope envelope,
                                          CellStyle headerStyle, CellStyle bodyStyle) {
        XSSFSheet sheet = wb.createSheet("決策表");
        if (envelope == null || envelope.getRule() == null) {
            // empty sheet — just two header rows
            sheet.createRow(0).createCell(0).setCellValue("(envelope 為空，無決策表資料)");
            return;
        }
        RuleEnvelope.Rule rule = envelope.getRule();
        List<RuleEnvelope.FieldDef> inputs = rule.getInputs() != null ? rule.getInputs() : Collections.emptyList();
        List<RuleEnvelope.FieldDef> outputs = rule.getOutputs() != null ? rule.getOutputs() : Collections.emptyList();
        List<RuleEnvelope.RuleRow> rules = rule.getRules() != null ? rule.getRules() : Collections.emptyList();

        // Row 0: Chinese header
        Row chRow = sheet.createRow(0);
        applyHeader(chRow, 0, "規則 ID", headerStyle);
        applyHeader(chRow, 1, "優先級", headerStyle);
        int col = 2;
        for (RuleEnvelope.FieldDef f : inputs) {
            applyHeader(chRow, col++, labelResolver.resolveFieldChinese(f.getName()), headerStyle);
        }
        for (RuleEnvelope.FieldDef f : outputs) {
            applyHeader(chRow, col++, labelResolver.resolveFieldChinese(f.getName()), headerStyle);
        }

        // Row 1: English header
        Row enRow = sheet.createRow(1);
        applyHeader(enRow, 0, "ruleId", headerStyle);
        applyHeader(enRow, 1, "priority", headerStyle);
        col = 2;
        for (RuleEnvelope.FieldDef f : inputs) applyHeader(enRow, col++, safe(f.getName()), headerStyle);
        for (RuleEnvelope.FieldDef f : outputs) applyHeader(enRow, col++, safe(f.getName()), headerStyle);

        // Data rows
        int rowIdx = 2;
        for (RuleEnvelope.RuleRow rr : rules) {
            Row dataRow = sheet.createRow(rowIdx++);
            applyBody(dataRow, 0, safe(rr.getRuleId()), bodyStyle);
            applyBody(dataRow, 1, rr.getPriority() == null ? "" : String.valueOf(rr.getPriority()), bodyStyle);

            // Index conditions and results by field for O(1) lookup
            Map<String, RuleEnvelope.Condition> condByField = new LinkedHashMap<>();
            if (rr.getConditions() != null) {
                for (RuleEnvelope.Condition c : rr.getConditions()) {
                    if (c.getField() != null) condByField.put(c.getField(), c);
                }
            }
            Map<String, RuleEnvelope.Result> resByField = new LinkedHashMap<>();
            if (rr.getResults() != null) {
                for (RuleEnvelope.Result r : rr.getResults()) {
                    if (r.getField() != null) resByField.put(r.getField(), r);
                }
            }

            col = 2;
            for (RuleEnvelope.FieldDef f : inputs) {
                applyBody(dataRow, col++, formatConditionCell(condByField.get(f.getName())), bodyStyle);
            }
            for (RuleEnvelope.FieldDef f : outputs) {
                RuleEnvelope.Result r = resByField.get(f.getName());
                String v = (r == null || r.getValue() == null) ? "" : String.valueOf(r.getValue());
                applyBody(dataRow, col++, v, bodyStyle);
            }
        }

        // Auto-size all columns
        int totalCols = 2 + inputs.size() + outputs.size();
        for (int c = 0; c < totalCols; c++) {
            try {
                sheet.autoSizeColumn(c);
            } catch (Exception ex) {
                sheet.setColumnWidth(c, 4000);
            }
        }
    }

    /**
     * 把單一 condition 攤平成 cell 文字。
     *
     * <p>業務需求（2026-05-18 demo）：每個欄位都要寫清楚，不可空白也不可
     * 短破折號。「不檢查此欄位」（anything 或欄位缺 condition）統一寫
     * 「任意」；其他 operator 用中文符號 + 值。</p>
     */
    static String formatConditionCell(RuleEnvelope.Condition c) {
        if (c == null) return "任意";
        String op = c.getOperator();
        if (op == null || "anything".equalsIgnoreCase(op)) return "任意";
        Object v = c.getValue();
        return switch (op) {
            case "equals" -> "= " + safe(String.valueOf(v));
            case "notEquals" -> "≠ " + v;
            case "greaterThan" -> "> " + v;
            case "greaterThanOrEqual" -> "≥ " + v;
            case "lessThan" -> "< " + v;
            case "lessThanOrEqual" -> "≤ " + v;
            case "between" -> (v instanceof List<?> list && list.size() == 2)
                    ? "介於 " + list.get(0) + " 至 " + list.get(1) + " 之間"
                    : String.valueOf(v);
            case "in" -> "屬於 " + v;
            case "notIn" -> "不屬於 " + v;
            case "isNull" -> "為空值";
            case "isNotNull" -> "不為空值";
            default -> op + (v == null ? "" : " " + v);
        };
    }

    // ============================================================
    // Sheet 5 — 擴充規則 (dynamic, only if envelope.extensions present)
    // ============================================================

    /**
     * 寫入「擴充規則」sheet — 把 globalGuards / fieldOr / groupings / footnotes / ruleStatus
     * 五類 extensions 各自做一個小區塊，中間留一空白列分隔。
     */
    private void writeExtensionsSheet(XSSFWorkbook wb, RuleEnvelopeExtensions ext,
                                       CellStyle headerStyle, CellStyle bodyStyle) {
        boolean hasAny = (ext.getGlobalGuards() != null && !ext.getGlobalGuards().isEmpty())
                || (ext.getFieldOr() != null && !ext.getFieldOr().isEmpty())
                || (ext.getGroupings() != null && !ext.getGroupings().isEmpty())
                || (ext.getFootnotes() != null && !ext.getFootnotes().isEmpty())
                || (ext.getRuleStatus() != null && !ext.getRuleStatus().isEmpty());
        if (!hasAny) return;

        XSSFSheet sheet = wb.createSheet("擴充規則");
        int[] rowIdx = {0};

        if (ext.getGlobalGuards() != null && !ext.getGlobalGuards().isEmpty()) {
            writeSectionTitle(sheet, rowIdx, "【全域守門 Global Guards】", headerStyle, 6);
            writeSectionHeader(sheet, rowIdx,
                    new String[]{"guardId", "description", "field", "operator", "value", "onFailure"},
                    headerStyle);
            for (GlobalGuard g : ext.getGlobalGuards()) {
                Row r = sheet.createRow(rowIdx[0]++);
                applyBody(r, 0, safe(g.getGuardId()), bodyStyle);
                applyBody(r, 1, safe(g.getDescription()), bodyStyle);
                applyBody(r, 2, g.getCondition() != null ? safe(g.getCondition().getField()) : "", bodyStyle);
                applyBody(r, 3, g.getCondition() != null ? safe(g.getCondition().getOperator()) : "", bodyStyle);
                applyBody(r, 4, g.getCondition() != null ? String.valueOf(g.getCondition().getValue()) : "", bodyStyle);
                applyBody(r, 5, g.getOnFailure() != null ? g.getOnFailure().toString() : "", bodyStyle);
            }
            rowIdx[0]++;
        }

        if (ext.getFieldOr() != null && !ext.getFieldOr().isEmpty()) {
            writeSectionTitle(sheet, rowIdx, "【欄位 OR Field-OR】", headerStyle, 5);
            writeSectionHeader(sheet, rowIdx,
                    new String[]{"orId", "fields", "predicate.operator", "predicate.value", "appliesToRuleIds"},
                    headerStyle);
            for (FieldOrSpec o : ext.getFieldOr()) {
                Row r = sheet.createRow(rowIdx[0]++);
                applyBody(r, 0, safe(o.getOrId()), bodyStyle);
                applyBody(r, 1, o.getFields() == null ? "" : String.join(", ", o.getFields()), bodyStyle);
                applyBody(r, 2, o.getPredicate() != null ? safe(o.getPredicate().getOperator()) : "", bodyStyle);
                applyBody(r, 3, o.getPredicate() != null ? String.valueOf(o.getPredicate().getValue()) : "", bodyStyle);
                applyBody(r, 4, o.getAppliesToRuleIds() == null ? "" : String.join(", ", o.getAppliesToRuleIds()), bodyStyle);
            }
            rowIdx[0]++;
        }

        if (ext.getFootnotes() != null && !ext.getFootnotes().isEmpty()) {
            writeSectionTitle(sheet, rowIdx, "【註腳 Footnotes】", headerStyle, 4);
            writeSectionHeader(sheet, rowIdx,
                    new String[]{"marker", "text", "appliesToRuleId", "altersApplicability"},
                    headerStyle);
            for (Footnote f : ext.getFootnotes()) {
                Row r = sheet.createRow(rowIdx[0]++);
                applyBody(r, 0, safe(f.getMarker()), bodyStyle);
                applyBody(r, 1, safe(f.getText()), bodyStyle);
                applyBody(r, 2, safe(f.getAppliesToRuleId()), bodyStyle);
                applyBody(r, 3, f.getAltersApplicability() == null ? "" : String.valueOf(f.getAltersApplicability()), bodyStyle);
            }
            rowIdx[0]++;
        }

        if (ext.getRuleStatus() != null && !ext.getRuleStatus().isEmpty()) {
            writeSectionTitle(sheet, rowIdx, "【規則狀態 Rule Status】", headerStyle, 4);
            writeSectionHeader(sheet, rowIdx,
                    new String[]{"ruleId", "status", "since", "reason"},
                    headerStyle);
            for (RuleStatus s : ext.getRuleStatus()) {
                Row r = sheet.createRow(rowIdx[0]++);
                applyBody(r, 0, safe(s.getRuleId()), bodyStyle);
                applyBody(r, 1, s.getStatus() == null ? "" : s.getStatus().name(), bodyStyle);
                applyBody(r, 2, safe(s.getSince()), bodyStyle);
                applyBody(r, 3, safe(s.getReason()), bodyStyle);
            }
            rowIdx[0]++;
        }

        if (ext.getGroupings() != null && !ext.getGroupings().isEmpty()) {
            writeSectionTitle(sheet, rowIdx, "【規則分組 Groupings】", headerStyle, 4);
            writeSectionHeader(sheet, rowIdx,
                    new String[]{"groupId", "title", "memberRuleIds", "level"},
                    headerStyle);
            for (RuleGrouping g : ext.getGroupings()) {
                Row r = sheet.createRow(rowIdx[0]++);
                applyBody(r, 0, safe(g.getGroupId()), bodyStyle);
                applyBody(r, 1, safe(g.getTitle()), bodyStyle);
                applyBody(r, 2, g.getMemberRuleIds() == null ? "" : String.join(", ", g.getMemberRuleIds()), bodyStyle);
                applyBody(r, 3, g.getLevel() == null ? "" : String.valueOf(g.getLevel()), bodyStyle);
            }
        }

        // Auto-size first 6 columns (max used)
        for (int c = 0; c < 6; c++) {
            try {
                sheet.autoSizeColumn(c);
            } catch (Exception ex2) {
                sheet.setColumnWidth(c, 4500);
            }
        }
    }

    // ============================================================
    // Cell helpers
    // ============================================================

    private void writeSectionTitle(XSSFSheet sheet, int[] rowIdxRef, String title, CellStyle style, int spanCols) {
        Row r = sheet.createRow(rowIdxRef[0]++);
        Cell c = r.createCell(0);
        c.setCellValue(title);
        c.setCellStyle(style);
        // (We don't merge cells for now — keep simple.)
    }

    private void writeSectionHeader(XSSFSheet sheet, int[] rowIdxRef, String[] cols, CellStyle style) {
        Row r = sheet.createRow(rowIdxRef[0]++);
        for (int i = 0; i < cols.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(style);
        }
    }

    private static void applyHeader(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void applyBody(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /**
     * 匯出結果：xlsx 二進位內容、語意警告、建議檔名。
     */
    public record XlsxExportResult(byte[] content, List<String> warnings, String filename) {}
}
