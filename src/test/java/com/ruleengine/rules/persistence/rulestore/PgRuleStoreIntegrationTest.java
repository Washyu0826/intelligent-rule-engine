package com.ruleengine.rules.persistence.rulestore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.rulestore.RuleStoreService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.sql.DriverManager;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 規則庫的<b>真 PostgreSQL</b> 整合驗證（P2-S1 深度驗證，補 H2 層的三個盲區）。
 *
 * <ol>
 *   <li><b>ddl-auto: validate 匹配</b> —— context 能啟動本身就是證明：
 *       entity 的 @JdbcTypeCode(SqlTypes.JSON) 映射與 V1 migration 的 jsonb 欄位相容。
 *       H2 上這條驗不到（H2 schema 是從 entity 生成的，永遠「自己對自己」）。</li>
 *   <li><b>單一 ACTIVE 部分唯一索引</b> —— {@code WHERE status='ACTIVE'} 的部分索引
 *       無法用 JPA 宣告，H2 測試層不存在，只有這裡能驗它與 JPA 寫入路徑的互動。</li>
 *   <li><b>真 jsonb 型別的往返</b> —— PG 會正規化 JSON（鍵序、空白），
 *       驗證我們的反序列化不依賴字面相等。</li>
 * </ol>
 *
 * <p>連線用本機開發庫（rules@localhost:5432）；每個測試在回滾交易內，不留資料。
 * PG 不可達時整類 skip（assumption），CI 無本機 PG 也不會紅。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/rules",
        "spring.datasource.username=rules",
        "spring.datasource.password=rules-local-dev",
        "spring.jpa.hibernate.ddl-auto=validate",   // 不准改表 —— schema 唯一真相是 Flyway
        "spring.flyway.enabled=false",              // 表已由應用啟動時的 migration 建好
})
@Import({RuleStoreService.class, PgRuleStoreIntegrationTest.TestBeans.class})
@DisplayName("規則庫 - 真 PostgreSQL 整合驗證")
class PgRuleStoreIntegrationTest {

    @TestConfiguration
    static class TestBeans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @BeforeAll
    static void requireLocalPostgres() {
        try (var c = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/rules", "rules", "rules-local-dev")) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "本機 PostgreSQL 不可達，跳過真 PG 驗證：" + e.getMessage());
        }
    }

    @Autowired RuleStoreService store;
    @Autowired RuleVersionRepository repository;

    private RuleEnvelope envelope(String reason) {
        RuleEnvelope e = new RuleEnvelope();
        e.setRuleType("DecisionTable");
        e.setReason(reason);
        return e;
    }

    @Test
    @DisplayName("盲區1+3：validate 通過（context 已啟動）且 jsonb 寫入讀回內容一致")
    void jsonbRoundTripOnRealPg() {
        var v1 = store.createDraft("pgtest.roundtrip", envelope("真PG往返-中文內容"), "maker");
        RuleEnvelope read = store.deserialize(store.findById(v1.getId()).orElseThrow());
        assertEquals("DecisionTable", read.getRuleType());
        assertEquals("真PG往返-中文內容", read.getReason());
    }

    @Test
    @DisplayName("盲區2：單一 ACTIVE 部分唯一索引在 JPA 寫入路徑上真的會咬")
    void partialUniqueIndexBitesThroughJpa() {
        var v1 = store.createDraft("pgtest.active", envelope("v1"), "maker");
        var v2 = store.createDraft("pgtest.active", envelope("v2"), "maker");

        v1.setStatus(RuleStatus.ACTIVE);
        repository.saveAndFlush(v1);

        // 第二個 ACTIVE：H2 上這會靜默成功（沒有部分索引），真 PG 必須擋下
        v2.setStatus(RuleStatus.ACTIVE);
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(v2),
                "uq_rule_version_single_active 必須經由 JPA 路徑也生效");

        // 而 RETIRED + ACTIVE 並存合法（版本演進常態）
        // 註：前一次 flush 失敗後 session 狀態已污染，這裡不再驗正向 —— 由 migration test 覆蓋
    }

    @Test
    @DisplayName("版本鏈在真 PG 上運作（IDENTITY 取號 + previousVersionId 接鏈）")
    void versionChainOnRealPg() {
        var v1 = store.createDraft("pgtest.chain", envelope("v1"), "maker");
        var v2 = store.createDraft("pgtest.chain", envelope("v2"), "maker");
        assertNotNull(v1.getId());
        assertEquals(v1.getId(), v2.getPreviousVersionId());
        assertEquals(2, store.history("pgtest.chain").size());
    }

    @Test
    @DisplayName("時間欄位映射：OffsetDateTime 落 TIMESTAMPTZ 不失真")
    void timestamptzMapping() {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        var v1 = store.createDraft("pgtest.time", envelope("t"), "maker");
        var read = store.findById(v1.getId()).orElseThrow();
        assertTrue(read.getCreatedAt().isAfter(before), "created_at 應為當下時間附近");
    }
}
