package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.ruleengine.rules.service.converter.TableToTreeConverter;
import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.OfflineFallbackService;
import com.ruleengine.rules.service.validator.RuleValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DecisionTree 生成器 — Phase 3 完整實作。
 *
 * 兩種運作模式（與 DecisionTableGenerator 對稱）：
 *
 * 【模式 A — MCP 模式（不花錢）】
 *   外部 AI 根據 tool description 中的 DecisionTree JSON 格式說明，
 *   自行將使用者的自然語言描述轉為完整樹結構 JSON。
 *   本 generator 做：解析 → 正規化 → 計算 evaluation → 回傳
 *
 * 【模式 B — REST + LLM 模式】
 *   接收純自然語言，server 端呼叫 LLM 轉為 DecisionTree JSON。
 *
 * 核心承諾：generate 的輸出可被 /validate 通過。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DecisionTreeGenerator implements RuleGenerator {

    /** Self-Repair 最大輪數（可透過 rules.tree.max-repair-rounds 配置） */
    @org.springframework.beans.factory.annotation.Value("${rules.tree.max-repair-rounds:3}")
    private int maxRepairRounds = 3;

    private final ObjectMapper objectMapper;
    private final TreeNormalizer treeNormalizer;
    private final TreeEvaluationComputer treeEvaluationComputer;
    private final OfflineFallbackService offlineFallbackService;

    private RuleValidator decisionTreeValidator;
    private LlmProvider llmProvider;
    private TableToTreeConverter tableToTreeConverter;

    @Autowired
    public void setTableToTreeConverter(TableToTreeConverter converter) {
        this.tableToTreeConverter = converter;
    }

    private com.ruleengine.rules.service.llm.LlmProviderRegistry llmProviderRegistry;

    @Autowired(required = false)
    public void setLlmProvider(com.ruleengine.rules.service.llm.LlmProviderRegistry registry) {
        this.llmProviderRegistry = registry;   // per-request provider 解析用（review 修復輪）
        this.llmProvider = registry.getDefault();
    }

    @Autowired
    public void setDecisionTreeValidator(
            @org.springframework.beans.factory.annotation.Qualifier("decisionTreeValidator") RuleValidator validator) {
        this.decisionTreeValidator = validator;
    }

    @Value("${rules.schema-version:1.0.0}")
    private String schemaVersion;

    @Value("${rules.prompt-version:p1.0.0}")
    private String promptVersion;

    @Value("${rules.llm.self-repair:false}")
    private boolean selfRepairEnabled;

    /**
     * 單次呼叫的生成上下文（review 修復輪）。
     * 取代原本的 currentMode 可變實例欄位 —— 單例 generator 上的可變欄位
     * 在併發請求下互相覆寫（fast/deep 撞在一起），與 ThreadLocal 污染同族。
     * provider 同時修復「前端選了模型、後端永遠跑預設」的參數斷層。
     */
    private record GenCtx(String mode, LlmProvider provider) {}

    @Override
    public RuleType supportedType() {
        return RuleType.DECISION_TREE;
    }

    @Override
    public JsonNode generate(String description, List<String> allowedFields, String mode) {
        return generate(description, allowedFields, mode, null);
    }

    @Override
    public JsonNode generate(String description, List<String> allowedFields) {
        return generate(description, allowedFields, "normal", null);
    }

    @Override
    public JsonNode generate(String description, List<String> allowedFields, String mode, String providerName) {
        GenCtx ctx = new GenCtx(mode != null ? mode : "normal", resolveProvider(providerName));
        return generateInternal(description, allowedFields, ctx);
    }

    /** 依名稱解析 provider；null/未知回預設（與 DecisionTableGenerator 同語意）。 */
    private LlmProvider resolveProvider(String providerName) {
        if (llmProviderRegistry == null || providerName == null || providerName.isBlank()) return llmProvider;
        LlmProvider p = llmProviderRegistry.getByName(providerName);
        return p != null ? p : llmProvider;
    }

    private JsonNode generateInternal(String description, List<String> allowedFields, GenCtx ctx) {
        log.info("DecisionTreeGenerator.generate: 開始（輸入長度={}, mode={}）",
                description != null ? description.length() : 0, ctx.mode());

        if (description == null || description.isBlank()) {
            return objectMapper.valueToTree(buildStubEnvelope("輸入為空"));
        }

        // 1. 模式判斷 + 解析
        RuleEnvelope envelope = resolveEnvelope(description, ctx);

        // 2. 正規化
        treeNormalizer.normalize(envelope, schemaVersion, promptVersion);

        // 3. 欄位白名單（DecisionTree 的 inputs 過濾）
        if (allowedFields != null && !allowedFields.isEmpty()) {
            applyAllowedFields(envelope, allowedFields);
        }

        // 4. 計算 evaluation 指標
        treeEvaluationComputer.computeEvaluation(envelope);

        // 5. Self-Repair Loop（deep 模式）
        if ("deep".equals(ctx.mode()) && ctx.provider() != null && hasTree(envelope) && needsRepair(envelope)) {
            envelope = selfRepairLoop(description, envelope, ctx);
        }

        log.info("DecisionTreeGenerator.generate: 完成，leafCount={}",
                envelope.getEvaluation() != null ? envelope.getEvaluation().getTotalScenarios() : 0);

        return objectMapper.valueToTree(envelope);
    }

    // ================================================================
    // Self-Repair Loop
    // ================================================================

    private RuleEnvelope selfRepairLoop(String originalDescription, RuleEnvelope envelope, GenCtx ctx) {
        for (int round = 1; round <= maxRepairRounds; round++) {
            String issues = collectIssues(envelope);
            if (issues.isEmpty()) {
                log.info("TreeRepair: round {} — 無問題", round);
                return envelope;
            }

            log.info("TreeRepair: round {}/{} — 發現問題，呼叫 LLM 修復", round, maxRepairRounds);

            try {
                String currentJson = objectMapper.writeValueAsString(envelope);
                String repairedJson = ctx.provider().repairRuleJson(originalDescription, currentJson, issues);
                if (repairedJson == null || repairedJson.isBlank()) {
                    return envelope;
                }

                String cleaned = repairedJson.strip();
                if (cleaned.startsWith("```")) {
                    cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
                }

                JsonNode repairedNode = objectMapper.readTree(cleaned);
                RuleEnvelope repairedEnvelope;

                if (isFullTreeEnvelope(repairedNode)) {
                    repairedEnvelope = objectMapper.treeToValue(repairedNode, RuleEnvelope.class);
                } else {
                    log.warn("TreeRepair: round {} — 修復後格式不符", round);
                    return envelope;
                }

                treeNormalizer.normalize(repairedEnvelope, schemaVersion, promptVersion);
                treeEvaluationComputer.computeEvaluation(repairedEnvelope);

                int oldErrors = countErrors(envelope);
                int newErrors = countErrors(repairedEnvelope);

                if (newErrors < oldErrors) {
                    log.info("TreeRepair: round {} — 改善！errors {} → {}", round, oldErrors, newErrors);
                    envelope = repairedEnvelope;
                } else {
                    log.info("TreeRepair: round {} — 未改善，保留原始版本", round);
                    return envelope;
                }
            } catch (Exception e) {
                log.warn("TreeRepair: round {} — 例外：{}", round, e.getMessage());
                return envelope;
            }
        }
        return envelope;
    }

    private String collectIssues(RuleEnvelope envelope) {
        StringBuilder sb = new StringBuilder();
        if (decisionTreeValidator != null) {
            try {
                List<ValidationError> errors = decisionTreeValidator.validate(envelope);
                if (errors != null && !errors.isEmpty()) {
                    sb.append("## 驗證錯誤（共 ").append(errors.size()).append(" 個）\n");
                    for (ValidationError err : errors) {
                        sb.append("- [").append(err.getCode()).append("] ").append(err.getMessage()).append("\n");
                    }
                }
            } catch (Exception e) {
                log.debug("TreeRepair collectIssues error: {}", e.getMessage());
            }
        }
        return sb.toString();
    }

    private int countErrors(RuleEnvelope envelope) {
        if (decisionTreeValidator == null) return 0;
        try {
            List<ValidationError> errors = decisionTreeValidator.validate(envelope);
            return errors != null ? errors.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean hasTree(RuleEnvelope envelope) {
        return envelope.getRule() != null && envelope.getRule().getRoot() != null;
    }

    private boolean needsRepair(RuleEnvelope envelope) {
        return countErrors(envelope) > 0;
    }

    // ================================================================
    // 模式判斷與解析
    // ================================================================

    private RuleEnvelope resolveEnvelope(String description, GenCtx ctx) {
        JsonNode parsed = tryParseJson(description);

        if (parsed != null && isFullTreeEnvelope(parsed)) {
            log.info("偵測到完整 DecisionTree RuleEnvelope JSON");
            return deserializeEnvelope(parsed);
        } else if (parsed != null && isTreeBody(parsed)) {
            log.info("偵測到 tree rule body JSON，包裝為 RuleEnvelope");
            return wrapTreeBody(parsed);
        } else {
            return resolveByMode(description, ctx);
        }
    }

    private RuleEnvelope resolveByMode(String description, GenCtx ctx) {
        switch (ctx.mode()) {
            case "fast" -> {
                log.info("mode=fast | 優先嘗試離線匹配（DecisionTree）");
                RuleEnvelope offline = tryOfflineOnly(description);
                if (offline != null && hasTree(offline)) {
                    return offline;
                }
                if (ctx.provider() != null && ctx.provider().isAvailable()) {
                    return generateViaLlm(description, ctx);
                }
                return tryOfflineThenStub(description);
            }
            case "deep" -> {
                if (ctx.provider() != null && ctx.provider().isAvailable()) {
                    log.info("mode=deep | 呼叫 LLM（DecisionTree 深度模式）");
                    RuleEnvelope result = generateViaLlm(description, ctx);
                    if (hasTree(result)) return result;
                }
                return tryOfflineThenStub(description);
            }
            default -> {
                if (ctx.provider() != null && ctx.provider().isAvailable()) {
                    log.info("mode=normal | 呼叫 LLM（DecisionTree）");
                    RuleEnvelope result = generateViaLlm(description, ctx);
                    if (hasTree(result)) return result;
                }
                return tryOfflineThenStub(description);
            }
        }
    }

    // ================================================================
    // LLM 生成
    // ================================================================

    private RuleEnvelope generateViaLlm(String description, GenCtx ctx) {
        try {
            String jsonStr = ctx.provider().generateRuleJson(description, ctx.mode(), "DecisionTree");
            if (jsonStr == null || jsonStr.isBlank()) {
                return tryOfflineThenStub(description);
            }

            String cleaned = jsonStr.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            JsonNode llmNode = objectMapper.readTree(cleaned);
            if (isFullTreeEnvelope(llmNode)) {
                return deserializeEnvelope(llmNode);
            } else if (isTreeBody(llmNode)) {
                return wrapTreeBody(llmNode);
            } else {
                return tryOfflineThenStub(description);
            }
        } catch (Exception e) {
            log.error("LLM DecisionTree 生成失敗：{}", e.getMessage());
            return tryOfflineThenStub(description);
        }
    }

    // ================================================================
    // 離線匹配
    // ================================================================

    private RuleEnvelope tryOfflineOnly(String description) {
        String offlineJson = offlineFallbackService.tryOffline(description);
        if (offlineJson != null) {
            JsonNode node = tryParseJson(offlineJson);

            // 1. 已經是 DecisionTree envelope → 直接用
            if (node != null && isFullTreeEnvelope(node)) {
                log.info("離線匹配成功：DecisionTree envelope");
                return deserializeEnvelope(node);
            }

            // 2. 是 DecisionTable envelope → 自動轉換為 DecisionTree
            if (node != null && isFullTableEnvelope(node) && tableToTreeConverter != null) {
                log.info("離線匹配到 DecisionTable，自動轉換為 DecisionTree");
                try {
                    RuleEnvelope tableEnvelope = deserializeEnvelope(node);
                    return tableToTreeConverter.convert(tableEnvelope);
                } catch (Exception e) {
                    log.warn("DecisionTable → DecisionTree 自動轉換失敗：{}", e.getMessage());
                }
            }

            // 3. 是 tree body → 包裝
            if (node != null && isTreeBody(node)) {
                return wrapTreeBody(node);
            }
        }
        return null;
    }

    private boolean isFullTableEnvelope(JsonNode node) {
        return node.isObject()
                && node.has("ruleType")
                && "DecisionTable".equalsIgnoreCase(node.get("ruleType").asText())
                && node.has("rule");
    }

    private RuleEnvelope tryOfflineThenStub(String description) {
        RuleEnvelope offline = tryOfflineOnly(description);
        if (offline != null && hasTree(offline)) return offline;
        return buildStubEnvelope(description);
    }

    // ================================================================
    // JSON 工具
    // ================================================================

    private JsonNode tryParseJson(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        if (!trimmed.startsWith("{")) return null;
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isFullTreeEnvelope(JsonNode node) {
        return node.isObject()
                && node.has("ruleType")
                && "DecisionTree".equalsIgnoreCase(node.get("ruleType").asText())
                && node.has("rule")
                && node.get("rule").has("root");
    }

    private boolean isTreeBody(JsonNode node) {
        return node.isObject() && node.has("root") && (node.has("inputs") || node.has("outputs"));
    }

    private RuleEnvelope deserializeEnvelope(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, RuleEnvelope.class);
        } catch (Exception e) {
            log.error("DecisionTree RuleEnvelope 反序列化失敗：{}", e.getMessage());
            return buildStubEnvelope("JSON 解析失敗：" + e.getMessage());
        }
    }

    private RuleEnvelope wrapTreeBody(JsonNode treeBody) {
        try {
            Rule rule = objectMapper.treeToValue(treeBody, Rule.class);
            return RuleEnvelope.builder()
                    .ruleType("DecisionTree")
                    .reason("由 AI 生成的 DecisionTree 規則（僅收到 rule body，已自動包裝）")
                    .rule(rule)
                    .build();
        } catch (Exception e) {
            log.error("Tree body 反序列化失敗：{}", e.getMessage());
            return buildStubEnvelope("tree body 解析失敗：" + e.getMessage());
        }
    }

    // ================================================================
    // 欄位白名單
    // ================================================================

    private void applyAllowedFields(RuleEnvelope envelope, List<String> allowedFields) {
        Rule rule = envelope.getRule();
        if (rule == null || rule.getInputs() == null) return;

        Set<String> allowed = new HashSet<>(allowedFields);
        List<FieldDef> before = rule.getInputs();
        rule.setInputs(new ArrayList<>(before.stream()
                .filter(f -> allowed.contains(f.getName()))
                .toList()));

        int removed = before.size() - rule.getInputs().size();
        if (removed > 0) {
            log.warn("DecisionTree allowedFields 過濾移除了 {} 個 input 欄位", removed);
        }
    }

    // ================================================================
    // Stub 模板
    // ================================================================

    private RuleEnvelope buildStubEnvelope(String context) {
        return RuleEnvelope.builder()
                .schemaVersion(schemaVersion)
                .promptVersion(promptVersion)
                .ruleType("DecisionTree")
                .reason("⚠ 收到純自然語言描述，無法直接生成 DecisionTree。請先將描述轉為 DecisionTree JSON 格式後再呼叫 generate。"
                        + "可呼叫 getRuleTypeExample('DecisionTree') 取得完整範例。"
                        + "原始輸入：" + truncate(context, 100))
                .rule(Rule.builder()
                        .inputs(new ArrayList<>())
                        .outputs(new ArrayList<>())
                        .build())
                .evaluation(Evaluation.builder()
                        .completeness("INCOMPLETE")
                        .totalScenarios(0)
                        .coverageRate(0.0)
                        .conflictDetection("NO_CONFLICT")
                        .recommendedStrategy("FIRST")
                        .build())
                .build();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
