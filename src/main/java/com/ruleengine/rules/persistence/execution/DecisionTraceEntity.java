package com.ruleengine.rules.persistence.execution;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * {@code decision_trace} 表的持久化模型（P2-S3）。
 *
 * <p>
 * <b>insert-only</b>：本 entity 沒有任何合法的更新路徑 —— 真 PG 上 UPDATE/DELETE
 * 會被 V3 migration 的 trigger 直接拒絕（DB 級不可變）。H2 測試層沒有該 trigger，
 * 不可變性由真 PG 整合測試驗證。
 * </p>
 */
@Entity
@Table(name = "decision_trace")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── 回放三要素 ──

    @Column(name = "rule_version_id", nullable = false)
    private Long ruleVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false)
    private String inputSnapshot;

    @Column(name = "engine_version", nullable = false, length = 40)
    private String engineVersion;

    // ── 查詢鍵與結果 ──

    @Column(name = "rule_key", nullable = false, length = 120)
    private String ruleKey;

    @Column(nullable = false)
    private boolean matched;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String outputs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_rule_ids", nullable = false)
    private String matchedRuleIds;

    // ── 明細與歸因 ──

    @Column(name = "trace_level", nullable = false, length = 10)
    private String traceLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trace_detail")
    private String traceDetail;

    @Column(name = "executed_by", nullable = false, length = 120)
    private String executedBy;

    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;

    @Column(name = "duration_nanos", nullable = false)
    private long durationNanos;
}
