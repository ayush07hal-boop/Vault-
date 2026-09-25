package com.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.service.ObjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recent/Trash/Storage/Projects behaviour behind the Drive-style UI. */
class DriveFeaturesTest extends AbstractClusterTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ObjectService objects;

    private String uploadAs(String auth, String name, int size, String... query) throws Exception {
        var req = multipart("/api/v1/objects").file(new MockMultipartFile("file", name, "application/octet-stream", randomBytes(size)))
                .header("Authorization", auth);
        for (int i = 0; i + 1 < query.length; i += 2) {
            req.param(query[i], query[i + 1]);
        }
        String body = mvc.perform(req).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("objectId").asText();
    }

    private String freshUser(String tag) {
        return userAuth(tag + "-" + System.nanoTime() + "@example.com");
    }

    @Test
    void trashHidesFilesButKeepsThemRestorableAndDeletableForever() throws Exception {
        String u = freshUser("trash");
        String id = uploadAs(u, "report.pdf", 500);

        mvc.perform(delete("/api/v1/objects/{id}", id).header("Authorization", u)).andExpect(status().isNoContent());

        // gone from normal views and access, present in the trash
        mvc.perform(get("/api/v1/objects").header("Authorization", u)).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/objects/{id}/metadata", id).header("Authorization", u)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/objects").param("trashed", "true").header("Authorization", u))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].trashedAt").isNotEmpty());
        // still stored and protected while trashed
        assertEquals(3, healthyReplicas(id).size());

        mvc.perform(post("/api/v1/objects/{id}/restore", id).header("Authorization", u)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/objects").header("Authorization", u)).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/v1/objects").param("trashed", "true").header("Authorization", u)).andExpect(jsonPath("$.totalItems").value(0));

        // delete forever
        mvc.perform(delete("/api/v1/objects/{id}", id).header("Authorization", u)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/objects/{id}", id).param("permanent", "true").header("Authorization", u)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/objects").param("trashed", "true").header("Authorization", u)).andExpect(jsonPath("$.totalItems").value(0));
        assertTrue(nodesHolding(id, 1).isEmpty(), "permanent delete removes every replica");
    }

    @Test
    void emptyTrashOnlyTouchesTheCallersOwnTrash() throws Exception {
        String a = freshUser("a");
        String b = freshUser("b");
        String idA = uploadAs(a, "a.txt", 100);
        String idB = uploadAs(b, "b.txt", 100);
        mvc.perform(delete("/api/v1/objects/{id}", idA).header("Authorization", a));
        mvc.perform(delete("/api/v1/objects/{id}", idB).header("Authorization", b));

        mvc.perform(post("/api/v1/objects/trash/empty").header("Authorization", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(1));

        mvc.perform(get("/api/v1/objects").param("trashed", "true").header("Authorization", a)).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/objects").param("trashed", "true").header("Authorization", b)).andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void expiredTrashIsPurgedAutomatically() throws Exception {
        String u = freshUser("purge");
        String id = uploadAs(u, "old.txt", 100);
        mvc.perform(delete("/api/v1/objects/{id}", id).header("Authorization", u));

        assertEquals(0, objects.purgeExpiredTrash(Instant.now().minus(Duration.ofDays(30))), "just trashed: kept");
        assertTrue(objects.purgeExpiredTrash(Instant.now().plus(Duration.ofDays(1))) >= 1, "past retention: purged");
        mvc.perform(get("/api/v1/objects").param("trashed", "true").header("Authorization", u)).andExpect(jsonPath("$.totalItems").value(0));
        assertTrue(nodesHolding(id, 1).isEmpty());
    }

    @Test
    void typeModifiedSortAndSearchFiltersWork() throws Exception {
        String u = freshUser("filters");
        uploadAs(u, "Budget.PDF", 300);
        uploadAs(u, "holiday.jpg", 900);
        uploadAs(u, "clip.mp4", 2000);
        uploadAs(u, "notes.txt", 100);
        uploadAs(u, "data.bin", 50);

        mvc.perform(get("/api/v1/objects").param("type", "pdf").header("Authorization", u))
                .andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].category").value("pdf"));
        mvc.perform(get("/api/v1/objects").param("type", "image").header("Authorization", u))
                .andExpect(jsonPath("$.items[0].fileName").value("holiday.jpg"));
        mvc.perform(get("/api/v1/objects").param("type", "other").header("Authorization", u))
                .andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].fileName").value("data.bin"));

        mvc.perform(get("/api/v1/objects").param("sort", "size").param("dir", "desc").header("Authorization", u))
                .andExpect(jsonPath("$.items[0].fileName").value("clip.mp4"))
                .andExpect(jsonPath("$.items[4].fileName").value("data.bin"));
        mvc.perform(get("/api/v1/objects").param("sort", "name").param("dir", "asc").header("Authorization", u))
                .andExpect(jsonPath("$.items[0].fileName").value("Budget.PDF"));

        mvc.perform(get("/api/v1/objects").param("modifiedAfter", Instant.now().minus(Duration.ofHours(1)).toString()).header("Authorization", u))
                .andExpect(jsonPath("$.totalItems").value(5));
        mvc.perform(get("/api/v1/objects").param("modifiedBefore", Instant.now().minus(Duration.ofHours(1)).toString()).header("Authorization", u))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/objects").param("q", "HOLI").header("Authorization", u)).andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void usageReportsBytesPerTypeAgainstTheQuota() throws Exception {
        String u = freshUser("usage");
        uploadAs(u, "a.pdf", 1000);
        uploadAs(u, "b.jpg", 500);
        String trashed = uploadAs(u, "c.jpg", 250);
        mvc.perform(delete("/api/v1/objects/{id}", trashed).header("Authorization", u));

        mvc.perform(get("/api/v1/objects/usage").header("Authorization", u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(1750)) // trash still occupies space
                .andExpect(jsonPath("$.fileCount").value(3))
                .andExpect(jsonPath("$.quotaBytes").value(16106127360L))
                .andExpect(jsonPath("$.bytesByType.pdf").value(1000))
                .andExpect(jsonPath("$.bytesByType.image").value(750));
    }

    @Test
    void projectsGroupFilesAndNeverLeakBetweenUsers() throws Exception {
        String a = freshUser("pa");
        String b = freshUser("pb");
        String project = json.readTree(mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Thesis  \"}").header("Authorization", a))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Thesis"))
                .andReturn().getResponse().getContentAsString()).get("projectId").asText();

        String inProject = uploadAs(a, "chapter1.txt", 100, "projectId", project);
        String loose = uploadAs(a, "misc.txt", 100);

        mvc.perform(get("/api/v1/objects").param("projectId", project).header("Authorization", a))
                .andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].objectId").value(inProject));
        mvc.perform(get("/api/v1/projects").header("Authorization", a))
                .andExpect(jsonPath("$[0].fileCount").value(1));

        // move a loose file in, then rename
        mvc.perform(put("/api/v1/objects/{id}/project", loose).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"" + project + "\"}").header("Authorization", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value(project));
        mvc.perform(patch("/api/v1/projects/{id}", project).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dissertation\"}").header("Authorization", a))
                .andExpect(jsonPath("$.name").value("Dissertation"));

        // someone else can neither see, rename, delete, upload into nor assign to it
        mvc.perform(get("/api/v1/projects").header("Authorization", b)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(patch("/api/v1/projects/{id}", project).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")
                .header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/projects/{id}", project).header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/objects").file(new MockMultipartFile("file", "x.txt", "text/plain", randomBytes(10)))
                .param("projectId", project).header("Authorization", b)).andExpect(status().isNotFound());

        // deleting a project ungroups its files; it does not delete them
        mvc.perform(delete("/api/v1/projects/{id}", project).header("Authorization", a)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/objects").header("Authorization", a))
                .andExpect(jsonPath("$.totalItems").value(2)).andExpect(jsonPath("$.items[0].projectId").doesNotExist());
    }

    @Test
    void adminDeleteIsAlwaysPermanent() throws Exception {
        String u = freshUser("adm");
        String id = uploadAs(u, "gone.txt", 100);
        mvc.perform(delete("/api/v1/objects/{id}", id).header("Authorization", userAuth(ADMIN_EMAIL)).header("X-Admin-Token", adminToken()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/objects").param("trashed", "true").header("Authorization", u)).andExpect(jsonPath("$.totalItems").value(0));
        assertTrue(nodesHolding(id, 1).isEmpty());
    }
}
