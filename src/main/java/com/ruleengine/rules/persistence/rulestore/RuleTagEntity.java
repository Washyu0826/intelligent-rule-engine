package com.ruleengine.rules.persistence.rulestore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** {@code rule_tag} 表：規則的標籤（dimension 是標籤維度，如分類、主題）。 */
@Entity
@Table(name = "rule_tag",
        uniqueConstraints = @UniqueConstraint(name = "uq_rule_tag",
                columnNames = {"rule_key", "dimension", "tag"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleTagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_key", nullable = false, length = 120)
    private String ruleKey;

    @Column(nullable = false, length = 60)
    private String dimension;

    @Column(nullable = false, length = 120)
    private String tag;
}
