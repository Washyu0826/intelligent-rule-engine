package com.ruleengine.rules.service.diff;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DecisionTree 語意結構 diff（research 方向③ — spike）。
 *
 * <p>用 <b>Zhang-Shasha 樹編輯距離</b>（1989）計算把 before 樹轉成 after 樹所需的最少
 * 節點 插入/刪除/重標記 次數，並回傳結構化的操作清單（哪些節點新增/刪除/修改）。</p>
 *
 * <p><b>為何優於文字 diff（{@code DiffView}）：</b>每個節點的「語意標籤」
 * <i>刻意排除 nodeId</i>，且兄弟節點以子樹簽章 canonical 排序，因此：</p>
 * <ul>
 *   <li>只改 nodeId（如 N01→X01）→ 編輯距離 <b>0</b></li>
 *   <li>兄弟分支重新排序 → 編輯距離 <b>0</b></li>
 *   <li>只有真正改了條件/結果 → 距離 = 受影響節點數，且能指出是哪個節點</li>
 * </ul>
 *
 * <p>自含實作、零外部依賴、純函式。對 &lt;30 節點的決策樹複雜度綽綽有餘
 * （Zhang-Shasha O(m²n²)）。設計與文獻見 {@code design/tech-research-three-directions.md} §方向③。</p>
 */
@Service
@Slf4j
public class TreeDiffService {

    /** 節點數上限。Zhang-Shasha 為 O(m²n²)，超過此值拒絕比對以免 hang（語意比對的合理工作範圍）。 */
    private static final int MAX_NODES = 200;
    /** 遞迴深度上限，兼作循環參照（畸形樹）防呆，避免 StackOverflow。 */
    private static final int MAX_DEPTH = 200;

    // ====================================================================
    //  公開 API
    // ====================================================================

    public record DiffOp(String type, String nodeIdBefore, String nodeIdAfter, String detail) {}

    public record TreeDiffResult(
            boolean comparable,
            int editDistance,
            int nodesAdded,
            int nodesRemoved,
            int nodesModified,
            int nodesUnchanged,
            List<DiffOp> operations,   // 僅含 INSERT/DELETE/UPDATE（MATCH 計入 unchanged）
            String summary
    ) {}

    /**
     * 比對兩個 DecisionTree envelope，回傳語意結構 diff。
     *
     * @param before 舊版 envelope（須為 DecisionTree）
     * @param after  新版 envelope（須為 DecisionTree）
     */
    public TreeDiffResult diff(RuleEnvelope before, RuleEnvelope after) {
        TreeNode r1 = rootOf(before);
        TreeNode r2 = rootOf(after);
        if (r1 == null || r2 == null) {
            return new TreeDiffResult(false, 0, 0, 0, 0, 0, List.of(),
                    "其中一方無 DecisionTree 樹根，無法做樹結構比對");
        }

        // 規模 / 循環防呆：先安全計數，超限即拒絕（避免 O(m²n²) hang 或 StackOverflow）
        int n1 = safeNodeCount(r1, 0);
        int n2 = safeNodeCount(r2, 0);
        if (n1 < 0 || n2 < 0) {
            return tooComplex("決策樹過深或疑似含循環參照（深度超過 " + MAX_DEPTH + "），無法做樹結構比對");
        }
        if (n1 > MAX_NODES || n2 > MAX_NODES) {
            return tooComplex(String.format(
                    "決策樹節點數（%d / %d）超過語意比對上限 %d；請改用結構/行為 diff 或拆分樹",
                    n1, n2, MAX_NODES));
        }

        TedNode t1 = build(r1, null);
        TedNode t2 = build(r2, null);
        List<Op> ops = computeTed(t1, t2);

        int added = 0, removed = 0, modified = 0, unchanged = 0;
        List<DiffOp> visible = new ArrayList<>();
        for (Op op : ops) {
            switch (op.type) {
                case "INSERT" -> {
                    added++;
                    visible.add(new DiffOp("INSERT", null, op.id2,
                            String.format("新增節點 %s（%s）", nz(op.id2), op.label2)));
                }
                case "DELETE" -> {
                    removed++;
                    visible.add(new DiffOp("DELETE", op.id1, null,
                            String.format("刪除節點 %s（%s）", nz(op.id1), op.label1)));
                }
                case "UPDATE" -> {
                    modified++;
                    String idPart = (op.id2 != null && !op.id2.equals(op.id1))
                            ? nz(op.id1) + "→" + nz(op.id2) : nz(op.id1);
                    visible.add(new DiffOp("UPDATE", op.id1, op.id2,
                            String.format("修改節點 %s：「%s」改為「%s」", idPart, op.label1, op.label2)));
                }
                default -> unchanged++; // MATCH
            }
        }

        int distance = added + removed + modified;
        String summary = String.format("編輯距離 %d：新增 %d、刪除 %d、修改 %d；%d 個節點不變",
                distance, added, removed, modified, unchanged);
        log.info("tree-diff | {}", summary);

        return new TreeDiffResult(true, distance, added, removed, modified, unchanged, visible, summary);
    }

    private static String nz(String s) { return s == null ? "?" : s; }

    private static TreeNode rootOf(RuleEnvelope e) {
        if (e == null || e.getRule() == null) return null;
        if (e.getRuleType() != null && !"DecisionTree".equalsIgnoreCase(e.getRuleType())) return null;
        return e.getRule().getRoot();
    }

    private TreeDiffResult tooComplex(String reason) {
        return new TreeDiffResult(false, 0, 0, 0, 0, 0, List.of(), reason);
    }

    /**
     * 安全計數子樹節點：以深度上限兼防循環參照。
     * @return 節點數；若深度超過 {@link #MAX_DEPTH}（畸形/循環樹）回 {@code -1}。
     */
    private int safeNodeCount(TreeNode node, int depth) {
        if (node == null) return 0;
        if (depth > MAX_DEPTH) return -1;
        int count = 1;
        if (node.getBranches() != null) {
            for (Branch b : node.getBranches()) {
                if (b == null || b.getChild() == null) continue;
                int c = safeNodeCount(b.getChild(), depth + 1);
                if (c < 0) return -1;
                count += c;
                if (count > MAX_NODES) return count; // 已超限，停止繼續展開
            }
        }
        if (node.getTrueBranch() != null) {
            int c = safeNodeCount(node.getTrueBranch(), depth + 1);
            if (c < 0) return -1;
            count += c;
        }
        if (node.getFalseBranch() != null) {
            int c = safeNodeCount(node.getFalseBranch(), depth + 1);
            if (c < 0) return -1;
            count += c;
        }
        return count;
    }

    // ====================================================================
    //  RuleEnvelope.TreeNode → 語意 TedNode（label 不含 nodeId、兄弟 canonical 排序）
    // ====================================================================

    private static final class TedNode {
        final String id;        // 原始 nodeId（僅供報告）
        final String label;     // 語意簽章（決定 cost；不含 nodeId）
        final List<TedNode> children = new ArrayList<>();
        TedNode(String id, String label) { this.id = id; this.label = label; }
    }

    private TedNode build(TreeNode tn, String incomingEdge) {
        String own = isLeaf(tn) ? "LEAF:" + resultsSig(tn.getResults())
                                : "BRANCH:" + conditionSig(tn.getCondition());
        String label = (incomingEdge != null && !incomingEdge.isEmpty())
                ? incomingEdge + " ⇒ " + own : own;
        TedNode node = new TedNode(tn.getNodeId(), label);

        List<TedNode> kids = new ArrayList<>();
        if (tn.getBranches() != null && !tn.getBranches().isEmpty()) {
            for (Branch b : tn.getBranches()) {
                if (b == null || b.getChild() == null) continue;
                String edge = b.getCondition() != null ? conditionSig(b.getCondition())
                        : (b.getLabel() != null ? b.getLabel() : "");
                kids.add(build(b.getChild(), edge));
            }
        } else {
            if (tn.getTrueBranch() != null) kids.add(build(tn.getTrueBranch(), "TRUE"));
            if (tn.getFalseBranch() != null) kids.add(build(tn.getFalseBranch(), "FALSE"));
        }

        // canonical 排序：兄弟以子樹簽章排序 → 重排不產生 diff
        kids.sort(Comparator.comparing(TreeDiffService::subtreeSig));
        node.children.addAll(kids);
        return node;
    }

    private static boolean isLeaf(TreeNode tn) {
        boolean hasChildren = (tn.getBranches() != null && !tn.getBranches().isEmpty())
                || tn.getTrueBranch() != null || tn.getFalseBranch() != null;
        return !hasChildren;
    }

    private static String conditionSig(Condition c) {
        if (c == null) return "";
        String val = c.getValueRef() != null ? "<" + c.getValueRef() + ">" : valStr(c.getValue());
        return safe(c.getField()) + " " + safe(c.getOperator()) + " " + val;
    }

    private static String resultsSig(List<Result> results) {
        if (results == null || results.isEmpty()) return "(無結果)";
        return results.stream()
                .map(r -> safe(r.getField()) + "=" + valStr(r.getValue()))
                .sorted()
                .collect(Collectors.joining(","));
    }

    @SuppressWarnings("unchecked")
    private static String valStr(Object v) {
        if (v == null) return "";
        if (v instanceof List<?> l) {
            return ((List<Object>) l).stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(v);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** 子樹 canonical 簽章（children 已遞迴排序，故此簽章穩定）。 */
    private static String subtreeSig(TedNode n) {
        StringBuilder sb = new StringBuilder(n.label).append('(');
        for (TedNode c : n.children) sb.append(subtreeSig(c)).append(';');
        return sb.append(')').toString();
    }

    // ====================================================================
    //  Zhang-Shasha 樹編輯距離（op-carrying DP）
    // ====================================================================

    private record Op(String type, String id1, String id2, String label1, String label2) {}

    private static final class Cell {
        final double cost;
        final List<Op> ops;
        Cell(double cost, List<Op> ops) { this.cost = cost; this.ops = ops; }
    }

    private List<Op> computeTed(TedNode root1, TedNode root2) {
        List<TedNode> po1 = new ArrayList<>();
        List<Integer> lmd1 = new ArrayList<>();
        fillPostorder(root1, po1, lmd1);
        List<TedNode> po2 = new ArrayList<>();
        List<Integer> lmd2 = new ArrayList<>();
        fillPostorder(root2, po2, lmd2);

        int n = po1.size(), m = po2.size();
        TedNode[] T1 = toArray(po1);   // 1-indexed
        TedNode[] T2 = toArray(po2);
        int[] L1 = toIntArray(lmd1);
        int[] L2 = toIntArray(lmd2);
        int[] KR1 = keyroots(L1, n);
        int[] KR2 = keyroots(L2, m);

        Cell[][] td = new Cell[n + 1][m + 1];

        for (int a = 0; a < KR1.length; a++) {
            for (int b = 0; b < KR2.length; b++) {
                int i = KR1[a], j = KR2[b];
                int ioff = L1[i] - 1, joff = L2[j] - 1;
                Cell[][] fd = new Cell[n + 1][m + 1];
                fd[ioff][joff] = new Cell(0, new ArrayList<>());

                for (int di = L1[i]; di <= i; di++) {
                    fd[di][joff] = withOp(fd[di - 1][joff], 1, del(T1[di]));
                }
                for (int dj = L2[j]; dj <= j; dj++) {
                    fd[ioff][dj] = withOp(fd[ioff][dj - 1], 1, ins(T2[dj]));
                }

                for (int di = L1[i]; di <= i; di++) {
                    for (int dj = L2[j]; dj <= j; dj++) {
                        double delCost = fd[di - 1][dj].cost + 1;
                        double insCost = fd[di][dj - 1].cost + 1;

                        if (L1[di] == L1[i] && L2[dj] == L2[j]) {
                            boolean eq = T1[di].label.equals(T2[dj].label);
                            double relCost = fd[di - 1][dj - 1].cost + (eq ? 0 : 1);
                            Cell chosen;
                            if (relCost <= delCost && relCost <= insCost) {
                                chosen = withOp(fd[di - 1][dj - 1], eq ? 0 : 1,
                                        new Op(eq ? "MATCH" : "UPDATE", T1[di].id, T2[dj].id,
                                                T1[di].label, T2[dj].label));
                            } else if (delCost <= insCost) {
                                chosen = withOp(fd[di - 1][dj], 1, del(T1[di]));
                            } else {
                                chosen = withOp(fd[di][dj - 1], 1, ins(T2[dj]));
                            }
                            fd[di][dj] = chosen;
                            td[di][dj] = chosen;
                        } else {
                            double matchCost = fd[L1[di] - 1][L2[dj] - 1].cost + td[di][dj].cost;
                            Cell chosen;
                            if (matchCost <= delCost && matchCost <= insCost) {
                                List<Op> ops = new ArrayList<>(fd[L1[di] - 1][L2[dj] - 1].ops);
                                ops.addAll(td[di][dj].ops);
                                chosen = new Cell(matchCost, ops);
                            } else if (delCost <= insCost) {
                                chosen = withOp(fd[di - 1][dj], 1, del(T1[di]));
                            } else {
                                chosen = withOp(fd[di][dj - 1], 1, ins(T2[dj]));
                            }
                            fd[di][dj] = chosen;
                        }
                    }
                }
            }
        }
        return td[n][m].ops;
    }

    private static Cell withOp(Cell base, double addCost, Op op) {
        List<Op> ops = new ArrayList<>(base.ops);
        ops.add(op);
        return new Cell(base.cost + addCost, ops);
    }

    private static Op del(TedNode n) { return new Op("DELETE", n.id, null, n.label, null); }
    private static Op ins(TedNode n) { return new Op("INSERT", null, n.id, null, n.label); }

    /** 後序走訪：填 po（節點）與 lmd（每節點的 leftmost-leaf 後序 1-indexed 位置）。回傳本節點 leftmost。 */
    private int fillPostorder(TedNode node, List<TedNode> po, List<Integer> lmd) {
        int leftmost = -1;
        for (int i = 0; i < node.children.size(); i++) {
            int cl = fillPostorder(node.children.get(i), po, lmd);
            if (i == 0) leftmost = cl;
        }
        po.add(node);
        int myIdx = po.size(); // 1-indexed
        if (node.children.isEmpty()) leftmost = myIdx;
        lmd.add(leftmost);
        return leftmost;
    }

    private static TedNode[] toArray(List<TedNode> po) {
        TedNode[] arr = new TedNode[po.size() + 1];
        for (int i = 0; i < po.size(); i++) arr[i + 1] = po.get(i);
        return arr;
    }

    private static int[] toIntArray(List<Integer> lmd) {
        int[] arr = new int[lmd.size() + 1];
        for (int i = 0; i < lmd.size(); i++) arr[i + 1] = lmd.get(i);
        return arr;
    }

    /** keyroots = 每個 leftmost 值對應的最大後序索引節點。 */
    private static int[] keyroots(int[] L, int n) {
        Map<Integer, Integer> maxIdxPerLeftmost = new HashMap<>();
        for (int i = 1; i <= n; i++) maxIdxPerLeftmost.put(L[i], i); // 升序 → 後者覆蓋 = 最大索引
        List<Integer> kr = new ArrayList<>(maxIdxPerLeftmost.values());
        Collections.sort(kr);
        int[] arr = new int[kr.size()];
        for (int i = 0; i < kr.size(); i++) arr[i] = kr.get(i);
        return arr;
    }
}
