package com.ruleengine.rules.service.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 中華民國身分證字號格式檢核工具。
 *
 * v3.14 場景驅動（case 1：ID + 國籍交叉檢核 family）的 caller 端 helper。
 * RuleEnvelope 自身只看 BOOLEAN `idFormatIsRocId`；本工具負責把原始 ID 字串
 * 算成那個 boolean，供呼叫端（test / 上游整合）使用。
 *
 * 規格決策（v3.14 case 1）：
 *   - 「身分證格式」= regex 第 2 碼為 1/2（本國身分證）+ 權重模 10 校驗碼通過。
 *   - 新式外來人口統一證號（第 2 碼 8/9）{@link #isNewResidentCertificate}
 *     算「非身分證格式」，由規則中的「非中華民國」分支處理。
 *   - 此分界依 需求方 對 A1 的回覆。若未來改成 8/9 也算身分證，
 *     只需 {@link #ROC_ID_PATTERN} 的 [1-2] 放寬成 [1-289]。
 *
 * 校驗碼演算法：
 *   1. 首字英文轉兩位數（A=10、B=11、I=34、O=35 …）
 *   2. 取得 11 個位元 [X1, X2, D1..D9]
 *   3. 權重 [1, 9, 8, 7, 6, 5, 4, 3, 2, 1, 1] 逐項相乘加總
 *   4. 加總 mod 10 = 0 為合法
 *
 * 此類別執行緒安全。
 */
public final class RocIdValidator {

    /** 本國身分證 regex：首字英文 + 第 2 碼 1（男）/ 2（女）+ 8 位數字。 */
    private static final Pattern ROC_ID_PATTERN = Pattern.compile("^[A-Z][1-2][0-9]{8}$");

    /** 新式外來人口統一證號 regex（2021/1/2 起）：首字英文 + 第 2 碼 8（男）/ 9（女）+ 8 位數字。 */
    private static final Pattern NEW_ARC_PATTERN = Pattern.compile("^[A-Z][8-9][0-9]{8}$");

    /** 權重表（位元數 = 11）。 */
    private static final int[] WEIGHTS = {1, 9, 8, 7, 6, 5, 4, 3, 2, 1, 1};

    /** 首字英文 → 兩位數字（內政部公告對照表）。 */
    private static final Map<Character, Integer> LETTER_VALUES = buildLetterValues();

    private static Map<Character, Integer> buildLetterValues() {
        Map<Character, Integer> m = new HashMap<>(26);
        m.put('A', 10); m.put('B', 11); m.put('C', 12); m.put('D', 13); m.put('E', 14);
        m.put('F', 15); m.put('G', 16); m.put('H', 17); m.put('I', 34); m.put('J', 18);
        m.put('K', 19); m.put('L', 20); m.put('M', 21); m.put('N', 22); m.put('O', 35);
        m.put('P', 23); m.put('Q', 24); m.put('R', 25); m.put('S', 26); m.put('T', 27);
        m.put('U', 28); m.put('V', 29); m.put('W', 32); m.put('X', 30); m.put('Y', 31);
        m.put('Z', 33);
        return Collections.unmodifiableMap(m);
    }

    private RocIdValidator() {}

    /**
     * 是否為本國身分證字號格式（含校驗碼）。
     * 用於 case 1 規則的 {@code idFormatIsRocId} BOOLEAN 輸入。
     *
     * @param id 完整身分證號（10 字元）；null / 空字串 / 格式不符皆回 false
     * @return regex 與校驗碼皆通過時回 true
     */
    public static boolean isRocId(String id) {
        if (id == null || !ROC_ID_PATTERN.matcher(id).matches()) {
            return false;
        }
        return checksum(id) % 10 == 0;
    }

    /**
     * 是否為新式外來人口統一證號（2021/1/2 起，格式比照身分證；第 2 碼 8/9）。
     * 預留給後續若 需求方 對 A1 回覆「8/9 也算身分證格式」時使用。
     */
    public static boolean isNewResidentCertificate(String id) {
        if (id == null || !NEW_ARC_PATTERN.matcher(id).matches()) {
            return false;
        }
        return checksum(id) % 10 == 0;
    }

    private static int checksum(String id) {
        int letterValue = LETTER_VALUES.get(id.charAt(0));
        int sum = (letterValue / 10) * WEIGHTS[0] + (letterValue % 10) * WEIGHTS[1];
        for (int i = 0; i < 9; i++) {
            int digit = id.charAt(i + 1) - '0';
            sum += digit * WEIGHTS[i + 2];
        }
        return sum;
    }
}
