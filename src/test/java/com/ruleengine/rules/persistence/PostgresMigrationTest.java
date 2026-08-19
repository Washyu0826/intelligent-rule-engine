package com.ruleengine.rules.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flyway migration 的真 PostgreSQL 驗證（P1-S2）。
 *
 * <p>
 * <b>為什麼不能只靠 H2：</b>全體單元測試跑 H2（毫秒級、免 Docker），但 migration
 * SQL 用了 PG 專屬能力 —— JSONB、部分唯一索引（{@code WHERE status='ACTIVE'}）、
 * {@code TIMESTAMPTZ}。這些在 H2 上要嘛不存在要嘛語意不同，「H2 上綠」證明不了
 * 「PG 上能跑」。這一層用 Testcontainers 起真 postgres:16 把關。
 * </p>
 *
 * <p>
 * {@code disabledWithoutDocker = true}：本機沒開 Docker Desktop 時整類跳過
 * （顯示 skipped 而非 failed），CI 上有 Docker 一定會跑。
 * </p>
 *
 * <p>
 * 刻意不用 @SpringBootTest —— 這裡驗的是「migration 檔案對真 PG 的行為」，
 * 直接 Flyway API + JDBC 就夠，不需要整個應用 context（快 10 倍以上）。
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Flyway migration - 真 PostgreSQL 驗證")
class PostgresMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("rules")
                    .withUsername("rules")
                    .withPassword("test");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("V1 建立 rule_version 與 audit_event，envelope 為 JSONB")
    void schemaCreated() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_name = 'rule_version' AND column_name = 'envelope'
                    """);
            assertTrue(rs.next(), "rule_version.envelope 欄位不存在");
            assertEquals("jsonb", rs.getString(1));

            ResultSet rs2 = st.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = 'audit_event'");
            rs2.next();
            assertEquals(1, rs2.getInt(1));
        }
    }

    @Test
    @DisplayName("JSONB 可整包寫入並以路徑查回 —— 引擎「整包取出」的存取模式")
    void jsonbRoundTrip() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO rule_version (rule_key, version_no, rule_type, envelope)
                    VALUES ('credit.age-gate', 1, 'DecisionTable',
                            '{"ruleType":"DecisionTable","rule":{"hitPolicy":"FIRST"}}'::jsonb)
                    """);
            ResultSet rs = st.executeQuery("""
                    SELECT envelope->'rule'->>'hitPolicy' FROM rule_version
                    WHERE rule_key = 'credit.age-gate' AND version_no = 1
                    """);
            assertTrue(rs.next());
            assertEquals("FIRST", rs.getString(1));
        }
    }

    @Test
    @DisplayName("部分唯一索引：同一 rule_key 不可有兩個 ACTIVE 版本")
    void singleActivePerRuleKey() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO rule_version (rule_key, version_no, rule_type, envelope, status)
                    VALUES ('credit.dual-active', 1, 'DecisionTable', '{}'::jsonb, 'ACTIVE')
                    """);
            // 第二個 ACTIVE 必須被索引擋下
            SQLException ex = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO rule_version (rule_key, version_no, rule_type, envelope, status)
                    VALUES ('credit.dual-active', 2, 'DecisionTable', '{}'::jsonb, 'ACTIVE')
                    """));
            assertTrue(ex.getMessage().contains("uq_rule_version_single_active"),
                    "應違反部分唯一索引，實際訊息：" + ex.getMessage());

            // 但 RETIRED + ACTIVE 並存是合法的（版本演進的常態）
            st.execute("""
                    INSERT INTO rule_version (rule_key, version_no, rule_type, envelope, status)
                    VALUES ('credit.dual-active', 3, 'DecisionTable', '{}'::jsonb, 'RETIRED')
                    """);
        }
    }

    @Test
    @DisplayName("非法 status 被 CHECK constraint 擋下")
    void statusCheckConstraint() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO rule_version (rule_key, version_no, rule_type, envelope, status)
                    VALUES ('credit.bad-status', 1, 'DecisionTable', '{}'::jsonb, 'HACKED')
                    """));
            assertTrue(ex.getMessage().toLowerCase().contains("check"),
                    "應違反 CHECK constraint，實際訊息：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("版本鏈：previous_version_id 外鍵擋掉指向不存在版本的鏈結")
    void versionChainForeignKey() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO rule_version (rule_key, version_no, rule_type, envelope, previous_version_id)
                    VALUES ('credit.broken-chain', 1, 'DecisionTable', '{}'::jsonb, 999999)
                    """));
            assertTrue(ex.getMessage().contains("previous_version_id")
                            || ex.getMessage().toLowerCase().contains("foreign key"),
                    "應違反外鍵，實際訊息：" + ex.getMessage());
        }
    }
}
