package com.ruleengine.rules.persistence.execution;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * decision_trace 的 <b>DB 級不可變</b>驗證（P2-S3；V3 migration 的 trigger）。
 *
 * <p>
 * 「稽核證據 write-once」不能只靠程式紀律 —— 這裡直接用 SQL 對真 PG 發
 * UPDATE / DELETE，驗證被 trigger 當場拒絕（含未來任何人用 psql 手改的情境）。
 * H2 沒有這個 trigger，此性質只有真 PG 能驗。
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("decision_trace - DB 級不可變（trigger）")
class PgDecisionTraceImmutabilityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("rules").withUsername("rules").withPassword("test");

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO rule_version (rule_key, version_no, rule_type, envelope, status)
                    VALUES ('imm.test', 1, 'DecisionTable', '{}'::jsonb, 'ACTIVE')""");
            st.execute("""
                    INSERT INTO decision_trace
                        (rule_version_id, input_snapshot, engine_version, rule_key, matched, trace_level)
                    SELECT id, '{"score":700}'::jsonb, 'exec-1.0.0', 'imm.test', true, 'SUMMARY'
                    FROM rule_version WHERE rule_key = 'imm.test'""");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("UPDATE 被 trigger 拒絕 —— 事後竄改決策紀錄不可能")
    void updateRejected() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class,
                    () -> st.execute("UPDATE decision_trace SET matched = false WHERE rule_key = 'imm.test'"));
            assertTrue(ex.getMessage().contains("append-only"), "拒絕訊息：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("DELETE 被 trigger 拒絕 —— 湮滅證據不可能")
    void deleteRejected() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class,
                    () -> st.execute("DELETE FROM decision_trace WHERE rule_key = 'imm.test'"));
            assertTrue(ex.getMessage().contains("append-only"));
        }
    }

    @Test
    @DisplayName("INSERT 照常允許（append-only 的 append 面）且資料完好")
    void insertStillAllowed() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO decision_trace
                        (rule_version_id, input_snapshot, engine_version, rule_key, matched, trace_level)
                    SELECT id, '{"score":500}'::jsonb, 'exec-1.0.0', 'imm.test', false, 'NONE'
                    FROM rule_version WHERE rule_key = 'imm.test'""");
            var rs = st.executeQuery("SELECT count(*) FROM decision_trace WHERE rule_key='imm.test'");
            rs.next();
            assertTrue(rs.getInt(1) >= 2);
        }
    }
}
