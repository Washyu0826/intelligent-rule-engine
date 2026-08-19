package com.ruleengine.rules.persistence.rulestore;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RuleVersionRepository extends JpaRepository<RuleVersionEntity, Long> {

    /** 熱路徑：引擎執行前取生效版（idx_rule_version_key_status 索引命中）。 */
    Optional<RuleVersionEntity> findByRuleKeyAndStatus(String ruleKey, RuleStatus status);

    Optional<RuleVersionEntity> findByRuleKeyAndVersionNo(String ruleKey, int versionNo);

    /** 版本鏈檢視：同一規則的所有版本，新的在前。 */
    List<RuleVersionEntity> findByRuleKeyOrderByVersionNoDesc(String ruleKey);

    /** 審核工作台：某狀態的所有版本（如 REVIEW = 待審清單），舊的先審。 */
    List<RuleVersionEntity> findByStatusOrderByUpdatedAtAsc(RuleStatus status);

    /** 下一個版本號 = max + 1；無版本時回 0（服務層 +1 得首版 1）。 */
    @Query("SELECT COALESCE(MAX(v.versionNo), 0) FROM RuleVersionEntity v WHERE v.ruleKey = :ruleKey")
    int maxVersionNo(@Param("ruleKey") String ruleKey);

    /** 規則清單（去重的 rule_key 一覽，管理頁用）。 */
    @Query("SELECT DISTINCT v.ruleKey FROM RuleVersionEntity v ORDER BY v.ruleKey")
    List<String> distinctRuleKeys();
}
