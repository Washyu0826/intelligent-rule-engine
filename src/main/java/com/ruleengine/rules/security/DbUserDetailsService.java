package com.ruleengine.rules.security;

import com.ruleengine.rules.persistence.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 從 {@code user_account} 表載入使用者（P1-S4）。
 *
 * <p>
 * 只服務「登入驗證」這一刻 —— 登入成功後身分完全由 JWT 攜帶
 * （stateless：之後的每個請求都不會再查這張表）。這是 JWT 模式的核心取捨：
 * 省掉每請求一次 DB 查詢，代價是「停權要等 token 過期才完全生效」——
 * 因此 token TTL 收在 60 分鐘（見 JwtService）。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var account = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("no such user: " + username));
        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .authorities(account.roleList().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList())
                .disabled(!account.isEnabled())
                .build();
    }
}
