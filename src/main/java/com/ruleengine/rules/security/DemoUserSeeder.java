package com.ruleengine.rules.security;

import com.ruleengine.rules.persistence.user.UserAccountEntity;
import com.ruleengine.rules.persistence.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Demo 帳號 seeder（P1-S4）。
 *
 * <p>
 * <b>為什麼用程式 seed 而非 migration INSERT：</b>schema 與資料分離 ——
 * migration 裡寫死的 BCrypt hash 無法輪替、無法按環境開關；這裡密碼由設定注入、
 * hash 執行期算、prod 設 {@code rules.security.demo-users.enabled=false} 整個關閉。
 * </p>
 *
 * <p>冪等：帳號已存在就跳過，重啟不會重複建立也不會重設密碼。</p>
 *
 * <p>三個角色對應 maker-checker 審核流（P2）：
 * maker 只能起草/送審，checker 只能核准/退回，admin 兩者皆可 + 管理操作。</p>
 */
@Component
@ConditionalOnProperty(name = "rules.security.demo-users.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DemoUserSeeder implements ApplicationRunner {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    @Value("${rules.security.demo-users.password:demo-pass-2026}")
    private String demoPassword;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        seed("maker",   "規則制單員", "MAKER");
        seed("checker", "規則審核員", "CHECKER");
        seed("admin",   "系統管理員", "MAKER,CHECKER,ADMIN");
    }

    private void seed(String username, String displayName, String roles) {
        if (users.existsByUsername(username)) return;
        OffsetDateTime now = OffsetDateTime.now();
        users.save(UserAccountEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(demoPassword))
                .displayName(displayName)
                .roles(roles)
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("Demo 帳號已建立 | username={} | roles={}", username, roles);
    }
}
