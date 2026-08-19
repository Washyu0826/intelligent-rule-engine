package com.ruleengine.rules.controller;

import com.ruleengine.rules.domain.dto.ToolDtos.*;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.RuleService;
import com.ruleengine.rules.service.audit.AuditService;
import com.ruleengine.rules.service.optimizer.TreeOptimizer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Tools REST API（計畫書 §6.4 / 設計文件 §4）
 *
 * Phase 1 端點：
 * - POST /tools/generate  → RuleEnvelope
 * - POST /tools/validate  → ValidateResponse（一律 HTTP 200）
 *
 * Phase 2 端點：
 * - POST /tools/recommend → RecommendResponse
 * - POST /tools/test-run  → TestRunResponse
 * - POST /tools/analyze   → AnalyzeResponse（DMN 幾何分析）
 *
 * 設計決策（§6.3）：validate 回傳一律 HTTP 200，用 valid=false 表達驗證失敗，
 * 因為驗證結果是業務回傳，不是系統異常。
 *
 * v2.0.0: 加入 @Validated + Bean Validation，搭配 GlobalExceptionHandler 回報驗證錯誤。
 */
@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
@Validated
@Slf4j
@io.swagger.v3.oas.annotations.tags.Tag(name = "Rules Tools", description = "規則生成、驗證、分析、建議、查詢 API")
public class ToolsController {

    private final RuleService ruleService;
    private final TreeOptimizer treeOptimizer;
    private final AuditService auditService;
    private final com.ruleengine.rules.service.InputSuggestionService inputSuggestionService;
    private final com.ruleengine.rules.service.RuleLookupService ruleLookupService;
    private final com.ruleengine.rules.service.ScenarioExpansionService scenarioExpansionService;
    private final com.ruleengine.rules.service.llm.LlmProviderRegistry llmProviderRegistry;
    private final com.ruleengine.rules.service.evaluator.LlmJudgeEvaluator llmJudgeEvaluator;
    private final com.ruleengine.rules.service.evaluator.RuleExplanationService ruleExplanationService;
    private final com.ruleengine.rules.service.narrative.BusinessNarrativeService businessNarrativeService;
    private final com.ruleengine.rules.service.optimizer.v2.TreeOptimizerV2 treeOptimizerV2;
    private final com.ruleengine.rules.service.exporter.InternalEngineExporter internalEngineExporter;
    private final com.ruleengine.rules.service.glossary.GlossaryService glossaryService;
    private final com.ruleengine.rules.service.PreflightService preflightService;
    private final com.ruleengine.rules.service.diff.TreeDiffService treeDiffService;
    private final com.ruleengine.rules.service.diff.RuleDiffService ruleDiffService;
    private final com.ruleengine.rules.service.TreePathService treePathService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    /** AsyncExecutorConfig 提供；同型別兩顆 bean 依建構子參數名對應 */
    private final java.util.concurrent.ExecutorService sseExecutor;
    private final java.util.concurrent.ExecutorService llmExecutor;

    /** 逾時公式集中於 LlmTimeoutPolicy（REST 與 MCP 入口共用） */
    private final com.ruleengine.rules.config.LlmTimeoutPolicy llmTimeoutPolicy;

    private long generateAsyncTimeoutMs() {
        return llmTimeoutPolicy.singleCallMs();
    }

    /**
     * UC-01：生成 DecisionTable JSON（完整回應：envelope + validation + analysis）
     * v2.2.0: 一次回傳所有結果，前端不需再分別呼叫 validate + analyze
     * v3.12: 套用 Resilience4j RateLimiter（每分鐘 10 次，保護下游 LLM API）
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "生成規則", description = "將自然語言描述轉換為 RuleEnvelope JSON，含自動驗證與分析；每分鐘最多 10 次呼叫")
    @PostMapping("/generate")
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(
            name = "generate", fallbackMethod = "generateRateLimited")
    public DeferredResult<ResponseEntity<GenerateResponse>>
    generate(@Valid @RequestBody GenerateRequest request) {
        log.info("POST /tools/generate | ruleType={}", request.getRuleType());
        return asyncLlm("POST /tools/generate",
                () -> ResponseEntity.ok(ruleService.generateFull(request)));
    }

    /**
     * v3.16: LLM 長工作共用非同步包裝 — LLM 呼叫最長 300s，必須移出 Tomcat worker
     * （DeferredResult + 專用有界池），避免少量併發佔滿請求執行緒導致全站排隊。
     * 所有 LLM 端點（generate / test-run / explain / narrate / evaluate）共用此 helper：
     *   - 逾時 = rules.llm.timeout-seconds + 60s 緩衝 → 504
     *   - llmExecutor 滿載 → 429 + Retry-After（backpressure 顯式化）
     *   - 業務例外 → setErrorResult 交由 GlobalExceptionHandler 統一格式化
     */
    private <T> DeferredResult<ResponseEntity<T>>
    asyncLlm(String endpoint, java.util.function.Supplier<ResponseEntity<T>> work) {
        return asyncLlm(endpoint, generateAsyncTimeoutMs(), work);
    }

    /** 逾時可指定版本 — 成本與 case 數成正比的端點（如 /test-run）需要更長的上限。 */
    private <T> DeferredResult<ResponseEntity<T>>
    asyncLlm(String endpoint, long timeoutMs, java.util.function.Supplier<ResponseEntity<T>> work) {
        var result = new DeferredResult<ResponseEntity<T>>(timeoutMs);
        var started = new java.util.concurrent.atomic.AtomicBoolean(false);
        var futureRef = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        result.onTimeout(() -> {
            // 取消執行中的工作（interrupt）— 否則 worker 在客戶端拿到 504 後仍跑到完，白耗 token 且佔住池
            var future = futureRef.get();
            if (future != null) future.cancel(true);
            if (started.get()) {
                result.setErrorResult(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                        "處理逾時（" + timeoutMs / 1000 + " 秒），請簡化輸入或稍後重試"));
            } else {
                // 還在佇列就過期 = 容量飽和，不是輸入太複雜 — 回 429 別誤導使用者改輸入
                log.warn("{} | 請求在佇列中逾時（未開始處理），以 429 回報容量飽和", endpoint);
                result.setErrorResult(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "伺服器忙碌中，請求尚未開始處理即逾時，請稍後重試"));
            }
        });
        try {
            futureRef.set(llmExecutor.submit(() -> {
                // 客戶端已逾時/斷線的排隊任務不再開工 — 避免重試堆積殭屍 LLM 工作白耗 token
                if (result.isSetOrExpired()) {
                    log.info("{} | 結果已逾時或取消，略過排隊中的工作", endpoint);
                    return;
                }
                started.set(true);
                try {
                    result.setResult(work.get());
                } catch (Exception e) {
                    if (result.isSetOrExpired()) {
                        log.info("{} | 客戶端已逾時，丟棄被中斷工作的例外：{}", endpoint, e.getMessage());
                    } else {
                        result.setErrorResult(e);
                    }
                }
            }));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("{} | LLM 工作池滿載，拒絕新請求", endpoint);
            result.setResult(ResponseEntity
                    .status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "30")
                    .build());
        }
        return result;
    }

    /**
     * RateLimiter fallback — 觸發限流時回 429。
     * resilience4j 透過 reflection 呼叫此方法，簽名須與原方法一致並多一個 Throwable 參數。
     */
    @SuppressWarnings("unused")
    private DeferredResult<ResponseEntity<GenerateResponse>>
    generateRateLimited(GenerateRequest request, Throwable t) {
        log.warn("POST /tools/generate | rate-limited | ruleType={}", request != null ? request.getRuleType() : "?");
        var result = new DeferredResult<ResponseEntity<GenerateResponse>>();
        result.setResult(ResponseEntity
                .status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(GenerateResponse.builder().build()));
        return result;
    }

    /**
     * v2.2.0: SSE <b>進度回報 + 最終結果</b>（review 修復輪更正文案）。
     *
     * <p>工程事實：這不是 token 級串流 —— generate 階段呼叫的是同步的
     * {@code generateFull()}，LLM 生成期間無 token 事件；step 事件是「任務進度
     * 狀態機」（其中 validate/analyze 兩步是 generateFull 完成後的補發）。
     * 真打字機效果需 ResponseBodyEmitter 對接 OllamaService.generateRuleJsonStreaming()
     * （已實作、未接線）—— 記 backlog，對外文件一律稱「進度回報」不稱「串流生成」。</p>
     */
    @PostMapping(value = "/generate/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter generateStream(
            @Valid @RequestBody GenerateRequest request) {
        log.info("POST /tools/generate/stream | ruleType={}", request.getRuleType());

        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(600_000L);

        Runnable streamTask = () -> {
            try {
                // Step 1: Recommend
                sendSseEvent(emitter, "step", "{\"step\":\"recommend\",\"status\":\"active\"}");
                var recommend = ruleService.recommend(
                        com.ruleengine.rules.domain.dto.ToolDtos.RecommendRequest.builder()
                                .description(request.getDescription()).build());
                sendSseEvent(emitter, "step", "{\"step\":\"recommend\",\"status\":\"done\"}");

                // Step 2: Generate with streaming
                sendSseEvent(emitter, "step", "{\"step\":\"generate\",\"status\":\"active\"}");
                var fullResponse = ruleService.generateFull(request);
                sendSseEvent(emitter, "step", "{\"step\":\"generate\",\"status\":\"done\"}");

                // Step 3: Validate（generateFull 已執行，補發 active 讓前端 5 格進度依序亮）
                sendSseEvent(emitter, "step", "{\"step\":\"validate\",\"status\":\"active\"}");
                Thread.sleep(200);
                sendSseEvent(emitter, "step", "{\"step\":\"validate\",\"status\":\"done\"}");

                // Step 4: Analyze（同上）
                sendSseEvent(emitter, "step", "{\"step\":\"analyze\",\"status\":\"active\"}");
                Thread.sleep(200);
                sendSseEvent(emitter, "step", "{\"step\":\"analyze\",\"status\":\"done\"}");

                // Final result
                String resultJson = objectMapper.writeValueAsString(fullResponse);
                sendSseEvent(emitter, "result", resultJson);
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE generate/stream error: {}", e.getMessage());
                try {
                    // objectMapper 序列化處理所有 JSON 跳脫（反斜線/換行/控制字元）；手動 replace 只蓋得住引號
                    String message = e.getMessage() != null ? e.getMessage() : e.toString();
                    sendSseEvent(emitter, "error",
                            objectMapper.writeValueAsString(java.util.Map.of("message", message)));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        };
        try {
            sseExecutor.submit(streamTask);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("POST /tools/generate/stream | SSE 併發已滿，拒絕新串流");
            sendSseEvent(emitter, "error", "{\"message\":\"伺服器忙碌中，請稍後重試\"}");
            emitter.complete();
        }

        return emitter;
    }

    private void sendSseEvent(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
                               String eventName, String data) {
        try {
            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (Exception e) {
            log.debug("SSE send failed: {}", e.getMessage());
        }
    }

    /**
     * UC-02：格式驗證，11 個錯誤碼。一律 HTTP 200。
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(@Valid @RequestBody ValidateRequest request) {
        log.info("POST /tools/validate | ruleType={}", request.getRuleType());
        return ResponseEntity.ok(ruleService.validate(request));
    }

    /**
     * UC-03：規則型態推薦（Phase 2）
     */
    @PostMapping("/recommend")
    public ResponseEntity<RecommendResponse> recommend(@Valid @RequestBody RecommendRequest request) {
        log.info("POST /tools/recommend");
        return ResponseEntity.ok(ruleService.recommend(request));
    }

    /**
     * 批次測試
     */
    @PostMapping("/test-run")
    public DeferredResult<ResponseEntity<TestRunResponse>>
    testRun(@RequestBody(required = false) TestRunRequest request) {
        if (request == null || request.getTestCases() == null || request.getTestCases().isEmpty()) {
            var bad = new DeferredResult<ResponseEntity<TestRunResponse>>();
            bad.setResult(ResponseEntity.badRequest().build());
            return bad;
        }
        log.info("POST /tools/test-run | cases={}", request.getTestCases().size());
        // v3.16: 每個 case 內部都會走 generate（可能打 LLM），N case 串行最長 N×timeout — 必須離開 Tomcat worker。
        // 逾時按 case 數縮放（上限可設定，預設 30 分鐘）；超限被 504 時 onTimeout 會 cancel(true)，
        // testRun 迴圈檢查中斷旗標提早中止，不會白跑到完
        return asyncLlm("POST /tools/test-run", llmTimeoutPolicy.asyncTimeoutMs(request.getTestCases().size()),
                () -> ResponseEntity.ok(ruleService.testRun(request)));
    }

    /**
     * DMN 幾何分析（v1.4+ DECISIONS Q15/Q16）
     */
    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(@RequestBody AnalyzeRequest request) {
        log.info("POST /tools/analyze | ruleType={}", request.getRuleType());
        return ResponseEntity.ok(ruleService.analyze(request));
    }

    /**
     * Phase 3：DecisionTree ↔ DecisionTable 雙向轉換。
     */
    @PostMapping("/convert")
    public ResponseEntity<com.ruleengine.rules.domain.envelope.RuleEnvelope> convert(
            @Valid @RequestBody ConvertRequest request) {
        log.info("POST /tools/convert | targetType={}", request.getTargetType());
        // deepCopy 取代序列化往返：隔離輸入、避免下游就地修改影響呼叫端
        com.fasterxml.jackson.databind.JsonNode ruleJson = request.getRuleJson().deepCopy();
        return ResponseEntity.ok(ruleService.convert(ruleJson, request.getTargetType()));
    }

    // ================================================================
    // GET /tools/providers — 可用的 LLM Provider 列表
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(summary = "LLM Provider 列表", description = "取得所有可用的 LLM Provider 名稱與狀態")
    @GetMapping("/providers")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> getProviders() {
        return ResponseEntity.ok(llmProviderRegistry.getAllProviderInfo());
    }

    // ================================================================
    // POST /tools/suggest — 輸入建議（產品化 Sprint 1）
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(summary = "輸入建議", description = "分析描述的完整性，偵測維度、建議缺失欄位、計算品質分數")
    @PostMapping("/suggest")
    public ResponseEntity<com.ruleengine.rules.service.InputSuggestionService.SuggestResponse> suggest(
            @Valid @RequestBody SuggestRequest request) {
        log.info("POST /tools/suggest | description.length={}", request.getDescription().length());
        return ResponseEntity.ok(inputSuggestionService.analyze(request.getDescription()));
    }

    // ================================================================
    // POST /tools/preflight — 生成前輸入偵測（紅黃綠燈，不阻擋、不打 LLM）
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(summary = "生成前偵測",
            description = "在按生成前即時偵測輸入是否不完整或有邏輯矛盾（純符號、0 token）；回 findings 與完整度供前端顯示")
    @PostMapping("/preflight")
    public ResponseEntity<com.ruleengine.rules.service.PreflightService.PreflightReport> preflight(
            @Valid @RequestBody SuggestRequest request) {
        log.info("POST /tools/preflight | description.length={}",
                request.getDescription() != null ? request.getDescription().length() : 0);
        return ResponseEntity.ok(preflightService.report(request.getDescription()));
    }

    // ================================================================
    // POST /tools/diff-tree — DecisionTree 語意結構 diff（與舊版比對）
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(summary = "決策樹語意比對",
            description = "用樹編輯距離比對兩個 DecisionTree，回報新增/刪除/修改的節點（語意層，不受 nodeId 改名或分支重排干擾）")
    @PostMapping("/diff-tree")
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "diff")
    public ResponseEntity<com.ruleengine.rules.service.diff.TreeDiffService.TreeDiffResult> diffTree(
            @Valid @RequestBody DiffTreeRequest request) {
        log.info("POST /tools/diff-tree | beforeType={} afterType={}",
                request.getBefore() != null ? request.getBefore().getRuleType() : "null",
                request.getAfter() != null ? request.getAfter().getRuleType() : "null");
        return ResponseEntity.ok(treeDiffService.diff(request.getBefore(), request.getAfter()));
    }

    // ================================================================
    // POST /tools/diff-table — DecisionTable 行為比對（超矩形集合差，回歸偵測）
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(summary = "決策表行為比對",
            description = "用超矩形集合差比對兩個 DecisionTable，找出回歸(漏覆蓋)、決策改變、新增覆蓋的輸入組合")
    @PostMapping("/diff-table")
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "diff")
    public ResponseEntity<com.ruleengine.rules.service.diff.RuleDiffService.TableDiffResult> diffTable(
            @Valid @RequestBody DiffTreeRequest request) {
        log.info("POST /tools/diff-table | beforeType={} afterType={}",
                request.getBefore() != null ? request.getBefore().getRuleType() : "null",
                request.getAfter() != null ? request.getAfter().getRuleType() : "null");
        return ResponseEntity.ok(ruleDiffService.diff(request.getBefore(), request.getAfter()));
    }

    // ================================================================
    // POST /tools/diff-rules — DecisionTable 結構 diff（規則列對齊，不靠 ruleId）
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(summary = "決策表結構比對",
            description = "依條件簽章對齊兩個 DecisionTable 的規則列，回報新增/刪除/決策改變的規則（不受 ruleId 改名干擾）")
    @PostMapping("/diff-rules")
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "diff")
    public ResponseEntity<com.ruleengine.rules.service.diff.RuleDiffService.RuleSetDiffResult> diffRules(
            @Valid @RequestBody DiffTreeRequest request) {
        log.info("POST /tools/diff-rules | beforeType={} afterType={}",
                request.getBefore() != null ? request.getBefore().getRuleType() : "null",
                request.getAfter() != null ? request.getAfter().getRuleType() : "null");
        return ResponseEntity.ok(ruleDiffService.structuralDiff(request.getBefore(), request.getAfter()));
    }

    // ================================================================
    // POST /tools/tree-paths — 決策樹路徑展開（每條 root→leaf 一句白話規則）
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(summary = "決策樹路徑展開",
            description = "把 DecisionTree 每條 root→leaf 路徑展開成一句白話規則，讓非技術使用者逐條檢視判斷")
    @PostMapping("/tree-paths")
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "diff")
    public ResponseEntity<com.ruleengine.rules.service.TreePathService.TreePathsResult> treePaths(
            @Valid @RequestBody TreePathsRequest request) {
        log.info("POST /tools/tree-paths | ruleType={}",
                request.getEnvelope() != null ? request.getEnvelope().getRuleType() : "null");
        return ResponseEntity.ok(treePathService.extractPaths(request.getEnvelope()));
    }

    // ================================================================
    // POST /tools/lookup — 規則查詢（產品化 Sprint 1）
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(summary = "規則查詢", description = "輸入條件值，查找匹配的規則（支援 DecisionTable 和 DecisionTree）")
    @PostMapping("/lookup")
    public ResponseEntity<com.ruleengine.rules.service.RuleLookupService.LookupResponse> lookup(
            @Valid @RequestBody LookupRequest request) {
        log.info("POST /tools/lookup | inputValues.size={}", request.getInputValues().size());
        return ResponseEntity.ok(ruleLookupService.lookup(
                request.getEnvelope(), request.getInputValues()));
    }

    // ================================================================
    // POST /tools/scenario-expand
    // ================================================================
    @PostMapping("/scenario-expand")
    public ResponseEntity<ScenarioExpandResponse> scenarioExpand(
            @Valid @RequestBody ScenarioExpandRequest request) {
        log.info("POST /tools/scenario-expand | maxScenarios={}", request.getMaxScenarios());
        return ResponseEntity.ok(scenarioExpansionService.expand(request));
    }

    // ================================================================
    // POST /tools/export — engine-neutral RuleEnvelope → 集團規則引擎 stub
    // v3.12: 顯示 RuleExporter 介面與 adapter pre-flight 警告
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(
            summary = "匯出至下游規則引擎（stub）",
            description = "把 engine-neutral RuleEnvelope 透過 RuleExporter 介面轉成目標引擎部署 wrapper，"
                    + "回傳 content（示意 JSON）與 warnings（adapter 缺料 / 不支援 operator 等）。"
                    + "此為 demo stub，正式接入由各引擎多實作版本取代。")
    @PostMapping("/export")
    public ResponseEntity<com.ruleengine.rules.service.exporter.RuleExporter.ExportResult> export(
            @Valid @RequestBody RuleEnvelope envelope) {
        log.info("POST /tools/export | engine={}", internalEngineExporter.engineName());
        return ResponseEntity.ok(internalEngineExporter.export(envelope));
    }

    // ================================================================
    // POST /tools/optimize — DecisionTree 優化
    // ================================================================
    @PostMapping("/optimize")
    public ResponseEntity<OptimizeResponse> optimize(
            @RequestBody @Valid OptimizeRequest request) {
        log.info("POST /tools/optimize | aggressive={}", request.isAggressive());
        try {
            RuleEnvelope envelope = objectMapper.treeToValue(request.getRuleJson(), RuleEnvelope.class);

            TreeOptimizer.OptimizeResult result = treeOptimizer.optimize(envelope, request.isAggressive());

            return ResponseEntity.ok(OptimizeResponse.builder()
                    .optimized(result.getOptimized())
                    .nodesRemoved(result.getNodesRemoved())
                    .depthReduction(result.getDepthReduction())
                    .appliedOptimizations(result.getAppliedOptimizations())
                    .build());
        } catch (Exception e) {
            log.error("optimize ERROR: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // ================================================================
    // POST /tools/explain — 每條規則 rationale 產生（v3.9.0）
    //
    // 2024-2025 "Explaining rules with LLMs" / reason+verify pattern：
    //   為每條規則補上 1-2 句中文業務解釋，對應描述中的哪段需求。
    //   Opt-in（不自動跑在 /generate），延遲不受影響。
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(
            summary = "產生每條規則的中文解釋",
            description = "為 RuleEnvelope 中每條規則補上 1-2 句中文 rationale，對應描述中的業務理由")
    @PostMapping("/explain")
    public DeferredResult<ResponseEntity<ExplainResponse>>
    explain(@Valid @RequestBody ExplainRequest request) {
        log.info("POST /tools/explain | provider={} | rules={}",
                request.getProvider(),
                request.getEnvelope() != null && request.getEnvelope().getRule() != null
                        && request.getEnvelope().getRule().getRules() != null
                        ? request.getEnvelope().getRule().getRules().size() : 0);

        return asyncLlm("POST /tools/explain", () -> {
            long startMs = System.currentTimeMillis();
            int applied = ruleExplanationService.explainAndApply(
                    request.getDescription(), request.getEnvelope(), request.getProvider());
            return ResponseEntity.ok(ExplainResponse.builder()
                    .envelope(request.getEnvelope())
                    .rationalesApplied(applied)
                    .provider(request.getProvider())
                    .durationMs(System.currentTimeMillis() - startMs)
                    .build());
        });
    }

    // ================================================================
    // POST /tools/optimize/v2 — DecisionTree sparsity-aware pruning（v3.13）
    //
    // 在 v1 3-pass（merge leaves / dead branches / collapse single-child）之上，
    // 再跑 3 個新 pass：同構子樹合併、冗餘條件消除、局部重構。
    // 文獻依據：GOSDT (ICML 2020) sparsity objective + subtree isomorphism canonical form。
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(
            summary = "DecisionTree v2 sparsity 優化",
            description = "6-pass 管線：Pass 1-3（v1）+ 深度同構子樹合併 / 冗餘條件消除 / 局部重構。" +
                    "每 pass 後以 objective 比較,不退化為硬性保證")
    @PostMapping("/optimize/v2")
    public ResponseEntity<OptimizeResponseV2> optimizeV2(@Valid @RequestBody OptimizeRequestV2 request) {
        log.info("POST /tools/optimize/v2 | config={}", request.getConfig());
        try {
            RuleEnvelope envelope = objectMapper.treeToValue(request.getRuleJson(), RuleEnvelope.class);

            if (envelope == null || envelope.getRuleType() == null
                    || !"DecisionTree".equals(envelope.getRuleType())) {
                log.warn("optimize/v2: ruleType 非 DecisionTree");
                return ResponseEntity.badRequest().build();
            }

            var result = treeOptimizerV2.optimize(envelope, request.getConfig());
            return ResponseEntity.ok(OptimizeResponseV2.builder()
                    .optimized(result.getOptimized())
                    .metricsBefore(result.getMetricsBefore())
                    .metricsAfter(result.getMetricsAfter())
                    .sparsityScoreBefore(result.getSparsityScoreBefore())
                    .sparsityScoreAfter(result.getSparsityScoreAfter())
                    .appliedOptimizations(result.getAppliedOptimizations())
                    .passContributions(result.getPassContributions())
                    .durationMs(result.getDurationMs())
                    .build());
        } catch (Exception e) {
            log.error("optimize/v2 ERROR: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    // ================================================================
    // POST /tools/narrate — 整體業務敘事（v3.12）
    //
    // 為非工程使用者（保險業務／精算師）產出整張規則表的中文總覽：
    //   summary / highlights / coverage / exceptions / actuarialNote
    // 與 /tools/explain 互補（explain 針對每條規則；narrate 針對整張表）
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(
            summary = "產出整體業務敘事",
            description = "為整張規則表產生業務語言總覽（摘要／要點／涵蓋範圍／例外／精算師備註），供非工程使用者閱讀")
    @PostMapping("/narrate")
    public DeferredResult<ResponseEntity<com.ruleengine.rules.service.narrative.BusinessNarrativeService.BusinessNarrative>>
    narrate(@Valid @RequestBody NarrateRequest request) {
        log.info("POST /tools/narrate | provider={} | rules={}",
                request.getProvider(),
                request.getEnvelope() != null && request.getEnvelope().getRule() != null
                        && request.getEnvelope().getRule().getRules() != null
                        ? request.getEnvelope().getRule().getRules().size() : 0);
        return asyncLlm("POST /tools/narrate",
                () -> ResponseEntity.ok(businessNarrativeService.narrate(
                        request.getDescription(), request.getEnvelope(), request.getProvider())));
    }

    // ================================================================
    // POST /tools/evaluate — LLM-as-Judge 評估（v3.6.0 升級）
    //
    // 基於 2024-2025 研究（arxiv 2411.15594 / 2412.05579 / 2512.16041）：
    //   - 跨 provider judge 消除 self-enhancement bias
    //   - self-consistency 以不同 criteria 順序對抗 positional bias
    //   - bias warnings 回報可能的評分失真
    // ================================================================
    @io.swagger.v3.oas.annotations.Operation(
            summary = "LLM-as-Judge 評估",
            description = "以第二個 LLM 評估生成規則的 faithfulness/completeness/hallucination/consistency，支援跨 provider 與 self-consistency")
    @PostMapping("/evaluate")
    public DeferredResult<ResponseEntity<com.ruleengine.rules.service.evaluator.LlmJudgeEvaluator.JudgeResult>>
    evaluate(@Valid @RequestBody EvaluateRequest request) {
        log.info("POST /tools/evaluate | generator={} | selfConsistency={}",
                request.getGeneratorProvider(), request.getSelfConsistency());

        return asyncLlm("POST /tools/evaluate", () -> {
            boolean selfConsistency = Boolean.TRUE.equals(request.getSelfConsistency());
            var judgeResult = selfConsistency
                    ? llmJudgeEvaluator.evaluateWithSelfConsistency(
                            request.getDescription(), request.getEnvelope(), request.getGeneratorProvider())
                    : llmJudgeEvaluator.evaluate(
                            request.getDescription(), request.getEnvelope(), request.getGeneratorProvider());

            if (judgeResult == null) {
                return ResponseEntity.status(503).build();
            }
            return ResponseEntity.ok(judgeResult);
        });
    }

    // ================================================================
    // GET /tools/audit — 操作日誌查詢
    // ================================================================
    @GetMapping("/audit")
    public ResponseEntity<java.util.Map<String, Object>> getAuditLogs(
            @RequestParam(defaultValue = "50") int limit) {
        var logs = auditService.getRecentLogs(limit);
        var stats = auditService.getStats();
        return ResponseEntity.ok(java.util.Map.of(
                "logs", logs,
                "stats", stats
        ));
    }

    // ================================================================
    // GET /tools/audit/stats — 操作統計
    // ================================================================
    @GetMapping("/audit/stats")
    public ResponseEntity<java.util.Map<String, Object>> getAuditStats() {
        return ResponseEntity.ok(auditService.getStats());
    }

    // ================================================================
    // v3.14 Phase A — 領域字彙表（glossary）查詢
    // ================================================================

    /** GET /tools/glossary — 列出全部字彙；?category=核保 過濾類別；?q=保額 模糊搜尋 */
    @io.swagger.v3.oas.annotations.Operation(summary = "查詢字彙表", description = "v3.14：列出領域字彙；支援 category 過濾與 q 模糊搜尋")
    @GetMapping("/glossary")
    public ResponseEntity<java.util.List<com.ruleengine.rules.domain.glossary.GlossaryEntry>> listGlossary(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(glossaryService.search(q, category));
    }

    /** GET /tools/glossary/stats — 字彙表統計（總數、active/deprecated/proposed、各類別計數） */
    @io.swagger.v3.oas.annotations.Operation(summary = "字彙表統計", description = "v3.14：字彙表概況、用於 admin / dashboard")
    @GetMapping("/glossary/stats")
    public ResponseEntity<com.ruleengine.rules.service.glossary.GlossaryService.Stats> glossaryStats() {
        return ResponseEntity.ok(glossaryService.stats());
    }

    /** GET /tools/glossary/{id} — 取單一字彙；404 若不存在 */
    @io.swagger.v3.oas.annotations.Operation(summary = "取單一字彙", description = "v3.14：依 id 取得字彙；不存在回 404")
    @GetMapping("/glossary/{id}")
    public ResponseEntity<com.ruleengine.rules.domain.glossary.GlossaryEntry> getGlossaryEntry(@PathVariable String id) {
        return glossaryService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
