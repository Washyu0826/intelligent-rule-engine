package com.ruleengine.rules.service.audit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v3.12 — 稽核日誌儲存介面。
 *
 * 把實際儲存（in-memory / JPA / Redis / Kafka）與 AuditService 的業務邏輯分離。
 * Demo 階段使用 {@link InMemoryAuditRepository}（行為與舊版相同）；
 * 正式部署時實作 JPA 版本即可，無需改動 AuditService 與 controller 呼叫端。
 */
public interface AuditRepository {

    /** 寫入一筆稽核紀錄（最新的應排在最前面，與舊版 ConcurrentLinkedDeque.addFirst 行為一致）。 */
    void save(AuditService.AuditLog entry);

    /** 最近 N 筆，依時間倒序。 */
    List<AuditService.AuditLog> recent(int limit);

    /** 依 operation 過濾的最近 N 筆。 */
    List<AuditService.AuditLog> findByOperation(String operation, int limit);

    /** 依 versionId 查單筆。 */
    Optional<AuditService.AuditLog> findByVersionId(String versionId);

    /** 取統計：總筆數、成功失敗、依 operation 分布等。 */
    Map<String, Object> stats();

    /** 目前儲存中的日誌筆數。 */
    long count();
}
