package com.ruleengine.rules.config;

import com.ruleengine.rules.service.llm.LlmProvider;
import com.ruleengine.rules.service.llm.LlmProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 啟動時檢查「設定要用的 LLM provider」是否真的具備可用憑證。
 *
 * <p>
 * <b>解決的問題：</b>provider 憑證缺失時，{@code isAvailable()} 回 false，
 * generator 會直接走 offline scenario / stub fallback —— 服務照常回 200、
 * 健康檢查照樣 UP、回應長得跟正常生成一模一樣，只是內容變成預存的罐頭規則。
 * 在核保情境下，這種「看起來正常的錯答案」比直接失敗危險得多。
 * </p>
 *
 * <p>
 * 過去這件事特別容易發生，因為設定散在多個名字上：
 * {@code application-prod.yml} 綁 {@code RULES_LLM_API_KEY}，但
 * {@code .env.example} / {@code docker-compose.yml} / k8s Deployment 注入的
 * 全都是 {@code CLAUDE_API_KEY} —— 照文件部署必然拿到空字串。
 * </p>
 *
 * <p>
 * 行為：一律以 ERROR 記錄可行動的訊息（缺哪個 provider、該設哪個環境變數）；
 * 另在 {@code rules.llm.fail-fast=true}（prod profile 預設開啟）時直接讓啟動失敗。
 * </p>
 */
@Component
@Slf4j
public class LlmCredentialCheck {

    private final LlmProviderRegistry registry;

    @Value("${rules.llm.enabled:true}")
    private boolean llmEnabled;

    @Value("${rules.llm.provider:}")
    private String configuredProvider;

    @Value("${rules.llm.fail-fast:false}")
    private boolean failFast;

    public LlmCredentialCheck(LlmProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 用 ApplicationReadyEvent 而非 @PostConstruct —— 需要等所有 provider 的
     * @PostConstruct（@Value 注入後才讀得到 key）都跑完。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        if (!llmEnabled) {
            log.info("LLM 已停用（rules.llm.enabled=false）—— 規則生成改由 MCP client 端負責，跳過憑證檢查");
            return;
        }

        // 嚴格比對「設定要用的那一家」——getDefault() 會靜默改用其他可用 provider，
        // 拿它來檢查等於自己騙自己（見 LlmProviderRegistry.getConfigured javadoc）。
        LlmProvider provider = registry.getConfigured();
        if (provider != null && provider.isAvailable()) {
            log.info("LLM 憑證檢查通過 | provider={} | 可用清單={}",
                    provider.getProviderName(), registry.getAvailableProviderNames());
            return;
        }

        String reason = provider == null
                ? "rules.llm.provider=\"" + configuredProvider + "\" 沒有對應的已註冊 provider（拼錯或未設定）"
                : provider.getProviderName() + " 已註冊但憑證未設定";
        String message = """
                設定的 LLM provider 無法使用：%s
                  設定值 rules.llm.provider = %s
                  目前可用的 provider = %s
                  請設定對應的環境變數（claude → CLAUDE_API_KEY、gemini → GEMINI_API_KEY、
                  openai → OPENAI_API_KEY、ollama → RULES_LLM_OLLAMA_BASE_URL）。
                在此狀態下 /tools/generate 仍會回 200，但內容來自離線情境或 stub 樣板，
                而非真正的規則生成 —— 不要把這個狀態當成正常運作。""".formatted(
                reason, configuredProvider, registry.getAvailableProviderNames());

        if (failFast) {
            log.error("LLM 憑證檢查失敗（rules.llm.fail-fast=true，中止啟動）\n{}", message);
            throw new IllegalStateException("LLM provider 無法使用：" + reason);
        }
        log.error("LLM 憑證檢查失敗（rules.llm.fail-fast=false，繼續啟動但功能降級）\n{}", message);
    }
}
