package com.vault.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.config.VaultProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies ID tokens by asking Google's tokeninfo endpoint (Google checks the signature and expiry), then
 * checks audience (must be OUR client id), issuer and verified email locally.
 */
@Component
public class GoogleTokenInfoVerifier implements GoogleIdentityVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenInfoVerifier.class);
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final String clientId;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GoogleTokenInfoVerifier(VaultProperties props, ObjectMapper json) {
        this.clientId = props.auth().googleClientId();
        this.json = json;
    }

    @Override
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank();
    }

    @Override
    public Optional<Identity> verify(String idToken) {
        if (!isConfigured() || idToken == null || idToken.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(
                            "https://oauth2.googleapis.com/tokeninfo?id_token=" + URLEncoder.encode(idToken, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode n = json.readTree(res.body());
            boolean ok = clientId.equals(n.path("aud").asText())
                    && ISSUERS.contains(n.path("iss").asText())
                    && "true".equals(n.path("email_verified").asText())
                    && n.path("exp").asLong() > System.currentTimeMillis() / 1000;
            if (!ok || n.path("sub").asText().isEmpty() || n.path("email").asText().isEmpty()) {
                return Optional.empty();
            }
            String email = n.path("email").asText();
            return Optional.of(new Identity(n.path("sub").asText(), email,
                    n.path("name").asText(email), n.path("picture").asText(null)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("google token verification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
