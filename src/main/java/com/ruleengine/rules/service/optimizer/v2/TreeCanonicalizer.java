package com.ruleengine.rules.service.optimizer.v2;

import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TreeCanonicalizer — 為 TreeNode 計算「語意正規形」雜湊（Merkle-like DFS）。
 *
 * <p>用途：
 * <ul>
 *   <li>Pass 4：偵測跨位置的**同構子樹**（sameHash → semantically equivalent）</li>
 *   <li>Pass 5/6 輔助：判斷 pruning 前後是否語意等價</li>
 *   <li>測試輔助：驗證 envelope 重排後仍語意等價</li>
 * </ul>
 *
 * <p>演算法：Merkle tree 雜湊的 bottom-up DFS
 * <pre>
 * canonicalHash(node):
 *     if leaf:   H("L|" + canonicalResults(results))
 *     else:      H("B|" + canonicalCondition(cond) + "|" + sortedChildHashes)
 * </pre>
 *
 * <p>規範化（canonicalization）重點：
 * <ul>
 *   <li>分支子節點按「入分支條件的規範形」排序（消除順序差異）</li>
 *   <li>between/in/notIn 的 value list 排序</li>
 *   <li>BOOLEAN string "true"/"false" 與 boolean true/false 視為同值</li>
 *   <li>數字 string "30" 與 integer 30 視為同值</li>
 *   <li>Results 依 field 字典序排序</li>
 *   <li>nodeId/label 字段**不**影響 hash（純結構比較）</li>
 * </ul>
 *
 * <p>注意：本類別僅回傳 hex hash 與 canonical form string，**不**修改輸入節點。
 */
@Component
@Slf4j
public class TreeCanonicalizer {

    // ============================================================
    // 公開 API
    // ============================================================

    /**
     * 計算單一節點（含子樹）的 canonical hash。
     *
     * @param node 節點；若為 null 回傳代表 NULL 的 hash
     * @return 16-char hex 字串（取 SHA-256 前 64 bits）
     */
    public String canonicalHash(TreeNode node) {
        String canon = canonicalForm(node);
        return shortHash(canon);
    }

    /**
     * 計算 node 的規範形字串（純文字，可讀、便於除錯與 snapshot 測試）。
     */
    public String canonicalForm(TreeNode node) {
        if (node == null) return "NULL";
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    /**
     * 判斷兩個子樹是否語意等價（不看 nodeId/branch label）。
     */
    public boolean semanticEqual(TreeNode a, TreeNode b) {
        return canonicalHash(a).equals(canonicalHash(b));
    }

    /**
     * 為子樹集合建立「hash → 節點 list」的分組，用於 Pass 4 偵測重複子樹。
     * 只收集分支節點（葉節點由 v1 Pass 1 處理）。
     */
    public Map<String, List<TreeNode>> groupByHash(TreeNode root) {
        Map<String, List<TreeNode>> map = new LinkedHashMap<>();
        if (root == null) return map;
        collectBranchNodes(root, map);
        return map;
    }

    private void collectBranchNodes(TreeNode node, Map<String, List<TreeNode>> map) {
        if (node == null) return;
        if (!isLeaf(node)) {
            String h = canonicalHash(node);
            map.computeIfAbsent(h, k -> new ArrayList<>()).add(node);
        }
        // 遞迴所有子節點
        List<TreeNode> children = getChildren(node);
        for (TreeNode c : children) {
            collectBranchNodes(c, map);
        }
    }

    // ============================================================
    // Canonical form 產生
    // ============================================================

    private void writeCanonical(TreeNode node, StringBuilder sb) {
        if (node == null) {
            sb.append("NULL");
            return;
        }
        if (isLeaf(node)) {
            sb.append("L{");
            sb.append(canonicalResults(node.getResults()));
            sb.append('}');
            return;
        }

        sb.append("B{");
        sb.append("cond=").append(canonicalCondition(node.getCondition()));
        sb.append(";children=");

        // 收集規範化的子樹字串（含入分支條件 + 子結構）
        List<String> childStrs = new ArrayList<>();
        if (node.getBranches() != null && !node.getBranches().isEmpty()) {
            for (Branch b : node.getBranches()) {
                StringBuilder cb = new StringBuilder();
                cb.append("label_cond=").append(canonicalCondition(b.getCondition()));
                cb.append(";subtree=");
                writeCanonical(b.getChild(), cb);
                childStrs.add(cb.toString());
            }
        } else if (node.getTrueBranch() != null || node.getFalseBranch() != null) {
            // 舊 binary 格式：treat as ordered pair with canonical pseudo-labels
            StringBuilder tb = new StringBuilder();
            tb.append("label_cond=").append(canonicalCondition(node.getCondition()));
            tb.append(";subtree=");
            writeCanonical(node.getTrueBranch(), tb);
            childStrs.add(tb.toString());

            StringBuilder fb = new StringBuilder();
            fb.append("label_cond=").append(canonicalCondition(negate(node.getCondition())));
            fb.append(";subtree=");
            writeCanonical(node.getFalseBranch(), fb);
            childStrs.add(fb.toString());
        }

        Collections.sort(childStrs);
        sb.append('[').append(String.join(",", childStrs)).append(']');
        sb.append('}');
    }

    // ============================================================
    // Condition 規範化
    // ============================================================

    String canonicalCondition(Condition c) {
        if (c == null) return "nil";
        String field = c.getField() == null ? "" : c.getField();
        String op = normalizeOperator(c.getOperator());
        Object v = canonicalValue(op, c.getValue());
        return "[" + field + " " + op + " " + v + "]";
    }

    /**
     * Operator 正規化 — 把「等價對」統一到單一正規形。
     * 例如：
     *   anything 本身就是正規形
     *   lessThanOrEqual 保留（非轉 negated greaterThan）
     * 以「字面字串」為主，不做語意翻轉（避免改變 results）。
     */
    private String normalizeOperator(String op) {
        return op == null ? "?" : op;
    }

    /**
     * Value 規範化：
     * - between: [min, max] 升冪
     * - in / notIn: 依字典序升冪
     * - BOOLEAN 字串 → boolean
     * - 數字字串 → number
     * - null / anything → "_"
     */
    Object canonicalValue(String op, Object v) {
        if (v == null) return "_";

        if ("between".equals(op) && v instanceof Collection<?> coll) {
            List<Object> list = new ArrayList<>(coll);
            list.sort(Comparator.comparing(Object::toString));
            return list;
        }
        if (("in".equals(op) || "notIn".equals(op)) && v instanceof Collection<?> coll) {
            List<Object> list = coll.stream()
                    .map(this::coerceScalar)
                    .sorted(Comparator.comparing(Object::toString))
                    .collect(Collectors.toList());
            return list;
        }
        return coerceScalar(v);
    }

    /**
     * Scalar 強制轉型正規化：
     * - "true"/"false"/"TRUE"/"FALSE" → Boolean
     * - 整數字串 "30" → Long
     * - 小數字串 "3.14" → Double（但若能整數表示則 Long）
     * - 其他保留原樣 toString
     */
    Object coerceScalar(Object v) {
        if (v == null) return "_";
        if (v instanceof Boolean) return v;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)
                    && Math.abs(d) < Long.MAX_VALUE) {
                return (long) d;
            }
            return d;
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
            try {
                long l = Long.parseLong(t);
                return l;
            } catch (NumberFormatException ignored) { }
            try {
                double d = Double.parseDouble(t);
                if (d == Math.floor(d)) return (long) d;
                return d;
            } catch (NumberFormatException ignored) { }
            return t;
        }
        return v.toString();
    }

    /**
     * Condition 負命題（供舊 binary 格式展開為 N-ary 時計算 FALSE 分支條件）。
     * 採純字串翻轉，不改變 field/value。
     */
    private Condition negate(Condition c) {
        if (c == null) return null;
        String op = c.getOperator();
        String neg = switch (op == null ? "" : op) {
            case "equals" -> "notEquals";
            case "notEquals" -> "equals";
            case "greaterThan" -> "lessThanOrEqual";
            case "greaterThanOrEqual" -> "lessThan";
            case "lessThan" -> "greaterThanOrEqual";
            case "lessThanOrEqual" -> "greaterThan";
            case "in" -> "notIn";
            case "notIn" -> "in";
            case "isNull" -> "isNotNull";
            case "isNotNull" -> "isNull";
            case "anything" -> "anything";
            default -> op == null ? "?" : ("not_" + op);
        };
        return Condition.builder().field(c.getField()).operator(neg).value(c.getValue()).build();
    }

    // ============================================================
    // Results 規範化
    // ============================================================

    private String canonicalResults(List<Result> results) {
        if (results == null || results.isEmpty()) return "";
        return results.stream()
                .map(r -> {
                    String f = r.getField() == null ? "" : r.getField();
                    Object v = coerceScalar(r.getValue());
                    return f + "=" + v;
                })
                .sorted()
                .collect(Collectors.joining(","));
    }

    // ============================================================
    // 工具方法
    // ============================================================

    boolean isLeaf(TreeNode node) {
        if (node == null) return false;
        boolean hasBranches = node.getBranches() != null && !node.getBranches().isEmpty();
        boolean hasBinary = node.getTrueBranch() != null || node.getFalseBranch() != null;
        return !hasBranches && !hasBinary;
    }

    List<TreeNode> getChildren(TreeNode node) {
        if (node == null) return List.of();
        List<TreeNode> out = new ArrayList<>();
        if (node.getBranches() != null) {
            for (Branch b : node.getBranches()) {
                if (b.getChild() != null) out.add(b.getChild());
            }
        }
        if (node.getTrueBranch() != null) out.add(node.getTrueBranch());
        if (node.getFalseBranch() != null) out.add(node.getFalseBranch());
        return out;
    }

    String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            // 取前 8 bytes = 16 hex chars，碰撞機率極低（對樹優化規模夠用）
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 理論上不會發生（SHA-256 保證存在）
            log.error("SHA-256 not available, falling back to Java hashCode: {}", e.getMessage());
            return Integer.toHexString(s.hashCode());
        }
    }
}
