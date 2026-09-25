package com.vault.api;

import com.vault.config.VaultProperties;
import com.vault.error.VaultException;
import com.vault.metadata.UserEntity;
import com.vault.security.AdminGate;
import com.vault.security.AuthFilter;
import com.vault.security.GoogleIdentityVerifier;
import com.vault.security.RateLimitStore;
import com.vault.security.Tokens;
import com.vault.security.UserPrincipal;
import com.vault.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Sign-in (Google, or dev-only email login), account info, and the admin elevation step.
 * Admin access needs (1) a signed-in account whose email is allowlisted and (2) the admin password.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final int MAX_ATTEMPTS_PER_MINUTE = 10;

    private final Tokens tokens;
    private final AdminGate gate;
    private final GoogleIdentityVerifier google;
    private final UserService users;
    private final RateLimitStore limiter;
    private final VaultProperties props;
    private final com.vault.service.NodeService nodes;

    public AuthController(Tokens tokens, AdminGate gate, GoogleIdentityVerifier google, UserService users,
                          RateLimitStore limiter, VaultProperties props,
                          com.vault.service.NodeService nodes) {
        this.nodes = nodes;
        this.tokens = tokens;
        this.gate = gate;
        this.google = google;
        this.users = users;
        this.limiter = limiter;
        this.props = props;
    }

    /** maxCopies = storage servers available (one copy per server); defaultCopies = configured replication factor. */
    public record Config(String googleClientId, boolean devLogin, int maxCopies, int defaultCopies) {
    }

    public record GoogleLogin(String credential) {
    }

    public record DevLogin(String email) {
    }

    public record AdminLogin(String password) {
    }

    public record UserView(String email, String name, String picture, boolean canAdmin, boolean admin) {
    }

    public record Session(String token, Instant expiresAt, UserView user) {
    }

    public record AdminSession(String token, Instant expiresAt) {
    }

    /** Public: tells the frontend how sign-in works. */
    @GetMapping("/config")
    public Config config() {
        int servers = Math.max(1, Math.min(16, nodes.all().size()));
        return new Config(google.isConfigured() ? props.auth().googleClientId() : "", props.auth().devLogin(), servers,
                Math.min(servers, props.replication().factor()));
    }

    @PostMapping("/google")
    public Session google(@RequestBody GoogleLogin body, HttpServletRequest request) {
        throttle(request);
        GoogleIdentityVerifier.Identity id = google.verify(body.credential())
                .orElseThrow(() -> new VaultException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                        "Google sign-in could not be verified", null));
        return session(users.upsert(id.subject(), id.email(), id.name(), id.picture()));
    }

    /** Local demos only (vault.auth.dev-login=true): signs in as any email without Google. */
    @PostMapping("/dev-login")
    public Session devLogin(@RequestBody DevLogin body, HttpServletRequest request) {
        if (!props.auth().devLogin()) {
            throw new VaultException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", null);
        }
        throttle(request);
        String email = body.email() == null ? "" : body.email().trim();
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw VaultException.badRequest("Enter a valid email address");
        }
        return session(users.upsert("dev:" + email.toLowerCase(), email, email.substring(0, email.indexOf('@')), null));
    }

    @GetMapping("/me")
    public UserView me(HttpServletRequest request) {
        UserPrincipal p = AuthFilter.user(request).orElseThrow();
        UserEntity u = users.find(p.userId()).orElseThrow(() -> new VaultException(HttpStatus.UNAUTHORIZED,
                "USER_REQUIRED", "Please sign in", null));
        return view(u, AuthFilter.isAdmin(request));
    }

    /**
     * Second step for allowlisted accounts. Everyone else gets 404, so the admin area does not even appear to exist.
     */
    @PostMapping("/admin-login")
    public AdminSession adminLogin(@RequestBody AdminLogin body, HttpServletRequest request) {
        UserPrincipal p = AuthFilter.user(request).orElseThrow();
        if (!gate.isAllowed(p.email())) {
            throw new VaultException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", null);
        }
        throttle(request);
        if (!gate.passwordMatches(body.password())) {
            throw new VaultException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Wrong admin password", null);
        }
        Tokens.Issued issued = tokens.issue(Tokens.Kind.ADMIN, p.userId(), p.email());
        return new AdminSession(issued.token(), issued.expiresAt());
    }

    private Session session(UserEntity u) {
        Tokens.Issued issued = tokens.issue(Tokens.Kind.USER, u.getUserId(), u.getEmail());
        return new Session(issued.token(), issued.expiresAt(), view(u, false));
    }

    private UserView view(UserEntity u, boolean admin) {
        return new UserView(u.getEmail(), u.getName(), u.getPicture(), gate.isAllowed(u.getEmail()), admin);
    }

    /** Brute-force guard shared by all credential-checking endpoints. */
    private void throttle(HttpServletRequest request) {
        if (limiter.increment("auth:" + request.getRemoteAddr(), 60) > MAX_ATTEMPTS_PER_MINUTE) {
            throw new VaultException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Too many attempts. Try again in a minute.", null);
        }
    }
}
