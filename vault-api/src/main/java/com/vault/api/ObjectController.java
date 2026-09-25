package com.vault.api;

import com.vault.api.dto.ObjectMetadataResponse;
import com.vault.error.VaultException;
import com.vault.security.AuthFilter;
import com.vault.security.UserPrincipal;
import com.vault.service.ObjectService;
import com.vault.service.ObjectService.CreateResult;
import com.vault.service.ObjectService.Download;
import com.vault.service.ProjectService;
import com.vault.storage.TempFiles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

/**
 * Every endpoint requires a signed-in user (enforced by AuthFilter). Users only ever reach their own objects;
 * other people's objects look like 404. Admins may inspect (list all, metadata, download) and permanently delete
 * any object, but never upload or replace content.
 */
@RestController
@RequestMapping("/api/v1/objects")
public class ObjectController {

    private final ObjectService objects;
    private final ProjectService projects;
    private final TempFiles tempFiles;

    public ObjectController(ObjectService objects, ProjectService projects, TempFiles tempFiles) {
        this.objects = objects;
        this.projects = projects;
        this.tempFiles = tempFiles;
    }

    /** Upload a new object owned by the caller. Send an {@code Idempotency-Key} header to make retries safe. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectMetadataResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "replicationFactor", required = false) Integer replicationFactor,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) throws IOException {
        UserPrincipal user = user(request);
        boolean inProject = projectId != null && !projectId.isBlank();
        if (inProject) {
            projects.requireOwned(user.userId(), projectId); // fail before spending any bandwidth
        }
        // keys are per-account, so two people can never collide on (or replay) each other's key
        String scopedKey = blankToNull(idempotencyKey) == null ? null : user.userId() + ":" + idempotencyKey.trim();
        try (InputStream in = file.getInputStream()) {
            CreateResult result = objects.create(user.userId(), in, fileNameOf(file), replicationFactor, scopedKey);
            ObjectMetadataResponse body = result.object();
            if (inProject && !result.replayed()) {
                projects.assign(user.userId(), body.objectId(), projectId);
                body = objects.metadata(body.objectId());
            }
            body = scrub(body, request);
            if (result.replayed()) {
                return ResponseEntity.ok().header("Idempotent-Replay", "true").body(body);
            }
            return ResponseEntity.created(URI.create("/api/v1/objects/" + body.objectId()))
                    .eTag(quote(body.checksum()))
                    .body(body);
        }
    }

    /**
     * Upload a new version (owner only). The expected current version is required (query param or
     * {@code If-Match} header); a stale value yields 409 Conflict and the client should re-read and retry.
     */
    @PutMapping(value = "/{objectId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectMetadataResponse update(
            @PathVariable String objectId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "expectedVersion", required = false) Long expectedVersion,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request) throws IOException {
        objects.authorize(objectId, user(request).userId(), false);
        long expected = resolveExpectedVersion(expectedVersion, ifMatch);
        try (InputStream in = file.getInputStream()) {
            return scrub(objects.update(objectId, expected, in, fileNameOf(file)), request);
        }
    }

    /**
     * Paged listing. Default: the caller's own files outside the trash. {@code trashed=true} lists the trash;
     * {@code scope=all} (admin only) lists everyone's with owner emails. Filters: {@code q} (name), {@code type}
     * (pdf|document|spreadsheet|presentation|image|video|audio|archive|other), {@code modifiedAfter} /
     * {@code modifiedBefore} (ISO-8601 instants), {@code projectId}. Sorting: {@code sort} =
     * name|size|modified|created, {@code dir} = asc|desc.
     */
    @GetMapping
    public ObjectService.Page list(@RequestParam(value = "page", defaultValue = "0") int page,
                                   @RequestParam(value = "size", defaultValue = "20") int size,
                                   @RequestParam(value = "q", required = false) String q,
                                   @RequestParam(value = "scope", defaultValue = "mine") String scope,
                                   @RequestParam(value = "trashed", defaultValue = "false") boolean trashed,
                                   @RequestParam(value = "type", required = false) String type,
                                   @RequestParam(value = "modifiedAfter", required = false) Instant modifiedAfter,
                                   @RequestParam(value = "modifiedBefore", required = false) Instant modifiedBefore,
                                   @RequestParam(value = "projectId", required = false) String projectId,
                                   @RequestParam(value = "sort", defaultValue = "created") String sort,
                                   @RequestParam(value = "dir", defaultValue = "desc") String dir,
                                   HttpServletRequest request) {
        boolean all = "all".equals(scope);
        if (all && !AuthFilter.isAdmin(request)) {
            throw new VaultException(HttpStatus.UNAUTHORIZED, "ADMIN_REQUIRED", "Admin login required", null);
        }
        ObjectService.Page p = objects.list(new ObjectService.ListQuery(all ? null : user(request).userId(), trashed, q,
                type, modifiedAfter, modifiedBefore, projectId, sort, !"asc".equalsIgnoreCase(dir), page, size));
        return new ObjectService.Page(p.items().stream().map(o -> scrub(o, request)).toList(), p.page(), p.size(),
                p.totalItems(), p.totalPages());
    }

    /** Storage used (trash included) against the account quota, with a per-type breakdown. */
    @GetMapping("/usage")
    public ObjectService.Usage usage(HttpServletRequest request) {
        return objects.usage(user(request).userId());
    }

    @GetMapping("/{objectId}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String objectId, HttpServletRequest request) {
        objects.authorize(objectId, user(request).userId(), AuthFilter.isAdmin(request));
        Download download = objects.download(objectId);
        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(download.file())) {
                in.transferTo(out);
            } finally {
                tempFiles.deleteQuietly(download.file());
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.object().getSizeBytes())
                .eTag(quote(download.object().getChecksum()))
                .header("X-Vault-Version", String.valueOf(download.object().getVersion()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.object().getFileName(), StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    /**
     * Owners move a file to the trash (restorable for 30 days); {@code permanent=true} deletes it for good.
     * Admins always delete permanently.
     */
    @DeleteMapping("/{objectId}")
    public ResponseEntity<Void> delete(@PathVariable String objectId,
                                       @RequestParam(value = "permanent", defaultValue = "false") boolean permanent,
                                       HttpServletRequest request) {
        boolean admin = AuthFilter.isAdmin(request);
        objects.authorizeAny(objectId, user(request).userId(), admin);
        if (permanent || admin) {
            objects.delete(objectId);
        } else {
            objects.moveToTrash(objectId);
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/{objectId}/restore")
    public ResponseEntity<Void> restore(@PathVariable String objectId, HttpServletRequest request) {
        objects.authorizeAny(objectId, user(request).userId(), false);
        objects.restore(objectId);
        return ResponseEntity.noContent().build();
    }

    /** Permanently deletes everything in the caller's trash. */
    @PostMapping("/trash/empty")
    public Map<String, Integer> emptyTrash(HttpServletRequest request) {
        return Map.of("deleted", objects.emptyTrash(user(request).userId()));
    }

    public record ProjectBody(String projectId) {
    }

    /** Moves a file into a project ({@code projectId} null = out of any project). */
    @PutMapping("/{objectId}/project")
    public ObjectMetadataResponse moveToProject(@PathVariable String objectId, @RequestBody ProjectBody body,
                                                HttpServletRequest request) {
        objects.authorize(objectId, user(request).userId(), false);
        projects.assign(user(request).userId(), objectId, body.projectId());
        return scrub(objects.metadata(objectId), request);
    }

    @GetMapping("/{objectId}/metadata")
    public ObjectMetadataResponse metadata(@PathVariable String objectId, HttpServletRequest request) {
        objects.authorize(objectId, user(request).userId(), AuthFilter.isAdmin(request));
        ObjectMetadataResponse o = objects.metadata(objectId);
        return scrub(AuthFilter.isAdmin(request) ? o.withOwner(objects.ownerEmailOf(objectId)) : o, request);
    }

    private static UserPrincipal user(HttpServletRequest request) {
        return AuthFilter.user(request).orElseThrow(() ->
                new VaultException(HttpStatus.UNAUTHORIZED, "USER_REQUIRED", "Please sign in", null));
    }

    /** Only admins may see which storage nodes hold an object. */
    private static ObjectMetadataResponse scrub(ObjectMetadataResponse o, HttpServletRequest request) {
        return AuthFilter.isAdmin(request) ? o : o.withoutReplicas();
    }

    private static long resolveExpectedVersion(Long param, String ifMatch) {
        if (param != null) {
            return param;
        }
        if (ifMatch != null && !ifMatch.isBlank()) {
            try {
                return Long.parseLong(ifMatch.replace("\"", "").replace("W/", "").trim());
            } catch (NumberFormatException e) {
                throw VaultException.badRequest("If-Match must be the numeric object version");
            }
        }
        throw VaultException.badRequest("expectedVersion query parameter or If-Match header is required");
    }

    private static String fileNameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "unnamed" : name;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
