package com.ruleengine.rules.persistence.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository（P1-S3）。
 *
 * <p>
 * 「最近 N 筆」用 {@link Pageable} 而非 derived method 的 TopN ——
 * Top50ByOrderBy... 的 N 是寫死在方法名裡的，caller 傳進來的 limit
 * 需要 Pageable 才接得住。排序鍵 (occurred_at DESC, id DESC)：
 * 同一毫秒寫入的兩筆靠 id 決定穩定順序，避免分頁時跳動。
 * </p>
 */
public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, Long> {

    @Query("SELECT e FROM AuditEventEntity e ORDER BY e.occurredAt DESC, e.id DESC")
    List<AuditEventEntity> findRecent(Pageable pageable);

    @Query("SELECT e FROM AuditEventEntity e WHERE e.operation = :operation "
            + "ORDER BY e.occurredAt DESC, e.id DESC")
    List<AuditEventEntity> findRecentByOperation(@Param("operation") String operation, Pageable pageable);

    Optional<AuditEventEntity> findFirstByVersionIdOrderByIdDesc(String versionId);

    long countBySuccess(boolean success);

    /** operation 分布統計：回傳 [operation, count] 對，供 stats() 組裝。 */
    @Query("SELECT e.operation, COUNT(e) FROM AuditEventEntity e GROUP BY e.operation ORDER BY COUNT(e) DESC")
    List<Object[]> countGroupByOperation();
}
