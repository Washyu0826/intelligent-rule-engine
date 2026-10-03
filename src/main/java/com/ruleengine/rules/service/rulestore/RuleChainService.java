package com.ruleengine.rules.service.rulestore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.persistence.rulestore.RuleChainEntity;
import com.ruleengine.rules.persistence.rulestore.RuleChainRepository;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import com.ruleengine.rules.service.engine.ExecutionResult;
import com.ruleengine.rules.service.engine.RuleEngineRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 線性規則串接（Q30）：檢核清單 → 核保表 → 費率表。
 * 每一步取該規則的 ACTIVE 版本；前一步的輸出併入下一步的輸入；
 * 設 stopOnHit 的步驟（通常是檢核清單）一命中就中斷，回報錯誤訊息。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleChainService {

    private final RuleChainRepository chains;
    private final RuleStoreService store;
    private final RuleEngineRunner engine;
    private final ObjectMapper objectMapper;

    public record Step(String ruleKey, boolean stopOnHit, String label) {}

    public record ChainView(String chainKey, String name, List<Step> steps, String updatedBy, OffsetDateTime updatedAt) {}

    public record StepOutcome(String ruleKey, String label, Integer versionNo, boolean matched,
                              Map<String, Object> outputs, List<String> hitRuleIds, boolean stopped, String note) {}

    public record ChainOutcome(String chainKey, List<StepOutcome> steps, Map<String, Object> finalOutputs,
                               boolean stopped, String stoppedAt, long nanos) {}

    @Transactional(readOnly = true)
    public List<ChainView> list() {
        return chains.findAll().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ChainView get(String chainKey) {
        return view(load(chainKey));
    }

    @Transactional
    public ChainView save(String chainKey, String name, List<Step> steps, String user) {
        if (chainKey == null || chainKey.isBlank()) throw new RuleStoreService.RuleStoreException("流程代號不可為空");
        if (steps == null || steps.isEmpty()) throw new RuleStoreService.RuleStoreException("流程至少要有一個步驟");
        for (Step s : steps) {
            if (s.ruleKey() == null || s.ruleKey().isBlank()) {
                throw new RuleStoreService.RuleStoreException("步驟缺少規則代號");
            }
        }
        RuleChainEntity e = chains.findById(chainKey).orElseGet(() -> RuleChainEntity.builder().chainKey(chainKey).build());
        e.setName(name == null || name.isBlank() ? chainKey : name.strip());
        e.setSteps(writeSteps(steps));
        e.setUpdatedBy(user);
        e.setUpdatedAt(OffsetDateTime.now());
        return view(chains.saveAndFlush(e));
    }

    @Transactional
    public void delete(String chainKey) {
        chains.deleteById(chainKey);
    }

    @Transactional(readOnly = true)
    public ChainOutcome execute(String chainKey, Map<String, Object> input) {
        RuleChainEntity chain = load(chainKey);
        List<Step> steps = readSteps(chain.getSteps());
        Map<String, Object> working = new LinkedHashMap<>(input == null ? Map.of() : input);
        List<StepOutcome> outcomes = new ArrayList<>();
        long t0 = System.nanoTime();
        boolean stopped = false;
        String stoppedAt = null;

        for (Step step : steps) {
            Optional<RuleVersionEntity> active = store.findActive(step.ruleKey());
            if (active.isEmpty()) {
                outcomes.add(new StepOutcome(step.ruleKey(), step.label(), null, false, Map.of(), List.of(), true,
                        "規則沒有生效版本，流程中斷"));
                stopped = true;
                stoppedAt = step.ruleKey();
                break;
            }
            RuleVersionEntity v = active.get();
            RuleEnvelope envelope = store.deserialize(v);
            ExecutionResult r = engine.evaluate(envelope, working);
            Map<String, Object> outputs = r.getResults() == null ? Map.of() : new LinkedHashMap<>(r.getResults());
            List<String> hits = new ArrayList<>();
            if (r.getAllMatches() != null && !r.getAllMatches().isEmpty()) {
                r.getAllMatches().forEach(m -> hits.add(m.getRuleId()));
            } else if (r.getHitRuleId() != null) {
                hits.add(r.getHitRuleId());
            }
            boolean checklist = WorkbenchService.isChecklist(envelope);
            boolean stopHere = step.stopOnHit() && r.isMatched();
            String note = null;
            if (stopHere) {
                note = checklist ? "檢核未通過，流程中斷" : "此步驟命中即中斷";
            } else if (r.isMatched()) {
                working.putAll(outputs);
            } else if (!checklist) {
                note = "未命中任何規則，輸出為空";
            } else {
                note = "檢核全部通過";
            }
            outcomes.add(new StepOutcome(step.ruleKey(), step.label(), v.getVersionNo(), r.isMatched(),
                    outputs, hits, stopHere, note));
            if (stopHere) {
                stopped = true;
                stoppedAt = step.ruleKey();
                break;
            }
        }
        Map<String, Object> finalOutputs = new LinkedHashMap<>(working);
        if (input != null) input.keySet().forEach(finalOutputs::remove);
        return new ChainOutcome(chainKey, outcomes, finalOutputs, stopped, stoppedAt, System.nanoTime() - t0);
    }

    // ────────────────────────────────────────────

    private RuleChainEntity load(String chainKey) {
        return chains.findById(chainKey).orElseThrow(() ->
                new ReviewWorkflowService.VersionNotFoundException("找不到流程 " + chainKey));
    }

    private ChainView view(RuleChainEntity e) {
        return new ChainView(e.getChainKey(), e.getName(), readSteps(e.getSteps()), e.getUpdatedBy(), e.getUpdatedAt());
    }

    private String writeSteps(List<Step> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception ex) {
            throw new RuleStoreService.RuleStoreException("流程步驟序列化失敗", ex);
        }
    }

    private List<Step> readSteps(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Step>>() {});
        } catch (Exception ex) {
            throw new RuleStoreService.RuleStoreException("流程步驟解析失敗", ex);
        }
    }
}
