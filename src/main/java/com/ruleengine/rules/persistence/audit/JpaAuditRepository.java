package com.ruleengine.rules.persistence.audit;

import com.ruleengine.rules.service.audit.AuditRepository;
import com.ruleengine.rules.service.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AuditRepository} 的 PostgreSQL 落地版（P1-S3）。
 *
 * <p>
 * <b>裝配方式：</b>bean 名稱刻意取 {@code jpaAuditRepository} ——
 * {@code InMemoryAuditRepository} 自 v3.12 起就掛著
 * {@code @ConditionalOnMissingBean(name = "jpaAuditRepository")}，等的就是這個名字。
 * 開關是 {@code audit.repository=jpa}（application.yml 已設）；
 * 測試環境不設此鍵 → {@code matchIfMissing=true} 讓 in-memory 版接手，
 * 既有 661 個測試零改動。
 * </p>
 *
 * <p>
 * <b>行為與 in-memory 版的差異（有意為之）：</b>
 * </p>
 * <ul>
 *   <li>logId 改由資料庫 IDENTITY 生成 —— app 內 AtomicLong 在多實例會撞號、重啟歸零</li>
 *   <li>上限 1000 筆的環形淘汰取消 —— 落地後容量不是問題，保留策略之後由
 *       資料保存政策（migration 加 partition / 清理 job）處理，不該由寫入路徑悄悄丟資料</li>
 * </ul>
 */
@Repository("jpaAuditRepository")
@ConditionalOnProperty(name = "audit.repository", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaAuditRepository implements AuditRepository {

    private final AuditEventJpaRepository jpa;

    @Override
    @Transactional
    public void save(AuditService.AuditLog entry) {
        if (entry == null) return;
        jpa.save(toEntity(entry));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditService.AuditLog> recent(int limit) {
        if (limit <= 0) return List.of();
        return jpa.findRecent(PageRequest.of(0, limit)).stream().map(this::toLog).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditService.AuditLog> findByOperation(String operation, int limit) {
        if (operation == null || limit <= 0) return List.of();
        return jpa.findRecentByOperation(operation, PageRequest.of(0, limit))
                .stream().map(this::toLog).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditService.AuditLog> findByVersionId(String versionId) {
        if (versionId == null) return Optional.empty();
        return jpa.findFirstByVersionIdOrderByIdDesc(versionId).map(this::toLog);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        long success = jpa.countBySuccess(true);
        long fail = jpa.countBySuccess(false);
        Map<String, Long> opCounts = new LinkedHashMap<>();
        for (Object[] row : jpa.countGroupByOperation()) {
            opCounts.put((String) row[0], (Long) row[1]);
        }
        // 鍵名對齊 InMemoryAuditRepository.stats() —— /tools/audit/stats 的回應形狀不變
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalLogs", success + fail);
        stats.put("successCount", success);
        stats.put("failCount", fail);
        stats.put("operationBreakdown", opCounts);
        stats.put("storage", "postgres");
        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return jpa.count();
    }

    // ── AuditLog（API 模型）↔ AuditEventEntity（儲存模型）──

    private AuditEventEntity toEntity(AuditService.AuditLog log) {
        return AuditEventEntity.builder()
                // id 留給資料庫 IDENTITY；AuditService 給的 logId 是 in-memory 時代的流水號，忽略
                .occurredAt(log.getTimestamp() != null
                        ? log.getTimestamp().atZone(ZoneId.systemDefault()).toOffsetDateTime()
                        : OffsetDateTime.now())
                .operation(log.getOperation())
                .userId(log.getUserId())
                .versionId(log.getVersionId())
                .previousVersionId(log.getPreviousVersionId())
                .ruleType(log.getRuleType())
                .reason(log.getReason())
                .success(log.isSuccess())
                .build();
    }

    private AuditService.AuditLog toLog(AuditEventEntity e) {
        return AuditService.AuditLog.builder()
                .logId(e.getId() != null ? e.getId() : 0L)
                .timestamp(e.getOccurredAt() != null
                        ? LocalDateTime.ofInstant(e.getOccurredAt().toInstant(), ZoneId.systemDefault())
                        : null)
                .operation(e.getOperation())
                .userId(e.getUserId())
                .versionId(e.getVersionId())
                .previousVersionId(e.getPreviousVersionId())
                .ruleType(e.getRuleType())
                .reason(e.getReason())
                .success(e.isSuccess())
                .build();
    }
}
