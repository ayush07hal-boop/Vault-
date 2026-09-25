package com.vault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "vault.user-quota.bytes=5000")
class QuotaTest extends AbstractClusterTest {

    @Autowired MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions up(String auth, int size) throws Exception {
        return mvc.perform(multipart("/api/v1/objects")
                .file(new MockMultipartFile("file", "f.bin", "application/octet-stream", randomBytes(size))).header("Authorization", auth));
    }

    @Test
    void uploadsBeyondTheQuotaAreRefusedAndNothingIsStored() throws Exception {
        String u = userAuth("quota-user@example.com");
        up(u, 3000).andExpect(status().isCreated());
        up(u, 2500).andExpect(status().isInsufficientStorage()).andExpect(jsonPath("$.error").value("QUOTA_EXCEEDED"));
        up(u, 2000).andExpect(status().isCreated()); // exactly fills it

        mvc.perform(get("/api/v1/objects/usage").header("Authorization", u))
                .andExpect(jsonPath("$.usedBytes").value(5000)).andExpect(jsonPath("$.quotaBytes").value(5000))
                .andExpect(jsonPath("$.fileCount").value(2));
        // other accounts have their own allowance
        up(userAuth("other-quota@example.com"), 4000).andExpect(status().isCreated());
    }

    @Test
    void trashStillCountsUntilDeletedForever() throws Exception {
        String u = userAuth("quota-trash@example.com");
        String body = up(u, 4000).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("objectId").asText();
        mvc.perform(delete("/api/v1/objects/{id}", id).header("Authorization", u));

        up(u, 2000).andExpect(status().isInsufficientStorage());

        mvc.perform(delete("/api/v1/objects/{id}", id).param("permanent", "true").header("Authorization", u));
        up(u, 2000).andExpect(status().isCreated());
    }
}
