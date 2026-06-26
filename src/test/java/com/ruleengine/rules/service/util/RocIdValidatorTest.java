package com.ruleengine.rules.service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中華民國身分證字號檢核工具測試（v3.14 case 1 family）。
 *
 * 涵蓋：
 *   - regex 第 2 碼分界（1/2 = 本國 ✓、8/9 = 新統號 ✗，依 A1 預設決策）
 *   - 校驗碼模 10 演算法（含 A123456789 經典 fixture）
 *   - 邊界：null、空、長度不足、英文小寫、純數字
 *   - 新統號路徑 {@link RocIdValidator#isNewResidentCertificate}
 */
class RocIdValidatorTest {

    // ================================================================
    // 合法本國身分證字號
    // ================================================================

    @Test
    @DisplayName("經典測試號 A123456789 → 校驗碼通過")
    void validRocId_classicTestCase() {
        assertTrue(RocIdValidator.isRocId("A123456789"));
    }

    @Test
    @DisplayName("不同首字英文（B/I/O/Z 含特殊對照值）皆能正確校驗")
    void validRocId_variousLetters() {
        // 以下皆為演算法合法的範例（手算校驗碼通過）
        assertTrue(RocIdValidator.isRocId("B142610731"));
        assertTrue(RocIdValidator.isRocId("Z100000002"));
    }

    // ================================================================
    // 校驗碼失敗
    // ================================================================

    @Test
    @DisplayName("regex 過但校驗碼錯 → false（A123456788）")
    void invalidRocId_checksumFail() {
        assertFalse(RocIdValidator.isRocId("A123456788"));
    }

    @Test
    @DisplayName("regex 過但校驗碼錯 → false（A111111111）")
    void invalidRocId_allOnesChecksumFail() {
        assertFalse(RocIdValidator.isRocId("A111111111"));
    }

    // ================================================================
    // 新式外來人口統一證號（第 2 碼 8/9）— 依 A1 決策應為「非身分證格式」
    // ================================================================

    @Test
    @DisplayName("新統號（第 2 碼 8）→ isRocId 回 false")
    void newArcMale_notRocId() {
        // 第 2 碼 8 = 新統號（男）；isRocId 預設視為非身分證格式
        assertFalse(RocIdValidator.isRocId("A800000014"));
    }

    @Test
    @DisplayName("新統號（第 2 碼 9）→ isRocId 回 false")
    void newArcFemale_notRocId() {
        // 第 2 碼 9 = 新統號（女）
        assertFalse(RocIdValidator.isRocId("A923456788"));
    }

    @Test
    @DisplayName("isNewResidentCertificate 能獨立判斷新統號")
    void newArcPath_separate() {
        // A800000014 是新統號合法格式（手算 mod 10 = 0）
        assertTrue(RocIdValidator.isNewResidentCertificate("A800000014"));
        // 本國身分證走這條路會回 false
        assertFalse(RocIdValidator.isNewResidentCertificate("A123456789"));
    }

    // ================================================================
    // 格式不符（regex 攔截）
    // ================================================================

    @Test
    @DisplayName("null → false（不拋例外）")
    void nullInput() {
        assertFalse(RocIdValidator.isRocId(null));
        assertFalse(RocIdValidator.isNewResidentCertificate(null));
    }

    @Test
    @DisplayName("空字串 → false")
    void emptyString() {
        assertFalse(RocIdValidator.isRocId(""));
    }

    @Test
    @DisplayName("長度不足 → false")
    void lengthTooShort() {
        assertFalse(RocIdValidator.isRocId("A12345678"));
    }

    @Test
    @DisplayName("長度超過 → false")
    void lengthTooLong() {
        assertFalse(RocIdValidator.isRocId("A1234567890"));
    }

    @Test
    @DisplayName("英文小寫 → false（regex 要求大寫）")
    void lowercaseLetter() {
        assertFalse(RocIdValidator.isRocId("a123456789"));
    }

    @Test
    @DisplayName("純數字（無首字英文）→ false")
    void allDigits() {
        assertFalse(RocIdValidator.isRocId("1234567890"));
    }

    @Test
    @DisplayName("第 2 碼為 0 / 3-7 → false（非合法性別碼）")
    void invalidSecondDigit() {
        assertFalse(RocIdValidator.isRocId("A023456789"));
        assertFalse(RocIdValidator.isRocId("A323456789"));
        assertFalse(RocIdValidator.isRocId("A723456789"));
    }
}
