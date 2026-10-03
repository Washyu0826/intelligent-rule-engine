package com.ruleengine.rules.persistence.rulestore;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RuleTagRepository extends JpaRepository<RuleTagEntity, Long> {

    List<RuleTagEntity> findByRuleKey(String ruleKey);

    @Modifying
    @Query("DELETE FROM RuleTagEntity t WHERE t.ruleKey = :ruleKey")
    void deleteByRuleKey(@Param("ruleKey") String ruleKey);
}
