package com.ruleengine.rules.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM 長工作逾時政策 — REST（ToolsController.asyncLlm）與 MCP（test_run_rules）共用，
 * 避免同一公式在兩個入口各寫一份而 drift。
 *
 * 公式：（LLM timeout + 60s 後處理緩衝）× case 數，再以可設定上限封頂。
 * 上限到頂時呼叫端須搭配取消機制（Future.cancel + 迴圈中斷檢查），逾時不白跑。
 */
@Component
public class LlmTimeoutPolicy {

    @Value("${rules.llm.timeout-seconds:180}")
    private int llmTimeoutSeconds;

    /** 多 case 端點（/test-run）的非同步逾時封頂 — 防止單一請求佔住 worker 過久 */
    @Value("${rules.llm.max-async-timeout-seconds:1800}")
    private int maxAsyncTimeoutSeconds;

    /** 單一 LLM 工作（/generate /explain /narrate /evaluate）的逾時毫秒數 */
    public long singleCallMs() {
        return asyncTimeoutMs(1);
    }

    /** 與 case 數成正比的逾時毫秒數（/test-run 等批次端點） */
    public long asyncTimeoutMs(int cases) {
        return Math.min((long) (llmTimeoutSeconds + 60) * cases, maxAsyncTimeoutSeconds) * 1000L;
    }
}
