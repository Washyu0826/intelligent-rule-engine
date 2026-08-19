package com.ruleengine.rules.persistence.audit;

import com.ruleengine.rules.service.audit.AuditService.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JpaAuditRepository} 的 JPA 切片測試（P1-S3，H2 層）。
 *
 * <p>
 * {@code @DataJpaTest}：只啟動 JPA 相關 bean（entity、Spring Data repo、
 * in-memory 資料庫、交易管理），不啟動 web/service/LLM —— 比 @SpringBootTest
 * 快一個數量級，且每個測試方法自動包在回滾交易裡，測試間零污染。
 * </p>
 *
 * <p>
 * {@code JpaAuditRepository} 掛著 {@code @ConditionalOnProperty(audit.repository=jpa)}，
 * 切片掃不到一般 @Repository，所以 @Import 手動帶入 + @TestPropertySource 滿足條件。
 * </p>
 *
 * <p>
 * 這層驗「查詢邏輯」（排序、過濾、統計、轉換）；PG 專屬行為（TIMESTAMPTZ、TEXT）
 * 由 PostgresMigrationTest（Testcontainers）與 S3 的端到端煙霧驗證把關。
 * </p>
 */
@DataJpaTest
@Import(JpaAuditRepository.class)
@TestPropertySource(properties = "audit.repository=jpa")
@DisplayName("JpaAuditRepository - H2 切片測試")
class JpaAuditRepositoryTest {

    @Autowired JpaAuditRepository repository;

    private AuditLog log(String op, String versionId, boolean success, LocalDateTime ts) {
        return AuditLog.builder()
                .operation(op).userId("tester").versionId(versionId)
                .ruleType("DecisionTable").reason("測試").success(success)
                .timestamp(ts)
                .build();
    }

    @Test
    @DisplayName("recent：依時間倒序，同一時間戳靠 id 穩定決序")
    void recentOrdering() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 19, 10, 0);
        repository.save(log("GENERATE", "v1", true, t.minusMinutes(2)));
        repository.save(log("VALIDATE", "v2", true, t.minusMinutes(1)));
        // 兩筆同時間戳：後寫入者 id 較大，應排前面
        repository.save(log("GENERATE", "v3", true, t));
        repository.save(log("CONVERT", "v4", true, t));

        List<AuditLog> recent = repository.recent(3);
        assertEquals(3, recent.size());
        assertEquals("v4", recent.get(0).getVersionId(), "同時間戳時 id 大者在前");
        assertEquals("v3", recent.get(1).getVersionId());
        assertEquals("v2", recent.get(2).getVersionId());
    }

    @Test
    @DisplayName("logId 由資料庫生成 —— 傳入的 in-memory 流水號被忽略")
    void logIdFromDatabase() {
        AuditLog entry = log("GENERATE", "v-id", true, LocalDateTime.now());
        entry.setLogId(999_999L); // in-memory 時代的計數器值
        repository.save(entry);

        AuditLog read = repository.findByVersionId("v-id").orElseThrow();
        assertNotEquals(999_999L, read.getLogId(), "logId 應為 DB IDENTITY，不是呼叫端給的");
        assertTrue(read.getLogId() > 0);
    }

    @Test
    @DisplayName("findByOperation 只回該操作，維持倒序")
    void filterByOperation() {
        LocalDateTime t = LocalDateTime.now();
        repository.save(log("GENERATE", "g1", true, t.minusSeconds(3)));
        repository.save(log("VALIDATE", "x1", true, t.minusSeconds(2)));
        repository.save(log("GENERATE", "g2", false, t.minusSeconds(1)));

        List<AuditLog> gens = repository.findByOperation("GENERATE", 10);
        assertEquals(List.of("g2", "g1"), gens.stream().map(AuditLog::getVersionId).toList());
    }

    @Test
    @DisplayName("stats 回應形狀與 in-memory 版一致（/tools/audit/stats 契約不變），storage=postgres")
    void statsShape() {
        repository.save(log("GENERATE", "s1", true, LocalDateTime.now()));
        repository.save(log("GENERATE", "s2", false, LocalDateTime.now()));
        repository.save(log("VALIDATE", "s3", true, LocalDateTime.now()));

        Map<String, Object> stats = repository.stats();
        assertEquals(3L, stats.get("totalLogs"));
        assertEquals(2L, stats.get("successCount"));
        assertEquals(1L, stats.get("failCount"));
        assertEquals("postgres", stats.get("storage"));
        @SuppressWarnings("unchecked")
        Map<String, Long> breakdown = (Map<String, Long>) stats.get("operationBreakdown");
        assertEquals(2L, breakdown.get("GENERATE"));
        assertEquals(1L, breakdown.get("VALIDATE"));
    }

    @Test
    @DisplayName("防呆：limit<=0 與 null 參數回空，不打資料庫")
    void guardClauses() {
        repository.save(log("GENERATE", "v-guard", true, LocalDateTime.now()));
        assertEquals(List.of(), repository.recent(0));
        assertEquals(List.of(), repository.recent(-1));
        assertEquals(List.of(), repository.findByOperation(null, 10));
        assertTrue(repository.findByVersionId(null).isEmpty());
        repository.save(null); // 不應丟例外
        assertEquals(1, repository.count());
    }

    @Test
    @DisplayName("時間欄位往返不因時區映射位移（OffsetDateTime ↔ TIMESTAMPTZ）")
    void timestampRoundTrip() {
        LocalDateTime ts = LocalDateTime.of(2026, 8, 19, 14, 30, 15);
        repository.save(log("GENERATE", "v-time", true, ts));
        AuditLog read = repository.findByVersionId("v-time").orElseThrow();
        assertEquals(ts, read.getTimestamp(), "同一 JVM 時區下寫入讀出應完全一致");
    }
}
