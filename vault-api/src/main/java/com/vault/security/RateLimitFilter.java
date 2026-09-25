package com.vault.security;

import com.vault.config.VaultProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Per-client request limit (429 + Retry-After). Client = API key if sent, otherwise remote address. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(name = "vault.rate-limit.enabled", havingValue = "true")
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitStore store;
    private final int limit;

    public RateLimitFilter(RateLimitStore store, VaultProperties props) {
        this.store = store;
        this.limit = props.rateLimit().requestsPerMinute();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = req.getHeader("X-API-Key");
        String client = apiKey != null ? "key:" + apiKey : "ip:" + req.getRemoteAddr();
        if (store.increment(client, 60) > limit) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Rate limit exceeded\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
