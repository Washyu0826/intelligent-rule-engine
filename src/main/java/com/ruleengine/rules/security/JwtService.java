package com.ruleengine.rules.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * JWT 簽發（P1-S4）。
 *
 * <p>
 * <b>HS256（對稱）而非 RS256（非對稱）—— 有意的選擇：</b>
 * 官方指引：對稱 key 適用 self-issued token（同一服務自己簽、自己驗）。
 * RS256 的價值在「公鑰可分發」—— 當出現第二個需要驗 token 的服務
 * （例如拆出獨立的執行引擎服務）時，換 {@code NimbusJwtEncoder} 的
 * RSA JWK 建構式即可，本類別介面不變。在那之前，HS256 少一組
 * 金鑰對管理成本，且效能更好。
 * </p>
 *
 * <p>
 * <b>TTL 60 分鐘：</b>JWT 是 stateless —— 停權/改權限要等 token 過期才完全生效，
 * TTL 就是這個空窗的上限。60 分鐘是「demo 便利」與「金融場景收斂」的折衷；
 * refresh token 刻意不做（單頁應用重新登入成本低，refresh token 的
 * 儲存與輪替是另一整包複雜度）—— 這是面試可講的取捨，不是遺漏。
 * </p>
 */
@Service
public class JwtService {

    public static final String ISSUER = "rules-mcp-server";
    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;

    @Value("${rules.security.jwt.ttl-minutes:60}")
    private long ttlMinutes;

    public JwtService(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    /** 為通過帳密驗證的使用者簽發 access token。 */
    public IssuedToken issue(Authentication authentication) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlMinutes * 60);
        // authorities 是 ROLE_MAKER 形式；claim 存純角色名（MAKER），
        // 解碼側由 JwtGrantedAuthoritiesConverter 加回 ROLE_ 前綴（見 SecurityConfig）
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(authentication.getName())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(ROLES_CLAIM, roles)
                .build();

        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, expiresAt, authentication.getName(), roles);
    }

    public record IssuedToken(String token, Instant expiresAt, String username, List<String> roles) {}
}
