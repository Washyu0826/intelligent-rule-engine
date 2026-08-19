package com.ruleengine.rules.service.execution;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Branch;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.TreeNode;
import com.ruleengine.rules.service.RuleLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 規則執行引擎（P2-S2）—— 本升級的核心元件。
 *
 * <p>
 * <b>架構定位（文獻調查結論的落地）：</b>解譯執行，非 Rete/PHREAK ——
 * 信用審核是「一筆進件、評估一次、回結果」的 stateless 場景，
 * Rete 家族的增量匹配網路（working memory、部分匹配快取）在這裡是負資產。
 * Camunda DMN 與 GoRules ZEN 在同場景同樣選擇解譯。
 * 表規模上限 200 列（rules.validation.max-table-rows），逐列評估即毫秒級。
 * </p>
 *
 * <p>
 * <b>條件語意單一來源：</b>單一條件的比對<b>委派</b>給
 * {@link RuleLookupService#matchesCondition}（12 operator、valueRef、型別 coercion、
 * 「缺欄位 ≠ 顯式 null」語意）—— 該方法已被三個測試類覆蓋。
 * 引擎不重寫比對邏輯：兩套語意各自演化是此類系統最危險的分歧。
 * 引擎的增量價值在：hit policy 編排、樹走訪、以及逐步 {@link DecisionTrace}。
 * </p>
 *
 * <p>
 * <b>trace 成本控制：</b>NONE 級零配置（不建集合）；SUMMARY 只記命中；
 * FULL 逐條件記錄「解析後的期望值」——回放時能看到當時 {@code $today} 是幾號。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleExecutionEngine {

    /**
     * 引擎語意版本（回放三要素③）。改變執行語意（operator 行為、hit policy、
     * 走訪順序）時必須 bump —— trace 裡的版本號是「這筆決策由哪套語意產生」的憑據。
     */
    public static final String ENGINE_VERSION = "exec-1.0.0";

    private final RuleLookupService lookupService;

    @Value("${rules.validation.max-tree-depth:10}")
    private int maxTreeDepth;

    public static class ExecutionException extends RuntimeException {
        public ExecutionException(String message) { super(message); }
    }

    // ================================================================
    // 入口
    // ================================================================

    public ExecutionResult execute(RuleEnvelope envelope, Map<String, Object> input, TraceLevel level) {
        if (envelope == null || envelope.getRule() == null) {
            throw new ExecutionException("envelope 或其 rule 區塊為空，無法執行");
        }
        TraceLevel traceLevel = level != null ? level : TraceLevel.NONE;
        // 輸入快照：立即淺複製 —— 呼叫端事後改 map 不可污染 trace（回放三要素②）
        Map<String, Object> snapshot = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();

        String ruleType = envelope.getRuleType() != null ? envelope.getRuleType() : "DecisionTable";
        long start = System.nanoTime();
        return switch (ruleType) {
            case "DecisionTable" -> executeTable(envelope, snapshot, traceLevel, start);
            case "DecisionTree" -> executeTree(envelope, snapshot, traceLevel, start);
            default -> throw new ExecutionException(
                    "ruleType \"" + ruleType + "\" 尚不支援執行（ScoreCard 為 stub 型態）");
        };
    }

    // ================================================================
    // DecisionTable：FIRST / MULTI
    // ================================================================

    private ExecutionResult executeTable(RuleEnvelope envelope, Map<String, Object> input,
                                         TraceLevel level, long start) {
        List<RuleRow> rows = envelope.getRule().getRules();
        if (rows == null || rows.isEmpty()) {
            throw new ExecutionException("DecisionTable 沒有任何規則列");
        }
        boolean multi = "MULTI".equalsIgnoreCase(envelope.getRule().getHitPolicy());

        // 防禦性排序：normalizer 已 sortByPriority，但引擎不假設上游（直接執行外部 envelope 時）
        List<RuleRow> ordered = rows.stream()
                .sorted(Comparator.comparing(r -> r.getPriority() != null ? r.getPriority() : Integer.MAX_VALUE))
                .toList();

        List<DecisionTrace.StepTrace> steps = level != TraceLevel.NONE ? new ArrayList<>() : null;
        List<ExecutionResult.MatchedRule> matched = new ArrayList<>();

        for (RuleRow row : ordered) {
            long rowStart = System.nanoTime();
            List<DecisionTrace.ConditionTrace> condTraces = level == TraceLevel.FULL ? new ArrayList<>() : null;
            boolean rowMatched = evaluateRow(row.getConditions(), input, condTraces);

            if (steps != null && (rowMatched || level == TraceLevel.FULL)) {
                steps.add(DecisionTrace.StepTrace.builder()
                        .id(row.getRuleId())
                        .order(row.getPriority())
                        .matched(rowMatched)
                        .conditions(condTraces)
                        .elapsedNanos(System.nanoTime() - rowStart)
                        .build());
            }
            if (rowMatched) {
                matched.add(toMatchedRule(row));
                if (!multi) break;   // FIRST：首中即停
            }
        }

        return buildResult(envelope, input, level, start, steps, matched, null);
    }

    /** 逐條件評估一列；condTraces 非 null（FULL 級）時記錄每個條件的比對明細。 */
    private boolean evaluateRow(List<Condition> conditions, Map<String, Object> input,
                                List<DecisionTrace.ConditionTrace> condTraces) {
        if (conditions == null || conditions.isEmpty()) return true;   // 無條件 = 恆真（與 lookup 語意一致）
        boolean all = true;
        for (Condition cond : conditions) {
            Object actual = cond.getField() != null ? input.get(cond.getField()) : null;
            boolean ok = lookupService.matchesCondition(cond, actual, input);
            if (condTraces != null) {
                condTraces.add(DecisionTrace.ConditionTrace.builder()
                        .field(cond.getField())
                        .operator(cond.getOperator())
                        .expected(resolvedExpected(cond, input))
                        .actual(actual)
                        .matched(ok)
                        .build());
            }
            if (!ok) {
                all = false;
                if (condTraces == null) return false;   // 非 FULL：短路
                // FULL：不短路 —— 記錄整列所有條件的比對結果，調查時才看得到全貌
            }
        }
        return all;
    }

    // ================================================================
    // DecisionTree：root → leaf 走訪
    // ================================================================

    private ExecutionResult executeTree(RuleEnvelope envelope, Map<String, Object> input,
                                        TraceLevel level, long start) {
        TreeNode root = envelope.getRule().getRoot();
        if (root == null) {
            throw new ExecutionException("DecisionTree 沒有 root 節點");
        }

        List<DecisionTrace.StepTrace> steps = level != TraceLevel.NONE ? new ArrayList<>() : null;
        List<ExecutionResult.MatchedRule> matched = new ArrayList<>();
        String noPathNode = null;

        TreeNode node = root;
        int depth = 0;
        while (node != null) {
            if (depth > maxTreeDepth) {
                throw new ExecutionException("樹走訪深度超過上限 " + maxTreeDepth + "（疑似循環或未正規化的樹）");
            }
            long nodeStart = System.nanoTime();

            // 葉節點：results 即輸出
            if (node.getResults() != null && !node.getResults().isEmpty()) {
                matched.add(ExecutionResult.MatchedRule.builder()
                        .ruleId(node.getNodeId())
                        .priority(depth)
                        .outputs(toOutputMap(node.getResults()))
                        .build());
                if (steps != null) {
                    steps.add(DecisionTrace.StepTrace.builder()
                            .id(node.getNodeId()).order(depth).matched(true)
                            .elapsedNanos(System.nanoTime() - nodeStart).build());
                }
                break;
            }

            // 分支節點：找第一個條件成立的 branch（引擎只吃正規化後的 n-ary 結構；
            // 舊 trueBranch/falseBranch 由 TreeNormalizer 先轉換，見 domain javadoc）
            List<Branch> branches = node.getBranches();
            if (branches == null || branches.isEmpty()) {
                throw new ExecutionException("節點 " + node.getNodeId()
                        + " 既無 results 也無 branches（未正規化？先經 TreeNormalizer）");
            }
            Branch taken = null;
            List<DecisionTrace.ConditionTrace> condTraces = level == TraceLevel.FULL ? new ArrayList<>() : null;
            for (Branch branch : branches) {
                Condition cond = branch.getCondition();
                Object actual = cond != null && cond.getField() != null ? input.get(cond.getField()) : null;
                boolean ok = cond == null || lookupService.matchesCondition(cond, actual, input);
                if (condTraces != null && cond != null) {
                    condTraces.add(DecisionTrace.ConditionTrace.builder()
                            .field(cond.getField()).operator(cond.getOperator())
                            .expected(resolvedExpected(cond, input)).actual(actual).matched(ok)
                            .build());
                }
                if (ok) { taken = branch; break; }
            }

            if (steps != null) {
                steps.add(DecisionTrace.StepTrace.builder()
                        .id(node.getNodeId()).order(depth)
                        .matched(taken != null)
                        .branchTaken(taken != null ? taken.getLabel() : null)
                        .conditions(condTraces)
                        .elapsedNanos(System.nanoTime() - nodeStart)
                        .build());
            }
            if (taken == null) {
                // 無分支成立：決策缺口（gap）—— 不擲例外，回「未命中」讓呼叫端決定 fallback；
                // gap 的存在本身就是 DmnAnalyzer 要抓的品質問題，執行期如實回報
                noPathNode = node.getNodeId();
                log.warn("樹走訪在節點 {} 無可行分支（決策缺口）| input keys={}", noPathNode, input.keySet());
                break;
            }
            node = taken.getChild();
            depth++;
        }

        return buildResult(envelope, input, level, start, steps, matched, noPathNode);
    }

    // ================================================================
    // 共用組裝
    // ================================================================

    private ExecutionResult buildResult(RuleEnvelope envelope, Map<String, Object> input,
                                        TraceLevel level, long start,
                                        List<DecisionTrace.StepTrace> steps,
                                        List<ExecutionResult.MatchedRule> matched,
                                        String noPathNode) {
        DecisionTrace trace = null;
        if (level != TraceLevel.NONE) {
            trace = DecisionTrace.builder()
                    .engineVersion(ENGINE_VERSION)
                    .ruleType(envelope.getRuleType())
                    .hitPolicy(envelope.getRule().getHitPolicy())
                    .inputSnapshot(level == TraceLevel.FULL ? Map.copyOf(input) : null)
                    .steps(steps != null ? List.copyOf(steps) : List.of())
                    .matchedRuleIds(matched.stream().map(ExecutionResult.MatchedRule::getRuleId).toList())
                    .totalNanos(System.nanoTime() - start)
                    .level(level)
                    .build();
        }
        if (noPathNode != null && log.isDebugEnabled()) {
            log.debug("execution ended with gap at node {}", noPathNode);
        }
        return ExecutionResult.builder()
                .matched(!matched.isEmpty())
                .outputs(matched.isEmpty() ? Map.of() : matched.get(0).getOutputs())
                .matchedRules(List.copyOf(matched))
                .trace(trace)
                .build();
    }

    private ExecutionResult.MatchedRule toMatchedRule(RuleRow row) {
        return ExecutionResult.MatchedRule.builder()
                .ruleId(row.getRuleId())
                .priority(row.getPriority())
                .outputs(toOutputMap(row.getResults()))
                .build();
    }

    private Map<String, Object> toOutputMap(List<Result> results) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (results != null) {
            for (Result r : results) map.put(r.getField(), r.getValue());
        }
        return map;
    }

    /** trace 用：解析後的期望值（valueRef 展開 —— $today 在回放紀錄裡是具體日期）。 */
    private Object resolvedExpected(Condition cond, Map<String, Object> input) {
        String ref = cond.getValueRef();
        if (ref == null || ref.isBlank()) return cond.getValue();
        return lookupService.resolveValueRef(ref, input);
    }
}
