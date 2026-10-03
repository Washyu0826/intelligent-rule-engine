-- ============================================================
-- V5: 審核工作台（目錄、標籤、送審理由與影響報告）
-- ============================================================
-- 目錄與標籤掛在 rule_key（規則身分）上，不掛版本 —— 換版本不應改變規則放在哪。
-- 影響報告在送審當下快照：主管看到的必須是送審時的差異與分析，
-- 之後即使生效中的版本改變，這份審核紀錄也不能事後漂移。
-- ============================================================

CREATE TABLE rule_directory (
    rule_key   VARCHAR(120) PRIMARY KEY,
    path       VARCHAR(400) NOT NULL,
    updated_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rule_directory_path ON rule_directory (path);

CREATE TABLE rule_tag (
    id        BIGSERIAL PRIMARY KEY,
    rule_key  VARCHAR(120) NOT NULL,
    dimension VARCHAR(60)  NOT NULL,
    tag       VARCHAR(120) NOT NULL,
    CONSTRAINT uq_rule_tag UNIQUE (rule_key, dimension, tag)
);

CREATE INDEX idx_rule_tag_dimension_tag ON rule_tag (dimension, tag);

ALTER TABLE rule_version ADD COLUMN submit_reason TEXT;
ALTER TABLE rule_version ADD COLUMN impact_report JSONB;
