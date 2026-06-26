package com.ruleengine.rules.controller;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.adapter.group.GroupXlsxExporter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo sprint (2026-05-18) — Group xlsx 下載端點（sub-agent B）。
 *
 * <p>單一 endpoint：{@code POST /tools/export/group-xlsx}，
 * 將 RuleEnvelope 攤平成「格式定義」xlsx 二進位回傳。</p>
 *
 * <p>API 契約見 {@code DEMO_PLAN_3H.md} §3.2。本 controller 與既有
 * {@code ToolsController} 完全分離，方便 demo 結束後若要拔掉只需移除一檔。</p>
 */
@RestController
@RequestMapping("/tools/export")
@RequiredArgsConstructor
@Slf4j
public class GroupXlsxExportController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final GroupXlsxExporter exporter;

    @PostMapping(value = "/group-xlsx", produces = XLSX_MIME)
    public ResponseEntity<byte[]> exportGroupXlsx(@Valid @RequestBody ExportRequest request) {
        GroupXlsxExporter.XlsxExportResult result = exporter.export(request.envelope());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_MIME));
        // RFC 6266 standard "attachment; filename=...; filename*=UTF-8''..." —
        // browsers and curl handle both ASCII and Chinese filenames cleanly.
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(result.filename(), StandardCharsets.UTF_8)
                        .build()
        );
        if (!result.warnings().isEmpty()) {
            // Surface semantic warnings to the frontend via custom header.
            // Sanitised: replace CR/LF to avoid header injection.
            String joined = String.join("; ", result.warnings())
                    .replace('\r', ' ')
                    .replace('\n', ' ');
            headers.add("X-Group-Export-Warnings", joined);
        }

        log.info("GroupXlsxExportController: returning xlsx filename={} size={} warnings={}",
                result.filename(), result.content().length, result.warnings().size());

        return new ResponseEntity<>(result.content(), headers, HttpStatus.OK);
    }

    /** Demo-only request DTO; envelope ships in full so endpoint is stateless. */
    public record ExportRequest(@NotNull RuleEnvelope envelope) {}
}
