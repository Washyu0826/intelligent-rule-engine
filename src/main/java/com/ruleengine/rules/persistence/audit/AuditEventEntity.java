package com.ruleengine.rules.persistence.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * {@code audit_event} 表的持久化模型（P1-S3）。
 *
 * <p>
 * <b>與 {@code AuditService.AuditLog} 刻意分離：</b>AuditLog 是 API 回應模型
 * （controller 直接序列化給前端），這個 entity 是儲存模型。兩者現在欄位幾乎一致，
 * 但演進方向不同 —— 儲存模型跟著 migration 走（之後 P2 會加審核欄位），
 * API 模型跟著前端契約走。共用一個類別省的那點程式碼，會在第一次
 * 「想改表但不想改 API」時加倍還回來。轉換collapse在 {@link JpaAuditRepository}。
 * </p>
 *
 * <p>
 * 欄位對應 {@code V1__rule_store_baseline.sql} 的 audit_event：
 * id 由資料庫 IDENTITY 生成（取代 in-memory 版的 AtomicLong 流水號 ——
 * 多實例部署時 app 內計數器會撞號，資料庫序號不會）。
 * </p>
 */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用 OffsetDateTime 對應 TIMESTAMPTZ —— LocalDateTime 對 timestamptz 的映射
     * 依 JVM 時區而變（部署到 UTC 容器就位移），帶時區型別沒有這個歧義。
     */
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(nullable = false, length = 60)
    private String operation;

    @Column(name = "user_id", length = 120)
    private String userId;

    @Column(name = "version_id", length = 120)
    private String versionId;

    @Column(name = "previous_version_id", length = 120)
    private String previousVersionId;

    @Column(name = "rule_type", length = 40)
    private String ruleType;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(nullable = false)
    private boolean success;
}
