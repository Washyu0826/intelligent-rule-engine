package com.ruleengine.rules.controller;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.engine.ExecutionResult;
import com.ruleengine.rules.service.engine.RuleEngineRunner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Engine execution endpoint introduced for the 業務部 demo
 * (DEMO_PLAN_3H §3.3). Accepts a complete RuleEnvelope plus a map of input
 * values, then delegates to a {@link RuleEngineRunner} bean (Spring autowires
 * {@code MockGroupEngine} by default) and returns the typed
 * {@link ExecutionResult}.
 *
 * <p>This controller is intentionally separate from {@code ToolsController} so
 * the wider tools surface stays unchanged while the demo sub-agents converge.
 */
@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
@Slf4j
public class EngineExecuteController {

    private final RuleEngineRunner engine;

    @PostMapping("/execute")
    public ResponseEntity<ExecutionResult> execute(@Valid @RequestBody ExecuteRequest request) {
        int inputCount = request.inputValues() != null ? request.inputValues().size() : 0;
        log.info("POST /tools/execute | engine={} | inputCount={}", engine.engineName(), inputCount);
        ExecutionResult result = engine.evaluate(request.envelope(), request.inputValues());
        return ResponseEntity.ok(result);
    }

    /** Request body for {@code POST /tools/execute}. */
    public record ExecuteRequest(
            @NotNull RuleEnvelope envelope,
            @NotNull Map<String, Object> inputValues
    ) {}
}
