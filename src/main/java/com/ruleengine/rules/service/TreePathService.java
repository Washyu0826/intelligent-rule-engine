package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 決策樹路徑展開（方向② — 條件判斷視覺化）。
 *
 * <p>把 DecisionTree 的每一條 root→leaf 路徑展開成「一句白話規則」，
 * 例如：<i>當 年齡 大於 60 且 有高血壓，則 核保決議=婉拒</i>。
 * 讓非技術 BA 不必讀懂樹結構，也能逐條檢視所有判斷。</p>
 *
 * <p>直接 DFS、忠於樹結構（一個葉節點 = 一條路徑），不經 table 轉換正規化。
 * 同時回傳結構化 steps（供前端用中文欄位字典渲染）與後端 narrative（供 MCP / 純文字）。</p>
 */
@Service
@Slf4j
public class TreePathService {

    private static final int MAX_PATHS = 500;
    /** 路徑深度上限，兼作循環參照（畸形樹）防呆，避免 StackOverflow。 */
    private static final int MAX_DEPTH = 500;

    public record PathStep(String field, String operator, Object value, boolean negated, String label) {}

    public record DecisionPath(String leafNodeId, List<PathStep> steps,
                               Map<String, String> outcome, String narrative) {}

    public record TreePathsResult(int totalPaths, List<DecisionPath> paths, String summary) {}

    public TreePathsResult extractPaths(RuleEnvelope envelope) {
        TreeNode root = rootOf(envelope);
        if (root == null) {
            return new TreePathsResult(0, List.of(), "非 DecisionTree 或缺樹根，無法展開路徑");
        }
        List<DecisionPath> paths = new ArrayList<>();
        dfs(root, new ArrayList<>(), paths);
        String summary = String.format("此決策樹共有 %d 條決策路徑", paths.size());
        log.info("tree-paths | {}", summary);
        return new TreePathsResult(paths.size(), paths, summary);
    }

    private static TreeNode rootOf(RuleEnvelope e) {
        if (e == null || e.getRule() == null) return null;
        if (e.getRuleType() != null && !"DecisionTree".equalsIgnoreCase(e.getRuleType())) return null;
        return e.getRule().getRoot();
    }

    private void dfs(TreeNode node, List<PathStep> acc, List<DecisionPath> out) {
        if (node == null || out.size() >= MAX_PATHS || acc.size() >= MAX_DEPTH) return;

        boolean hasBranches = node.getBranches() != null && !node.getBranches().isEmpty();
        boolean hasTF = node.getTrueBranch() != null || node.getFalseBranch() != null;

        if (!hasBranches && !hasTF) {
            // 葉節點 → 輸出一條路徑
            out.add(new DecisionPath(node.getNodeId(), new ArrayList<>(acc),
                    outcomeOf(node), narrative(acc, node)));
            return;
        }

        if (hasBranches) {
            for (Branch b : node.getBranches()) {
                if (b == null || b.getChild() == null) continue;
                // In N-ary trees a branch without a condition is a fallback branch.
                // Reusing the parent condition would describe the fallback incorrectly.
                Condition cond = b.getCondition();
                PathStep step = (cond != null)
                        ? new PathStep(cond.getField(), cond.getOperator(), valueOf(cond), false, b.getLabel())
                        : new PathStep(null, null, null, false, b.getLabel());
                acc.add(step);
                dfs(b.getChild(), acc, out);
                acc.remove(acc.size() - 1);
            }
            // Canonical N-ary branches take precedence over legacy binary fields.
            return;
        }
        if (node.getTrueBranch() != null) {
            Condition c = node.getCondition();
            acc.add(new PathStep(c != null ? c.getField() : null, c != null ? c.getOperator() : null,
                    c != null ? valueOf(c) : null, false, "是"));
            dfs(node.getTrueBranch(), acc, out);
            acc.remove(acc.size() - 1);
        }
        if (node.getFalseBranch() != null) {
            Condition c = node.getCondition();
            acc.add(new PathStep(c != null ? c.getField() : null, c != null ? c.getOperator() : null,
                    c != null ? valueOf(c) : null, true, "否"));
            dfs(node.getFalseBranch(), acc, out);
            acc.remove(acc.size() - 1);
        }
    }

    private static Object valueOf(Condition c) {
        return c.getValueRef() != null ? "<" + c.getValueRef() + ">" : c.getValue();
    }

    private Map<String, String> outcomeOf(TreeNode leaf) {
        Map<String, String> m = new LinkedHashMap<>();
        if (leaf.getResults() != null) {
            for (Result r : leaf.getResults()) {
                if (r.getField() != null) m.put(r.getField(), String.valueOf(r.getValue()));
            }
        }
        return m;
    }

    // ====================================================================
    //  白話 narrative
    // ====================================================================

    private String narrative(List<PathStep> steps, TreeNode leaf) {
        String cond = steps.stream()
                .map(this::renderStep)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" 且 "));
        Map<String, String> outcome = outcomeOf(leaf);
        String res = outcome.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("、"));
        if (res.isBlank()) res = "（無結果）";
        return cond.isBlank() ? "無條件 → " + res : "當 " + cond + "，則 " + res;
    }

    private String renderStep(PathStep s) {
        if (s.field() == null || s.operator() == null) {
            return s.label() != null ? s.label() : "";
        }
        String base = renderCondition(s.field(), s.operator(), s.value());
        return s.negated() ? "非（" + base + "）" : base;
    }

    private String renderCondition(String field, String op, Object value) {
        String v = valStr(value);
        return switch (op) {
            case "equals" -> field + " 等於 " + v;
            case "notEquals" -> field + " 不等於 " + v;
            case "greaterThan" -> field + " 大於 " + v;
            case "greaterThanOrEqual" -> field + " 大於等於 " + v;
            case "lessThan" -> field + " 小於 " + v;
            case "lessThanOrEqual" -> field + " 小於等於 " + v;
            case "between" -> field + " 介於 " + betweenStr(value);
            case "in" -> field + " 屬於 [" + v + "]";
            case "notIn" -> field + " 不屬於 [" + v + "]";
            case "isNull" -> field + " 為空";
            case "isNotNull" -> field + " 不為空";
            case "anything" -> field + " 不限";
            default -> field + " " + op + " " + v;
        };
    }

    @SuppressWarnings("unchecked")
    private String valStr(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> l) {
            return ((List<Object>) l).stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    private String betweenStr(Object value) {
        if (value instanceof List<?> l && l.size() == 2) {
            return l.get(0) + " ~ " + l.get(1);
        }
        return valStr(value);
    }
}
