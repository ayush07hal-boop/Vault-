package com.vault.metadata;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * All metadata mutations that must be atomic live here. Every method is a single transaction,
 * so the database is the one place that decides which write wins.
 */
@Service
public class MetadataService {

    private final ObjectRepository objects;
    private final ReplicaRepository replicas;

    public MetadataService(ObjectRepository objects, ReplicaRepository replicas) {
        this.objects = objects;
        this.replicas = replicas;
    }

    @Transactional(readOnly = true)
    public Optional<ObjectEntity> find(String objectId) {
        return objects.findById(objectId);
    }

    @Transactional(readOnly = true)
    public List<ReplicaEntity> replicasOf(String objectId) {
        return replicas.findByObjectId(objectId);
    }

    /** Creates the object at version 1 together with the replicas that acknowledged the write. */
    @Transactional
    public ObjectEntity createObject(ObjectEntity object, List<ReplicaEntity> acknowledged) {
        objects.saveAndFlush(object);
        Instant now = Instant.now();
        for (ReplicaEntity r : acknowledged) {
            r.setLastVerified(now);
            replicas.save(r);
        }
        return object;
    }

    /**
     * Commits an update if (and only if) {@code expectedVersion} is still current.
     *
     * @param acknowledged nodes that stored the new version
     * @param failedTargets nodes we tried to update but failed: their old copy becomes OUTDATED
     * @param dropped nodes that hold an old copy but are not part of the new placement: old copy is scheduled for deletion
     * @return false on version conflict (nothing was changed)
     */
    @Transactional
    public boolean commitUpdate(String objectId, long expectedVersion, String fileName, long size, String checksum,
                                List<String> acknowledged, List<String> failedTargets, List<String> dropped) {
        long newVersion = expectedVersion + 1;
        int changed = objects.compareAndSetVersion(objectId, expectedVersion, newVersion, fileName, size, checksum,
                Instant.now());
        if (changed == 0) {
            return false;
        }
        Instant now = Instant.now();
        for (String nodeId : acknowledged) {
            ReplicaEntity r = replicas.findByObjectIdAndNodeId(objectId, nodeId)
                    .orElseGet(() -> new ReplicaEntity(objectId, nodeId, newVersion, checksum, ReplicaStatus.HEALTHY));
            r.setVersion(newVersion);
            r.setChecksum(checksum);
            r.setStatus(ReplicaStatus.HEALTHY);
            r.setLastVerified(now);
            replicas.save(r);
        }
        for (String nodeId : failedTargets) {
            replicas.findByObjectIdAndNodeId(objectId, nodeId).ifPresent(r -> {
                r.setStatus(ReplicaStatus.OUTDATED);
                replicas.save(r);
            });
        }
        for (String nodeId : dropped) {
            replicas.findByObjectIdAndNodeId(objectId, nodeId).ifPresent(r -> {
                r.setStatus(ReplicaStatus.PENDING_DELETE);
                replicas.save(r);
            });
        }
        return true;
    }

    /**
     * Tombstones the object and schedules every replica for removal.
     *
     * @return the replica rows that must be deleted from nodes, or empty if the object was not ACTIVE
     */
    @Transactional
    public Optional<List<ReplicaEntity>> beginDelete(String objectId) {
        if (objects.transition(objectId, ObjectStatus.ACTIVE, ObjectStatus.DELETING, Instant.now()) == 0) {
            return Optional.empty();
        }
        List<ReplicaEntity> rows = replicas.findByObjectId(objectId);
        for (ReplicaEntity r : rows) {
            r.setStatus(ReplicaStatus.PENDING_DELETE);
        }
        replicas.saveAll(rows);
        return Optional.of(rows);
    }

    /** Removes a replica row (its file is gone) and completes the tombstone once no rows remain. */
    @Transactional
    public void removeReplica(String objectId, String nodeId) {
        replicas.deleteReplica(objectId, nodeId);
        finalizeDeletionIfDrained(objectId);
    }

    @Transactional
    public void finalizeDeletionIfDrained(String objectId) {
        if (replicas.countByObjectId(objectId) == 0) {
            objects.transition(objectId, ObjectStatus.DELETING, ObjectStatus.DELETED, Instant.now());
        }
    }

    /** @return true if the row still described {@code version} and was updated */
    @Transactional
    public boolean markReplica(String objectId, String nodeId, long version, ReplicaStatus status) {
        return replicas.updateStatusIfVersion(objectId, nodeId, version, status) > 0;
    }

    @Transactional
    public void markVerified(String objectId, String nodeId, long version) {
        replicas.touchVerified(objectId, nodeId, version, Instant.now());
    }

    /**
     * Records a freshly copied replica as HEALTHY, but only if the object is still ACTIVE at {@code version}.
     * Otherwise the copy is already stale and the caller must discard it.
     */
    @Transactional
    public boolean commitRepairedReplica(String objectId, String nodeId, long version, String checksum) {
        Optional<ObjectEntity> obj = objects.findById(objectId);
        if (obj.isEmpty() || obj.get().getStatus() != ObjectStatus.ACTIVE || obj.get().getVersion() != version) {
            return false;
        }
        ReplicaEntity r = replicas.findByObjectIdAndNodeId(objectId, nodeId)
                .orElseGet(() -> new ReplicaEntity(objectId, nodeId, version, checksum, ReplicaStatus.HEALTHY));
        r.setVersion(version);
        r.setChecksum(checksum);
        r.setStatus(ReplicaStatus.HEALTHY);
        r.setLastVerified(Instant.now());
        replicas.save(r);
        return true;
    }

    /**
     * Rebalancing step: the new replica becomes HEALTHY and the old one is queued for deletion in one
     * transaction, so there is never a moment when metadata claims fewer verified copies than exist.
     */
    @Transactional
    public boolean commitMove(String objectId, String fromNode, String toNode, long version, String checksum) {
        Optional<ObjectEntity> obj = objects.findById(objectId);
        Optional<ReplicaEntity> old = replicas.findByObjectIdAndNodeId(objectId, fromNode);
        if (obj.isEmpty() || obj.get().getStatus() != ObjectStatus.ACTIVE || obj.get().getVersion() != version
                || old.isEmpty() || old.get().getVersion() != version
                || old.get().getStatus() != ReplicaStatus.HEALTHY
                || replicas.findByObjectIdAndNodeId(objectId, toNode).isPresent()) {
            return false;
        }
        ReplicaEntity moved = new ReplicaEntity(objectId, toNode, version, checksum, ReplicaStatus.HEALTHY);
        moved.setLastVerified(Instant.now());
        replicas.save(moved);
        old.get().setStatus(ReplicaStatus.PENDING_DELETE);
        replicas.save(old.get());
        return true;
    }
}
