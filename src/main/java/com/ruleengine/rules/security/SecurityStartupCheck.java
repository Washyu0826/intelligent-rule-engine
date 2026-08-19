package com.ruleengine.rules.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 資安設定的啟動檢查（資安收緊③④）—— 沿用 LlmCredentialCheck 的哲學：
 * <b>設定錯誤時寧可開不起來，也不要帶著空殼防護上線。</b>
 *
 * <p>檢查項目：</p>
 * <ul>
 *   <li>JWT secret 長度 &lt; 32 bytes → 任何模式都直接失敗
 *       （HS256 規範下限；Nimbus 會在第一次簽發時才炸，這裡把失敗提前到啟動）</li>
 *   <li>enforce 模式 + secret 是 dev 預設值 → 失敗
 *       （dev 預設值在 git 裡是公開的 —— 拿它跑 enforce 等於沒有簽章）</li>
 *   <li>API key 認證啟用但 key 為空 → 失敗
 *       （原行為：啟動成功但所有請求 403 —— 看起來像「保護很嚴」實際是設定壞了）</li>
 * </ul>
 */
@Component
@Slf4j
public class SecurityStartupCheck {

    /** 與 SecurityConfig 的 dev 預設值同步（測試會驗證兩者一致，防止改一漏一）。 */
    public static final String DEV_DEFAULT_SECRET = "local-dev-only-secret-change-me-0123456789abcdef";

    @Value("${rules.security.jwt.mode:permissive}")
    private String jwtMode;

    @Value("${rules.security.jwt.secret:" + DEV_DEFAULT_SECRET + "}")
    private String jwtSecret;

    @Value("${rules.security.api-key-enabled:false}")
    private boolean apiKeyEnabled;

    @Value("${rules.security.api-key:}")
    private String apiKey;

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        int secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < 32) {
            throw new IllegalStateException(
                    "JWT secret 只有 " + secretBytes + " bytes —— HS256 規範要求至少 32 bytes（256 bits）。"
                            + " 請設定 RULES_JWT_SECRET 為足夠長的隨機值。");
        }
        boolean enforce = "enforce".equalsIgnoreCase(jwtMode);
        if (enforce && DEV_DEFAULT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "enforce 模式不可使用 dev 預設 JWT secret（它在版本庫裡是公開的，"
                            + "任何人都能偽造 token）。請設定 RULES_JWT_SECRET。");
        }
        if (apiKeyEnabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException(
                    "rules.security.api-key-enabled=true 但 api-key 為空 —— 所有 /tools/** 請求"
                            + "將一律 403。請設定 RULES_API_KEY，或關閉 api-key-enabled。");
        }
        log.info("資安啟動檢查通過 | jwt.mode={} | secretBytes={} | apiKeyEnabled={}",
                jwtMode, secretBytes, apiKeyEnabled);
    }
}
