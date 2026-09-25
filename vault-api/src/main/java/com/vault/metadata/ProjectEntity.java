package com.vault.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A named group of a user's files. Deleting a project ungroups its files; it never deletes them. */
@Entity
@Table(name = "projects")
public class ProjectEntity {

    @Id
    @Column(name = "project_id", length = 100)
    private String projectId;

    @Column(name = "owner_id", nullable = false, length = 100)
    private String ownerId;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProjectEntity() {
    }

    public ProjectEntity(String projectId, String ownerId, String name) {
        this.projectId = projectId;
        this.ownerId = ownerId;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public String getProjectId() {
        return projectId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setName(String name) {
        this.name = name;
    }
}
