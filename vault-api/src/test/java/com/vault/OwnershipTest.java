package com.vault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OwnershipTest extends AbstractClusterTest {

    @Autowired MockMvc mvc;

    private String uploadAs(String auth, String name, byte[] data) throws Exception {
        String body = mvc.perform(multipart("/api/v1/objects")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", data)).header("Authorization", auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("objectId").asText();
    }

    @Test
    void usersCannotSeeChangeOrDeleteEachOthersFiles() throws Exception {
        String alice = userAuth(ALICE);
        String bob = userAuth(BOB);
        byte[] data = randomBytes(2000);
        String id = uploadAs(alice, "alice-secret.pdf", data);

        // to Bob the file does not exist at all (no 403 that would confirm the id is real)
        mvc.perform(get("/api/v1/objects/{id}/metadata", id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/objects/{id}", id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/objects/{id}", id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/objects/{id}", id)
                        .file(new MockMultipartFile("file", "x", "text/plain", randomBytes(10)))
                        .param("expectedVersion", "1").header("Authorization", bob)
                        .with(r -> { r.setMethod("PUT"); return r; }))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/objects").header("Authorization", bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));

        // Alice is unaffected
        mvc.perform(get("/api/v1/objects").header("Authorization", alice))
                .andExpect(jsonPath("$.items[0].fileName").value("alice-secret.pdf"));
        MvcResult started = mvc.perform(get("/api/v1/objects/{id}", id).header("Authorization", alice))
                .andExpect(request().asyncStarted()).andReturn();
        assertArrayEquals(data, mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    void adminsCanInspectAndDeleteEverythingButNeverReplaceContent() throws Exception {
        String alice = userAuth(ALICE);
        String boss = userAuth(ADMIN_EMAIL);
        String admin = adminToken();
        byte[] data = randomBytes(3000);
        String id = uploadAs(alice, "for-admin.txt", data);

        // scope=all is admin-only and shows owners
        mvc.perform(get("/api/v1/objects").param("scope", "all").header("Authorization", alice))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/objects").param("scope", "all").header("Authorization", boss).header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.fileName=='for-admin.txt')].ownerEmail").value(ALICE));
        // an admin's own "my files" view stays personal
        mvc.perform(get("/api/v1/objects").header("Authorization", boss).header("X-Admin-Token", admin))
                .andExpect(jsonPath("$.totalItems").value(0));

        MvcResult started = mvc.perform(get("/api/v1/objects/{id}", id).header("Authorization", boss).header("X-Admin-Token", admin))
                .andExpect(request().asyncStarted()).andReturn();
        assertArrayEquals(data, mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsByteArray());

        mvc.perform(multipart("/api/v1/objects/{id}", id)
                        .file(new MockMultipartFile("file", "x", "text/plain", randomBytes(10)))
                        .param("expectedVersion", "1").header("Authorization", boss).header("X-Admin-Token", admin)
                        .with(r -> { r.setMethod("PUT"); return r; }))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/objects/{id}", id).header("Authorization", boss).header("X-Admin-Token", admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/objects/{id}/metadata", id).header("Authorization", alice)).andExpect(status().isNotFound());
    }
}
