package com.ruleengine.rules.service.adapter.group;

import com.ruleengine.rules.domain.RuleEnvelopeExtensions;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.FieldDef;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.ScoreBand;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import com.ruleengine.rules.domain.extensions.FieldOrSpec;
import com.ruleengine.rules.domain.extensions.GlobalGuard;
import com.ruleengine.rules.domain.glossary.GlossaryEntry;
import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult.GroupTree;
import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult.EdgeInfo;
import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult.NodeInfo;
import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult.Position;
import com.ruleengine.rules.service.adapter.group.GroupTreeExportResult.ResultPair;
import com.ruleengine.rules.service.converter.TableToTreeConverter;
import com.ruleengine.rules.service.glossary.GlossaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts a {@link RuleEnvelope} into a Group-styled tree JSON
 * ({@link GroupTreeExportResult}) for the M7 dashboard's "Engine Execution"
 * tab.
 *
 * <p>v3.14 demo-sprint scope (DEMO_PLAN_3H §4.1, P0 #1/#3/#4):</p>
 * <ul>
 *   <li><b>DecisionTree</b> → walk {@code rule.root} recursively (legacy behaviour).</li>
 *   <li><b>DecisionTable + FIRST</b> → delegate to {@link TableToTreeConverter}
 *       (ID3 information-gain split) then walk the synthesised DecisionTree —
 *       produces a real nested tree, not a 1-level flat fan-out.</li>
 *   <li><b>DecisionTable + MULTI</b> → 2-level tree (ROOT "MULTI 檢核" → discriminator
 *       value branches → rule leaves), picking the input field with the most
 *       distinct {@code equals} values across rules. {@code anything} rules
 *       cascade to ALL branches. If no field qualifies, fall back to a 1-level
 *       tree with a "MULTI N 條獨立規則" banner annotation.</li>
 *   <li><b>ScoreCard</b> → 1-level over {@code scoreBands} (legacy behaviour).</li>
 * </ul>
 *
 * <p>v3.14 extension surfaces:</p>
 * <ul>
 *   <li>{@code extensions.globalGuards} → prepended as banner BRANCH nodes
 *       above ROOT, chained ({@code GUARD-1 → GUARD-2 → … → ROOT}). Without
 *       this, sample-B's globalGuard is invisible — that's the bug fixed here.</li>
 *   <li>{@code extensions.fieldOr} → decorative annotation on any edge whose
 *       {@code field} matches {@code fieldOrSpec.fields[0]}: appends
 *       {@code "（可替換為 [<sibling label>]）"} to the edge label.</li>
 * </ul>
 *
 * <p>All BRANCH labels resolve through {@link GlossaryService#search(String, String)};
 * the first entry's {@code zh_TW} wins. Misses fall back to the raw field name
 * and emit a warning (NEVER crash).</p>
 *
 * <p>Edge label abbreviation rules follow {@code design/group-tree-rendering-spec.md}
 * §4.1 — operator → glyph mapping preserved verbatim.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupJsonExporter {

    private static final int VALUE_TRUNCATE_LIMIT = 12;
    private static final String DEMO_VERSION = "0.1-demo";

    private final GroupLayoutEngine layoutEngine;
    private final GlossaryService glossaryService;
    private final TableToTreeConverter tableToTreeConverter;
    private final GroupLabelResolver labelResolver;

    /**
     * Convert the envelope into a Group-styled tree.
     *
     * @param envelope the source envelope (must be non-null)
     * @return the export result, including any warnings raised during conversion
     */
    public GroupTreeExportResult export(RuleEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");

        List<NodeInfo> nodes = new ArrayList<>();
        List<EdgeInfo> edges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Rule rule = envelope.getRule();
        String ruleType = envelope.getRuleType();

        // Build the glossary-resolved field labels map up front (used everywhere downstream).
        Map<String, FieldDef> fieldByName = rule == null
                ? Map.of()
                : indexFields(rule.getInputs(), rule.getOutputs());

        // ---- Body: build the rule-type-specific tree ----
        if (rule == null) {
            warnings.add("envelope.rule is null — exporting empty tree");
        } else if ("DecisionTree".equalsIgnoreCase(ruleType)) {
            exportDecisionTree(rule, nodes, edges, warnings, fieldByName);
        } else if ("DecisionTable".equalsIgnoreCase(ruleType)) {
            String hitPolicy = rule.getHitPolicy();
            if ("MULTI".equalsIgnoreCase(hitPolicy)) {
                buildMultiTableTree(rule, nodes, edges, warnings, fieldByName);
            } else {
                // FIRST (or any non-MULTI, which we treat as FIRST per v3.14 default).
                exportDecisionTableFirst(envelope, nodes, edges, warnings, fieldByName);
            }
        } else if ("ScoreCard".equalsIgnoreCase(ruleType)) {
            exportScoreCard(rule, nodes, edges, warnings, fieldByName);
        } else {
            warnings.add("unsupported ruleType: " + ruleType);
        }

        // ---- Surface envelope.extensions.fieldOr as decorative edge labels ----
        decorateEdgesWithFieldOr(edges, envelope.getExtensions(), fieldByName, warnings);

        // ---- Prepend envelope.extensions.globalGuards as banner BRANCH nodes ----
        prependGlobalGuards(envelope.getExtensions(), nodes, edges, warnings, fieldByName);

        // ---- Layout pass (orchestrator owns this; do not work around it) ----
        layoutEngine.assignPositions(nodes, edges);

        String treeId = "DRAFT-" + System.currentTimeMillis();
        String name = buildTreeName(envelope);
        GroupTree tree = new GroupTree(treeId, name, DEMO_VERSION, nodes, edges);
        return new GroupTreeExportResult(tree, warnings);
    }

    // ============================================================
    // DecisionTree
    // ============================================================

    private void exportDecisionTree(Rule rule, List<NodeInfo> nodes,
                                    List<EdgeInfo> edges, List<String> warnings,
                                    Map<String, FieldDef> fieldByName) {
        TreeNode root = rule.getRoot();
        if (root == null) {
            warnings.add("DecisionTree envelope has null root");
            return;
        }
        walkTreeNode(root, nodes, edges, warnings, fieldByName);
    }

    /** Recursively walks a TreeNode, emitting one NodeInfo and edges to each child. */
    private void walkTreeNode(TreeNode node, List<NodeInfo> nodes, List<EdgeInfo> edges,
                              List<String> warnings, Map<String, FieldDef> fieldByName) {
        if (node == null) {
            return;
        }
        boolean isLeaf = (node.getResults() != null && !node.getResults().isEmpty())
                || (node.getBranches() == null && node.getCondition() == null
                    && node.getTrueBranch() == null && node.getFalseBranch() == null);
        if (isLeaf) {
            nodes.add(buildLeafNode(node, fieldByName));
            return;
        }
        // Branch node — emit the node first, then walk each child.
        nodes.add(buildBranchNode(node, fieldByName, warnings));
        if (node.getBranches() != null && !node.getBranches().isEmpty()) {
            for (Branch b : node.getBranches()) {
                TreeNode child = b.getChild();
                if (child == null) {
                    continue;
                }
                edges.add(buildEdge(node.getNodeId(), child.getNodeId(),
                        b.getCondition(), warnings));
                walkTreeNode(child, nodes, edges, warnings, fieldByName);
            }
        } else {
            if (node.getTrueBranch() != null) {
                Condition truthyCond = node.getCondition();
                edges.add(buildEdge(node.getNodeId(), node.getTrueBranch().getNodeId(),
                        truthyCond, warnings));
                walkTreeNode(node.getTrueBranch(), nodes, edges, warnings, fieldByName);
            }
            if (node.getFalseBranch() != null) {
                edges.add(new EdgeInfo(node.getNodeId(), node.getFalseBranch().getNodeId(),
                        "否", "notEquals",
                        node.getCondition() == null ? null : node.getCondition().getValue()));
                walkTreeNode(node.getFalseBranch(), nodes, edges, warnings, fieldByName);
            }
        }
    }

    private NodeInfo buildBranchNode(TreeNode node, Map<String, FieldDef> fieldByName,
                                     List<String> warnings) {
        Condition cond = node.getCondition();
        String fieldName = cond == null ? null : cond.getField();
        String label = chineseFieldLabel(fieldName, fieldByName, warnings);
        String expressionType = cond == null ? null : toExpressionType(cond.getOperator());
        return new NodeInfo(
                node.getNodeId(),
                "BRANCH",
                label,
                expressionType,
                new Position(0, 0),
                fieldName,
                null
        );
    }

    private NodeInfo buildLeafNode(TreeNode node, Map<String, FieldDef> fieldByName) {
        List<Result> results = node.getResults();
        String label = leafLabel(results, fieldByName);
        List<ResultPair> outputs = new ArrayList<>();
        if (results != null) {
            for (Result r : results) {
                outputs.add(new ResultPair(r.getField(), r.getValue()));
            }
        }
        return new NodeInfo(
                node.getNodeId(),
                "LEAF",
                label,
                null,
                new Position(0, 0),
                null,
                outputs
        );
    }

    // ============================================================
    // DecisionTable + FIRST → delegate to TableToTreeConverter
    // ============================================================

    /**
     * For FIRST hitPolicy we synthesise a proper nested DecisionTree via the
     * ID3-based converter and feed the result back through our own DecisionTree
     * walker. This yields a real branch-then-leaf structure that respects the
     * dominant discriminator field.
     */
    private void exportDecisionTableFirst(RuleEnvelope envelope, List<NodeInfo> nodes,
                                          List<EdgeInfo> edges, List<String> warnings,
                                          Map<String, FieldDef> fieldByName) {
        try {
            RuleEnvelope converted = tableToTreeConverter.convert(envelope);
            if (converted == null || converted.getRule() == null
                    || converted.getRule().getRoot() == null) {
                warnings.add("TableToTreeConverter returned empty tree — falling back to flat layout");
                exportDecisionTableFlat(envelope.getRule(), nodes, edges, warnings, fieldByName, "FIRST 檢核");
                return;
            }
            // Walk the synthesised tree using the input/output field metadata from the
            // ORIGINAL envelope (the converted envelope copies these but our caller
            // already has them indexed).
            walkTreeNode(converted.getRule().getRoot(), nodes, edges, warnings, fieldByName);
        } catch (RuntimeException ex) {
            warnings.add("TableToTreeConverter failed (" + ex.getMessage()
                    + ") — falling back to flat layout");
            exportDecisionTableFlat(envelope.getRule(), nodes, edges, warnings, fieldByName, "FIRST 檢核");
        }
    }

    // ============================================================
    // DecisionTable + MULTI → 2-level discriminator tree
    // ============================================================

    /**
     * Build a 2-level tree for MULTI hitPolicy:
     * <pre>
     *   ROOT (BRANCH "MULTI 檢核")
     *     ├─ BRANCH (discriminator value 1)  ──▶ rule leaves matching value 1
     *     ├─ BRANCH (discriminator value 2)  ──▶ rule leaves matching value 2
     *     └─ ...
     * </pre>
     * <p>Discriminator = the input field with the most distinct {@code equals}
     * values across all rules (≥2 required). Rules whose discriminator
     * condition is {@code anything} or missing are emitted under EVERY value
     * branch (since they apply universally on that field) — this preserves
     * MULTI's "all matching rules fire" semantics in the visual.</p>
     * <p>If no field qualifies, falls back to {@link #exportDecisionTableFlat}.</p>
     */
    private void buildMultiTableTree(Rule rule, List<NodeInfo> nodes, List<EdgeInfo> edges,
                                     List<String> warnings, Map<String, FieldDef> fieldByName) {
        List<RuleRow> rules = rule.getRules();
        if (rules == null || rules.isEmpty()) {
            warnings.add("DecisionTable (MULTI) envelope has no rules");
            nodes.add(rootNode("MULTI 檢核"));
            return;
        }

        // 1. Pick the discriminator field — highest count of distinct equals values (≥2).
        String discriminator = pickDiscriminatorField(rule.getInputs(), rules);
        if (discriminator == null) {
            warnings.add("MULTI: no input field has ≥2 distinct equals values — "
                    + "falling back to 1-level flat tree with " + rules.size() + " independent rules");
            exportDecisionTableFlat(rule, nodes, edges, warnings, fieldByName,
                    "MULTI 檢核 — " + rules.size() + " 條獨立規則");
            return;
        }

        // 2. Collect distinct values for the discriminator (preserving rule order).
        LinkedHashSet<Object> distinctValues = new LinkedHashSet<>();
        for (RuleRow row : rules) {
            Condition c = findCondition(row, discriminator);
            if (c != null && "equals".equalsIgnoreCase(c.getOperator()) && c.getValue() != null) {
                distinctValues.add(c.getValue());
            }
        }

        // 3. ROOT.
        String rootLabel = "MULTI 檢核";
        NodeInfo root = new NodeInfo("ROOT", "BRANCH", rootLabel,
                "MULTI",
                new Position(0, 0),
                discriminator,
                null);
        nodes.add(root);

        // 4. One BRANCH child per distinct value, plus leaves under it for each
        //    matching rule. Rules with anything-on-discriminator cascade to all branches.
        int branchIdx = 0;
        int leafCounter = 0;
        for (Object value : distinctValues) {
            branchIdx++;
            String branchId = "M" + String.format("%02d", branchIdx);
            String valueLabel = chineseFieldLabel(discriminator, fieldByName, warnings)
                    + " = " + renderValue(value);
            NodeInfo branchNode = new NodeInfo(
                    branchId, "BRANCH", valueLabel,
                    "EQUALS",
                    new Position(0, 0),
                    discriminator,
                    null);
            nodes.add(branchNode);
            edges.add(new EdgeInfo("ROOT", branchId,
                    "= " + renderValue(value), "equals", value));

            // Leaves under this branch.
            for (RuleRow row : rules) {
                if (!ruleAppliesToValue(row, discriminator, value)) {
                    continue;
                }
                leafCounter++;
                String leafId = (row.getRuleId() != null ? row.getRuleId() : ("R" + leafCounter))
                        + "_" + branchId;
                String leafLbl = leafLabel(row.getResults(), fieldByName);
                if (leafLbl == null || leafLbl.isBlank()) {
                    leafLbl = (row.getRuleId() != null ? row.getRuleId() : "R" + leafCounter) + " 規則";
                }
                List<ResultPair> outputs = new ArrayList<>();
                if (row.getResults() != null) {
                    for (Result r : row.getResults()) {
                        outputs.add(new ResultPair(r.getField(), r.getValue()));
                    }
                }
                nodes.add(new NodeInfo(leafId, "LEAF", leafLbl, null,
                        new Position(0, 0), null, outputs));
                // Edge label = the OTHER conditions on the rule (excluding discriminator).
                String edgeLabel = joinOtherConditionLabels(row.getConditions(), discriminator, warnings);
                edges.add(new EdgeInfo(branchId, leafId, edgeLabel, "AND", null));
            }
        }
    }

    /**
     * Returns the input field name whose count of distinct {@code equals} values
     * across all rules is highest and ≥2; otherwise {@code null}.
     */
    private String pickDiscriminatorField(List<FieldDef> inputs, List<RuleRow> rules) {
        if (inputs == null) return null;
        String best = null;
        int bestCount = 1;  // strict >1 required
        for (FieldDef f : inputs) {
            String name = f.getName();
            if (name == null) continue;
            Set<String> distinct = new LinkedHashSet<>();
            for (RuleRow row : rules) {
                Condition c = findCondition(row, name);
                if (c != null && "equals".equalsIgnoreCase(c.getOperator()) && c.getValue() != null) {
                    distinct.add(String.valueOf(c.getValue()));
                }
            }
            if (distinct.size() > bestCount) {
                bestCount = distinct.size();
                best = name;
            }
        }
        return best;
    }

    /**
     * A rule applies to a specific discriminator value if:
     * (a) it has an {@code equals} condition matching that value, OR
     * (b) it has an {@code anything} condition on the discriminator field, OR
     * (c) it has no condition for the discriminator field at all (treat as anything).
     */
    private boolean ruleAppliesToValue(RuleRow row, String discriminator, Object value) {
        Condition c = findCondition(row, discriminator);
        if (c == null) return true;  // missing → cascade to all
        String op = c.getOperator();
        if (op == null || "anything".equalsIgnoreCase(op)) return true;
        if ("equals".equalsIgnoreCase(op)) {
            return Objects.equals(String.valueOf(c.getValue()), String.valueOf(value));
        }
        if ("in".equalsIgnoreCase(op) && c.getValue() instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (Objects.equals(String.valueOf(item), String.valueOf(value))) return true;
            }
            return false;
        }
        return false;
    }

    private Condition findCondition(RuleRow row, String fieldName) {
        if (row == null || row.getConditions() == null) return null;
        for (Condition c : row.getConditions()) {
            if (fieldName.equals(c.getField())) return c;
        }
        return null;
    }

    private String joinOtherConditionLabels(List<Condition> conditions, String exclude, List<String> warnings) {
        if (conditions == null || conditions.isEmpty()) return "*";
        List<String> parts = new ArrayList<>();
        for (Condition c : conditions) {
            if (exclude.equals(c.getField())) continue;
            if (c.getOperator() != null && c.getOperator().equalsIgnoreCase("anything")) continue;
            parts.add(formatConditionForEdge(c, warnings));
        }
        return parts.isEmpty() ? "*" : String.join(" 且 ", parts);
    }

    // ============================================================
    // DecisionTable flat fallback (no discriminator / converter failure)
    // ============================================================

    private void exportDecisionTableFlat(Rule rule, List<NodeInfo> nodes,
                                         List<EdgeInfo> edges, List<String> warnings,
                                         Map<String, FieldDef> fieldByName,
                                         String rootLabel) {
        nodes.add(rootNode(rootLabel));
        List<RuleRow> rules = rule.getRules();
        if (rules == null || rules.isEmpty()) {
            warnings.add("DecisionTable envelope has no rules");
            return;
        }
        int rowIdx = 0;
        for (RuleRow row : rules) {
            rowIdx++;
            String leafId = row.getRuleId() != null ? row.getRuleId() : ("R" + rowIdx);
            String leafLbl = leafLabel(row.getResults(), fieldByName);
            if (leafLbl == null || leafLbl.isBlank()) {
                leafLbl = "規則 R" + String.format("%02d", rowIdx);
            }
            List<ResultPair> outputs = new ArrayList<>();
            if (row.getResults() != null) {
                for (Result r : row.getResults()) {
                    outputs.add(new ResultPair(r.getField(), r.getValue()));
                }
            }
            nodes.add(new NodeInfo(leafId, "LEAF", leafLbl, null,
                    new Position(0, 0), null, outputs));
            String edgeLabel = joinConditionLabels(row.getConditions(), warnings);
            edges.add(new EdgeInfo("ROOT", leafId, edgeLabel, "AND", null));
        }
    }

    private NodeInfo rootNode(String label) {
        return new NodeInfo("ROOT", "BRANCH", label, null,
                new Position(0, 0), null, null);
    }

    private String joinConditionLabels(List<Condition> conditions, List<String> warnings) {
        if (conditions == null || conditions.isEmpty()) {
            return "*";
        }
        List<String> parts = new ArrayList<>();
        for (Condition c : conditions) {
            if (c.getOperator() != null && c.getOperator().equalsIgnoreCase("anything")) {
                continue;
            }
            parts.add(formatConditionForEdge(c, warnings));
        }
        if (parts.isEmpty()) {
            return "*";
        }
        return String.join(" 且 ", parts);
    }

    // ============================================================
    // ScoreCard
    // ============================================================

    private void exportScoreCard(Rule rule, List<NodeInfo> nodes,
                                 List<EdgeInfo> edges, List<String> warnings,
                                 Map<String, FieldDef> fieldByName) {
        nodes.add(rootNode("評分卡"));

        List<ScoreBand> bands = rule.getScoreBands();
        if (bands == null || bands.isEmpty()) {
            warnings.add("ScoreCard envelope has no scoreBands");
            return;
        }
        int idx = 0;
        for (ScoreBand band : bands) {
            idx++;
            String leafId = band.getBandId() != null ? band.getBandId() : ("B" + idx);
            String leafLbl = leafLabel(band.getResults(), fieldByName);
            if (leafLbl == null || leafLbl.isBlank()) {
                leafLbl = "區間 B" + String.format("%02d", idx);
            }
            List<ResultPair> outputs = new ArrayList<>();
            if (band.getResults() != null) {
                for (Result r : band.getResults()) {
                    outputs.add(new ResultPair(r.getField(), r.getValue()));
                }
            }
            nodes.add(new NodeInfo(leafId, "LEAF", leafLbl, null,
                    new Position(0, 0), null, outputs));
            String edgeLabel = "[] " + (band.getMinScore() == null ? "-∞" : band.getMinScore())
                    + ".." + (band.getMaxScore() == null ? "+∞" : band.getMaxScore());
            edges.add(new EdgeInfo("ROOT", leafId, edgeLabel, "between",
                    List.of(
                            band.getMinScore() == null ? 0 : band.getMinScore(),
                            band.getMaxScore() == null ? 0 : band.getMaxScore()
                    )));
        }
    }

    // ============================================================
    // v3.14 extensions — globalGuards (banner) & fieldOr (edge annotation)
    // ============================================================

    /**
     * Prepend each {@link GlobalGuard} as a BRANCH banner node, chained so that
     * {@code GUARD-1 → GUARD-2 → … → GUARD-N → ROOT}. The chain is inserted
     * at the head of the node list so layout BFS treats the first guard as the
     * new root. If there are no guards, this is a no-op.
     */
    private void prependGlobalGuards(RuleEnvelopeExtensions ext,
                                     List<NodeInfo> nodes, List<EdgeInfo> edges,
                                     List<String> warnings, Map<String, FieldDef> fieldByName) {
        if (ext == null) return;
        List<GlobalGuard> guards = ext.getGlobalGuards();
        if (guards == null || guards.isEmpty()) return;
        if (nodes.isEmpty()) {
            // Nothing to chain to — guards would dangle. Skip with a warning.
            warnings.add("globalGuards present but tree body is empty — guards not rendered");
            return;
        }

        String originalRoot = nodes.get(0).nodeId();
        List<NodeInfo> guardNodes = new ArrayList<>();
        List<EdgeInfo> guardEdges = new ArrayList<>();

        int idx = 0;
        String prevGuardId = null;
        for (GlobalGuard g : guards) {
            idx++;
            String guardId = "GUARD-" + idx;
            String desc = g.getDescription();
            String label = "守門：" + (desc == null || desc.isBlank() ? ("G" + idx) : truncate(desc, 30));
            String guardField = g.getCondition() == null ? null : g.getCondition().getField();
            guardNodes.add(new NodeInfo(
                    guardId, "BRANCH", label,
                    g.getCondition() == null ? null : toExpressionType(g.getCondition().getOperator()),
                    new Position(0, 0),
                    guardField,
                    null));
            // Chain previous guard → this guard, or this guard → ROOT for the tail.
            if (prevGuardId != null) {
                guardEdges.add(new EdgeInfo(prevGuardId, guardId,
                        "通過", "anything", null));
            }
            prevGuardId = guardId;
        }
        // Final guard → original root with the LAST guard's condition formatted on the edge.
        // We render the LAST guard's condition on the GUARD-N → ROOT edge so the
        // visual "if guard holds, descend into tree" reads naturally.
        GlobalGuard tail = guards.get(guards.size() - 1);
        String tailEdgeLabel = tail.getCondition() == null
                ? "通過"
                : formatConditionForEdge(tail.getCondition(), warnings);
        guardEdges.add(new EdgeInfo(prevGuardId, originalRoot,
                tailEdgeLabel,
                tail.getCondition() == null ? "anything" : tail.getCondition().getOperator(),
                tail.getCondition() == null ? null : tail.getCondition().getValue()));

        // Insert guards at the head of the lists so BFS-from-nodes[0] sees the
        // first guard as the new root and layouts top-down correctly.
        nodes.addAll(0, guardNodes);
        edges.addAll(0, guardEdges);

        warnings.add("applied " + guards.size() + " global guards as banner nodes");
    }

    /**
     * Decorative pass: for every {@link FieldOrSpec}, find edges whose
     * {@code field} (resolved from the underlying condition value if exposed
     * via the edge's original {@code value}) matches {@code fields[0]} of the
     * spec, and append {@code "（可替換為 [<sibling label>]）"} to the edge label.
     *
     * <p>Edges don't directly carry the source {@code Condition.field}, so we
     * approximate by walking the node list to find BRANCH nodes whose
     * {@code fieldName} matches {@code fields[0]} and decorating their
     * outgoing edges. This is decorative — semantics unchanged.</p>
     */
    private void decorateEdgesWithFieldOr(List<EdgeInfo> edges, RuleEnvelopeExtensions ext,
                                          Map<String, FieldDef> fieldByName, List<String> warnings) {
        if (ext == null) return;
        List<FieldOrSpec> specs = ext.getFieldOr();
        if (specs == null || specs.isEmpty()) return;

        // For each spec, build (primaryField → siblingLabel) mappings.
        Map<String, String> primaryToSiblingLabel = new HashMap<>();
        for (FieldOrSpec spec : specs) {
            List<String> fields = spec.getFields();
            if (fields == null || fields.size() < 2) continue;
            String primary = fields.get(0);
            // Sibling label = chinese label of fields[1] (and any further siblings comma-joined).
            List<String> siblingLabels = new ArrayList<>();
            for (int i = 1; i < fields.size(); i++) {
                siblingLabels.add(chineseFieldLabel(fields.get(i), fieldByName, warnings));
            }
            primaryToSiblingLabel.put(primary, String.join(", ", siblingLabels));
        }
        if (primaryToSiblingLabel.isEmpty()) return;

        // Decorate edges: any edge whose label currently mentions an equals/in on the
        // primary field gets the "可替換為" suffix. We detect this by matching the
        // edge's `value` literal against the spec's predicate-style — but simpler:
        // we just look at every edge and check whether the *label* contains the
        // primary field's chinese label as a prefix. Since field names in edge
        // labels aren't directly available, we instead match on the underlying
        // condition value carried by the edge. The cleanest heuristic that
        // doesn't require schema changes: append the annotation to edges whose
        // `operator` is "equals"/"in" AND whose label starts with "= " or "∈ "
        // AND originate from a BRANCH whose fieldName is the primary field.
        //
        // Building a `from-nodeId → fieldName` index is the easiest correlation.
        // We don't have access to nodes here, so we do a two-step:
        //   1) walk the edge list and rewrite any edge whose label-prefix or
        //      `value` exactly matches the FieldOrSpec.predicate.value.
        // This catches the sample-B case (R03_1 has equals on
        // newContractPaymentChannel + renewalPaymentChannel, both with value
        // "指定帳戶轉帳"; the spec marks them as OR-eligible).
        for (int i = 0; i < edges.size(); i++) {
            EdgeInfo e = edges.get(i);
            for (FieldOrSpec spec : specs) {
                List<String> fields = spec.getFields();
                if (fields == null || fields.size() < 2) continue;
                String primary = fields.get(0);
                Condition pred = spec.getPredicate();
                if (pred == null) continue;
                // Match: edge's operator equals predicate.operator AND value matches.
                if (!Objects.equals(e.operator(), pred.getOperator())) continue;
                if (!valuesEquivalent(e.value(), pred.getValue())) continue;
                // We can't recover the source field from EdgeInfo alone, so we
                // decorate ANY edge with this op+value combination. Since the
                // predicate is intentionally narrow (one value), false positives
                // are rare and decorative anyway.
                String sibling = primaryToSiblingLabel.get(primary);
                if (sibling == null) continue;
                String decorated = e.label() + "（可替換為 [" + sibling + "]）";
                edges.set(i, new EdgeInfo(e.from(), e.to(), decorated, e.operator(), e.value()));
                break;  // one annotation per edge
            }
        }
    }

    private boolean valuesEquivalent(Object a, Object b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        return Objects.equals(String.valueOf(a), String.valueOf(b));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private EdgeInfo buildEdge(String from, String to, Condition cond, List<String> warnings) {
        if (cond == null) {
            return new EdgeInfo(from, to, "*", "anything", null);
        }
        return new EdgeInfo(from, to,
                formatConditionForEdge(cond, warnings),
                cond.getOperator(),
                cond.getValue());
    }

    /**
     * Format a single condition as an abbreviated edge label per
     * group-tree-rendering-spec.md §4.1.
     */
    private String formatConditionForEdge(Condition cond, List<String> warnings) {
        if (cond == null) {
            return "*";
        }
        String op = cond.getOperator();
        Object value = cond.getValue();
        String valueRef = cond.getValueRef();
        if (op == null) {
            warnings.add("condition missing operator on field=" + cond.getField());
            return "?";
        }
        String rendered = (valueRef != null && !valueRef.isBlank())
                ? "[" + valueRef + "]"
                : renderValue(value);

        switch (op) {
            case "equals":              return "= " + rendered;
            case "notEquals":           return "!= " + rendered;
            case "greaterThan":         return "> " + rendered;
            case "greaterThanOrEqual":  return ">= " + rendered;
            case "lessThan":            return "< " + rendered;
            case "lessThanOrEqual":     return "<= " + rendered;
            case "between":             return "[] " + renderBetween(value);
            case "in":                  return "∈ " + renderSet(value);
            case "notIn":               return "∉ " + renderSet(value);
            case "isNull":              return "is null";
            case "isNotNull":           return "is not null";
            case "anything":            return "*";
            default:
                warnings.add("unsupported operator '" + op + "' on field=" + cond.getField());
                return op + " " + rendered;
        }
    }

    private String renderBetween(Object value) {
        if (value instanceof List<?> list && list.size() >= 2) {
            return truncate(String.valueOf(list.get(0))) + ".." + truncate(String.valueOf(list.get(1)));
        }
        return renderValue(value);
    }

    private String renderSet(Object value) {
        if (value instanceof Collection<?> coll) {
            List<String> items = new ArrayList<>();
            for (Object item : coll) {
                items.add(truncate(String.valueOf(item)));
            }
            return "{" + String.join(", ", items) + "}";
        }
        return "{" + renderValue(value) + "}";
    }

    private String renderValue(Object value) {
        if (value == null) {
            return "null";
        }
        return truncate(String.valueOf(value));
    }

    private String truncate(String s) {
        return truncate(s, VALUE_TRUNCATE_LIMIT);
    }

    private String truncate(String s, int limit) {
        if (s == null) return "";
        if (s.length() <= limit) return s;
        return s.substring(0, limit) + "...";
    }

    /**
     * Build a leaf-node label using the first output: {@code "<output-chinese>: <value>"}.
     * Falls back to the result value alone if it's already a long human-readable string
     * (e.g. an errorMessage like "1.1 被保人ID..."), since prefixing it adds noise.
     */
    private String leafLabel(List<Result> results, Map<String, FieldDef> fieldByName) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        Result first = results.get(0);
        Object value = first.getValue();
        if (value == null) {
            return chineseFieldLabel(first.getField(), fieldByName, null) + "(無對應)";
        }
        String valueStr = String.valueOf(value);
        // For long human-readable messages, just return the value (avoids "errorMessage: 1.1 ...").
        if (valueStr.length() > 20) {
            return truncate(valueStr, 40);
        }
        String fieldLabel = chineseFieldLabel(first.getField(), fieldByName, null);
        return fieldLabel + ": " + truncate(valueStr);
    }

    /**
     * Resolve a field name to a Chinese display label, in priority order:
     * <ol>
     *   <li>{@link GlossaryService#search(String, String)} first hit's {@code zh_TW}</li>
     *   <li>{@link FieldDef#getName()} from the envelope's input/output definitions</li>
     *   <li>The raw field name (with a warning recorded if {@code warnings != null})</li>
     * </ol>
     */
    private String chineseFieldLabel(String fieldName, Map<String, FieldDef> fieldByName,
                                     List<String> warnings) {
        if (fieldName == null) return "(空)";

        // 1. Smart resolver: direct glossary hit → whole-name shim → camelCase token-by-token.
        try {
            String resolved = labelResolver.resolveFieldChinese(fieldName);
            // labelResolver returns either a real Chinese label or "<name>（待補）" / the original name.
            // Treat anything ending with "（待補）" as a miss for warning purposes.
            if (resolved != null && !resolved.equals(fieldName) && !resolved.endsWith("（待補）")) {
                return resolved;
            }
            if (resolved != null && resolved.endsWith("（待補）") && warnings != null) {
                warnings.add("field '" + fieldName + "' has no glossary / shim match — keeping English with (待補) suffix");
            }
        } catch (RuntimeException ex) {
            log.debug("label resolution failed for field={}: {}", fieldName, ex.getMessage());
        }

        // 2. FieldDef fallback (rare — most fieldDef.name == fieldName so this stays English).
        FieldDef def = fieldByName == null ? null : fieldByName.get(fieldName);
        if (def != null && def.getName() != null && !def.getName().equals(fieldName)) {
            return def.getName();
        }

        // 3. Raw field name — record a warning.
        if (warnings != null) {
            warnings.add("no glossary entry for field '" + fieldName + "' — using raw name");
        }
        return fieldName;
    }

    private Map<String, FieldDef> indexFields(List<FieldDef> inputs, List<FieldDef> outputs) {
        Map<String, FieldDef> map = new LinkedHashMap<>();
        if (inputs != null) {
            for (FieldDef f : inputs) {
                if (f.getName() != null) map.put(f.getName(), f);
            }
        }
        if (outputs != null) {
            for (FieldDef f : outputs) {
                if (f.getName() != null) map.put(f.getName(), f);
            }
        }
        return map;
    }

    private String toExpressionType(String operator) {
        if (operator == null) return null;
        return switch (operator) {
            case "equals" -> "EQUALS";
            case "notEquals" -> "NOT_EQUALS";
            case "greaterThan" -> "GREATER_THAN";
            case "greaterThanOrEqual" -> "GREATER_THAN_OR_EQUAL";
            case "lessThan" -> "LESS_THAN";
            case "lessThanOrEqual" -> "LESS_THAN_OR_EQUAL";
            case "between" -> "BETWEEN";
            case "in" -> "IN";
            case "notIn" -> "NOT_IN";
            case "isNull" -> "IS_NULL";
            case "isNotNull" -> "IS_NOT_NULL";
            case "anything" -> "ANYTHING";
            default -> operator.toUpperCase();
        };
    }

    private String buildTreeName(RuleEnvelope envelope) {
        String reason = envelope.getReason();
        if (reason == null || reason.isBlank()) {
            return envelope.getRuleType() == null ? "規則樹" : envelope.getRuleType();
        }
        return reason.length() <= 30 ? reason : reason.substring(0, 30);
    }
}
