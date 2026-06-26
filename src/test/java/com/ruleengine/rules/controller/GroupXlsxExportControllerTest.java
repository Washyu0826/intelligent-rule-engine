package com.ruleengine.rules.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link GroupXlsxExportController}
 * (POST /tools/export/group-xlsx) — Demo-sprint P1 #8.
 *
 * <p>Asserts the binary xlsx response shape (DEMO_PLAN_3H §3.2): MIME type,
 * Content-Disposition filename pattern, structural soundness via Apache POI
 * (sheet name 「格式定義」, 7-column header, columnType=0 for inputs +
 * columnType=1 for outputs, ENUM List&lt;map&gt; in column 4), and the warning
 * header surfaced when fields are missing glossary entries.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GroupXlsxExportController — POST /tools/export/group-xlsx")
class GroupXlsxExportControllerTest {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ================================================================
    // sample-A — binary response shape
    // ================================================================

    @Test
    @DisplayName("sample-A → 200 + xlsx MIME + Content-Disposition 含 field-spec-/.xlsx + body > 1000 bytes")
    void exportSampleA_returnsValidXlsxBytes() throws Exception {
        String envelopeJson = loadGolden("sample-A/expected-envelope.json");
        String requestBody = "{\"envelope\":" + envelopeJson + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/export/group-xlsx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String contentType = mvc.getResponse().getContentType();
        assertThat(contentType)
                .as("Content-Type")
                .isNotNull()
                .startsWith(XLSX_MIME);

        String disposition = mvc.getResponse().getHeader("Content-Disposition");
        assertThat(disposition)
                .as("Content-Disposition")
                .isNotNull()
                .contains("field-spec-")
                .contains(".xlsx");

        byte[] body = mvc.getResponse().getContentAsByteArray();
        assertThat(body.length)
                .as("xlsx body byte length")
                .isGreaterThan(1000);
    }

    // ================================================================
    // sample-A — Apache POI structural parse
    // ================================================================

    @Test
    @DisplayName("sample-A xlsx → POI 開啟成功，sheet「格式定義」存在，header 7 欄正確，rowCount = 1 + inputs + outputs")
    void exportSampleA_xlsxParseableByPoi() throws Exception {
        String envelopeJson = loadGolden("sample-A/expected-envelope.json");
        JsonNode envelope = objectMapper.readTree(envelopeJson);
        int inputCount = envelope.path("rule").path("inputs").size();
        int outputCount = envelope.path("rule").path("outputs").size();

        String requestBody = "{\"envelope\":" + envelopeJson + "}";
        MvcResult mvc = mockMvc.perform(post("/tools/export/group-xlsx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        byte[] body = mvc.getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet sheet = wb.getSheet("格式定義");
            assertThat(sheet).as("sheet 「格式定義」").isNotNull();

            // Header row
            Row header = sheet.getRow(0);
            assertThat(header).as("header row").isNotNull();
            assertThat(stringAt(header, 0)).isEqualTo("欄位類型");
            assertThat(stringAt(header, 1)).isEqualTo("欄位名稱");
            assertThat(stringAt(header, 2)).isEqualTo("欄位中文");
            assertThat(stringAt(header, 3)).isEqualTo("資料型態");
            assertThat(stringAt(header, 4)).isEqualTo("代碼表清單");
            assertThat(stringAt(header, 5)).isEqualTo("是否必填");
            assertThat(stringAt(header, 6)).isEqualTo("欄位說明");

            // Total physical row count = 1 header + inputs + outputs
            int expectedRows = 1 + inputCount + outputCount;
            assertThat(sheet.getPhysicalNumberOfRows())
                    .as("total physical row count")
                    .isEqualTo(expectedRows);

            // Input rows (1..inputCount) → col 0 = "0"
            for (int i = 1; i <= inputCount; i++) {
                Row row = sheet.getRow(i);
                assertThat(stringAt(row, 0))
                        .as("input row " + i + " col 0 (欄位類型)")
                        .isEqualTo("0");
            }
            // Output rows (inputCount+1 .. inputCount+outputCount) → col 0 = "1"
            for (int i = inputCount + 1; i <= inputCount + outputCount; i++) {
                Row row = sheet.getRow(i);
                assertThat(stringAt(row, 0))
                        .as("output row " + i + " col 0 (欄位類型)")
                        .isEqualTo("1");
            }
        }
    }

    // ================================================================
    // ENUM field → List<map> in column 4
    // ================================================================

    @Test
    @DisplayName("sample-A insuredNationality (ENUM) → col 4 為 List<map> 樣式 [{\"1\":\"TW\"}...]")
    void exportSampleAEnumCodeList_isListOfMap() throws Exception {
        String envelopeJson = loadGolden("sample-A/expected-envelope.json");
        String requestBody = "{\"envelope\":" + envelopeJson + "}";

        MvcResult mvc = mockMvc.perform(post("/tools/export/group-xlsx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        byte[] body = mvc.getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet sheet = wb.getSheet("格式定義");
            assertThat(sheet).isNotNull();

            String codeList = null;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String fieldName = stringAt(row, 1);
                if ("insuredNationality".equals(fieldName)) {
                    codeList = stringAt(row, 4);
                    break;
                }
            }
            assertThat(codeList)
                    .as("col 4 (代碼表清單) for insuredNationality")
                    .isNotNull()
                    .startsWith("[{\"1\":\"TW\"}");
        }
    }

    // ================================================================
    // Warnings header — fields missing glossary entries
    // ================================================================

    @Test
    @DisplayName("純未知欄位 → 回應含 X-Group-Export-Warnings header")
    void exportRespectsGlossaryWarnings() throws Exception {
        // Synthetic envelope with field names guaranteed to miss both the
        // glossary AND the resolver's built-in shim — proves the warning
        // pathway still works after the shim shipped.
        String requestBody = """
                {
                  "envelope": {
                    "ruleType": "DecisionTable",
                    "rule": {
                      "hitPolicy": "FIRST",
                      "inputs": [
                        { "name": "zzUnknownAlphaQuux", "typeRef": "STRING" }
                      ],
                      "outputs": [
                        { "name": "zzUnknownBetaXyzzy", "typeRef": "STRING" }
                      ],
                      "rules": [
                        {
                          "ruleId": "R01",
                          "priority": 1,
                          "conditions": [
                            { "field": "zzUnknownAlphaQuux", "operator": "equals", "value": "x" }
                          ],
                          "results": [
                            { "field": "zzUnknownBetaXyzzy", "value": "y" }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;

        MvcResult mvc = mockMvc.perform(post("/tools/export/group-xlsx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String warnings = mvc.getResponse().getHeader("X-Group-Export-Warnings");
        assertThat(warnings)
                .as("X-Group-Export-Warnings header should be set when fields lack glossary entries")
                .isNotNull()
                .isNotBlank();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static String stringAt(Row row, int col) {
        if (row == null) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> cell.toString();
        };
    }

    private String loadGolden(String relativePath) throws Exception {
        Path p = Path.of("golden-tests", relativePath).toAbsolutePath();
        if (!Files.exists(p)) {
            p = Path.of("..", "golden-tests", relativePath).toAbsolutePath().normalize();
        }
        return Files.readString(p);
    }
}
