package com.ruleengine.rules.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * RuleService 的 AOP 切面 — 統一處理 metrics、MDC traceId、計時。
 *
 * 將這些橫切關注點從 RuleService 業務邏輯中抽離，
 * 讓 RuleService 只需要關注核心業務流程。
 *
 * 支援的操作：generate, validate, analyze, recommend, testRun
 * 每個操作自動記錄：
 *   - Counter: rules.{operation}.total, .success, .fail
 *   - Timer:   rules.{operation}.duration
 *   - MDC:     traceId（16 字元 UUID）
 */
@Aspect
@Component
@Slf4j
public class RuleServiceMetricsAspect {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> totalCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> successCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> failCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public RuleServiceMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 攔截 RuleService 的所有 public 方法，自動加上 metrics + traceId + 計時。
     */
    @Around("execution(public * com.ruleengine.rules.service.RuleService.*(..))")
    public Object instrumentRuleService(ProceedingJoinPoint pjp) throws Throwable {
        String methodName = pjp.getSignature().getName();
        String metricsKey = mapMethodToMetricsKey(methodName);

        // 設定 MDC traceId
        String traceId = newTraceId();
        MDC.put("traceId", traceId);

        // 計數
        getOrCreateCounter(metricsKey, "total").increment();

        long startMs = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();

            // 成功
            getOrCreateCounter(metricsKey, "success").increment();
            return result;

        } catch (Throwable e) {
            // 失敗
            getOrCreateCounter(metricsKey, "fail").increment();
            throw e;

        } finally {
            // 計時
            long durationMs = System.currentTimeMillis() - startMs;
            getOrCreateTimer(metricsKey).record(durationMs, TimeUnit.MILLISECONDS);

            log.debug("RuleService.{} | traceId={} | durationMs={}", methodName, traceId, durationMs);
            MDC.remove("traceId");
        }
    }

    // ================================================================
    // 內部方法
    // ================================================================

    /**
     * 將方法名稱映射為 metrics key。
     * generate → generate, validate → validate, analyze → analyze,
     * recommend → recommend, testRun → testRun
     */
    private String mapMethodToMetricsKey(String methodName) {
        return switch (methodName) {
            case "generate" -> "generate";
            case "validate" -> "validate";
            case "analyze" -> "analyze";
            case "recommend" -> "recommend";
            case "testRun" -> "testRun";
            default -> methodName;
        };
    }

    private Counter getOrCreateCounter(String operation, String suffix) {
        String key = operation + "." + suffix;
        return totalCounters.computeIfAbsent(key,
                k -> meterRegistry.counter("rules." + k));
    }

    private Timer getOrCreateTimer(String operation) {
        return timers.computeIfAbsent(operation,
                k -> meterRegistry.timer("rules." + k + ".duration"));
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
