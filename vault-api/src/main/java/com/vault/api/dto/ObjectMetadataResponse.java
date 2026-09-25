package com.vault.api.dto;

import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.StorageNodeEntity;
import com.vault.service.FileTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ObjectMetadataResponse(
        String objectId,
        String fileName,
        long size,
        String checksum,
        long version,
        int replicationFactor,
        String status,
        int healthyReplicas,
        Instant createdAt,
        Instant updatedAt,
        List<ReplicaView> replicas,
        /** Set for admins only. */
        String ownerEmail,
        /** pdf, document, spreadsheet, presentation, image, video, audio, archive or other. */
        String category,
        /** Non-null while the file is in the trash. */
        Instant trashedAt,
        String projectId) {

    public record ReplicaView(String nodeId, long version, String checksum, String status, String nodeStatus,
                              Instant lastVerified) {
    }

    public ObjectMetadataResponse withOwner(String email) {
        return new ObjectMetadataResponse(objectId, fileName, size, checksum, version, replicationFactor, status,
                healthyReplicas, createdAt, updatedAt, replicas, email, category, trashedAt, projectId);
    }

    /** Copy without replica placement (which nodes hold the data is admin-only information). */
    public ObjectMetadataResponse withoutReplicas() {
        return new ObjectMetadataResponse(objectId, fileName, size, checksum, version, replicationFactor, status,
                healthyReplicas, createdAt, updatedAt, List.of(), ownerEmail, category, trashedAt, projectId);
    }

    /** {@code nodes} may be empty, in which case node status is reported as UNKNOWN. */
    public static ObjectMetadataResponse of(ObjectEntity o, List<ReplicaEntity> replicas,
                                            Map<String, StorageNodeEntity> nodes) {
        List<ReplicaView> views = replicas.stream()
                .map(r -> {
                    StorageNodeEntity n = nodes.get(r.getNodeId());
                    return new ReplicaView(r.getNodeId(), r.getVersion(), r.getChecksum(), r.getStatus().name(),
                            n == null ? "UNKNOWN" : n.getStatus().name(), r.getLastVerified());
                })
                .toList();
        int healthy = (int) replicas.stream()
                .filter(r -> r.getStatus() == com.vault.metadata.ReplicaStatus.HEALTHY && r.getVersion() == o.getVersion())
                .filter(r -> {
                    StorageNodeEntity n = nodes.get(r.getNodeId());
                    return n != null && NodeStatus.REACHABLE.contains(n.getStatus());
                })
                .count();
        return new ObjectMetadataResponse(o.getObjectId(), o.getFileName(), o.getSizeBytes(), o.getChecksum(),
                o.getVersion(), o.getReplicationFactor(), o.getStatus().name(), healthy, o.getCreatedAt(),
                o.getUpdatedAt(), views, null, FileTypes.categoryOf(o.getFileName()).key(), o.getTrashedAt(),
                o.getProjectId());
    }
}
