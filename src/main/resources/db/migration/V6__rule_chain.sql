-- ============================================================
-- V6: 規則串接（線性流程：檢核 → 核保 → 費率）
-- ============================================================
-- 一條流程 = 有序的規則 key 清單。執行時逐步取各規則的 ACTIVE 版本：
-- 前一步的輸出併入下一步的輸入；檢核清單（MULTI）命中即中斷（stopOnHit）。
-- 流程本身不版本化、不送審 —— 它只是把已經各自審核過的規則排成一列。
-- ============================================================

CREATE TABLE rule_chain (
    chain_key  VARCHAR(120) PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    steps      JSONB        NOT NULL,
    updated_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
