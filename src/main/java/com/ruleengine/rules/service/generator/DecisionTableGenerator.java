package com.ruleengine.rules.service.generator;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.ruleengine.rules.service.analyzer.AnalysisResult;
import com.ruleengine.rules.service.analyzer.DmnAnalyzer;
import com.ruleengine.rules.service.llm.DescriptionDimensionParser;
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
import java.util.stream.Collectors;

/**
 * DecisionTable 生成器 — Phase 1 核心交付。
 *
 * 設計文件 §2 端對端流程：
 *   01 輸入 POST /tools/generate
 *   02 AI 分析（外部 AI 或內部 LLM）
 *   03 驗證 POST /tools/validate
 *   04 輸出 RuleEnvelope JSON
 *
 * 兩種運作模式：
 *
 * 【模式 A — MCP 模式（不花錢）】
 *   外部 AI（Claude/Gemini）根據 tool description 中的格式說明，
 *   自行將使用者的自然語言描述轉換為 RuleEnvelope JSON。
 *   AI 把轉好的 JSON 字串作為 description 傳進來。
 *   本 generator 做：解析 → 正規化 → 欄位白名單 → 計算 evaluation → 回傳
 *
 * 【模式 B — REST + LLM 模式（Phase 2+ 擴展）】
 *   接收純自然語言，server 端呼叫 LLM 轉換。
 *
 * Phase 1 核心承諾：generate 的輸出可被 /validate 通過。
 *
 * v2.0.0 重構：
 *   - 正規化邏輯委派給 EnvelopeNormalizer
 *   - Evaluation 計算委派給 EvaluationComputer
 *   - 重疊偵測邏輯委派給 ConditionOverlapDetector
 *   - 本類別僅負責流程協調（模式判斷 → 解析 → 委派）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DecisionTableGenerator implements RuleGenerator {

    /** Self-Repair 最大輪數（Level B：驗證錯誤 + 覆蓋缺口回饋修復） */
    private static final int MAX_REPAIR_ROUNDS = 1;
    /** 只在 coverage 低於此閾值或有 error 時才觸發 repair */
    private static final double REPAIR_COVERAGE_THRESHOLD = 0.95;

    private final ObjectMapper objectMapper;
    private final EnvelopeNormalizer normalizer;
    private final EvaluationComputer evaluationComputer;
    private final OfflineFallbackService offlineFallbackService;
    private final DimensionExpander dimensionExpander;
    private final CartesianProductFiller cartesianProductFiller;
    private final DescriptionDimensionParser dimensionParser;

    /**
     * 單次 generate 呼叫的區域性生成上下文。
     *
     * <p>
     * 只在 {@link #generateInternal} 的呼叫堆疊內存活、以參數顯式傳遞，
     * 因此不存在跨請求或跨執行緒共享的可能（取代 static ThreadLocal 的老路）。
     * review 修復輪擴充：攜帶本次請求解析出的 {@code provider} ——
     * 修復「前端選了 A 模型、後端永遠跑預設 B」的參數斷層；
     * 刻意不改 {@code this.llmProvider} 欄位（那是共享狀態，改了就是併發污染）。
     * </p>
     */
    private static final class GenerationContext {
        private final LlmProvider provider;
        private DescriptionDimensionParser.ParsedDimensions dimensions;

        GenerationContext(LlmProvider provider) {
            this.provider = provider;
        }

        LlmProvider provider() {
            return provider;
        }

        DescriptionDimensionParser.ParsedDimensions getDimensions() {
            return dimensions;
        }

        void setDimensions(DescriptionDimensionParser.ParsedDimensions dimensions) {
            this.dimensions = dimensions;
        }
    }

    /** 用於 Self-Repair loop 中的驗證和分析 */
    private RuleValidator decisionTableValidator;
    private DmnAnalyzer dmnAnalyzer;

    /** LLM 提供者（向後相容：預設 provider） */
    private LlmProvider llmProvider;

    /** LLM Provider Registry — 支援動態選擇 provider */
    private com.ruleengine.rules.service.llm.LlmProviderRegistry llmProviderRegistry;

    @Autowired(required = false)
    public void setLlmProvider(com.ruleengine.rules.service.llm.LlmProviderRegistry registry) {
        this.llmProviderRegistry = registry;
        this.llmProvider = registry.getDefault();
        if (llmProvider != null) {
            log.info("LlmProvider 已注入（{}），自然語言生成功能已啟用", llmProvider.getProviderName());
        }
    }

    /** 動態取得指定 provider（供 generate 時使用） */
    public LlmProvider resolveProvider(String providerName) {
        if (llmProviderRegistry == null) return llmProvider;
        if (providerName == null || providerName.isBlank()) return llmProvider;
        LlmProvider p = llmProviderRegistry.getByName(providerName);
        return p != null ? p : llmProvider;
    }

    @Autowired
    public void setDecisionTableValidator(@org.springframework.beans.factory.annotation.Qualifier("decisionTableValidator") RuleValidator validator) {
        this.decisionTableValidator = validator;
    }

    @Autowired
    public void setDmnAnalyzer(DmnAnalyzer analyzer) {
        this.dmnAnalyzer = analyzer;
    }

    @Value("${rules.schema-version:1.0.0}")
    private String schemaVersion;

    @Value("${rules.prompt-version:p1.0.0}")
    private String promptVersion;

    @Value("${rules.llm.self-repair:false}")
    private boolean selfRepairEnabled;

    @Override
    public RuleType supportedType() {
        return RuleType.DECISION_TABLE;
    }

    @Override
    public JsonNode generate(String description, List<String> allowedFields, String mode) {
        return generateInternal(description, allowedFields, mode != null ? mode : "normal", null);
    }

    @Override
    public JsonNode generate(String description, List<String> allowedFields) {
        return generateInternal(description, allowedFields, "normal", null);
    }

    @Override
    public JsonNode generate(String description, List<String> allowedFields, String mode, String providerName) {
        return generateInternal(description, allowedFields, mode != null ? mode : "normal", providerName);
    }

    private JsonNode generateInternal(String description, List<String> allowedFields, String mode, String providerName) {
        log.info("DecisionTableGenerator.generate: 開始（輸入長度={}, mode={}）",
                description != null ? description.length() : 0, mode);

        if (description == null || description.isBlank()) {
            return objectMapper.valueToTree(buildStubEnvelope("輸入為空"));
        }

        // 1. 模式判斷 + 解析
        //    context 是方法區域物件：攜帶本次請求的 provider（resolveProvider 至此才有呼叫者）
        //    與維度預解析結果，顯式沿參數鏈傳遞。
        GenerationContext ctx = new GenerationContext(resolveProvider(providerName));
        if (providerName != null && !providerName.isBlank() && ctx.provider() != null) {
            log.info("本次請求指定 provider={} → 解析為 {}", providerName, ctx.provider().getProviderName());
        }
        RuleEnvelope envelope = resolveEnvelope(description, mode, ctx);

        // 2. 正規化
        normalizer.normalize(envelope, schemaVersion, promptVersion);

        // 2.5 維度擴展 + Two-Pass 笛卡爾積填充（僅在本次確實由預解析型 provider 生成時）
        DescriptionDimensionParser.ParsedDimensions parsedDims = ctx.getDimensions();
        if (parsedDims != null && !parsedDims.inputs().isEmpty() && hasRules(envelope)
                && !isMultiHitPolicy(envelope)) {
            // 2.5a 先補齊遺漏的維度（若 LLM 只用了部分 inputs）
            envelope = dimensionExpander.expand(envelope, parsedDims);

            // 2.5b Two-Pass: 笛卡爾積填充（補齊缺少的條件組合）
            if (parsedDims.estimatedCartesian() > 1) {
                int ruleCount = envelope.getRule().getRules().size();
                int cartesian = parsedDims.estimatedCartesian();
                if (ruleCount < cartesian) {
                    log.info("Two-Pass: rules={} < cartesian={}，啟動笛卡爾積填充", ruleCount, cartesian);
                    envelope = cartesianProductFiller.fill(envelope, parsedDims);
                }
            }
        }

        // 3. 欄位白名單檢查
        if (allowedFields != null && !allowedFields.isEmpty()) {
            applyAllowedFields(envelope, allowedFields);
        }

        // 4. 計算 evaluation 指標
        evaluationComputer.computeEvaluation(envelope);

        // 5. Self-Repair Loop（Level B）：只在 deep 模式且有問題時觸發
        if ("deep".equals(mode) && ctx.provider() != null && hasRules(envelope) && needsRepair(envelope)) {
            envelope = selfRepairLoop(description, envelope, ctx);
        }

        log.info("DecisionTableGenerator.generate: 完成，rules={}",
                envelope.getRule() != null && envelope.getRule().getRules() != null
                        ? envelope.getRule().getRules().size() : 0);

        return objectMapper.valueToTree(envelope);
    }

    // ================================================================
    // Self-Repair Loop（Level B）
    // ================================================================

    /**
     * 驗證 + 分析 → 收集問題 → 餵回 LLM 修復 → 重新驗證，最多 MAX_REPAIR_ROUNDS 輪。
     *
     * 每一輪：
     * 1. 用 Validator 找驗證錯誤
     * 2. 用 DmnAnalyzer 找覆蓋缺口
     * 3. 如果無問題 → 結束
     * 4. 組合問題描述 → 呼叫 LLM repairRuleJson
     * 5. 解析修復後的 JSON → 正規化 → 重新計算 evaluation
     */
    private RuleEnvelope selfRepairLoop(String originalDescription, RuleEnvelope envelope, GenerationContext ctx) {
        for (int round = 1; round <= MAX_REPAIR_ROUNDS; round++) {
            String issues = collectIssues(envelope);
            if (issues.isEmpty()) {
                log.info("Self-Repair: round {} — 無問題，跳過修復", round);
                return envelope;
            }

            log.info("Self-Repair: round {}/{} — 發現問題，呼叫 LLM 修復", round, MAX_REPAIR_ROUNDS);

            try {
                String currentJson = objectMapper.writeValueAsString(envelope);
                String repairedJson = ctx.provider().repairRuleJson(originalDescription, currentJson, issues);
                if (repairedJson == null || repairedJson.isBlank()) {
                    log.warn("Self-Repair: round {} — LLM 回傳空結果，保留原始版本", round);
                    return envelope;
                }

                // 清理 markdown
                String cleaned = repairedJson.strip();
                if (cleaned.startsWith("```")) {
                    cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
                }

                JsonNode repairedNode = objectMapper.readTree(cleaned);
                RuleEnvelope repairedEnvelope;

                if (isFullEnvelope(repairedNode)) {
                    repairedEnvelope = deserializeEnvelope(repairedNode);
                } else if (isRuleBody(repairedNode)) {
                    repairedEnvelope = wrapRuleBody(repairedNode);
                } else {
                    log.warn("Self-Repair: round {} — 修復後格式不符，保留原始版本", round);
                    return envelope;
                }

                // 重新正規化和計算
                normalizer.normalize(repairedEnvelope, schemaVersion, promptVersion);
                evaluationComputer.computeEvaluation(repairedEnvelope);

                // 檢查修復後是否真的更好
                int oldErrors = countErrors(envelope);
                int newErrors = countErrors(repairedEnvelope);

                if (newErrors < oldErrors || repairedEnvelope.getEvaluation().getCoverageRate() > envelope.getEvaluation().getCoverageRate()) {
                    log.info("Self-Repair: round {} — 改善！errors {} → {}, coverage {} → {}",
                            round, oldErrors, newErrors,
                            String.format("%.1f%%", envelope.getEvaluation().getCoverageRate() * 100),
                            String.format("%.1f%%", repairedEnvelope.getEvaluation().getCoverageRate() * 100));
                    envelope = repairedEnvelope;
                } else {
                    log.info("Self-Repair: round {} — 未改善（errors {} → {}, coverage {} → {}），保留原始版本",
                            round, oldErrors, newErrors,
                            String.format("%.1f%%", envelope.getEvaluation().getCoverageRate() * 100),
                            String.format("%.1f%%", repairedEnvelope.getEvaluation().getCoverageRate() * 100));
                    return envelope;
                }

            } catch (Exception e) {
                log.warn("Self-Repair: round {} — 修復過程發生例外：{}", round, e.getMessage());
                return envelope;
            }
        }

        return envelope;
    }

    /**
     * 收集當前 envelope 的所有問題（驗證錯誤 + 覆蓋缺口）。
     *
     * @return 問題描述字串，空字串表示無問題
     */
    private String collectIssues(RuleEnvelope envelope) {
        StringBuilder sb = new StringBuilder();

        // 1. 驗證錯誤
        if (decisionTableValidator != null) {
            try {
                List<ValidationError> errors = decisionTableValidator.validate(envelope);
                if (errors != null && !errors.isEmpty()) {
                    sb.append("## 驗證錯誤（共 ").append(errors.size()).append(" 個）\n");
                    for (ValidationError err : errors) {
                        sb.append("- [").append(err.getCode()).append("] ").append(err.getMessage()).append("\n");
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                log.debug("Self-Repair: 驗證過程出錯：{}", e.getMessage());
            }
        }

        // 2. 覆蓋缺口
        if (dmnAnalyzer != null) {
            try {
                JsonNode envelopeNode = objectMapper.valueToTree(envelope);
                AnalysisResult analysis = dmnAnalyzer.analyze(envelopeNode);
                if (analysis.getGaps() != null && !analysis.getGaps().isEmpty()) {
                    int gapCount = analysis.getGaps().size();
                    sb.append("## 覆蓋缺口（共 ").append(gapCount).append(" 個，");
                    sb.append("覆蓋率 ").append(String.format("%.1f%%", analysis.getCoverageRate() * 100)).append("）\n");
                    // 最多列出前 10 個缺口
                    int limit = Math.min(gapCount, 10);
                    for (int i = 0; i < limit; i++) {
                        AnalysisResult.GapInfo gap = analysis.getGaps().get(i);
                        sb.append("- ").append(gap.getMessage()).append("\n");
                    }
                    if (gapCount > limit) {
                        sb.append("- ...還有 ").append(gapCount - limit).append(" 個缺口\n");
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                log.debug("Self-Repair: 分析過程出錯：{}", e.getMessage());
            }
        }

        return sb.toString();
    }

    /**
     * 計算 envelope 的驗證錯誤數。
     */
    private int countErrors(RuleEnvelope envelope) {
        if (decisionTableValidator == null) return 0;
        try {
            List<ValidationError> errors = decisionTableValidator.validate(envelope);
            return errors != null ? errors.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean hasRules(RuleEnvelope envelope) {
        return envelope.getRule() != null
                && envelope.getRule().getRules() != null
                && !envelope.getRule().getRules().isEmpty();
    }

    private boolean isMultiHitPolicy(RuleEnvelope envelope) {
        return envelope.getRule() != null
                && "MULTI".equalsIgnoreCase(envelope.getRule().getHitPolicy());
    }

    /**
     * 判斷是否需要觸發 Self-Repair：有驗證錯誤 或 覆蓋率低於閾值。
     */
    private boolean needsRepair(RuleEnvelope envelope) {
        // 有驗證錯誤
        if (countErrors(envelope) > 0) {
            log.info("needsRepair: 有 {} 個驗證錯誤，需要修復", countErrors(envelope));
            return true;
        }
        // 覆蓋率低於閾值
        if (envelope.getEvaluation() != null
                && envelope.getEvaluation().getCoverageRate() < REPAIR_COVERAGE_THRESHOLD) {
            log.info("needsRepair: 覆蓋率 {}% < {}%，需要修復",
                    String.format("%.1f", envelope.getEvaluation().getCoverageRate() * 100),
                    String.format("%.0f", REPAIR_COVERAGE_THRESHOLD * 100));
            return true;
        }
        log.info("needsRepair: 無需修復（0 errors, coverage >= {}%）",
                String.format("%.0f", REPAIR_COVERAGE_THRESHOLD * 100));
        return false;
    }

    // ================================================================
    // 模式判斷與解析
    // ================================================================

    /**
     * 根據輸入內容判斷模式，回傳對應的 RuleEnvelope。
     */
    private RuleEnvelope resolveEnvelope(String description, String mode, GenerationContext ctx) {
        JsonNode parsed = tryParseJson(description);

        if (parsed != null && isFullEnvelope(parsed)) {
            // 模式 A-1：完整 RuleEnvelope
            log.info("偵測到完整 RuleEnvelope JSON");
            return deserializeEnvelope(parsed);
        } else if (parsed != null && isRuleBody(parsed)) {
            // 模式 A-2：只有 rule body（缺 ruleType / evaluation 外殼）
            log.info("偵測到 rule body JSON，包裝為 RuleEnvelope");
            return wrapRuleBody(parsed);
        } else {
            // 模式 B：純自然語言 — 根據 mode 決定策略
            return resolveByMode(description, mode, ctx);
        }
    }

    // ================================================================
    // 根據 mode 決定生成策略
    // ================================================================

    /**
     * fast: offline 優先（≤5s），offline 沒匹配到才呼叫 LLM
     * normal: LLM 優先（≤15s），LLM 失敗 fallback offline
     * deep: LLM 優先（≤30s），LLM 失敗 fallback offline
     */
    private RuleEnvelope resolveByMode(String description, String mode, GenerationContext ctx) {
        switch (mode) {
            case "fast" -> {
                // fast: offline 優先（快速回應）
                log.info("mode=fast | 優先嘗試離線匹配");
                RuleEnvelope offline = tryOfflineOnly(description);
                if (offline != null && hasRules(offline)) {
                    return offline;
                }
                // offline 沒匹配到，才呼叫 LLM
                if (ctx.provider() != null && ctx.provider().isAvailable()) {
                    log.info("mode=fast | 離線無匹配，呼叫 LLM");
                    return generateViaLlm(description, mode, ctx);
                }
                return tryOfflineThenStub(description);
            }
            case "deep" -> {
                // deep: LLM 優先 + Self-Repair
                if (ctx.provider() != null && ctx.provider().isAvailable()) {
                    log.info("mode=deep | 呼叫 LLM（深度模式）");
                    RuleEnvelope result = generateViaLlm(description, mode, ctx);
                    if (hasRules(result)) {
                        return result;
                    }
                }
                log.info("mode=deep | LLM 失敗，fallback 離線");
                return tryOfflineThenStub(description);
            }
            default -> {
                // normal: LLM 優先
                if (ctx.provider() != null && ctx.provider().isAvailable()) {
                    log.info("mode=normal | 呼叫 LLM");
                    RuleEnvelope result = generateViaLlm(description, mode, ctx);
                    if (hasRules(result)) {
                        return result;
                    }
                }
                log.info("mode=normal | LLM 失敗，fallback 離線");
                return tryOfflineThenStub(description);
            }
        }
    }

    /**
     * 只嘗試 offline 匹配，不 fallback 到 stub。
     */
    private RuleEnvelope tryOfflineOnly(String description) {
        String offlineJson = offlineFallbackService.tryOffline(description);
        if (offlineJson != null) {
            JsonNode offlineNode = tryParseJson(offlineJson);
            if (offlineNode != null && isFullEnvelope(offlineNode)) {
                return deserializeEnvelope(offlineNode);
            } else if (offlineNode != null && isRuleBody(offlineNode)) {
                return wrapRuleBody(offlineNode);
            }
        }
        return null;
    }

    // ================================================================
    // JSON 解析
    // ================================================================

    private JsonNode tryParseJson(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        if (!trimmed.startsWith("{")) return null;
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception e) {
            log.debug("非合法 JSON：{}", e.getMessage());
            return null;
        }
    }

    private boolean isFullEnvelope(JsonNode node) {
        return node.isObject() && node.has("ruleType") && node.has("rule");
    }

    private boolean isRuleBody(JsonNode node) {
        return node.isObject() && (node.has("hitPolicy") || node.has("inputs") || node.has("rules"));
    }

    private RuleEnvelope deserializeEnvelope(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, RuleEnvelope.class);
        } catch (Exception e) {
            log.error("RuleEnvelope 反序列化失敗：{}", e.getMessage());
            return buildStubEnvelope("JSON 解析失敗：" + e.getMessage());
        }
    }

    private RuleEnvelope wrapRuleBody(JsonNode ruleBody) {
        try {
            Rule rule = objectMapper.treeToValue(ruleBody, Rule.class);
            return RuleEnvelope.builder()
                    .ruleType("DecisionTable")
                    .reason("由 AI 生成的 DecisionTable 規則（僅收到 rule body，已自動包裝）")
                    .rule(rule)
                    .build();
        } catch (Exception e) {
            log.error("Rule body 反序列化失敗：{}", e.getMessage());
            return buildStubEnvelope("rule body 解析失敗：" + e.getMessage());
        }
    }

    // ================================================================
    // 欄位白名單（設計文件 §十 風險：欄位憑空生成）
    // ================================================================

    private void applyAllowedFields(RuleEnvelope envelope, List<String> allowedFields) {
        Rule rule = envelope.getRule();
        if (rule == null) return;

        Set<String> allowed = new HashSet<>(allowedFields);

        if (rule.getInputs() != null) {
            List<FieldDef> before = rule.getInputs();
            rule.setInputs(before.stream()
                    .filter(f -> allowed.contains(f.getName()))
                    .collect(Collectors.toCollection(ArrayList::new)));
            int removed = before.size() - rule.getInputs().size();
            if (removed > 0) {
                log.warn("allowedFields 過濾移除了 {} 個 input 欄位", removed);
                String removedNames = before.stream()
                        .map(FieldDef::getName)
                        .filter(n -> !allowed.contains(n))
                        .collect(Collectors.joining(", "));
                String currentReason = envelope.getReason() != null ? envelope.getReason() : "";
                envelope.setReason(currentReason
                        + "（⚠ allowedFields 過濾移除了不在白名單中的欄位：" + removedNames + "）");
            }
        }
    }

    // ================================================================
    // 模式 B-1：呼叫 LLM 生成
    // ================================================================

    private RuleEnvelope generateViaLlm(String description, String mode, GenerationContext ctx) {
        try {
            // 只有採預解析補償的 provider（本地小模型）才啟用後續的維度擴展 / 笛卡爾積填充。
            // parse 是純函式（regex，典型 <5ms），與 provider 內部那次各自獨立 ——
            // 這正是 PreflightService / SpecLintService 既有的做法，避免共享可變狀態。
            if (ctx.provider().usesDimensionPreparse()) {
                ctx.setDimensions(dimensionParser.parse(description));
            }
            String jsonStr = ctx.provider().generateRuleJson(description, mode);
            if (jsonStr == null || jsonStr.isBlank()) {
                log.warn("LLM 回傳空結果，嘗試離線匹配");
                return tryOfflineThenStub(description);
            }

            // 清理可能的 markdown 包裹
            String cleaned = jsonStr.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            JsonNode llmNode = objectMapper.readTree(cleaned);

            if (isFullEnvelope(llmNode)) {
                log.info("LLM 回傳完整 RuleEnvelope");
                return deserializeEnvelope(llmNode);
            } else if (isRuleBody(llmNode)) {
                log.info("LLM 回傳 rule body，包裝為 RuleEnvelope");
                return wrapRuleBody(llmNode);
            } else {
                log.warn("LLM 回傳格式不符預期，嘗試離線匹配");
                return tryOfflineThenStub(description);
            }
        } catch (Exception e) {
            log.error("LLM 生成失敗：{}，嘗試離線匹配", e.getMessage());
            return tryOfflineThenStub(description);
        }
    }

    // ================================================================
    // 離線匹配 fallback（DECISIONS Q28）
    // ================================================================

    /**
     * 嘗試用 OfflineFallbackService 匹配離線 scenario。
     * 匹配成功則解析為 RuleEnvelope，失敗則回傳 stub 模板。
     *
     * Fallback 鏈路：LLM → OfflineScenario → StubTemplate
     */
    private RuleEnvelope tryOfflineThenStub(String description) {
        String offlineJson = offlineFallbackService.tryOffline(description);
        if (offlineJson != null) {
            log.info("離線匹配成功，解析 scenario JSON");
            JsonNode offlineNode = tryParseJson(offlineJson);
            if (offlineNode != null && isFullEnvelope(offlineNode)) {
                return deserializeEnvelope(offlineNode);
            } else if (offlineNode != null && isRuleBody(offlineNode)) {
                return wrapRuleBody(offlineNode);
            }
            log.warn("離線 scenario JSON 格式不符預期，fallback 為模板");
        }
        return buildStubEnvelope(description);
    }

    // ================================================================
    // 模板（純自然語言輸入時回傳提示）
    // ================================================================

    private RuleEnvelope buildStubEnvelope(String context) {
        return RuleEnvelope.builder()
                .schemaVersion(schemaVersion)
                .promptVersion(promptVersion)
                .ruleType("DecisionTable")
                .reason("⚠ 收到純自然語言描述，無法直接生成。請先將描述轉為 RuleEnvelope JSON 格式後再呼叫 generate。"
                        + "可呼叫 getRuleTypeExample('DecisionTable') 取得完整範例。"
                        + "原始輸入：" + truncate(context, 100))
                .rule(Rule.builder()
                        .hitPolicy("FIRST")
                        .inputs(new ArrayList<>())
                        .outputs(new ArrayList<>())
                        .rules(new ArrayList<>())
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
