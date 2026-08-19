-- ============================================================
-- V3: 決策軌跡（P2-S3）—— insert-only 的執行證據
-- ============================================================
-- 設計決策：
--
-- 1. 回放三要素為 NOT NULL
--    rule_version_id（哪一版規則）+ input_snapshot（當時輸入）+ engine_version
--    （哪套語意）缺一則該紀錄永遠無法回放 —— 所以是欄位約束，不是程式約定。
--
-- 2. DB 級不可變（trigger 禁 UPDATE/DELETE）
--    金融稽核要求決策紀錄寫後不可改（write-once）。程式層「不寫 update」只是
--    紀律；trigger 讓資料庫直接拒絕 —— 含未來任何人用 psql 手改。
--    保存政策（如 7 年後歸檔）屆時用表分割 + DROP PARTITION 實作：
--    DDL 不觸發 row-level trigger，不與本設計衝突。
--
-- 3. trace 明細與頂層欄位分離
--    matched / outputs / matched_rule_ids 提升為一級欄位（查詢用），
--    steps 明細整包存 trace_detail JSONB（豐富度由 trace_level 決定）。
-- ============================================================

CREATE TABLE decision_trace (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 回放三要素（NOT NULL：缺一即不可回放）
    rule_version_id  BIGINT       NOT NULL REFERENCES rule_version(id),
    input_snapshot   JSONB        NOT NULL,
    engine_version   VARCHAR(40)  NOT NULL,
    -- 冗餘 rule_key：免 JOIN 的常用查詢鍵（值不可變，無一致性風險）
    rule_key         VARCHAR(120) NOT NULL,
    -- 決策結果（一級欄位，供 SQL 篩選統計）
    matched          BOOLEAN      NOT NULL,
    outputs          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    matched_rule_ids JSONB        NOT NULL DEFAULT '[]'::jsonb,
    -- 明細（steps 等；豐富度由 trace_level 決定）
    trace_level      VARCHAR(10)  NOT NULL CHECK (trace_level IN ('NONE','SUMMARY','FULL')),
    trace_detail     JSONB,
    -- 歸因
    executed_by      VARCHAR(120) NOT NULL DEFAULT 'anonymous',
    executed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    duration_nanos   BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_decision_trace_key_time ON decision_trace (rule_key, executed_at DESC);
CREATE INDEX idx_decision_trace_version ON decision_trace (rule_version_id);

-- ── DB 級不可變（決策 2）──
CREATE FUNCTION forbid_decision_trace_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'decision_trace 是 append-only 稽核證據，禁止 % 操作', TG_OP
        USING HINT = '保存政策請用表分割 + DROP PARTITION，不要改列';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_decision_trace_immutable
    BEFORE UPDATE OR DELETE ON decision_trace
    FOR EACH ROW EXECUTE FUNCTION forbid_decision_trace_mutation();
