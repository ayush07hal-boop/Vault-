package com.vault.security;

import com.vault.config.VaultProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Stateless signed session tokens: base64url(kind, userId, email, expiry) + "." + base64url(HMAC-SHA256).
 * "user" tokens identify a signed-in account; "admin" tokens are issued on top of one after the admin
 * password check. No server-side session store, so several controller instances work if they share
 * VAULT_TOKEN_SECRET.
 */
@Component
public class Tokens {

    public enum Kind { USER, ADMIN }

    public record Claims(String userId, String email) {
    }

    public record Issued(String token, Instant expiresAt) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final String SEP = "\n";

    private final byte[] secret;
    private final long userTtl;
    private final long adminTtl;

    public Tokens(VaultProperties props) {
        String s = props.admin().tokenSecret();
        if (s == null || s.isBlank()) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            this.secret = raw;
        } else {
            this.secret = s.getBytes(StandardCharsets.UTF_8);
        }
        this.userTtl = props.admin().userTokenTtlMinutes() * 60L;
        this.adminTtl = props.admin().adminTokenTtlMinutes() * 60L;
    }

    public Issued issue(Kind kind, String userId, String email) {
        Instant exp = Instant.now().plusSeconds(kind == Kind.USER ? userTtl : adminTtl);
        String payload = B64.encodeToString(
                (kind + SEP + userId + SEP + email + SEP + exp.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        return new Issued(payload + "." + sign(payload), exp);
    }

    public Optional<Claims> verify(Kind kind, String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).split(SEP, 4);
            if (parts.length != 4 || !parts[0].equals(kind.name())
                    || Instant.now().getEpochSecond() >= Long.parseLong(parts[3])) {
                return Optional.empty();
            }
            return Optional.of(new Claims(parts[1], parts[2]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
