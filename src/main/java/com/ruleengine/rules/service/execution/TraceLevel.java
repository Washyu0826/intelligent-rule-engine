package com.ruleengine.rules.service.execution;

/**
 * 決策軌跡的詳細度（P2-S2；文獻調查修正點①：trace 有成本，必須可開關）。
 *
 * <ul>
 *   <li>{@link #NONE} —— 只回結果，零 trace 開銷（高頻執行路徑）。</li>
 *   <li>{@link #SUMMARY} —— 記命中的規則/走過的節點，不記逐條件明細
 *       （日常稽核：知道「哪條規則判的」即可）。</li>
 *   <li>{@link #FULL} —— 逐規則、逐條件、含解析後期望值與輸入快照
 *       （爭議調查 / 回放驗證 / 監理詢問）。</li>
 * </ul>
 */
public enum TraceLevel {
    NONE,
    SUMMARY,
    FULL
}
