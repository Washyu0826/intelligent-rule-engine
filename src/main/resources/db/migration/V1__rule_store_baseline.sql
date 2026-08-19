-- ============================================================
-- V1: 規則庫 + 稽核 baseline（P1-S2）
-- ============================================================
-- 設計決策：
--
-- 1. envelope 存 JSONB 而非全拆關聯表
--    RuleEnvelope 是深巢狀、schema 演進中的文件（rule.rules[].conditions[] ...）。
--    全拆成關聯表要 8+ 張表、每次 schemaVersion 演進都要 migration；
--    而系統對規則內容的查詢模式是「整包取出 → 引擎編譯/執行」，不是逐條 SQL 篩選。
--    JSONB 保留：整包原子讀寫、GIN 索引可查（需要時）、與現有 API 契約零轉換。
--    需要用 SQL 篩的欄位（rule_key/status/rule_type/版本）提升為一級欄位。
--
-- 2. 版本鏈是 append-only
--    同一 rule_key 的每個版本一列，previous_version_id 串鏈；
--    不做 UPDATE-in-place —— 金融稽核要求「當時生效的是哪一版」可回溯，
--    這也是之後決策回放（replay）的前提：trace 指向 rule_version.id 即可重現。
--
-- 3. status 用 VARCHAR + CHECK 而非 enum type
--    PG 原生 enum 加值要 ALTER TYPE 且不能在交易內（舊版），
--    CHECK constraint 的演進只是普通 migration。狀態機語意由應用層
--    （P2 的審核狀態機）強制，DB 層只擋非法值。
-- ============================================================

CREATE TABLE rule_version (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 業務識別：同一條規則的所有版本共享同一 rule_key
    rule_key            VARCHAR(120)  NOT NULL,
    version_no          INTEGER       NOT NULL,
    rule_type           VARCHAR(40)   NOT NULL,
    -- 完整 RuleEnvelope JSON（含 evaluation / audit metadata）
    envelope            JSONB         NOT NULL,
    -- 生命週期狀態機（P2 接手轉移規則；DRAFT 之外的轉移都要留審核紀錄）
    status              VARCHAR(20)   NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','REVIEW','APPROVED','ACTIVE','RETIRED','REJECTED')),
    -- 版本鏈（append-only；NULL = 首版）
    previous_version_id BIGINT        REFERENCES rule_version(id),
    -- 稽核最小集（完整審核角色/簽核在 P2 的 migration 增量加入）
    created_by          VARCHAR(120)  NOT NULL DEFAULT 'system',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- 同一規則不可有重複版本號
    CONSTRAINT uq_rule_version UNIQUE (rule_key, version_no)
);

-- 「取某規則目前生效版」是最熱查詢：WHERE rule_key = ? AND status = 'ACTIVE'
CREATE INDEX idx_rule_version_key_status ON rule_version (rule_key, status);

-- 同一 rule_key 同時間最多一個 ACTIVE（部分唯一索引 —— PG 專屬能力，
-- 這正是 migration 測試要跑真 PG 而不是 H2 的原因之一）
CREATE UNIQUE INDEX uq_rule_version_single_active
    ON rule_version (rule_key) WHERE status = 'ACTIVE';

-- ============================================================
-- 稽核事件（取代 InMemoryAuditRepository 的落地版）
-- 欄位對齊現有 AuditService.AuditLog，遷移時零語意變更
-- ============================================================
CREATE TABLE audit_event (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    operation           VARCHAR(60)   NOT NULL,
    user_id             VARCHAR(120),
    version_id          VARCHAR(120),
    previous_version_id VARCHAR(120),
    rule_type           VARCHAR(40),
    reason              TEXT,
    success             BOOLEAN       NOT NULL
);

-- 稽核查詢模式：按時間倒序、按操作類別篩選
CREATE INDEX idx_audit_event_time ON audit_event (occurred_at DESC);
CREATE INDEX idx_audit_event_operation ON audit_event (operation, occurred_at DESC);
