package com.vault.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "replicas")
@IdClass(ReplicaId.class)
public class ReplicaEntity {

    @Id
    @Column(name = "object_id", length = 100)
    private String objectId;

    @Id
    @Column(name = "node_id", length = 100)
    private String nodeId;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false, length = 128)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReplicaStatus status;

    @Column(name = "last_verified")
    private Instant lastVerified;

    protected ReplicaEntity() {
    }

    public ReplicaEntity(String objectId, String nodeId, long version, String checksum, ReplicaStatus status) {
        this.objectId = objectId;
        this.nodeId = nodeId;
        this.version = version;
        this.checksum = checksum;
        this.status = status;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public long getVersion() {
        return version;
    }

    public String getChecksum() {
        return checksum;
    }

    public ReplicaStatus getStatus() {
        return status;
    }

    public Instant getLastVerified() {
        return lastVerified;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public void setStatus(ReplicaStatus status) {
        this.status = status;
    }

    public void setLastVerified(Instant lastVerified) {
        this.lastVerified = lastVerified;
    }
}
