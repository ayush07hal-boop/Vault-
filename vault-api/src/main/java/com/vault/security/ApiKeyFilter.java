package com.vault.security;

import com.vault.config.VaultProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Requires X-API-Key on /api/** when vault.security.api-key is set. Actuator stays open for scrapers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public ApiKeyFilter(VaultProperties props) {
        String key = props.security().apiKey();
        this.expected = key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return expected == null || "OPTIONS".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader("X-API-Key");
        if (given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-API-Key\"}");
    }
}
