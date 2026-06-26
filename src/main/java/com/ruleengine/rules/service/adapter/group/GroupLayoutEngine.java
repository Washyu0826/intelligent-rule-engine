package com.ruleengine.rules.service.adapter.group;

import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult.EdgeInfo;
import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult.NodeInfo;
import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult.Position;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-down tidy-tree layout for Group-styled trees.
 *
 * <p>Algorithm (Reingold–Tilford simplified, per group-tree-rendering-spec.md §5):</p>
 * <ol>
 *   <li>Build an ordered child map from edges (edge insertion order = left-to-right siblings).</li>
 *   <li>Depth-first walk from the root (the first node — exporters always emit root first):
 *       each leaf takes the next horizontal slot ({@code NODE_WIDTH + H_GAP} apart); each
 *       internal node is centred over the midpoint of its first and last child.</li>
 *   <li>{@code y = depth * LEVEL_HEIGHT}.</li>
 * </ol>
 *
 * <p>Because leaf slots are assigned sequentially in DFS order and every internal node sits
 * over its own descendant span, sibling subtrees never overlap and edges do not cross — which
 * the previous "evenly spread each level across a fixed width" approach did not guarantee once
 * a level had more than a handful of nodes or the labels were wide.</p>
 *
 * <p>The method mutates the {@code nodes} list — it replaces each {@code NodeInfo}'s
 * {@code position} with a freshly computed {@link Position}. Edges are read-only; they are
 * consulted only to build the child map.</p>
 */
@Component
@Slf4j
public class GroupLayoutEngine {

    private static final int LEVEL_HEIGHT = 120;
    /** Slot width per leaf; tuned to clear the frontend's 148px node box plus breathing room. */
    private static final int NODE_WIDTH = 160;
    /** Minimum horizontal gap between adjacent leaf slots. */
    private static final int H_GAP = 48;

    /**
     * Compute and assign {@code position.x/y} on each node in-place. If the
     * {@code nodes} list is empty the call is a no-op.
     *
     * @param nodes the node list (mutated in-place — each entry is replaced
     *              with a copy carrying a fresh {@link Position})
     * @param edges the edge list, read-only; used to build the child map
     */
    public void assignPositions(List<NodeInfo> nodes, List<EdgeInfo> edges) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        // Ordered child map (from → list of to); insertion order keeps siblings left-to-right.
        Map<String, List<String>> children = new LinkedHashMap<>();
        if (edges != null) {
            for (EdgeInfo edge : edges) {
                children.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            }
        }

        String rootId = nodes.get(0).nodeId();
        Map<String, Integer> xPos = new HashMap<>();
        Map<String, Integer> depthMap = new HashMap<>();
        Set<String> visited = new HashSet<>();
        int[] cursor = {0};

        assignSubtree(rootId, 0, children, xPos, depthMap, visited, cursor);

        // Any node unreachable from the root (detached / out-of-order) is laid out to the right
        // at depth 0 so it is still visible rather than collapsed onto the origin.
        for (NodeInfo node : nodes) {
            if (!xPos.containsKey(node.nodeId())) {
                xPos.put(node.nodeId(), cursor[0]);
                depthMap.put(node.nodeId(), 0);
                cursor[0] += NODE_WIDTH + H_GAP;
            }
        }

        // Rebuild the nodes list in-place with fresh positions.
        for (int i = 0; i < nodes.size(); i++) {
            NodeInfo n = nodes.get(i);
            int x = xPos.getOrDefault(n.nodeId(), 0);
            int y = depthMap.getOrDefault(n.nodeId(), 0) * LEVEL_HEIGHT;
            nodes.set(i, new NodeInfo(
                    n.nodeId(),
                    n.type(),
                    n.labelChinese(),
                    n.expressionType(),
                    new Position(x, y),
                    n.fieldName(),
                    n.outputs()
            ));
        }
    }

    /**
     * Post-order placement: place all (fresh) children first, then centre this node over the
     * midpoint of its first and last child. Leaves consume the next horizontal slot.
     */
    private void assignSubtree(String id,
                               int depth,
                               Map<String, List<String>> children,
                               Map<String, Integer> xPos,
                               Map<String, Integer> depthMap,
                               Set<String> visited,
                               int[] cursor) {
        if (!visited.add(id)) {
            return; // cycle / shared-child guard (trees shouldn't hit this, but stay safe)
        }
        depthMap.put(id, depth);

        List<String> freshKids = new ArrayList<>();
        for (String kid : children.getOrDefault(id, List.of())) {
            if (!visited.contains(kid)) {
                freshKids.add(kid);
            }
        }

        if (freshKids.isEmpty()) {
            xPos.put(id, cursor[0]);
            cursor[0] += NODE_WIDTH + H_GAP;
            return;
        }

        for (String kid : freshKids) {
            assignSubtree(kid, depth + 1, children, xPos, depthMap, visited, cursor);
        }
        int first = xPos.get(freshKids.get(0));
        int last = xPos.get(freshKids.get(freshKids.size() - 1));
        xPos.put(id, (first + last) / 2);
    }
}
