-- ============================================================
-- V2: 使用者帳號（P1-S4 JWT + RBAC 的身分地基）
-- ============================================================
-- 設計決策：
--
-- 1. roles 用 VARCHAR 逗號清單而非關聯表
--    本系統角色是封閉小集合（MAKER/CHECKER/ADMIN，maker-checker 語意），
--    一個使用者最多 2-3 個角色，沒有「查詢擁有某角色的所有人」的存取模式。
--    user_role 關聯表是為開放式角色系統準備的 —— 這裡會是過度設計。
--    應用層以 CHECK 之外的驗證守住合法值；若未來角色開放化，V(n) 再拆表。
--
-- 2. 不在 migration 塞 demo 帳號
--    schema 與資料分離 —— demo 帳號由 DemoUserSeeder 程式化建立
--    （BCrypt hash 執行期算、密碼可由設定注入、prod 可整個關閉）。
--    migration 裡的寫死 hash 無法輪替也無法按環境關閉。
--
-- 3. enabled 旗標而非刪除
--    金融稽核：離職/停權使用者的歷史操作紀錄必須可歸因，帳號只停用不刪除。
-- ============================================================

CREATE TABLE user_account (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(120)  NOT NULL UNIQUE,
    -- BCrypt hash（含演算法前綴 {bcrypt} 的 Spring DelegatingPasswordEncoder 格式，
    -- 60-72 字元；欄寬 100 留未來換 argon2 的空間）
    password_hash VARCHAR(100)  NOT NULL,
    display_name  VARCHAR(120),
    -- 逗號分隔角色清單，如 'MAKER' 或 'CHECKER,ADMIN'（決策 1）
    roles         VARCHAR(120)  NOT NULL,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
