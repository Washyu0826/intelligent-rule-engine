-- ============================================================
-- V4: 審核工作流欄位（P2-S4 maker-checker）
-- ============================================================
-- rule_version 補「誰送審、誰審核、審核意見」——
-- maker-checker 的可歸因性：每個狀態轉移都要能回答「誰、何時、為什麼」。
-- （轉移本身的事件同步落 audit_event，這裡是版本列上的「目前狀態快照」。）
-- ============================================================

ALTER TABLE rule_version ADD COLUMN submitted_by   VARCHAR(120);
ALTER TABLE rule_version ADD COLUMN submitted_at   TIMESTAMPTZ;
ALTER TABLE rule_version ADD COLUMN reviewed_by    VARCHAR(120);
ALTER TABLE rule_version ADD COLUMN reviewed_at    TIMESTAMPTZ;
ALTER TABLE rule_version ADD COLUMN review_comment TEXT;

-- 審核佇列的熱查詢（REVIEW 狀態按送審時間排序）已由 idx_rule_version_key_status 部分覆蓋；
-- 佇列是全 key 掃描 → 補一個狀態+時間索引
CREATE INDEX idx_rule_version_status_submitted ON rule_version (status, submitted_at);
