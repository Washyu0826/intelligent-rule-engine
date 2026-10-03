package com.ruleengine.rules.persistence.rulestore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** {@code rule_chain} 表：有序的規則 key 清單（線性流程）。steps 為 JSON 陣列（PG=jsonb / H2=JSON）。 */
@Entity
@Table(name = "rule_chain")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleChainEntity {

    @Id
    @Column(name = "chain_key", length = 120)
    private String chainKey;

    @Column(nullable = false, length = 200)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String steps;

    @Column(name = "updated_by", nullable = false, length = 120)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
