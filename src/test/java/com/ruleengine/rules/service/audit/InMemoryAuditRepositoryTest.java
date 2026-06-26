package com.ruleengine.rules.service.audit;

import com.ruleengine.rules.service.audit.AuditService.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditRepositoryTest {

    @Test
    @DisplayName("save / recent：最新寫入排在最前面")
    void recentReturnsNewestFirst() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository(100);
        repo.save(log(1, "generate", "v1"));
        repo.save(log(2, "validate", "v2"));
        repo.save(log(3, "analyze", "v3"));

        var recent = repo.recent(10);
        assertThat(recent).extracting(AuditLog::getLogId).containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("超過 maxLogs 上限會把最舊的擠掉")
    void evictsOldestWhenOverCapacity() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository(3);
        for (int i = 1; i <= 5; i++) {
            repo.save(log(i, "generate", "v" + i));
        }
        assertThat(repo.count()).isEqualTo(3);
        assertThat(repo.recent(10))
                .extracting(AuditLog::getLogId)
                .containsExactly(5L, 4L, 3L);
    }

    @Test
    @DisplayName("findByOperation / findByVersionId 行為正確")
    void findsByOperationAndVersion() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository(100);
        repo.save(log(1, "generate", "v1"));
        repo.save(log(2, "validate", "v2"));
        repo.save(log(3, "generate", "v3"));

        assertThat(repo.findByOperation("generate", 10))
                .extracting(AuditLog::getLogId)
                .containsExactly(3L, 1L);
        assertThat(repo.findByVersionId("v2")).isPresent()
                .get().extracting(AuditLog::getOperation).isEqualTo("validate");
        assertThat(repo.findByVersionId("v999")).isEmpty();
    }

    @Test
    @DisplayName("stats 含 storage / maxLogs / 操作分布")
    void statsBreakdown() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository(50);
        repo.save(log(1, "generate", "v1"));
        repo.save(log(2, "generate", "v2"));
        repo.save(log(3, "validate", "v3"));

        var stats = repo.stats();
        assertThat(stats).containsEntry("storage", "in-memory");
        assertThat(stats).containsEntry("totalLogs", 3L);
        assertThat(stats).containsEntry("maxLogs", 50L);
        @SuppressWarnings("unchecked")
        var ops = (java.util.Map<String, Long>) stats.get("operationBreakdown");
        assertThat(ops).containsEntry("generate", 2L).containsEntry("validate", 1L);
    }

    @Test
    @DisplayName("非法 maxLogs 自動回退為 1000")
    void invalidMaxLogsFallsBackToDefault() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository(0);
        var stats = repo.stats();
        assertThat(stats).containsEntry("maxLogs", 1000L);
    }

    private AuditLog log(long id, String op, String version) {
        return AuditLog.builder()
                .logId(id)
                .timestamp(LocalDateTime.now())
                .operation(op)
                .userId("u")
                .versionId(version)
                .ruleType("DecisionTable")
                .success(true)
                .build();
    }
}
