package com.vault.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS as a servlet filter that runs BEFORE the API-key and rate-limit filters, so preflight requests are
 * answered without credentials and 401/429 responses still carry CORS headers (otherwise the browser hides them).
 */
@Configuration
public class CorsConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(VaultProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "X-API-Key", "If-Match", "Idempotency-Key", "Authorization", "X-Admin-Token"));
        cfg.setExposedHeaders(List.of("ETag", "Location", "Content-Disposition", "Content-Length", "X-Vault-Version",
                "Idempotent-Replay", "Retry-After"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
