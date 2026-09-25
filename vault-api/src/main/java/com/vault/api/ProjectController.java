package com.vault.api;

import com.vault.error.VaultException;
import com.vault.security.AuthFilter;
import com.vault.service.ProjectService;
import com.vault.service.ProjectService.ProjectView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** A signed-in user's projects (AuthFilter guarantees a user; every call is scoped to them). */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    public record NameBody(String name) {
    }

    @GetMapping
    public List<ProjectView> list(HttpServletRequest request) {
        return projects.list(userId(request));
    }

    @PostMapping
    public ResponseEntity<ProjectView> create(@RequestBody NameBody body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.create(userId(request), body.name()));
    }

    @PatchMapping("/{projectId}")
    public ProjectView rename(@PathVariable String projectId, @RequestBody NameBody body, HttpServletRequest request) {
        return projects.rename(userId(request), projectId, body.name());
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, HttpServletRequest request) {
        projects.delete(userId(request), projectId);
        return ResponseEntity.noContent().build();
    }

    private static String userId(HttpServletRequest request) {
        return AuthFilter.user(request).orElseThrow(() ->
                new VaultException(HttpStatus.UNAUTHORIZED, "USER_REQUIRED", "Please sign in", null)).userId();
    }
}
