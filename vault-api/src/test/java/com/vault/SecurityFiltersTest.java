package com.vault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {"vault.security.api-key=s3cret", "vault.rate-limit.enabled=true",
        "vault.rate-limit.requests-per-minute=5"})
class SecurityFiltersTest extends AbstractClusterTest {

    @Autowired MockMvc mvc;

    @Test
    void gatewayKeyIsRequiredAndRateLimitedButActuatorIsOpen() throws Exception {
        String alice = userAuth(ALICE);
        mvc.perform(get("/api/v1/objects").header("Authorization", alice)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/objects").header("X-API-Key", "wrong").header("Authorization", alice))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // browsers preflight without credentials, and error responses must still be readable cross-origin
        mvc.perform(options("/api/v1/objects").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "x-api-key"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/objects").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/objects").header("X-API-Key", "s3cret").header("Authorization", alice))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/objects").header("X-API-Key", "s3cret").header("Authorization", alice))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"));
    }
}
