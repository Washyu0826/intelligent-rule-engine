package com.ruleengine.rules.service.rulestore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruleengine.rules.domain.dto.ToolDtos;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.persistence.rulestore.RuleDirectoryEntity;
import com.ruleengine.rules.persistence.rulestore.RuleDirectoryRepository;
import com.ruleengine.rules.persistence.rulestore.RuleStatus;
import com.ruleengine.rules.persistence.rulestore.RuleTagEntity;
import com.ruleengine.rules.persistence.rulestore.RuleTagRepository;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import com.ruleengine.rules.persistence.rulestore.RuleVersionRepository;
import com.ruleengine.rules.service.RuleService;
import com.ruleengine.rules.service.analyzer.FairnessService;
import com.ruleengine.rules.service.analyzer.GapCaseBuilder;
import com.ruleengine.rules.service.analyzer.RegressionService;
import com.ruleengine.rules.service.bounds.BoundsService;
import com.ruleengine.rules.service.diff.RuleDiffService;
import com.ruleengine.rules.service.diff.TreeDiffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 審核工作台的服務層：目錄與標籤、送審（理由＋影響報告快照）、審核單、AI 改規則建議。
 *
 * <p>
 * 影響報告在送審當下計算並落庫：主管審核的是「送審時」的差異與分析，
 * 之後生效中的版本即使改變，這份審核紀錄也不會事後漂移。
 * 比對基準 = 同 key 生效中的版本；沒有生效版時退回前一版；兩者皆無則視為首版。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkbenchService {

    private static final int MAX_LISTED = 10;
    private static final int MAX_PATH_LENGTH = 400;

    private final RuleVersionRepository versions;
    private final RuleDirectoryRepository directories;
    private final RuleTagRepository tags;
    private final RuleStoreService store;
    private final ReviewWorkflowService workflow;
    private final RuleService ruleService;
    private final RuleDiffService ruleDiffService;
    private final TreeDiffService treeDiffService;
    private final GapCaseBuilder gapCaseBuilder;
    private final BoundsService boundsService;
    private final RegressionService regressionService;
    private final FairnessService fairnessService;
    private final ObjectMapper objectMapper;

    @Value("${rules.workbench.tag-dimensions:分類,主題,法規}")
    private List<String> tagDimensions;

    // ================================================================
    // 目錄與標籤
    // ================================================================

    public record TreeEntry(String ruleKey, String path, Map<String, List<String>> tags,
                            Integer activeVersionNo, String latestStatus, int latestVersionNo) {}

    public record TreeView(List<String> tagDimensions, List<TreeEntry> rules) {}

    /** 全部規則（未分類者 path 為空字串），可依標籤篩選：每個指定維度的標籤都要命中。 */
    @Transactional(readOnly = true)
    public TreeView tree(Map<String, String> tagFilter) {
        Map<String, String> pathByKey = directories.findAll().stream()
                .collect(Collectors.toMap(RuleDirectoryEntity::getRuleKey, RuleDirectoryEntity::getPath));
        Map<String, Map<String, List<String>>> tagsByKey = new LinkedHashMap<>();
        for (RuleTagEntity t : tags.findAll()) {
            tagsByKey.computeIfAbsent(t.getRuleKey(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(t.getDimension(), k -> new ArrayList<>()).add(t.getTag());
        }
        List<TreeEntry> entries = new ArrayList<>();
        for (String key : store.allRuleKeys()) {
            Map<String, List<String>> ruleTags = tagsByKey.getOrDefault(key, Map.of());
            if (tagFilter != null && !matches(ruleTags, tagFilter)) {
                continue;
            }
            List<RuleVersionEntity> history = store.history(key);
            RuleVersionEntity latest = history.get(0);
            Integer activeNo = history.stream().filter(v -> v.getStatus() == RuleStatus.ACTIVE)
                    .map(RuleVersionEntity::getVersionNo).findFirst().orElse(null);
            entries.add(new TreeEntry(key, pathByKey.getOrDefault(key, ""), ruleTags,
                    activeNo, latest.getStatus().name(), latest.getVersionNo()));
        }
        entries.sort(Comparator.comparing(TreeEntry::path).thenComparing(TreeEntry::ruleKey));
        return new TreeView(List.copyOf(tagDimensions), entries);
    }

    private static boolean matches(Map<String, List<String>> ruleTags, Map<String, String> filter) {
        return filter.entrySet().stream().allMatch(f ->
                ruleTags.getOrDefault(f.getKey(), List.of()).contains(f.getValue()));
    }

    /** 設定規則的目錄位置與標籤（整組取代）。path 以 / 分層，不可有空層。 */
    @Transactional
    public TreeEntry place(String ruleKey, String path, Map<String, List<String>> newTags, String user) {
        if (store.history(ruleKey).isEmpty()) {
            throw new ReviewWorkflowService.VersionNotFoundException("找不到規則：" + ruleKey);
        }
        String normalized = normalizePath(path);
        Map<String, List<String>> cleaned = new LinkedHashMap<>();
        if (newTags != null) {
            for (var e : newTags.entrySet()) {
                if (!tagDimensions.contains(e.getKey())) {
                    throw new RuleStoreService.RuleStoreException(
                            "未知的標籤維度「" + e.getKey() + "」，可用：" + tagDimensions);
                }
                Set<String> values = e.getValue().stream().map(String::strip)
                        .filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
                if (!values.isEmpty()) {
                    cleaned.put(e.getKey(), List.copyOf(values));
                }
            }
        }

        directories.save(RuleDirectoryEntity.builder().ruleKey(ruleKey).path(normalized)
                .updatedBy(user).updatedAt(OffsetDateTime.now()).build());
        tags.deleteByRuleKey(ruleKey);
        tags.flush();
        cleaned.forEach((dim, values) -> values.forEach(tag -> tags.save(
                RuleTagEntity.builder().ruleKey(ruleKey).dimension(dim).tag(tag).build())));
        log.info("WORKBENCH 放置規則 | key={} | path={} | tags={} | by={}", ruleKey, normalized, cleaned, user);

        return tree(null).rules().stream().filter(r -> r.ruleKey().equals(ruleKey)).findFirst().orElseThrow();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String[] parts = path.strip().split("/", -1);
        List<String> clean = new ArrayList<>();
        for (String part : parts) {
            String p = part.strip();
            if (p.isEmpty()) {
                throw new RuleStoreService.RuleStoreException("目錄路徑不可有空的層級：" + path);
            }
            clean.add(p);
        }
        String joined = String.join("/", clean);
        if (joined.length() > MAX_PATH_LENGTH) {
            throw new RuleStoreService.RuleStoreException("目錄路徑過長（上限 " + MAX_PATH_LENGTH + " 字元）");
        }
        return joined;
    }

    // ================================================================
    // 送審：理由必填，影響報告在此刻快照
    // ================================================================

    @Transactional
    public RuleVersionEntity submit(Long versionId, String maker, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new RuleStoreService.RuleStoreException("送審必須填寫修改理由");
        }
        RuleVersionEntity draft = load(versionId);
        BoundsService.BoundsReport bounds = boundsService.check(store.deserialize(draft));
        if (!bounds.violations().isEmpty()) {
            throw new BoundsViolationException(bounds);
        }
        RuleVersionEntity v = workflow.submit(versionId, maker);
        v.setSubmitReason(reason.strip());
        v.setImpactReport(impactReportJson(v));
        return versions.saveAndFlush(v);
    }

    /** 越過精算邊界：送審直接擋下，前端提示升級給精算（Q9／Q32）。 */
    public static class BoundsViolationException extends RuntimeException {
        private final transient BoundsService.BoundsReport report;

        public BoundsViolationException(BoundsService.BoundsReport report) {
            super("越過精算邊界 " + report.violations().size() + " 項，無法送審；請聯絡 "
                    + (report.escalateTo() == null ? "精算" : report.escalateTo()));
            this.report = report;
        }

        public BoundsService.BoundsReport getReport() { return report; }
    }

    // ================================================================
    // 審核單
    // ================================================================

    public record ReviewSheet(Long id, String ruleKey, int versionNo, String ruleType, String status,
                              String submittedBy, OffsetDateTime submittedAt, String submitReason,
                              Integer baseVersionNo, JsonNode before, JsonNode after, JsonNode impact) {}

    @Transactional(readOnly = true)
    public ReviewSheet reviewSheet(Long id) {
        RuleVersionEntity v = load(id);
        Optional<RuleVersionEntity> base = baseOf(v);
        JsonNode impact = v.getImpactReport() != null ? readJson(v.getImpactReport()) : null;
        if (impact == null) {
            impact = buildImpact(base.map(store::deserialize).orElse(null), store.deserialize(v));
        }
        return new ReviewSheet(v.getId(), v.getRuleKey(), v.getVersionNo(), v.getRuleType(),
                v.getStatus().name(), v.getSubmittedBy(), v.getSubmittedAt(), v.getSubmitReason(),
                base.map(RuleVersionEntity::getVersionNo).orElse(null),
                base.map(b -> readJson(b.getEnvelope())).orElse(null),
                readJson(v.getEnvelope()), impact);
    }

    // ================================================================
    // 用中文描述想改什麼 → AI 提出修改建議（只回傳，不落庫）
    // ================================================================

    public record ChangeSuggestion(JsonNode proposed, JsonNode validation, JsonNode impact) {}

    @Transactional(readOnly = true)
    public ChangeSuggestion suggestChange(Long versionId, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new RuleStoreService.RuleStoreException("請描述想修改什麼");
        }
        RuleVersionEntity v = load(versionId);
        RuleEnvelope current = store.deserialize(v);
        String description = "以下是目前的規則（RuleEnvelope JSON）：\n" + v.getEnvelope()
                + "\n\n請依下列修改要求，輸出修改後的完整規則；未被要求改動的部分必須保持原樣：\n"
                + instruction.strip();

        ToolDtos.GenerateResponse generated = ruleService.generateFull(ToolDtos.GenerateRequest.builder()
                .description(description).ruleType(v.getRuleType()).build());
        RuleEnvelope proposed = generated.getEnvelope();
        return new ChangeSuggestion(objectMapper.valueToTree(proposed),
                objectMapper.valueToTree(generated.getValidation()),
                buildImpact(current, proposed));
    }

    /**
     * 把一個缺口補成一列待填規則（Q9：決議類填保守值、其餘留空），回傳與 AI 建議相同形狀，
     * 讓前端走同一條「前後對照 → 採用存成新草稿」的路。只支援決策表。
     */
    public ChangeSuggestion gapCase(Long versionId, Map<String, String> gapConditions) {
        if (gapConditions == null || gapConditions.isEmpty()) {
            throw new RuleStoreService.RuleStoreException("缺口沒有條件描述，無法補列");
        }
        RuleVersionEntity v = load(versionId);
        if (!"DecisionTable".equalsIgnoreCase(v.getRuleType())) {
            throw new RuleStoreService.RuleStoreException("目前只有決策表支援把缺口補成案例");
        }
        RuleEnvelope current = store.deserialize(v);
        RuleEnvelope proposed = store.deserialize(v);
        List<RuleEnvelope.RuleRow> rows = new ArrayList<>(
                proposed.getRule().getRules() == null ? List.of() : proposed.getRule().getRules());
        rows.add(gapCaseBuilder.build(proposed, gapConditions));
        proposed.getRule().setRules(rows);

        JsonNode proposedJson = objectMapper.valueToTree(proposed);
        var validation = ruleService.validate(ToolDtos.ValidateRequest.builder()
                .ruleJson(proposedJson).ruleType(v.getRuleType()).build());
        return new ChangeSuggestion(proposedJson, objectMapper.valueToTree(validation),
                buildImpact(current, proposed));
    }

    // ================================================================
    // 影響報告
    // ================================================================

    private String impactReportJson(RuleVersionEntity v) {
        RuleEnvelope before = baseOf(v).map(store::deserialize).orElse(null);
        try {
            return objectMapper.writeValueAsString(buildImpact(before, store.deserialize(v)));
        } catch (Exception e) {
            throw new RuleStoreService.RuleStoreException("影響報告序列化失敗：" + e.getMessage(), e);
        }
    }

    private Optional<RuleVersionEntity> baseOf(RuleVersionEntity v) {
        Optional<RuleVersionEntity> active = store.findActive(v.getRuleKey())
                .filter(a -> !a.getId().equals(v.getId()));
        if (active.isPresent()) {
            return active;
        }
        return v.getPreviousVersionId() == null ? Optional.empty() : store.findById(v.getPreviousVersionId());
    }

    /** before 為 null 表示首版（沒有可比對的舊版）。 */
    ObjectNode buildImpact(RuleEnvelope before, RuleEnvelope after) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("firstVersion", before == null);

        boolean sameType = before != null && after.getRuleType() != null
                && after.getRuleType().equalsIgnoreCase(before.getRuleType());
        if (before != null && !sameType) {
            report.put("note", "規則型態由 " + before.getRuleType() + " 改為 " + after.getRuleType()
                    + "，無法逐項比對差異");
        }
        if (sameType && "DecisionTree".equalsIgnoreCase(after.getRuleType())) {
            report.set("treeDiff", objectMapper.valueToTree(treeDiffService.diff(before, after)));
        } else if (sameType && "DecisionTable".equalsIgnoreCase(after.getRuleType())) {
            report.set("structuralDiff", objectMapper.valueToTree(ruleDiffService.structuralDiff(before, after)));
            report.set("behaviorDiff", objectMapper.valueToTree(ruleDiffService.diff(before, after)));
        }

        report.set("bounds", objectMapper.valueToTree(boundsService.check(after)));
        if (!isChecklist(after)) {
            try {
                report.set("fairness", objectMapper.valueToTree(fairnessService.analyze(after)));
            } catch (Exception e) {
                log.warn("公平待遇分析失敗，影響報告略過此段：{}", e.getMessage());
            }
        }
        if (sameType && !isChecklist(after)) {
            try {
                report.set("regression", objectMapper.valueToTree(regressionService.run(before, after)));
            } catch (Exception e) {
                log.warn("批次回歸失敗，影響報告略過此段：{}", e.getMessage());
            }
        }
        if (isChecklist(after)) {
            report.put("checklist", true);
            report.put("note", "檢核清單（多重命中）：每條檢核各自獨立，不做缺口與重疊分析");
            return report;
        }
        var analysis = ruleService.analyze(ToolDtos.AnalyzeRequest.builder()
                .ruleJson(objectMapper.valueToTree(after)).ruleType(after.getRuleType()).build());
        ObjectNode a = report.putObject("analysis");
        a.put("coverageRate", analysis.getCoverageRate());
        a.put("gapCount", analysis.getGaps() == null ? 0 : analysis.getGaps().size());
        a.put("overlapCount", analysis.getOverlaps() == null ? 0 : analysis.getOverlaps().size());
        ArrayNode gaps = a.putArray("gaps");
        if (analysis.getGaps() != null) {
            analysis.getGaps().stream().limit(MAX_LISTED).forEach(g -> {
                ObjectNode gap = gaps.addObject();
                gap.put("message", g.getMessage());
                if (g.getConditions() != null) gap.set("conditions", objectMapper.valueToTree(g.getConditions()));
            });
        }
        ArrayNode overlaps = a.putArray("overlaps");
        if (analysis.getOverlaps() != null) {
            analysis.getOverlaps().stream().limit(MAX_LISTED).forEach(o -> overlaps.add(o.getMessage()));
        }
        return report;
    }

    /** 多重命中的決策表 = 檢核清單：每條各自獨立，缺口／重疊分析不適用（Q11）。 */
    static boolean isChecklist(RuleEnvelope envelope) {
        return envelope != null && "DecisionTable".equalsIgnoreCase(envelope.getRuleType())
                && envelope.getRule() != null
                && "MULTI".equalsIgnoreCase(envelope.getRule().getHitPolicy());
    }

    // ================================================================

    private RuleVersionEntity load(Long id) {
        return versions.findById(id).orElseThrow(() ->
                new ReviewWorkflowService.VersionNotFoundException("找不到版本 id=" + id));
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuleStoreService.RuleStoreException("儲存的 JSON 無法解析：" + e.getMessage(), e);
        }
    }
}
