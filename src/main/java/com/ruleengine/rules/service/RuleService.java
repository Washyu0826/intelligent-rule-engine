package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.exception.RuleGenerationException;
import com.ruleengine.rules.registry.RuleTypeRegistry;
import com.ruleengine.rules.service.analyzer.AnalysisResult;
import com.ruleengine.rules.service.analyzer.DmnAnalyzer;
import com.ruleengine.rules.service.analyzer.ScoreCardAnalyzer;
import com.ruleengine.rules.service.analyzer.TreeAnalyzer;
import com.ruleengine.rules.service.audit.AuditService;
import com.ruleengine.rules.service.converter.TableToTreeConverter;
import com.ruleengine.rules.service.converter.TreeToTableConverter;
import com.ruleengine.rules.service.generator.RuleGenerator;
import com.ruleengine.rules.service.recommender.RuleRecommender;
import com.ruleengine.rules.service.validator.RuleValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 核心編排服務（計畫書 §6.1）
 *
 * Phase 1 實作範圍：
 * - generate：DecisionTable 生成（v1.4+ 帶 DMN 分析結果）
 * - validate：DecisionTable 11 個錯誤碼驗證
 * - test-run：批次測試
 *
 * Phase 2 才加入：
 * - recommend：規則型態推薦
 * - analyze：獨立 DMN 幾何分析端點
 *
 * 核心原則：schema-first → 再生成 → 必驗證 → 再回傳
 *
 * v2.0.0 重構：
 * - metrics / MDC traceId / 計時 已移至 RuleServiceMetricsAspect（AOP 切面）
 * - 例外使用自訂 RuleException 階層
 * - 本類別只包含純業務邏輯
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleService {

    private final RuleTypeRegistry registry;
    private final RuleRecommender recommender;
    private final DmnAnalyzer dmnAnalyzer;
    private final TreeAnalyzer treeAnalyzer;
    private final ScoreCardAnalyzer scoreCardAnalyzer;
    private final TreeToTableConverter treeToTableConverter;
    private final TableToTreeConverter tableToTreeConverter;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final com.ruleengine.rules.service.evaluator.GroundingGuardService groundingGuardService;
    private final com.ruleengine.rules.service.evaluator.ConfidenceScorer confidenceScorer;
    private final PreflightService preflightService;

    @Value("${rules.schema-version:1.0.0}")
    private String schemaVersion;

    @Value("${rules.prompt-version:p1.0.0}")
    private String promptVersion;

    // ========================================================================
    //  GENERATE（設計文件 §4 UC-01）
    // ========================================================================

    /**
     * 生成 DecisionTable RuleEnvelope。
     *
     * Flow:
     * 1. 判斷規則型態（Phase 1 固定 DecisionTable）
     * 2. 呼叫對應 Generator 生成 payload
     * 3. 自動呼叫 validate 驗證（closed loop 保證）
     * 4. 呼叫 DmnAnalyzer 進行幾何分析，結果塞入 evaluation
     * 5. 回傳 RuleEnvelope
     *
     * Post-condition: 回傳的 JSON 可直接通過 /validate，valid = true
     */
    public GenerateResponse generateFull(GenerateRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("generate START | description.length={} | ruleType={}",
                request.getDescription() != null ? request.getDescription().length() : 0,
                request.getRuleType());

        try {
            // Step 0: Pre-flight gate（生成前輸入偵測）—
            //   純符號、0 token。mode=off 時 evaluate 回 null、行為與未導入前位元級相同。
            //   block 模式下偵測到 ERROR 級問題即短路，不呼叫 LLM（省 token）。
            PreflightService.PreflightReport preflight =
                    preflightService.evaluate(request.getDescription(), request.getPreflightMode());
            if (preflight != null && preflight.blocked()) {
                log.warn("generate BLOCKED by pre-flight | findings={} | desc.length={}",
                        preflight.findings().size(),
                        request.getDescription() != null ? request.getDescription().length() : 0);
                return GenerateResponse.builder()
                        .preflight(preflight)
                        .durationMs(System.currentTimeMillis() - startMs)
                        .build();
            }

            // Step 1: 決定規則型態
            RuleType type = resolveRuleType(request);

            // Step 2: 檢查 registry
            RuleGenerator generator = registry.getGenerator(type)
                    .orElseThrow(() -> new RuleGenerationException(
                            "型態 \"" + type.getCode() + "\" 的 Generator 尚未註冊"));

            // Step 3: 生成（帶模式參數）
            // review 修復輪：provider 終於傳下去了 —— 之前 request.getProvider() 在這裡被丟棄，
            // UI 的模型下拉選單形同虛設（永遠跑預設 provider）
            JsonNode resultNode = generator.generate(request.getDescription(), request.getAllowedFields(),
                    request.getMode(), request.getProvider());
            RuleEnvelope envelope = objectMapper.treeToValue(resultNode, RuleEnvelope.class);

            // Step 4: 確保 metadata
            if (envelope.getSchemaVersion() == null) envelope.setSchemaVersion(schemaVersion);
            if (envelope.getPromptVersion() == null) envelope.setPromptVersion(promptVersion);

            // Step 5: 自動驗證（closed loop）
            ValidateResponse validation = doValidateEnvelope(envelope, type);
            if (!validation.isValid()) {
                log.warn("generate AUTO-VALIDATE FAIL | errors={} | 第一個錯誤: {}",
                        validation.getErrors().size(),
                        validation.getErrors().isEmpty() ? "none" : validation.getErrors().get(0).getMessage());
                String warning = String.format("（⚠ 自動驗證發現 %d 個問題，建議呼叫 /validate 查看詳情）",
                        validation.getErrors().size());
                envelope.setReason(
                        (envelope.getReason() != null ? envelope.getReason() : "") + warning);
            } else {
                log.info("generate AUTO-VALIDATE PASS");
            }

            // Step 6: 分析（DecisionTable → DMN 幾何分析；DecisionTree → 路徑分析）
            JsonNode envelopeNode = objectMapper.valueToTree(envelope);
            AnalyzeResponse analyzeResponse;
            if (type == RuleType.DECISION_TREE) {
                enrichWithTreeAnalysis(envelope, envelopeNode);
                analyzeResponse = buildAnalyzeFromEnvelope(envelope);
            } else {
                enrichWithAnalysis(envelope, envelopeNode);
                analyzeResponse = buildAnalyzeFromEnvelope(envelope);
            }

            long durationMs = System.currentTimeMillis() - startMs;
            log.info("generate END | ruleType={} | rules={} | durationMs={}",
                    envelope.getRuleType(),
                    envelope.getRule() != null && envelope.getRule().getRules() != null
                            ? envelope.getRule().getRules().size() : 0,
                    durationMs);

            // Step 7: 稽核記錄（不影響主流程）
            try {
                auditService.recordOperation(envelope, "GENERATE", null, "自然語言生成");
            } catch (Exception auditEx) {
                log.warn("Audit record failed (non-fatal): {}", auditEx.getMessage());
            }

            // Step 8: Symbolic grounding check（v3.8.0, AWS Automated Reasoning 2024 inspired）—
            //          純符號幻覺偵測，檢查欄位／ENUM 值是否都能 ground 回原始描述。
            com.ruleengine.rules.service.evaluator.GroundingGuardService.GroundingReport groundingReport = null;
            try {
                groundingReport = groundingGuardService.check(
                        request.getDescription(), request.getAllowedFields(), envelope);
                if ("HIGH".equals(groundingReport.getSuspicionLevel())) {
                    log.warn("generate GROUNDING HIGH SUSPICION | ratio={} | ungroundedFields={} | ungroundedValues={}",
                            String.format("%.2f", groundingReport.getGroundingRatio()),
                            groundingReport.getUngroundedFields() == null ? 0 : groundingReport.getUngroundedFields().size(),
                            groundingReport.getUngroundedEnumValues() == null ? 0 : groundingReport.getUngroundedEnumValues().size());
                }
            } catch (Exception gcEx) {
                log.warn("Grounding check failed (non-fatal): {}", gcEx.getMessage());
            }

            // Step 9: Composite confidence score（v3.10.0）— 聚合所有品質訊號成一個 0-100 分。
            com.ruleengine.rules.service.evaluator.ConfidenceScorer.ConfidenceReport confidence = null;
            try {
                confidence = confidenceScorer.score(envelope, validation, groundingReport);
                if ("NOT_RECOMMENDED".equals(confidence.getTier())) {
                    log.warn("generate LOW CONFIDENCE | score={} | tier={}",
                            confidence.getOverallScore(), confidence.getTier());
                }
            } catch (Exception csEx) {
                log.warn("Confidence scoring failed (non-fatal): {}", csEx.getMessage());
            }

            return GenerateResponse.builder()
                    .envelope(envelope)
                    .validation(validation)
                    .analysis(analyzeResponse)
                    .groundingCheck(groundingReport)
                    .confidence(confidence)
                    .preflight(preflight)
                    .durationMs(durationMs)
                    .build();
        } catch (RuleGenerationException e) {
            try { auditService.recordFailure("GENERATE", null, request.getRuleType(), e.getMessage()); } catch (Exception ignored) {}
            throw e;
        } catch (Exception e) {
            try { auditService.recordFailure("GENERATE", null, request.getRuleType(), e.getMessage()); } catch (Exception ignored) {}
            throw new RuleGenerationException("生成失敗：" + e.getMessage(), e);
        }
    }

    /** 向後相容：僅回傳 RuleEnvelope（供 MCP Tools 使用） */
    public RuleEnvelope generate(GenerateRequest request) {
        return generateFull(request).getEnvelope();
    }

    // ========================================================================
    //  VALIDATE（設計文件 §4 UC-02）
    // ========================================================================

    /**
     * 驗證 RuleEnvelope JSON。
     * 一律回傳 ValidateResponse，用 valid=false 表達驗證失敗（設計決策 §6.3）。
     */
    public ValidateResponse validate(ValidateRequest request) {
        try {
            JsonNode ruleJson = request.getRuleJson();

            if (ruleJson == null || ruleJson.isNull()) {
                return ValidateResponse.builder()
                        .valid(false)
                        .errors(List.of(err("INVALID_JSON", "輸入的 JSON 為空或無法解析")))
                        .build();
            }

            String typeStr = resolveTypeFromRequest(request, ruleJson);
            if (typeStr == null) {
                return ValidateResponse.builder()
                        .valid(false)
                        .errors(List.of(err(ErrorCodes.MISSING_FIELD,
                                "無法判斷規則型態。請在請求中指定 ruleType 或確認 JSON 中包含 ruleType 欄位")))
                        .build();
            }

            ValidateResponse result = doValidate(ruleJson, typeStr);

            log.info("validate END | valid={} | errorCount={} | errorCodes={}",
                    result.isValid(),
                    result.getErrors() != null ? result.getErrors().size() : 0,
                    result.getErrors() != null
                            ? result.getErrors().stream().map(ValidationError::getCode).distinct().toList()
                            : "[]");

            return result;
        } catch (Exception e) {
            log.error("validate ERROR | error={}", e.getMessage(), e);
            return ValidateResponse.builder()
                    .valid(false)
                    .errors(List.of(err("RUNTIME_ERROR", "驗證過程發生例外：" + e.getMessage())))
                    .build();
        }
    }

    // ========================================================================
    //  ANALYZE（DECISIONS Q15/Q16 — 獨立 DMN 分析端點）
    // ========================================================================

    public AnalyzeResponse analyze(AnalyzeRequest request) {
        log.info("analyze START | ruleType={}", request.getRuleType());

        JsonNode ruleJson = request.getRuleJson();
        if (ruleJson == null || ruleJson.isNull()) {
            log.warn("analyze SKIP | ruleJson is null");
            return emptyAnalyzeResponse();
        }

        try {
            // 根據 ruleType 路由到不同的分析引擎
            AnalysisResult result;
            String ruleType = request.getRuleType();
            if (ruleType == null && ruleJson.has("ruleType")) {
                ruleType = ruleJson.get("ruleType").asText();
            }

            if ("DecisionTree".equalsIgnoreCase(ruleType)) {
                result = treeAnalyzer.analyze(ruleJson);
            } else if ("ScoreCard".equalsIgnoreCase(ruleType)) {
                result = scoreCardAnalyzer.analyze(ruleJson);
            } else {
                result = dmnAnalyzer.analyze(ruleJson);
            }

            log.info("analyze END | coverageRate={} | gaps={} | overlaps={} | simplifications={}",
                    result.getCoverageRate(),
                    result.getGaps() != null ? result.getGaps().size() : 0,
                    result.getOverlaps() != null ? result.getOverlaps().size() : 0,
                    result.getSimplifications() != null ? result.getSimplifications().size() : 0);

            return toAnalyzeResponse(result);
        } catch (Exception e) {
            log.error("analyze ERROR | error={}", e.getMessage(), e);
            return emptyAnalyzeResponse();
        }
    }

    // ========================================================================
    //  RECOMMEND（設計文件 §4 UC-03 - Phase 2）
    // ========================================================================

    public RecommendResponse recommend(RecommendRequest request) {
        log.info("recommend START | description.length={}",
                request.getDescription() != null ? request.getDescription().length() : 0);
        RecommendResponse response = recommender.recommend(request.getDescription());
        log.info("recommend END | type={} | confidence={}",
                response.getRecommendedRuleType(), response.getConfidence());
        return response;
    }

    // ========================================================================
    //  TEST-RUN（計畫書 Phase 1：10 筆案例跑通）
    // ========================================================================

    public TestRunResponse testRun(TestRunRequest request) {
        log.info("testRun START | cases={}", request.getTestCases().size());

        List<TestCaseResult> results = new ArrayList<>();
        Map<String, Integer> errorBreakdown = new LinkedHashMap<>();
        int passed = 0;

        for (TestCase tc : request.getTestCases()) {
            // 客戶端逾時後 asyncLlm 會 cancel(true) — 在 case 邊界響應中斷，停止燒 token
            if (Thread.currentThread().isInterrupted()) {
                log.warn("testRun INTERRUPTED | 客戶端已逾時/取消，於第 {}/{} 案中止",
                        results.size(), request.getTestCases().size());
                break;
            }
            long start = System.currentTimeMillis();
            TestCaseResult.TestCaseResultBuilder rb = TestCaseResult.builder().id(tc.getId());

            try {
                RuleEnvelope envelope = generate(GenerateRequest.builder()
                        .description(tc.getDescription())
                        .ruleType(tc.getExpectedRuleType() != null ? tc.getExpectedRuleType() : "DecisionTable")
                        .build());

                rb.jsonParseable(true);
                rb.recommendedType(envelope.getRuleType());
                rb.typeMatchExpected(tc.getExpectedRuleType() == null
                        || tc.getExpectedRuleType().equalsIgnoreCase(envelope.getRuleType()));

                RuleType ruleType = RuleType.fromString(
                        envelope.getRuleType() != null ? envelope.getRuleType() : "DecisionTable");
                ValidateResponse vr = doValidateEnvelope(envelope, ruleType);

                rb.schemaValid(vr.isValid());
                rb.errors(vr.getErrors());

                boolean casePassed = vr.isValid() == tc.isExpectValid();
                rb.passed(casePassed);
                if (casePassed) passed++;

                if (vr.getErrors() != null) {
                    for (ValidationError e : vr.getErrors()) {
                        errorBreakdown.merge(e.getCode(), 1, Integer::sum);
                    }
                }
            } catch (Exception e) {
                // 中斷（cancel）會以 InterruptedException 形式從 LLM 呼叫冒出且旗標被清除 —
                // 恢復旗標讓迴圈頂部的檢查中止剩餘 case，而不是當成單一 case 失敗繼續跑
                if (isInterruption(e)) {
                    Thread.currentThread().interrupt();
                }
                rb.passed(false).jsonParseable(false).schemaValid(false);
                rb.errors(List.of(err("RUNTIME_ERROR", e.getMessage())));
                errorBreakdown.merge("RUNTIME_ERROR", 1, Integer::sum);
            }

            rb.durationMs(System.currentTimeMillis() - start);
            results.add(rb.build());
        }

        int total = request.getTestCases().size();
        double passRate = total > 0 ? Math.round((double) passed / total * 10000.0) / 100.0 : 0;

        log.info("testRun END | total={} | passed={} | passRate={}% | errorBreakdown={}",
                total, passed, passRate, errorBreakdown);

        return TestRunResponse.builder()
                .total(total).passed(passed).failed(total - passed)
                .passRate(passRate).errorBreakdown(errorBreakdown).results(results)
                .build();
    }

    /** 判斷例外鏈中是否含中斷（執行緒被 cancel(true) 打斷時，下游常包裝後重拋） */
    private static boolean isInterruption(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    // ========================================================================
    //  CONVERT（Phase 3：DecisionTree ↔ DecisionTable 雙向轉換）
    // ========================================================================

    /**
     * 將 RuleEnvelope 轉換為指定的目標規則型態。
     *
     * 支援的轉換：
     * - DecisionTree → DecisionTable（路徑展平）
     * - DecisionTable → DecisionTree（ID3 資訊增益分裂）
     *
     * @param ruleJson   原始 RuleEnvelope JSON
     * @param targetType 目標型態（"DecisionTable" 或 "DecisionTree"）
     * @return 轉換後的 RuleEnvelope
     */
    public RuleEnvelope convert(JsonNode ruleJson, String targetType) {
        log.info("convert START | targetType={}", targetType);

        try {
            RuleEnvelope source = objectMapper.treeToValue(ruleJson, RuleEnvelope.class);
            String sourceType = source.getRuleType();

            if (sourceType == null) {
                throw new RuleGenerationException("來源 JSON 缺少 ruleType");
            }

            RuleEnvelope result;
            if ("DecisionTree".equalsIgnoreCase(sourceType) && "DecisionTable".equalsIgnoreCase(targetType)) {
                result = treeToTableConverter.convert(source);
            } else if ("DecisionTable".equalsIgnoreCase(sourceType) && "DecisionTree".equalsIgnoreCase(targetType)) {
                result = tableToTreeConverter.convert(source);
            } else if (sourceType.equalsIgnoreCase(targetType)) {
                log.info("convert: 來源與目標型態相同（{}），直接回傳", sourceType);
                return source;
            } else {
                throw new RuleGenerationException(
                        "不支援的轉換：" + sourceType + " → " + targetType);
            }

            // 確保版本號
            if (result.getSchemaVersion() == null) result.setSchemaVersion(schemaVersion);
            if (result.getPromptVersion() == null) result.setPromptVersion(promptVersion);

            log.info("convert END | {} → {} 成功", sourceType, targetType);
            return result;
        } catch (RuleGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuleGenerationException("轉換失敗：" + e.getMessage(), e);
        }
    }

    // ========================================================================
    //  Internal — DMN Analysis helpers
    // ========================================================================

    private void enrichWithTreeAnalysis(RuleEnvelope envelope, JsonNode resultNode) {
        try {
            AnalysisResult analysis = treeAnalyzer.analyze(resultNode);

            if (envelope.getEvaluation() == null) {
                envelope.setEvaluation(RuleEnvelope.Evaluation.builder().build());
            }

            RuleEnvelope.Evaluation eval = envelope.getEvaluation();
            eval.setGaps(analysis.getGaps());
            eval.setOverlaps(analysis.getOverlaps());
            eval.setSimplifications(analysis.getSimplifications());

            if (analysis.getCoverageRate() > 0) {
                eval.setCoverageRate(analysis.getCoverageRate());
            }

            log.info("enrichWithTreeAnalysis | coverageRate={} | gaps={} | simplifications={}",
                    analysis.getCoverageRate(),
                    analysis.getGaps() != null ? analysis.getGaps().size() : 0,
                    analysis.getSimplifications() != null ? analysis.getSimplifications().size() : 0);

        } catch (Exception e) {
            log.warn("enrichWithTreeAnalysis FAILED (graceful degradation) | error={}", e.getMessage());
        }
    }

    private void enrichWithAnalysis(RuleEnvelope envelope, JsonNode resultNode) {
        try {
            AnalysisResult analysis = dmnAnalyzer.analyze(resultNode);

            if (envelope.getEvaluation() == null) {
                envelope.setEvaluation(RuleEnvelope.Evaluation.builder().build());
            }

            RuleEnvelope.Evaluation eval = envelope.getEvaluation();
            eval.setGaps(analysis.getGaps());
            eval.setOverlaps(analysis.getOverlaps());
            eval.setSimplifications(analysis.getSimplifications());
            // 截斷透明度必須跟著分析結果走 — 否則 /generate 看不到 /analyze 會回報的警告。
            // 只在非空時設定：Evaluation 是 NON_NULL，空清單會讓所有舊回應多出空欄位
            if (analysis.getTruncatedRuleIds() != null && !analysis.getTruncatedRuleIds().isEmpty()) {
                eval.setTruncatedRuleIds(analysis.getTruncatedRuleIds());
            }

            if (analysis.getCoverageRate() > 0) {
                eval.setCoverageRate(analysis.getCoverageRate());
            }

            log.info("enrichWithAnalysis | coverageRate={} | gaps={} | overlaps={} | simplifications={}",
                    analysis.getCoverageRate(),
                    analysis.getGaps() != null ? analysis.getGaps().size() : 0,
                    analysis.getOverlaps() != null ? analysis.getOverlaps().size() : 0,
                    analysis.getSimplifications() != null ? analysis.getSimplifications().size() : 0);

        } catch (Exception e) {
            log.warn("enrichWithAnalysis FAILED (graceful degradation) | error={}", e.getMessage());
        }
    }

    /** 從已 enrich 過的 envelope.evaluation 中提取 AnalyzeResponse（避免重複計算） */
    private AnalyzeResponse buildAnalyzeFromEnvelope(RuleEnvelope envelope) {
        if (envelope.getEvaluation() == null) return emptyAnalyzeResponse();
        RuleEnvelope.Evaluation eval = envelope.getEvaluation();
        return AnalyzeResponse.builder()
                .coverageRate(eval.getCoverageRate())
                .gaps(eval.getGaps() != null
                        ? eval.getGaps().stream().map(g -> GapInfo.builder()
                                .conditions(g.getConditions())
                                .message(g.getMessage())
                                .build())
                            .collect(Collectors.toList())
                        : Collections.emptyList())
                .overlaps(eval.getOverlaps() != null
                        ? eval.getOverlaps().stream().map(o -> OverlapInfo.builder()
                                .ruleIds(o.getRuleIds())
                                .intersection(o.getIntersection())
                                .message(o.getMessage())
                                .build())
                            .collect(Collectors.toList())
                        : Collections.emptyList())
                .simplifications(eval.getSimplifications() != null
                        ? eval.getSimplifications().stream().map(s -> SimplificationHint.builder()
                                .ruleIds(s.getRuleIds())
                                .suggestion(s.getSuggestion())
                                .build())
                            .collect(Collectors.toList())
                        : Collections.emptyList())
                .truncatedRuleIds(eval.getTruncatedRuleIds() != null
                        ? eval.getTruncatedRuleIds() : Collections.emptyList())
                .build();
    }

    private AnalyzeResponse toAnalyzeResponse(AnalysisResult result) {
        return AnalyzeResponse.builder()
                .coverageRate(result.getCoverageRate())
                .gaps(result.getGaps() != null
                        ? result.getGaps().stream().map(g -> GapInfo.builder()
                                .conditions(g.getConditions())
                                .message(g.getMessage())
                                .build())
                            .collect(Collectors.toList())
                        : Collections.emptyList())
                .overlaps(result.getOverlaps() != null
                        ? result.getOverlaps().stream().map(o -> OverlapInfo.builder()
                                .ruleIds(o.getRuleIds())
                                .intersection(o.getIntersection())
                                .message(o.getMessage())
                                .build())
                            .collect(Collectors.toList())
                        : Collections.emptyList())
                .simplifications(result.getSimplifications() != null
                        ? result.getSimplifications().stream().map(s -> SimplificationHint.builder()
                                .ruleIds(s.getRuleIds())
                                .suggestion(s.getSuggestion())
                                .build())
                            .collect(Collectors.toList())
                        : Collections.emptyList())
                // AnalysisResult 有 @Builder.Default、production 只經 builder 建構 — 不會是 null；
                // AnalyzeResponse 也有 @Builder.Default，未設定時自帶空清單
                .truncatedRuleIds(result.getTruncatedRuleIds())
                .build();
    }

    private AnalyzeResponse emptyAnalyzeResponse() {
        return AnalyzeResponse.builder()
                .coverageRate(0.0)
                .gaps(Collections.emptyList())
                .overlaps(Collections.emptyList())
                .simplifications(Collections.emptyList())
                .build();
    }

    // ========================================================================
    //  Internal — Validation helpers
    // ========================================================================

    /**
     * 型別安全的驗證方法 — 直接接受 RuleEnvelope。
     * 內部透過 RuleValidator.validate(RuleEnvelope) 委派。
     */
    private ValidateResponse doValidateEnvelope(RuleEnvelope envelope, RuleType type) {
        RuleValidator validator = registry.getValidator(type).orElse(null);
        if (validator == null) {
            return ValidateResponse.builder()
                    .valid(false)
                    .errors(List.of(err(ErrorCodes.MISSING_FIELD,
                            "型態 \"" + type.getCode() + "\" 的 Validator 尚未註冊")))
                    .build();
        }

        List<ValidationError> errors = validator.validate(envelope);
        return ValidateResponse.builder()
                .valid(isValid(errors))
                .errors(errors)
                .build();
    }

    private ValidateResponse doValidate(JsonNode ruleJson, String typeStr) {
        RuleType type;
        try {
            type = RuleType.fromString(typeStr);
        } catch (IllegalArgumentException e) {
            return ValidateResponse.builder()
                    .valid(false)
                    .errors(List.of(err(ErrorCodes.MISSING_FIELD, "不支援的規則型態：" + typeStr)))
                    .build();
        }

        RuleValidator validator = registry.getValidator(type).orElse(null);
        if (validator == null) {
            return ValidateResponse.builder()
                    .valid(false)
                    .errors(List.of(err(ErrorCodes.MISSING_FIELD,
                            "型態 \"" + type.getCode() + "\" 的 Validator 尚未註冊")))
                    .build();
        }

        List<ValidationError> errors = validator.validate(ruleJson);
        return ValidateResponse.builder()
                .valid(isValid(errors))
                .errors(errors)
                .build();
    }

    private RuleType resolveRuleType(GenerateRequest request) {
        if (request.getRuleType() != null && !request.getRuleType().isBlank()) {
            return RuleType.fromString(request.getRuleType());
        }
        return RuleType.DECISION_TABLE;
    }

    private String resolveTypeFromRequest(ValidateRequest request, JsonNode ruleJson) {
        if (request.getRuleType() != null && !request.getRuleType().isBlank()) {
            return request.getRuleType();
        }
        if (ruleJson.has("ruleType")) {
            return ruleJson.get("ruleType").asText();
        }
        return null;
    }

    private ValidationError err(String code, String message) {
        return ValidationError.builder().code(code).message(message).build();
    }

    /**
     * v3.15：valid 只看致命錯誤。severity=null 或 "ERROR" 視為致命；
     * "WARNING"（如 REDUNDANT_RULE 冗餘規則提示）不影響 valid。
     */
    private boolean isValid(List<ValidationError> errors) {
        return errors.stream().noneMatch(e ->
                e.getSeverity() == null || "ERROR".equalsIgnoreCase(e.getSeverity()));
    }
}
