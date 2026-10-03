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

import java.time.OffsetDateTime;

/** {@code rule_directory} 表：規則在樹狀目錄中的位置（掛 rule_key，不掛版本）。 */
@Entity
@Table(name = "rule_directory")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleDirectoryEntity {

    @Id
    @Column(name = "rule_key", length = 120)
    private String ruleKey;

    @Column(nullable = false, length = 400)
    private String path;

    @Column(name = "updated_by", nullable = false, length = 120)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
