package com.vault.security;

import com.vault.security.Tokens.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Establishes who is calling and enforces coarse access rules:
 * <ul>
 *   <li>{@code Authorization: Bearer <user token>} identifies a signed-in account;</li>
 *   <li>{@code X-Admin-Token} is an admin elevation, honoured only while the account's email is still allowlisted;</li>
 *   <li>objects and account endpoints need a user; cluster internals need an admin.</li>
 * </ul>
 * Per-object ownership is enforced in the controllers/services.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class AuthFilter extends OncePerRequestFilter {

    private static final String USER_ATTR = "vault.user";
    private static final String ADMIN_ATTR = "vault.admin";

    private static final List<String> USER_REQUIRED = List.of("/api/v1/objects", "/api/v1/projects", "/api/v1/auth/me", "/api/v1/auth/admin-login");
    private static final List<String> ADMIN_ONLY = List.of("/api/v1/admin", "/api/v1/nodes", "/api/v1/stats", "/api/v1/health");

    private final Tokens tokens;
    private final AdminGate gate;

    public AuthFilter(Tokens tokens, AdminGate gate) {
        this.tokens = tokens;
        this.gate = gate;
    }

    public static Optional<UserPrincipal> user(HttpServletRequest request) {
        return Optional.ofNullable((UserPrincipal) request.getAttribute(USER_ATTR));
    }

    public static boolean isAdmin(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ADMIN_ATTR));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String bearer = req.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            tokens.verify(Tokens.Kind.USER, bearer.substring(7).trim())
                    .ifPresent(c -> req.setAttribute(USER_ATTR, new UserPrincipal(c.userId(), c.email())));
        }
        Optional<Claims> admin = tokens.verify(Tokens.Kind.ADMIN, req.getHeader("X-Admin-Token"));
        UserPrincipal user = user(req).orElse(null);
        if (admin.isPresent() && user != null && admin.get().userId().equals(user.userId())
                && gate.isAllowed(user.email())) {
            req.setAttribute(ADMIN_ATTR, true);
        }

        String path = req.getRequestURI();
        if (ADMIN_ONLY.stream().anyMatch(path::startsWith) && !isAdmin(req)) {
            reject(res, "ADMIN_REQUIRED", "Admin login required");
        } else if (USER_REQUIRED.stream().anyMatch(path::startsWith) && user == null) {
            reject(res, "USER_REQUIRED", "Please sign in");
        } else {
            chain.doFilter(req, res);
        }
    }

    private static void reject(HttpServletResponse res, String code, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
