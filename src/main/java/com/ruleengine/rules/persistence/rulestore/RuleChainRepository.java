package com.ruleengine.rules.persistence.rulestore;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleChainRepository extends JpaRepository<RuleChainEntity, String> {
}
