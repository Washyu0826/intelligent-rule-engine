package com.ruleengine.rules.service.rulestore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.persistence.rulestore.RuleStatus;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 規則庫存取層測試（P2-S1，H2 切片）。
 *
 * <p>PG 專屬行為（jsonb、部分唯一索引）由 PostgresMigrationTest 把關；
 * 這層驗版本鏈邏輯、序列化邊界、狀態轉移表。</p>
 */
@DataJpaTest
@Import({RuleStoreService.class, RuleStoreServiceTest.TestBeans.class})
@DisplayName("RuleStoreService - 規則庫存取層")
class RuleStoreServiceTest {

    @TestConfiguration
    static class TestBeans {
        /** @DataJpaTest 切片不含 web 層的 ObjectMapper bean，補一個。 */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired RuleStoreService store;
    @Autowired com.ruleengine.rules.persistence.rulestore.RuleVersionRepository repository;

    private RuleEnvelope envelope(String reason) {
        RuleEnvelope e = new RuleEnvelope();
        e.setRuleType("DecisionTable");
        e.setReason(reason);
        return e;
    }

    @Nested
    @DisplayName("版本鏈")
    class VersionChain {

        @Test
        @DisplayName("首版 version_no=1、無前版；續版 max+1、previousVersionId 接鏈")
        void chainGrows() {
            var v1 = store.createDraft("credit.loan-gate", envelope("首版"), "maker");
            assertEquals(1, v1.getVersionNo());
            assertNull(v1.getPreviousVersionId());
            assertEquals(RuleStatus.DRAFT, v1.getStatus());

            var v2 = store.createDraft("credit.loan-gate", envelope("二版"), "maker");
            assertEquals(2, v2.getVersionNo());
            assertEquals(v1.getId(), v2.getPreviousVersionId(), "版本鏈應指向前一版");

            List<RuleVersionEntity> history = store.history("credit.loan-gate");
            assertEquals(List.of(2, 1), history.stream().map(RuleVersionEntity::getVersionNo).toList());
        }

        @Test
        @DisplayName("不同 rule_key 的版本號各自獨立")
        void perKeyNumbering() {
            store.createDraft("credit.a", envelope("a"), "maker");
            store.createDraft("credit.a", envelope("a2"), "maker");
            var b1 = store.createDraft("credit.b", envelope("b"), "maker");
            assertEquals(1, b1.getVersionNo(), "b 的首版不受 a 已有 2 版影響");
        }

        @Test
        @DisplayName("envelope 序列化往返：存進去的 domain 物件讀回內容一致")
        void envelopeRoundTrip() {
            var v1 = store.createDraft("credit.rt", envelope("往返測試"), "maker");
            RuleEnvelope read = store.deserialize(store.findById(v1.getId()).orElseThrow());
            assertEquals("DecisionTable", read.getRuleType());
            assertEquals("往返測試", read.getReason());
        }

        @Test
        @DisplayName("防呆：空 ruleKey / 空 envelope 拋 RuleStoreException")
        void guards() {
            assertThrows(RuleStoreService.RuleStoreException.class,
                    () -> store.createDraft(" ", envelope("x"), "maker"));
            assertThrows(RuleStoreService.RuleStoreException.class,
                    () -> store.createDraft("credit.x", null, "maker"));
        }
    }

    @Nested
    @DisplayName("查詢")
    class Queries {

        @Test
        @DisplayName("findActive 只回 ACTIVE 狀態的版本")
        void findActiveFiltersStatus() {
            var v1 = store.createDraft("credit.q", envelope("draft only"), "maker");
            assertTrue(store.findActive("credit.q").isEmpty(), "草稿不是生效版");

            // 直接改狀態模擬（正規轉移是 P2-S4 的事）。
            // 必須 saveAndFlush：查詢方法是 @Transactional(readOnly=true)，
            // Spring 會把 flush mode 壓成 MANUAL —— 未 flush 的 dirty 變更查不到（錯誤#2 的教訓）
            v1.setStatus(RuleStatus.ACTIVE);
            repository.saveAndFlush(v1);
            assertEquals(v1.getId(), store.findActive("credit.q").orElseThrow().getId());
        }

        @Test
        @DisplayName("listByStatus：待審清單舊的先審（updated_at 升冪）")
        void reviewQueueOrder() {
            var a = store.createDraft("credit.qa", envelope("a"), "maker");
            var b = store.createDraft("credit.qb", envelope("b"), "maker");
            a.setStatus(RuleStatus.REVIEW);
            b.setStatus(RuleStatus.REVIEW);
            a.setUpdatedAt(a.getUpdatedAt().plusSeconds(10)); // a 較晚送審
            repository.saveAndFlush(a);
            repository.saveAndFlush(b);

            List<RuleVersionEntity> queue = store.listByStatus(RuleStatus.REVIEW);
            assertEquals(List.of(b.getId(), a.getId()),
                    queue.stream().map(RuleVersionEntity::getId).toList());
        }
    }

    @Nested
    @DisplayName("併發防線（缺口②：ConcurrentVersionException 必須真的會觸發）")
    class ConcurrencyGuard {

        @Test
        @DisplayName("同 (rule_key, version_no) 第二筆被唯一約束擋下（H2 也有約束了—缺口③修復的證明）")
        void uniqueConstraintExists() {
            store.createDraft("credit.cc", envelope("v1"), "maker");
            // 手工造一筆撞號的（模擬兩請求同時算出 max+1=2 的後到者）
            var dup = com.ruleengine.rules.persistence.rulestore.RuleVersionEntity.builder()
                    .ruleKey("credit.cc").versionNo(1).ruleType("DecisionTable")
                    .envelope("{}").status(RuleStatus.DRAFT)
                    .createdBy("maker")
                    .createdAt(java.time.OffsetDateTime.now())
                    .updatedAt(java.time.OffsetDateTime.now())
                    .build();
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> repository.saveAndFlush(dup),
                    "撞號必須被資料庫唯一約束擋下 —— 這是樂觀併發策略的最後防線");
        }
    }

    @Nested
    @DisplayName("狀態轉移表（enum 自持，P2-S4 狀態機的基礎）")
    class Transitions {

        @Test
        @DisplayName("maker-checker 正向路徑全通")
        void happyPath() {
            assertTrue(RuleStatus.DRAFT.canTransitionTo(RuleStatus.REVIEW));
            assertTrue(RuleStatus.REVIEW.canTransitionTo(RuleStatus.APPROVED));
            assertTrue(RuleStatus.APPROVED.canTransitionTo(RuleStatus.ACTIVE));
            assertTrue(RuleStatus.ACTIVE.canTransitionTo(RuleStatus.RETIRED));
        }

        @Test
        @DisplayName("可逆性轉移（文獻調查補強）：撤回、退回、改後重送")
        void reversibility() {
            assertTrue(RuleStatus.REVIEW.canTransitionTo(RuleStatus.DRAFT), "maker 撤回");
            assertTrue(RuleStatus.REVIEW.canTransitionTo(RuleStatus.REJECTED), "checker 退回");
            assertTrue(RuleStatus.REJECTED.canTransitionTo(RuleStatus.DRAFT), "改後重送");
        }

        @Test
        @DisplayName("窮舉 6x6 矩陣：合法轉移恰好 8 條，其餘全擋")
        void exhaustiveMatrix() {
            var legal = java.util.Set.of(
                    "DRAFT>REVIEW", "REVIEW>APPROVED", "REVIEW>REJECTED", "REVIEW>DRAFT",
                    "APPROVED>ACTIVE", "APPROVED>REVIEW", "ACTIVE>RETIRED", "REJECTED>DRAFT");
            int allowedCount = 0;
            for (RuleStatus from : RuleStatus.values()) {
                for (RuleStatus to : RuleStatus.values()) {
                    boolean allowed = from.canTransitionTo(to);
                    assertEquals(legal.contains(from + ">" + to), allowed,
                            from + " -> " + to + " 的合法性與規格不符");
                    if (allowed) allowedCount++;
                }
            }
            assertEquals(8, allowedCount, "合法轉移總數應恰為 8 —— 多了少了都是規格變更");
        }

        @Test
        @DisplayName("非法轉移被擋：跳關與死而復生")
        void illegalTransitions() {
            assertFalse(RuleStatus.DRAFT.canTransitionTo(RuleStatus.ACTIVE), "不可跳過審核直接生效");
            assertFalse(RuleStatus.DRAFT.canTransitionTo(RuleStatus.APPROVED), "不可跳過送審");
            assertFalse(RuleStatus.RETIRED.canTransitionTo(RuleStatus.ACTIVE), "退役不可復活");
            assertTrue(RuleStatus.RETIRED.allowedTargets().isEmpty(), "RETIRED 是終態");
        }
    }
}
