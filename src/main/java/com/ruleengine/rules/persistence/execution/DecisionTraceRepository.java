package com.ruleengine.rules.persistence.execution;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * insert-only repository（P2-S3）—— 刻意只暴露 save 與讀取；
 * 不覆寫也不呼叫 delete/update 類方法（真 PG 上 trigger 會拒絕，
 * 這裡的窄介面讓「程式不會去試」）。
 */
public interface DecisionTraceRepository extends JpaRepository<DecisionTraceEntity, Long> {

    @Query("SELECT t FROM DecisionTraceEntity t WHERE t.ruleKey = :ruleKey "
            + "ORDER BY t.executedAt DESC, t.id DESC")
    List<DecisionTraceEntity> findRecentByRuleKey(@Param("ruleKey") String ruleKey, Pageable pageable);

    @Query("SELECT t FROM DecisionTraceEntity t ORDER BY t.executedAt DESC, t.id DESC")
    List<DecisionTraceEntity> findRecent(Pageable pageable);
}
