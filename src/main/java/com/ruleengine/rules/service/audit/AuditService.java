package com.ruleengine.rules.service.audit;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.AuditMetadata;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 操作稽核服務 — 記錄所有規則操作的完整軌跡。
 *
 * 功能：
 * - 記錄 generate / validate / optimize / convert 操作
 * - 為 RuleEnvelope 附加 AuditMetadata
 * - 版本管理（versionId 自動生成、版本鏈追溯）
 * - 操作日誌查詢
 *
 * v3.12 重構：儲存層由 {@link AuditRepository} 注入。
 *   - 預設 {@link InMemoryAuditRepository}
 *   - 生產可換 JPA / Redis 實作而 service 邏輯不變
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditRepository auditRepository;
    private final AtomicLong logIdCounter = new AtomicLong(1);
    private final AtomicLong versionCounter = new AtomicLong(1);

    /**
     * 記錄操作並為 envelope 附加稽核元資料。
     */
    public void recordOperation(RuleEnvelope envelope, String operation, String userId, String reason) {
        String versionId = generateVersionId();

        // 附加 AuditMetadata
        AuditMetadata audit = AuditMetadata.builder()
                .createdBy(userId != null ? userId : "system")
                .createdAt(LocalDateTime.now().toString())
                .modifiedBy(userId != null ? userId : "system")
                .modifiedAt(LocalDateTime.now().toString())
                .changeReason(reason)
                .operation(operation)
                .build();

        // 如果已有 audit，保留 createdBy/At
        if (envelope.getAudit() != null) {
            audit.setCreatedBy(envelope.getAudit().getCreatedBy());
            audit.setCreatedAt(envelope.getAudit().getCreatedAt());
        }

        envelope.setAudit(audit);

        // 版本管理
        if (envelope.getVersionId() != null) {
            envelope.setPreviousVersionId(envelope.getVersionId());
        }
        envelope.setVersionId(versionId);

        // 寫入日誌
        AuditLog logEntry = AuditLog.builder()
                .logId(logIdCounter.getAndIncrement())
                .timestamp(LocalDateTime.now())
                .operation(operation)
                .userId(userId != null ? userId : "system")
                .versionId(versionId)
                .previousVersionId(envelope.getPreviousVersionId())
                .ruleType(envelope.getRuleType())
                .reason(reason)
                .success(true)
                .build();

        auditRepository.save(logEntry);

        log.info("AUDIT | op={} | user={} | version={} | prevVersion={} | ruleType={}",
                operation, userId, versionId, envelope.getPreviousVersionId(), envelope.getRuleType());
    }

    /**
     * 記錄失敗操作。
     */
    public void recordFailure(String operation, String userId, String ruleType, String errorMessage) {
        AuditLog logEntry = AuditLog.builder()
                .logId(logIdCounter.getAndIncrement())
                .timestamp(LocalDateTime.now())
                .operation(operation)
                .userId(userId != null ? userId : "system")
                .ruleType(ruleType)
                .reason(errorMessage)
                .success(false)
                .build();

        auditRepository.save(logEntry);

        log.warn("AUDIT FAIL | op={} | user={} | error={}", operation, userId, errorMessage);
    }

    /** 查詢最近的操作日誌（委派給 repository）。 */
    public List<AuditLog> getRecentLogs(int limit) {
        return auditRepository.recent(limit);
    }

    /** 依操作類型查詢。 */
    public List<AuditLog> getLogsByOperation(String operation, int limit) {
        return auditRepository.findByOperation(operation, limit);
    }

    /** 依版本 ID 查詢。 */
    public Optional<AuditLog> getLogByVersionId(String versionId) {
        return auditRepository.findByVersionId(versionId);
    }

    /** 取得統計摘要（含 storage 來源資訊）。 */
    public Map<String, Object> getStats() {
        return auditRepository.stats();
    }

    private String generateVersionId() {
        return String.format("v%d-%d", System.currentTimeMillis() / 1000, versionCounter.getAndIncrement());
    }

    // ── Data ──

    @Data @Builder
    public static class AuditLog {
        private long logId;
        private LocalDateTime timestamp;
        private String operation;
        private String userId;
        private String versionId;
        private String previousVersionId;
        private String ruleType;
        private String reason;
        private boolean success;
    }
}
