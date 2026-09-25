package com.vault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Own class (own context) so exhausting the login limiter cannot affect other tests. */
class AuthThrottleTest extends AbstractClusterTest {

    @Autowired MockMvc mvc;

    @Test
    void repeatedAdminPasswordGuessesAreThrottled() throws Exception {
        String boss = userAuth(ADMIN_EMAIL);
        int throttled = 0;
        for (int i = 0; i < 14; i++) {
            int code = mvc.perform(post("/api/v1/auth/admin-login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"guess" + i + "\"}").header("Authorization", boss))
                    .andReturn().getResponse().getStatus();
            if (code == 429) {
                throttled++;
            }
        }
        assertTrue(throttled >= 3, "brute force must hit the limiter, got " + throttled);
    }
}
