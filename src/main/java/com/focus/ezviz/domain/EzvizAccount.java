package com.focus.ezviz.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 萤石账号（开放平台应用）配置。
 * appKey / appSecret 在萤石开放平台「我的应用」中获取，accessToken 由后端自动换取并缓存。
 */
@Entity
@Table(name = "t_ezviz_account")
public class EzvizAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 128)
    private String appKey;

    @Column(nullable = false, length = 256)
    private String appSecret;

    /** 手动指定 token（可选），留空则由 appKey/appSecret 自动换取 */
    @Column(length = 512)
    private String accessToken;

    private LocalDateTime tokenExpiresAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 500)
    private String lastCheckMessage;

    private LocalDateTime lastCheckAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public LocalDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLastCheckMessage() { return lastCheckMessage; }
    public void setLastCheckMessage(String lastCheckMessage) { this.lastCheckMessage = lastCheckMessage; }
    public LocalDateTime getLastCheckAt() { return lastCheckAt; }
    public void setLastCheckAt(LocalDateTime lastCheckAt) { this.lastCheckAt = lastCheckAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
