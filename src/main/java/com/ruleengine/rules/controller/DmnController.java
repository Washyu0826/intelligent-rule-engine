package com.ruleengine.rules.controller;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.exporter.DmnCrossCheckService;
import com.ruleengine.rules.service.exporter.DmnExporter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 標準 DMN 1.3 匯出與交叉驗證。
 * <pre>
 *   POST /tools/dmn/export      → { xml, decisionId, warnings }
 *   POST /tools/dmn/export.xml  → application/xml（可直接存成 .dmn）
 *   POST /tools/dmn/check       → 內建引擎 vs Camunda DMN 引擎逐欄比對
 * </pre>
 */
@RestController
@RequestMapping("/tools/dmn")
@RequiredArgsConstructor
@Slf4j
@io.swagger.v3.oas.annotations.tags.Tag(name = "DMN", description = "RuleEnvelope → DMN 1.3 匯出與第三方引擎交叉驗證")
public class DmnController {

    private final DmnExporter exporter;
    private final DmnCrossCheckService crossCheck;

    public record ExportRequest(@NotNull RuleEnvelope envelope, String name) {}

    public record CheckRequest(@NotNull RuleEnvelope envelope, @NotNull Map<String, Object> inputValues) {}

    @PostMapping("/export")
    public DmnExporter.DmnExport export(@Valid @RequestBody ExportRequest request) {
        log.info("POST /tools/dmn/export | ruleType={}", request.envelope().getRuleType());
        return exporter.export(request.envelope(), request.name() == null ? "rules-mcp-decision" : request.name());
    }

    @PostMapping(value = "/export.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> exportXml(@Valid @RequestBody ExportRequest request) {
        String name = request.name() == null ? "rules-mcp-decision" : request.name();
        DmnExporter.DmnExport export = exporter.export(request.envelope(), name);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + DmnExporter.slug(name) + ".dmn\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(export.xml());
    }

    @PostMapping("/check")
    public DmnCrossCheckService.CrossCheck check(@Valid @RequestBody CheckRequest request) {
        log.info("POST /tools/dmn/check | ruleType={} | inputs={}",
                request.envelope().getRuleType(), request.inputValues().size());
        return crossCheck.check(request.envelope(), request.inputValues());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
