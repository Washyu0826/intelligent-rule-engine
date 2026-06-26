package com.ruleengine.rules.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalExceptionHandler 單元測試（聚焦限流 → 429 對映）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("RequestNotPermitted → 429 + Retry-After + RATE_LIMITED body")
    void rateLimitedMapsTo429() {
        RequestNotPermitted ex = RequestNotPermitted.createRequestNotPermitted(
                RateLimiter.ofDefaults("diff"));

        ResponseEntity<Map<String, Object>> resp = handler.handleRateLimited(ex);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        assertEquals("60", resp.getHeaders().getFirst("Retry-After"));
        assertNotNull(resp.getBody());
        assertEquals("RATE_LIMITED", resp.getBody().get("error"));
        assertNotNull(resp.getBody().get("timestamp"));
    }
}
