package com.ruleengine.rules.service.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * v3.12 — 預設 in-memory 稽核儲存。
 *
 * 行為與舊版 AuditService 內嵌的 ConcurrentLinkedDeque 相同：
 * - addFirst 寫入（最新在前）
 * - 上限 audit.in-memory.max-logs（預設 1000）
 * - 重啟後清空，**不適用於正式生產**
 *
 * 正式部署：實作 {@code JpaAuditRepository} 或 {@code RedisAuditRepository}
 * 並透過 {@link ConditionalOnProperty} 對換，AuditService 不需改動。
 */
@Repository
@ConditionalOnMissingBean(name = "jpaAuditRepository")
@ConditionalOnProperty(name = "audit.repository", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryAuditRepository implements AuditRepository {

    private final ConcurrentLinkedDeque<AuditService.AuditLog> logs = new ConcurrentLinkedDeque<>();
    private final int maxLogs;

    public InMemoryAuditRepository(@Value("${audit.in-memory.max-logs:1000}") int maxLogs) {
        this.maxLogs = maxLogs <= 0 ? 1000 : maxLogs;
    }

    @Override
    public void save(AuditService.AuditLog entry) {
        if (entry == null) return;
        logs.addFirst(entry);
        while (logs.size() > maxLogs) {
            logs.removeLast();
        }
    }

    @Override
    public List<AuditService.AuditLog> recent(int limit) {
        if (limit <= 0) return List.of();
        List<AuditService.AuditLog> result = new ArrayList<>(Math.min(limit, logs.size()));
        int taken = 0;
        for (AuditService.AuditLog entry : logs) {
            if (taken >= limit) break;
            result.add(entry);
            taken++;
        }
        return result;
    }

    @Override
    public List<AuditService.AuditLog> findByOperation(String operation, int limit) {
        if (operation == null || limit <= 0) return List.of();
        return logs.stream()
                .filter(l -> operation.equals(l.getOperation()))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<AuditService.AuditLog> findByVersionId(String versionId) {
        if (versionId == null) return Optional.empty();
        return logs.stream()
                .filter(l -> versionId.equals(l.getVersionId()))
                .findFirst();
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Long> opCounts = new LinkedHashMap<>();
        long success = 0;
        long fail = 0;
        for (AuditService.AuditLog entry : logs) {
            opCounts.merge(entry.getOperation(), 1L, Long::sum);
            if (entry.isSuccess()) success++;
            else fail++;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalLogs", (long) logs.size());
        stats.put("successCount", success);
        stats.put("failCount", fail);
        stats.put("operationBreakdown", opCounts);
        stats.put("storage", "in-memory");
        stats.put("maxLogs", (long) maxLogs);
        return stats;
    }

    @Override
    public long count() {
        return logs.size();
    }
}
