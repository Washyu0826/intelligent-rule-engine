package com.ruleengine.rules.service.adapter.group;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Group-styled tree export result — the JSON-shape consumed by the M7 dashboard
 * "Engine Execution" tab's tree visualization.
 *
 * <p>Demo-sprint contract (DEMO_PLAN_3H.md §3.1):</p>
 * <pre>
 * POST /tools/export/group-json → { tree: { treeId, name, version, nodes[], edges[] }, warnings[] }
 * </pre>
 *
 * <p>Field shapes follow Group's standard tree-engine convention as decoded in
 * {@code design/group-tree-rendering-spec.md} §3–§5 — branch vs leaf nodes,
 * abbreviated edge labels, integer position.x/y, etc.</p>
 *
 * <p>Records (not Lombok @Data) — Spring Boot 3.4 Jackson handles record
 * serialization out of the box and these are immutable transport DTOs.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupTreeExportResult(
        GroupTree tree,
        List<String> warnings
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupTree(
            String treeId,
            String name,
            String version,
            List<NodeInfo> nodes,
            List<EdgeInfo> edges
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeInfo(
            String nodeId,
            /** "BRANCH" or "LEAF" */
            String type,
            String labelChinese,
            /** Optional — e.g. "EQUALS", carries the dominant operator for BRANCH nodes. */
            String expressionType,
            Position position,
            /** Optional — only set for BRANCH nodes; the input field's name. */
            String fieldName,
            /** Optional — only set for LEAF nodes; the result field/value pairs. */
            List<ResultPair> outputs
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EdgeInfo(
            String from,
            String to,
            String label,
            String operator,
            Object value
    ) {}

    public record Position(int x, int y) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResultPair(String field, Object value) {}
}
