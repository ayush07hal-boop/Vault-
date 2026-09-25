package com.vault.service;

import com.vault.error.VaultException;
import com.vault.metadata.ObjectRepository;
import com.vault.metadata.ProjectEntity;
import com.vault.metadata.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Projects: named groups of one user's files. Ownership is checked on every call. */
@Service
public class ProjectService {

    private static final int MAX_NAME = 100;

    private final ProjectRepository projects;
    private final ObjectRepository objects;

    public ProjectService(ProjectRepository projects, ObjectRepository objects) {
        this.projects = projects;
        this.objects = objects;
    }

    public record ProjectView(String projectId, String name, long fileCount, Instant createdAt) {
    }

    public List<ProjectView> list(String ownerId) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : objects.countByProjectOf(ownerId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return projects.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .map(p -> new ProjectView(p.getProjectId(), p.getName(), counts.getOrDefault(p.getProjectId(), 0L), p.getCreatedAt()))
                .toList();
    }

    @Transactional
    public ProjectView create(String ownerId, String name) {
        ProjectEntity p = projects.save(new ProjectEntity(UUID.randomUUID().toString(), ownerId, cleanName(name)));
        return new ProjectView(p.getProjectId(), p.getName(), 0, p.getCreatedAt());
    }

    @Transactional
    public ProjectView rename(String ownerId, String projectId, String name) {
        ProjectEntity p = require(ownerId, projectId);
        p.setName(cleanName(name));
        return new ProjectView(p.getProjectId(), p.getName(), 0, p.getCreatedAt());
    }

    /** Deleting a project only ungroups its files; nothing stored is deleted. */
    @Transactional
    public void delete(String ownerId, String projectId) {
        ProjectEntity p = require(ownerId, projectId);
        objects.clearProject(projectId);
        projects.delete(p);
    }

    /** Puts a file into a project (or out of any project when {@code projectId} is null). */
    @Transactional
    public void assign(String ownerId, String objectId, String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            require(ownerId, projectId);
        } else {
            projectId = null;
        }
        objects.setProjectId(objectId, projectId);
    }

    public void requireOwned(String ownerId, String projectId) {
        require(ownerId, projectId);
    }

    private ProjectEntity require(String ownerId, String projectId) {
        return projects.findByProjectIdAndOwnerId(projectId, ownerId).orElseThrow(() ->
                new VaultException(org.springframework.http.HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                        "Project not found", null));
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw VaultException.badRequest("Project name must be 1-" + MAX_NAME + " characters");
        }
        return n;
    }
}
