package com.ruleengine.rules.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登入爆破防護（資安收緊②）：帳號級失敗計數 + 暫時鎖定。
 *
 * <p>
 * 連續失敗達閾值（預設 5 次）後鎖定該帳號一段時間（預設 15 分鐘）——
 * 讓線上字典攻擊的成本從「毫秒一次」變成「15 分鐘 5 次」。
 * 搭配 /auth/login 的全域限流（RateLimitConfig "login"），形成兩層：
 * 帳號級擋「針對單一帳號的爆破」，全域級擋「大量帳號的噴灑（password spraying）」。
 * </p>
 *
 * <p>
 * <b>鎖定計數放 Caffeine（in-memory）而非資料庫 —— 有意的取捨：</b>
 * 攻擊者重啟不了我們的 app，計數器丟失只發生在部署時（可接受）；
 * 進 DB 反而讓爆破流量變成 DB 寫入放大。多實例部署時改 Redis 同理可換。
 * 鎖定狀態<b>不寫回</b> user_account.enabled —— 暫時鎖定與永久停權是兩個語意，
 * 混用會讓管理員誤解帳號狀態。
 * </p>
 *
 * <p>
 * 對外訊息刻意<b>不區分</b>「密碼錯」與「已鎖定」——鎖定回應若不同，
 * 攻擊者可用它探測「這個帳號存在且密碼快猜到了」。代價是真使用者也看不到
 * 鎖定提示（訊息引導稍後重試），這是資安與 UX 的權衡，選資安。
 * </p>
 */
@Service
@Slf4j
public class LoginAttemptService {

    private final int maxAttempts;
    private final Cache<String, AtomicInteger> attempts;

    public LoginAttemptService(
            @Value("${rules.security.login.max-attempts:5}") int maxAttempts,
            @Value("${rules.security.login.lockout-minutes:15}") long lockoutMinutes) {
        this.maxAttempts = maxAttempts;
        // expireAfterWrite = 鎖定窗口：最後一次失敗後 N 分鐘自動解鎖
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(lockoutMinutes))
                .maximumSize(100_000)   // 防攻擊者用海量帳號名灌爆記憶體
                .build();
    }

    /** 該帳號目前是否被鎖定。 */
    public boolean isLocked(String username) {
        if (username == null) return false;
        AtomicInteger count = attempts.getIfPresent(key(username));
        return count != null && count.get() >= maxAttempts;
    }

    /** 記一次失敗；回傳是否因此進入鎖定。 */
    public boolean recordFailure(String username) {
        if (username == null) return false;
        AtomicInteger count = attempts.get(key(username), k -> new AtomicInteger());
        int now = count.incrementAndGet();
        if (now == maxAttempts) {
            log.warn("SECURITY | 帳號 {} 連續失敗 {} 次，進入暫時鎖定", username, now);
            return true;
        }
        return false;
    }

    /** 登入成功即清零 —— 正常使用者打錯兩次後登入成功，不留殘餘計數。 */
    public void recordSuccess(String username) {
        if (username != null) attempts.invalidate(key(username));
    }

    private String key(String username) {
        return username.trim().toLowerCase();   // Maker / maker 是同一個帳號的爆破目標
    }
}
