package com.ruleengine.rules.persistence.rulestore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * {@code rule_version} 表的持久化模型（P2-S1）。
 *
 * <p>
 * <b>envelope 用 {@code String} + {@code @JdbcTypeCode(SqlTypes.JSON)}：</b>
 * Hibernate 6 依 dialect 映射 —— PG 是 {@code jsonb}（ddl validate 通過）、
 * H2 是 {@code JSON}（測試層照跑）。刻意<b>不寫</b> {@code columnDefinition="jsonb"}，
 * 那會把 PG 方言寫死進 entity，H2 的 create-drop 直接炸。
 * 也刻意不用 {@code JsonNode} 型別 —— entity 不綁 Jackson，
 * 序列化邊界收在 RuleStoreService 一處。
 * </p>
 *
 * <p>
 * <b>append-only：</b>除了 {@code status} 與 {@code updated_at}，
 * 版本內容（envelope / version_no / 鏈結）落庫後不再修改 ——
 * 「當時生效的是哪一版」的可回溯性是決策回放（P2-S3）的前提。
 * 修改規則 = 開新版本接鏈，不是改舊列。
 * </p>
 */
@Entity
// uniqueConstraints 顯式宣告：PG 的真 schema 由 V1 migration 定義（validate 模式不看這裡），
// 但 H2 測試層的 schema 是「從 entity 生成」的 —— 不宣告的話 H2 上就沒有這條約束，
// 併發防線（ConcurrentVersionException）在測試層形同虛設。
// 注意「單一 ACTIVE」的部分唯一索引無法用 JPA 宣告 —— 那條只有真 PG 測試能驗。
@Table(name = "rule_version",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_rule_version", columnNames = {"rule_key", "version_no"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 業務識別：同一條規則的所有版本共享同一 rule_key（如 credit.loan-gate）。 */
    @Column(name = "rule_key", nullable = false, length = 120)
    private String ruleKey;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "rule_type", nullable = false, length = 40)
    private String ruleType;

    /** 完整 RuleEnvelope JSON（PG=jsonb / H2=JSON，見類別 javadoc）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String envelope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleStatus status;

    /** 版本鏈（NULL = 首版）。 */
    @Column(name = "previous_version_id")
    private Long previousVersionId;

    @Column(name = "created_by", nullable = false, length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
