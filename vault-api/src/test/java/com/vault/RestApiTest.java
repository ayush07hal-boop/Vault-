package com.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestApiTest extends AbstractClusterTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static MockMultipartFile file(String name, byte[] data) {
        return new MockMultipartFile("file", name, "application/octet-stream", data);
    }

    @Test
    void fullObjectLifecycleOverHttp() throws Exception {
        String alice = userAuth(ALICE);
        byte[] data = randomBytes(50_000);

        MvcResult created = mvc.perform(multipart("/api/v1/objects").file(file("photo.jpg", data)).header("Authorization", alice))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.fileName").value("photo.jpg"))
                .andExpect(jsonPath("$.healthyReplicas").value(3))
                .andReturn();
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        String id = body.get("objectId").asText();

        MvcResult started = mvc.perform(get("/api/v1/objects/{id}", id).header("Authorization", alice))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult downloaded = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Vault-Version", "1"))
                .andExpect(header().string("ETag", "\"" + body.get("checksum").asText() + "\""))
                .andReturn();
        assertArrayEquals(data, downloaded.getResponse().getContentAsByteArray());

        // owners see health but not which nodes hold the data; admins see placement
        mvc.perform(get("/api/v1/objects/{id}/metadata", id).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthyReplicas").value(3))
                .andExpect(jsonPath("$.replicas.length()").value(0));
        mvc.perform(get("/api/v1/objects/{id}/metadata", id).header("Authorization", userAuth(ADMIN_EMAIL))
                        .header("X-Admin-Token", adminToken()))
                .andExpect(jsonPath("$.replicas.length()").value(3))
                .andExpect(jsonPath("$.ownerEmail").value(ALICE));

        // update with the right and the wrong version
        mvc.perform(multipart("/api/v1/objects/{id}", id).file(file("photo.jpg", randomBytes(10)))
                        .param("expectedVersion", "1").header("Authorization", alice)
                        .with(r -> { r.setMethod("PUT"); return r; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(multipart("/api/v1/objects/{id}", id).file(file("photo.jpg", randomBytes(10)))
                        .header("If-Match", "\"1\"").header("Authorization", alice)
                        .with(r -> { r.setMethod("PUT"); return r; }))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"));
        mvc.perform(multipart("/api/v1/objects/{id}", id).file(file("photo.jpg", randomBytes(10)))
                        .header("Authorization", alice)
                        .with(r -> { r.setMethod("PUT"); return r; }))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/v1/objects/{id}", id).header("Authorization", alice)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/objects/{id}/metadata", id).header("Authorization", alice))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("OBJECT_NOT_FOUND"));
    }

    @Test
    void idempotentUploadReturnsSameObject() throws Exception {
        String alice = userAuth(ALICE);
        String first = mvc.perform(multipart("/api/v1/objects").file(file("a.bin", randomBytes(100)))
                        .header("Idempotency-Key", "http-key").header("Authorization", alice))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(multipart("/api/v1/objects").file(file("a.bin", randomBytes(100)))
                        .header("Idempotency-Key", "http-key").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(json.readTree(first).get("objectId"), json.readTree(second).get("objectId"));

        // the same key from another account is a different request
        mvc.perform(multipart("/api/v1/objects").file(file("a.bin", randomBytes(100)))
                        .header("Idempotency-Key", "http-key").header("Authorization", userAuth(BOB)))
                .andExpect(status().isCreated());
    }

    @Test
    void listingIsOwnFilesPagedNewestFirstAndSearchable() throws Exception {
        String carol = userAuth("carol@example.com");
        for (String name : new String[] {"alpha-report.pdf", "beta-photo.jpg", "alpha-notes.txt"}) {
            mvc.perform(multipart("/api/v1/objects").file(file(name, randomBytes(50))).header("Authorization", carol))
                    .andExpect(status().isCreated());
        }
        upload(randomBytes(20)); // someone else's (ownerless) file must never appear
        mvc.perform(get("/api/v1/objects").param("q", "ALPHA").param("size", "1").header("Authorization", carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].fileName").value("alpha-notes.txt"))
                .andExpect(jsonPath("$.items[0].healthyReplicas").value(3));
        mvc.perform(get("/api/v1/objects").param("q", "alpha").param("size", "1").param("page", "1").header("Authorization", carol))
                .andExpect(jsonPath("$.items[0].fileName").value("alpha-report.pdf"));
        mvc.perform(get("/api/v1/objects").header("Authorization", carol)).andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void statsNodesAndHealthAreAdminOnlyAndReportTheCluster() throws Exception {
        upload(randomBytes(1000));
        String user = userAuth(ADMIN_EMAIL);
        String admin = adminToken();
        mvc.perform(get("/api/v1/nodes").header("Authorization", user).header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(NODE_COUNT))
                .andExpect(jsonPath("$[0].status").value("HEALTHY"));
        String health = mvc.perform(get("/api/v1/health").header("Authorization", user).header("X-Admin-Token", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(health.contains("\"status\":\"UP\""), health);
        mvc.perform(get("/api/v1/stats").header("Authorization", user).header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.HEALTHY").value(NODE_COUNT))
                .andExpect(jsonPath("$.storage.totalCapacityBytes").isNumber())
                .andExpect(jsonPath("$.replicasByStatus.HEALTHY").isNumber())
                .andExpect(jsonPath("$.counters.uploads").isNumber());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void corsPreflightAndExposedHeaders() throws Exception {
        mvc.perform(MockMvcRequestBuilders.options("/api/v1/objects")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,authorization,x-admin-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(get("/api/v1/objects").header("Origin", "http://localhost:5173").header("Authorization", userAuth(ALICE)))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(get("/api/v1/objects").header("Origin", "http://evil.example")).andExpect(status().isForbidden());
    }

    @Test
    void unknownObjectIs404() throws Exception {
        mvc.perform(get("/api/v1/objects/{id}/metadata", "obj-nope").header("Authorization", userAuth(ALICE)))
                .andExpect(status().isNotFound());
    }
}
