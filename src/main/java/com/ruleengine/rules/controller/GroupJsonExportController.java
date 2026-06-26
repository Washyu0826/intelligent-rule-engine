package com.ruleengine.rules.controller;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.adapter.group.GroupJsonExporter;
import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-sprint endpoint: convert a {@link RuleEnvelope} to a Group-styled tree
 * JSON for the M7 dashboard's "Engine Execution" tab.
 *
 * <p>Lives in a dedicated controller (separate from {@code ToolsController}) so
 * the three demo exports (group-json, group-xlsx, execute) can each be wired
 * by an independent sub-agent without merge conflicts on a shared file.</p>
 *
 * <p>Contract — DEMO_PLAN_3H.md §3.1:</p>
 * <pre>
 * POST /tools/export/group-json
 *   Request : { "envelope": &lt;RuleEnvelope JSON&gt; }
 *   Response: GroupTreeExportResult { tree, warnings }
 * </pre>
 */
@RestController
@RequestMapping("/tools/export")
@RequiredArgsConstructor
@Slf4j
@io.swagger.v3.oas.annotations.tags.Tag(
        name = "Group Tree Export",
        description = "RuleEnvelope → Group 樹 JSON 匯出（demo M7 引擎執行 tab 用）")
public class GroupJsonExportController {

    private final GroupJsonExporter exporter;

    @io.swagger.v3.oas.annotations.Operation(
            summary = "匯出 Group 樹 JSON",
            description = "把 RuleEnvelope 轉成集團規則引擎風格的樹 JSON，供前端視覺化")
    @PostMapping("/group-json")
    public ResponseEntity<GroupTreeExportResult> exportGroupJson(
            @Valid @RequestBody ExportRequest request) {
        log.info("export group-json: ruleType={}",
                request.envelope() == null ? null : request.envelope().getRuleType());
        GroupTreeExportResult result = exporter.export(request.envelope());
        return ResponseEntity.ok(result);
    }

    /** Request body for {@code POST /tools/export/group-json}. */
    public record ExportRequest(@NotNull RuleEnvelope envelope) {}
}
