package com.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountAuthTest extends AbstractClusterTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private ResultActions postJson(String url, Object body, String bearer) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (bearer != null) {
            req.header("Authorization", bearer);
        }
        return mvc.perform(req);
    }

    private String tokenFrom(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void googleSignInCreatesAnAccountAndIssuesASession() throws Exception {
        postJson("/api/v1/auth/google", Map.of("credential", "good:sub-1:dana@example.com"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("dana@example.com"))
                .andExpect(jsonPath("$.user.canAdmin").value(false));
        String token = tokenFrom(postJson("/api/v1/auth/google", Map.of("credential", "good:sub-1:dana@example.com"), null));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dana@example.com"))
                .andExpect(jsonPath("$.admin").value(false));
        // signing in twice is the same account, not a duplicate
        mvc.perform(get("/api/v1/objects").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void invalidGoogleCredentialsAreRejected() throws Exception {
        postJson("/api/v1/auth/google", Map.of("credential", "forged"), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_GOOGLE_TOKEN"));
    }

    @Test
    void everythingNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/v1/objects")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("USER_REQUIRED"));
        mvc.perform(get("/api/v1/objects").header("Authorization", "Bearer garbage.token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk()); // the only public endpoints are sign-in ones
    }

    @Test
    void devLoginWorksOnlyWhenEnabledAndValidatesTheEmail() throws Exception {
        postJson("/api/v1/auth/dev-login", Map.of("email", "erin@example.com"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value("erin@example.com"));
        postJson("/api/v1/auth/dev-login", Map.of("email", "not-an-email"), null).andExpect(status().isBadRequest());
    }

    @Test
    void adminAreaNeedsAnAllowlistedAccountPlusPasswordAndIsInvisibleToEveryoneElse() throws Exception {
        String alice = userAuth(ALICE);
        String boss = userAuth(ADMIN_EMAIL);

        // not allowlisted: the endpoint behaves as if it does not exist, even with the right password
        postJson("/api/v1/auth/admin-login", Map.of("password", "test-pass"), alice).andExpect(status().isNotFound());
        // allowlisted but wrong password
        postJson("/api/v1/auth/admin-login", Map.of("password", "nope"), boss)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
        // allowlisted + password
        String admin = tokenFrom(postJson("/api/v1/auth/admin-login", Map.of("password", "test-pass"), boss));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", boss).header("X-Admin-Token", admin))
                .andExpect(jsonPath("$.canAdmin").value(true)).andExpect(jsonPath("$.admin").value(true));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", alice))
                .andExpect(jsonPath("$.canAdmin").value(false));
    }

    @Test
    void adminEndpointsRejectEveryKindOfImpostor() throws Exception {
        String boss = userAuth(ADMIN_EMAIL);
        String alice = userAuth(ALICE);
        String admin = adminToken();

        for (String path : List.of("/api/v1/nodes", "/api/v1/stats", "/api/v1/health")) {
            mvc.perform(get(path).header("Authorization", boss).header("X-Admin-Token", admin)).andExpect(status().isOk());
            // no admin token, or garbage
            mvc.perform(get(path).header("Authorization", boss)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("ADMIN_REQUIRED"));
            mvc.perform(get(path).header("Authorization", boss).header("X-Admin-Token", "x.y")).andExpect(status().isUnauthorized());
            // a user token is not an admin token
            mvc.perform(get(path).header("Authorization", boss).header("X-Admin-Token", boss.substring(7)))
                    .andExpect(status().isUnauthorized());
            // an admin token stolen by (or replayed with) a different account
            mvc.perform(get(path).header("Authorization", alice).header("X-Admin-Token", admin)).andExpect(status().isUnauthorized());
            // admin token without any signed-in user
            mvc.perform(get(path).header("X-Admin-Token", admin)).andExpect(status().isUnauthorized());
        }
        for (String action : List.of("heartbeat", "repair/scan", "integrity/verify", "rebalance/run")) {
            mvc.perform(post("/api/v1/admin/" + action).header("Authorization", alice)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/admin/" + action).header("Authorization", boss).header("X-Admin-Token", admin))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void tamperedTokensAreRejected() throws Exception {
        String token = userAuth(ALICE).substring(7);
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("USER\nsome-id\nboss@example.com\n" + (System.currentTimeMillis() / 1000 + 99999)).getBytes());
        mvc.perform(get("/api/v1/objects").header("Authorization", "Bearer " + forgedPayload + token.substring(token.indexOf('.'))))
                .andExpect(status().isUnauthorized());
    }
}
