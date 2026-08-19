package com.ruleengine.rules.persistence.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code user_account} 表的持久化模型（P1-S4）。
 *
 * <p>
 * roles 以逗號清單儲存（封閉小集合 MAKER/CHECKER/ADMIN，無「依角色查人」的
 * 存取模式 —— 關聯表在這裡是過度設計，理由詳見 V2 migration 檔頭）。
 * {@link #roleList()} 提供解析後的視圖。
 * </p>
 */
@Entity
@Table(name = "user_account")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 120)
    private String displayName;

    /** 逗號分隔，如 "MAKER" 或 "CHECKER,ADMIN"。 */
    @Column(nullable = false, length = 120)
    private String roles;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public List<String> roleList() {
        if (roles == null || roles.isBlank()) return List.of();
        return Arrays.stream(roles.split(",")).map(String::trim).filter(r -> !r.isEmpty()).toList();
    }
}
