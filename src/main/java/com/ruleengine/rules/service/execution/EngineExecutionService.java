package com.ruleengine.rules.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.persistence.execution.DecisionTraceEntity;
import com.ruleengine.rules.persistence.execution.DecisionTraceRepository;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import com.ruleengine.rules.service.rulestore.RuleStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 引擎執行服務（P2-S3）：規則庫取版 → 執行 → 落 trace → 回放。
 *
 * <p>
 * <b>落庫策略：</b>每次 {@link #executeActive} 一律留痕（金融場景執行即證據），
 * {@code traceLevel} 只控制明細豐富度 —— 回放三要素（輸入快照 / 規則版本 id /
 * 引擎版本）無條件必存（V3 schema 的 NOT NULL 兜底）。
 * </p>
 *
 * <p>
 * <b>回放語意：</b>取歷史 trace 的輸入快照 + <b>當時那個版本</b>的 envelope
 * （by id —— 即使該版已 RETIRED 也可回放，append-only 版本鏈的直接回報），
 * 以 FULL 級重新執行後比對 outputs 與 matchedRuleIds。
 * 引擎升版後回放可能不一致 —— 那正是要如實回報的資訊，不是要掩蓋的錯誤。
 * </p>
 *
 * <p>
 * <b>已知限制（誠實聲明）：</b>含 {@code $today} 相對日期 valueRef 的規則
 * 天生非決定性 —— 回放時「今天」已不同。偵測到時在結果標
 * {@code nonDeterministicWarning}，差異不視為引擎錯誤。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EngineExecutionService {

    private final RuleStoreService ruleStore;
    private final RuleExecutionEngine engine;
    private final DecisionTraceRepository traceRepository;
    private final ObjectMapper objectMapper;

    public static class EngineServiceException extends RuntimeException {
        public EngineServiceException(String message) { super(message); }
        public EngineServiceException(String message, Throwable cause) { super(message, cause); }
    }

    // ================================================================
    // 執行（取 ACTIVE 版 + 落庫）
    // ================================================================

    public record ExecutionOutcome(Long traceId, Long ruleVersionId, int versionNo,
                                   ExecutionResult result) {}

    @Transactional
    public ExecutionOutcome executeActive(String ruleKey, Map<String, Object> input,
                                          TraceLevel level, String executedBy) {
        RuleVersionEntity version = ruleStore.findActive(ruleKey)
                .orElseThrow(() -> new EngineServiceException(
                        "規則 \"" + ruleKey + "\" 沒有生效（ACTIVE）版本 —— 請先完成審核流程"));
        return executeVersion(version, input, level, executedBy);
    }

    /** 指定版本執行（回放與測試用；一般執行走 {@link #executeActive}）。 */
    @Transactional
    public ExecutionOutcome executeVersion(RuleVersionEntity version, Map<String, Object> input,
                                           TraceLevel level, String executedBy) {
        RuleEnvelope envelope = ruleStore.deserialize(version);
        TraceLevel resolved = level != null ? level : TraceLevel.SUMMARY;
        // trace 落庫需要輸入快照 —— 引擎內部已快照，這裡為落庫再持有一份不可變視圖
        Map<String, Object> snapshot = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();

        ExecutionResult result = engine.execute(envelope, snapshot, resolved);

        DecisionTraceEntity entity = DecisionTraceEntity.builder()
                .ruleVersionId(version.getId())
                .inputSnapshot(writeJson(snapshot))
                .engineVersion(RuleExecutionEngine.ENGINE_VERSION)
                .ruleKey(version.getRuleKey())
                .matched(result.isMatched())
                .outputs(writeJson(result.getOutputs()))
                .matchedRuleIds(writeJson(result.getMatchedRules().stream()
                        .map(ExecutionResult.MatchedRule::getRuleId).toList()))
                .traceLevel(resolved.name())
                .traceDetail(result.getTrace() != null ? writeJson(result.getTrace()) : null)
                .executedBy(executedBy != null && !executedBy.isBlank() ? executedBy : "anonymous")
                .executedAt(OffsetDateTime.now())
                .durationNanos(result.getTrace() != null ? result.getTrace().getTotalNanos() : 0)
                .build();
        DecisionTraceEntity saved = traceRepository.save(entity);

        log.info("ENGINE exec | key={} v{} | matched={} | rules={} | traceId={} | by={}",
                version.getRuleKey(), version.getVersionNo(), result.isMatched(),
                result.getMatchedRules().size(), saved.getId(), entity.getExecutedBy());
        return new ExecutionOutcome(saved.getId(), version.getId(), version.getVersionNo(), result);
    }

    // ================================================================
    // 回放
    // ================================================================

    public record ReplayReport(
            Long traceId,
            Long ruleVersionId,
            boolean consistent,
            /** 原紀錄與重放的 outputs / matchedRuleIds 差異描述；一致時為空。 */
            List<String> differences,
            /** 規則含 $today 相對日期 —— 天生非決定性，差異不視為引擎錯誤。 */
            boolean nonDeterministicWarning,
            String originalEngineVersion,
            String currentEngineVersion,
            Map<String, Object> originalOutputs,
            Map<String, Object> replayedOutputs,
            ExecutionResult replayed) {}

    @Transactional(readOnly = true)
    public ReplayReport replay(Long traceId) {
        DecisionTraceEntity trace = traceRepository.findById(traceId)
                .orElseThrow(() -> new EngineServiceException("trace " + traceId + " 不存在"));
        RuleVersionEntity version = ruleStore.findById(trace.getRuleVersionId())
                .orElseThrow(() -> new EngineServiceException(
                        "trace 指向的規則版本 " + trace.getRuleVersionId() + " 不存在（資料完整性受損）"));

        Map<String, Object> input = readJson(trace.getInputSnapshot(),
                new TypeReference<Map<String, Object>>() {});
        List<String> originalRuleIds = readJson(trace.getMatchedRuleIds(),
                new TypeReference<List<String>>() {});
        Map<String, Object> originalOutputs = readJson(trace.getOutputs(),
                new TypeReference<Map<String, Object>>() {});

        RuleEnvelope envelope = ruleStore.deserialize(version);
        // 回放一律 FULL —— 回放的目的就是看清楚每一步
        ExecutionResult replayed = engine.execute(envelope, input, TraceLevel.FULL);

        List<String> replayedRuleIds = replayed.getMatchedRules().stream()
                .map(ExecutionResult.MatchedRule::getRuleId).toList();

        List<String> differences = new java.util.ArrayList<>();
        if (!Objects.equals(originalRuleIds, replayedRuleIds)) {
            differences.add("命中規則不同：原=" + originalRuleIds + " 重放=" + replayedRuleIds);
        }
        if (!outputsEquivalent(originalOutputs, replayed.getOutputs())) {
            differences.add("輸出不同：原=" + originalOutputs + " 重放=" + replayed.getOutputs());
        }
        if (!RuleExecutionEngine.ENGINE_VERSION.equals(trace.getEngineVersion())) {
            differences.add("引擎版本不同：原=" + trace.getEngineVersion()
                    + " 現=" + RuleExecutionEngine.ENGINE_VERSION + "（語意可能已變更）");
        }

        boolean nonDeterministic = version.getEnvelope().contains("$today");
        boolean consistent = differences.isEmpty();
        log.info("ENGINE replay | traceId={} | consistent={} | diffs={} | nonDeterministic={}",
                traceId, consistent, differences.size(), nonDeterministic);

        return new ReplayReport(traceId, version.getId(), consistent, List.copyOf(differences),
                nonDeterministic, trace.getEngineVersion(), RuleExecutionEngine.ENGINE_VERSION,
                originalOutputs, replayed.getOutputs(), replayed);
    }

    /**
     * JSON 往返後的數值型別會漂移（int 750 → 可能讀回 Integer/Long/Double）——
     * 輸出比對用「數值語意相等」而非嚴格型別相等。
     */
    private boolean outputsEquivalent(Map<String, Object> a, Map<String, Object> b) {
        if (a.size() != b.size()) return false;
        for (var entry : a.entrySet()) {
            Object x = entry.getValue();
            Object y = b.get(entry.getKey());
            if (x instanceof Number nx && y instanceof Number ny) {
                if (Double.compare(nx.doubleValue(), ny.doubleValue()) != 0) return false;
            } else if (!Objects.equals(x, y)) {
                return false;
            }
        }
        return true;
    }

    // ================================================================
    // 查詢
    // ================================================================

    @Transactional(readOnly = true)
    public List<DecisionTraceEntity> recentTraces(String ruleKey, int limit) {
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        return ruleKey != null && !ruleKey.isBlank()
                ? traceRepository.findRecentByRuleKey(ruleKey, page)
                : traceRepository.findRecent(page);
    }

    @Transactional(readOnly = true)
    public DecisionTraceEntity getTrace(Long id) {
        return traceRepository.findById(id)
                .orElseThrow(() -> new EngineServiceException("trace " + id + " 不存在"));
    }

    // ── JSON 邊界 ──

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EngineServiceException("trace 序列化失敗", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new EngineServiceException("trace 反序列化失敗（資料損毀？）", e);
        }
    }
}
