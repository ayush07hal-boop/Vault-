package com.vault.security;

import com.vault.config.VaultProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Who may become an admin: an allowlisted email (proven by Google sign-in) plus the admin password. */
@Component
public class AdminGate {

    private static final Logger log = LoggerFactory.getLogger(AdminGate.class);

    private final List<String> allowedEmails;
    private final byte[] password;

    public AdminGate(VaultProperties props) {
        this.allowedEmails = props.admin().emails();
        String pw = props.admin().password();
        if (pw == null || pw.isBlank()) {
            byte[] raw = new byte[12];
            new SecureRandom().nextBytes(raw);
            pw = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            log.warn("No VAULT_ADMIN_PASSWORD set. Generated admin password for this run: {}", pw);
        }
        this.password = pw.getBytes(StandardCharsets.UTF_8);
        if (allowedEmails.isEmpty()) {
            log.warn("No VAULT_ADMIN_EMAILS configured: nobody can access the admin area.");
        }
    }

    public boolean isAllowed(String email) {
        return email != null && allowedEmails.contains(email.toLowerCase(Locale.ROOT).trim());
    }

    public boolean passwordMatches(String candidate) {
        return MessageDigest.isEqual(password, candidate == null ? new byte[0] : candidate.getBytes(StandardCharsets.UTF_8));
    }
}
