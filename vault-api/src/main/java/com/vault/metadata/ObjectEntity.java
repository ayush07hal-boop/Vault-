package com.vault.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Source of truth for one object. {@code version} is managed explicitly (compare-and-set in
 * {@link ObjectRepository}) rather than through JPA @Version so the conflict semantics are visible in SQL.
 */
@Entity
@Table(name = "objects")
public class ObjectEntity {

    @Id
    @Column(name = "object_id", length = 100)
    private String objectId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 128)
    private String checksum;

    @Column(nullable = false)
    private long version;

    @Column(name = "replication_factor", nullable = false)
    private int replicationFactor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ObjectStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Account that uploaded the object; null only for system-created test data. */
    @Column(name = "owner_id", length = 100)
    private String ownerId;

    /** Non-null = in the trash (still ACTIVE underneath, so replicas keep being verified and repaired). */
    @Column(name = "trashed_at")
    private Instant trashedAt;

    @Column(name = "project_id", length = 100)
    private String projectId;

    protected ObjectEntity() {
    }

    public Instant getTrashedAt() {
        return trashedAt;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public ObjectEntity(String objectId, String fileName, long sizeBytes, String checksum, long version,
                        int replicationFactor, ObjectStatus status, Instant now) {
        this.objectId = objectId;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.version = version;
        this.replicationFactor = replicationFactor;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public long getVersion() {
        return version;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public ObjectStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
