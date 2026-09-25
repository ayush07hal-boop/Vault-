package com.vault.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "google_sub", nullable = false, unique = true)
    private String googleSub;

    @Column(nullable = false, unique = true)
    private String email;

    private String name;

    @Column(length = 1000)
    private String picture;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login", nullable = false)
    private Instant lastLogin;

    protected UserEntity() {
    }

    public UserEntity(String userId, String googleSub, String email, String name, String picture) {
        this.userId = userId;
        this.googleSub = googleSub;
        this.email = email;
        this.name = name;
        this.picture = picture;
        this.createdAt = Instant.now();
        this.lastLogin = this.createdAt;
    }

    public String getUserId() {
        return userId;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getPicture() {
        return picture;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLogin() {
        return lastLogin;
    }

    public void touch(String email, String name, String picture) {
        this.email = email;
        this.name = name;
        this.picture = picture;
        this.lastLogin = Instant.now();
    }
}
