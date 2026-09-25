package com.vault.service;

import com.vault.api.dto.ObjectMetadataResponse;
import com.vault.config.VaultProperties;
import com.vault.error.VaultException;
import com.vault.events.VaultEvent;
import com.vault.events.VaultEventPublisher;
import com.vault.metadata.MetadataService;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ObjectStatus;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaStatus;
import com.vault.metadata.StorageNodeEntity;
import com.vault.metrics.VaultMetrics;
import com.vault.replication.ObjectLocks;
import com.vault.replication.PlacementService;
import com.vault.replication.ReplicationManager;
import com.vault.replication.ReplicationManager.WriteResult;
import com.vault.repair.ReplicaGarbageCollector;
import com.vault.storage.ChecksumService;
import com.vault.storage.NodeRpcException;
import com.vault.storage.StorageNodeClient;
import com.vault.storage.StorageNodeClient.Downloaded;
import com.vault.storage.TempFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

/** Object lifecycle: create, read, update, delete. Replication policy lives in {@link ReplicationManager}. */
@Service
public class ObjectService {

    private static final Logger log = LoggerFactory.getLogger(ObjectService.class);
    private static final int MAX_REPLICATION_FACTOR = 16;
    private static final int MAX_READ_ATTEMPTS = 8;

    private final com.vault.metadata.ObjectRepository objectRepository;
    private final UserService users;
    private final MetadataService metadata;
    private final NodeService nodeService;
    private final PlacementService placement;
    private final ReplicationManager replication;
    private final StorageNodeClient client;
    private final ChecksumService checksums;
    private final TempFiles tempFiles;
    private final IdempotencyService idempotency;
    private final ReplicaGarbageCollector garbageCollector;
    private final ObjectLocks locks;
    private final VaultEventPublisher events;
    private final VaultMetrics metrics;
    private final VaultProperties props;

    public ObjectService(com.vault.metadata.ObjectRepository objectRepository, UserService users, MetadataService metadata, NodeService nodeService, PlacementService placement,
                         ReplicationManager replication, StorageNodeClient client, ChecksumService checksums,
                         TempFiles tempFiles, IdempotencyService idempotency, ReplicaGarbageCollector garbageCollector,
                         ObjectLocks locks, VaultEventPublisher events, VaultMetrics metrics, VaultProperties props) {
        this.objectRepository = objectRepository;
        this.users = users;
        this.metadata = metadata;
        this.nodeService = nodeService;
        this.placement = placement;
        this.replication = replication;
        this.client = client;
        this.checksums = checksums;
        this.tempFiles = tempFiles;
        this.idempotency = idempotency;
        this.garbageCollector = garbageCollector;
        this.locks = locks;
        this.events = events;
        this.metrics = metrics;
        this.props = props;
    }

    public record CreateResult(ObjectMetadataResponse object, boolean replayed) {
    }

    /** A verified download. The caller must delete {@code file} once it has been streamed. */
    public record Download(ObjectEntity object, Path file) {
    }

    private record Spooled(Path file, String checksum, long size) {
    }

    // ---- create ----------------------------------------------------------------------------------

    /** Convenience for system/test callers: object has no owner. */
    public CreateResult create(InputStream body, String fileName, Integer requestedReplication, String idempotencyKey) {
        return create(null, body, fileName, requestedReplication, idempotencyKey);
    }

    public CreateResult create(String ownerId, InputStream body, String fileName, Integer requestedReplication,
                               String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<String> prior = idempotency.begin(idempotencyKey);
            if (prior.isPresent()) {
                return new CreateResult(metadata(prior.get()), true);
            }
        }
        try {
            ObjectMetadataResponse created = doCreate(ownerId, body, fileName, requestedReplication);
            if (idempotencyKey != null) {
                idempotency.complete(idempotencyKey, created.objectId());
            }
            return new CreateResult(created, false);
        } catch (RuntimeException e) {
            if (idempotencyKey != null) {
                idempotency.release(idempotencyKey);
            }
            throw e;
        }
    }

    private ObjectMetadataResponse doCreate(String ownerId, InputStream body, String fileName, Integer requestedReplication) {
        long started = System.nanoTime();
        int rf = requestedReplication == null ? props.replication().factor() : requestedReplication;
        if (rf < 1 || rf > MAX_REPLICATION_FACTOR) {
            throw VaultException.badRequest("replicationFactor must be between 1 and " + MAX_REPLICATION_FACTOR);
        }
        Spooled data = spool(body);
        try {
            checkQuota(ownerId, data.size());
            String objectId = "obj-" + UUID.randomUUID();
            int quorum = replication.writeQuorum(rf);
            List<StorageNodeEntity> targets = placement.choose(rf, data.size(), Set.of());
            if (targets.size() < quorum) {
                throw VaultException.insufficientReplicas(objectId, "Only " + targets.size()
                        + " healthy node(s) with capacity available; write quorum is " + quorum);
            }

            List<WriteResult> results = replication.writeAll(objectId, 1, data.file(), data.checksum(), targets);
            List<WriteResult> acknowledged = results.stream().filter(WriteResult::ok).toList();
            if (acknowledged.size() < quorum) {
                replication.discard(objectId, 1, results);
                throw VaultException.insufficientReplicas(objectId, "Write quorum not reached: "
                        + acknowledged.size() + " of " + quorum + " required replicas acknowledged");
            }

            ObjectEntity object = new ObjectEntity(objectId, fileName, data.size(), data.checksum(), 1, rf,
                    ObjectStatus.ACTIVE, Instant.now());
            object.setOwnerId(ownerId);
            List<ReplicaEntity> rows = acknowledged.stream()
                    .map(w -> new ReplicaEntity(objectId, w.node().getNodeId(), 1, data.checksum(), ReplicaStatus.HEALTHY))
                    .toList();
            try {
                metadata.createObject(object, rows);
            } catch (RuntimeException e) {
                replication.discard(objectId, 1, results);
                throw e;
            }
            if (acknowledged.size() < rf) {
                events.publish(new VaultEvent.ObjectUnderReplicated(objectId));
            }
            metrics.uploads.increment();
            metrics.uploadLatency.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            return view(object.getObjectId());
        } finally {
            tempFiles.deleteQuietly(data.file());
        }
    }

    // ---- update ----------------------------------------------------------------------------------

    /**
     * Writes a new version. Fails with 409 unless {@code expectedVersion} is the current version
     * (optimistic concurrency control), so two writers can never silently overwrite each other.
     */
    public ObjectMetadataResponse update(String objectId, long expectedVersion, InputStream body, String fileName) {
        ObjectEntity current = requireActive(objectId);
        if (current.getVersion() != expectedVersion) {
            metrics.versionConflicts.increment();
            throw VaultException.versionConflict(objectId, expectedVersion, current.getVersion());
        }
        Spooled data = spool(body);
        try {
            checkQuota(current.getOwnerId(), data.size() - current.getSizeBytes());
        } catch (RuntimeException e) {
            tempFiles.deleteQuietly(data.file());
            throw e;
        }
        // Serialise writers of the same object: node files are named by version, so two concurrent
        // attempts at the same version would overwrite each other's bytes on disk.
        Lock lock = locks.lockFor(objectId);
        lock.lock();
        try {
            ObjectEntity fresh = requireActive(objectId);
            if (fresh.getVersion() != expectedVersion) {
                metrics.versionConflicts.increment();
                throw VaultException.versionConflict(objectId, expectedVersion, fresh.getVersion());
            }
            return doUpdate(fresh, data, fileName);
        } finally {
            lock.unlock();
            tempFiles.deleteQuietly(data.file());
        }
    }

    private ObjectMetadataResponse doUpdate(ObjectEntity current, Spooled data, String fileName) {
        String objectId = current.getObjectId();
        long expected = current.getVersion();
        long newVersion = expected + 1;
        int rf = current.getReplicationFactor();
        int quorum = replication.writeQuorum(rf);

        List<ReplicaEntity> existing = metadata.replicasOf(objectId);
        Map<String, StorageNodeEntity> nodes = nodeService.byId();

        // Prefer nodes that already hold a copy (fewer stale files), then fill from the placement policy.
        List<StorageNodeEntity> targets = new ArrayList<>();
        for (ReplicaEntity r : existing) {
            StorageNodeEntity n = nodes.get(r.getNodeId());
            if (r.getStatus() != ReplicaStatus.PENDING_DELETE && n != null && n.getStatus() == NodeStatus.HEALTHY
                    && targets.size() < rf) {
                targets.add(n);
            }
        }
        if (targets.size() < rf) {
            Set<String> exclude = new HashSet<>();
            existing.forEach(r -> exclude.add(r.getNodeId()));
            targets.addAll(placement.choose(rf - targets.size(), data.size(), exclude));
        }
        if (targets.size() < quorum) {
            throw VaultException.insufficientReplicas(objectId, "Only " + targets.size()
                    + " healthy node(s) available; write quorum is " + quorum);
        }

        List<WriteResult> results = replication.writeAll(objectId, newVersion, data.file(), data.checksum(), targets);
        List<WriteResult> acknowledged = results.stream().filter(WriteResult::ok).toList();
        if (acknowledged.size() < quorum) {
            replication.discard(objectId, newVersion, results);
            throw VaultException.insufficientReplicas(objectId, "Write quorum not reached: " + acknowledged.size()
                    + " of " + quorum + " required replicas acknowledged");
        }

        Set<String> targetIds = new HashSet<>();
        targets.forEach(n -> targetIds.add(n.getNodeId()));
        List<String> acked = acknowledged.stream().map(w -> w.node().getNodeId()).toList();
        List<String> failed = results.stream().filter(w -> !w.ok()).map(w -> w.node().getNodeId()).toList();
        List<String> dropped = existing.stream()
                .filter(r -> !targetIds.contains(r.getNodeId()) && r.getStatus() != ReplicaStatus.PENDING_DELETE)
                .map(ReplicaEntity::getNodeId).toList();

        boolean committed;
        try {
            committed = metadata.commitUpdate(objectId, expected,
                    fileName == null || fileName.isBlank() ? current.getFileName() : fileName,
                    data.size(), data.checksum(), acked, failed, dropped);
        } catch (RuntimeException e) {
            replication.discard(objectId, newVersion, results);
            throw e;
        }
        if (!committed) {
            replication.discard(objectId, newVersion, results);
            metrics.versionConflicts.increment();
            long actual = metadata.find(objectId).map(ObjectEntity::getVersion).orElse(-1L);
            throw VaultException.versionConflict(objectId, expected, actual);
        }

        // The new version is durable; now drop the old version's files on nodes that were updated in place.
        for (ReplicaEntity old : existing) {
            if (acked.contains(old.getNodeId()) && old.getVersion() < newVersion) {
                StorageNodeEntity n = nodes.get(old.getNodeId());
                if (n != null) {
                    garbageCollector.deleteFileQuietly(n, objectId, old.getVersion());
                }
            }
        }
        if (acknowledged.size() < rf) {
            events.publish(new VaultEvent.ObjectUnderReplicated(objectId));
        }
        metrics.updates.increment();
        return view(objectId);
    }

    // ---- read ------------------------------------------------------------------------------------

    /**
     * Returns a checksum-verified copy of the object. Corrupt or missing replicas found on the way are
     * flagged for repair and the next replica is tried.
     */
    public Download download(String objectId) {
        long started = System.nanoTime();
        for (int attempt = 0; attempt < MAX_READ_ATTEMPTS; attempt++) {
            ObjectEntity object = requireActive(objectId);
            Optional<Download> result = tryDownload(object);
            if (result.isPresent()) {
                metrics.downloads.increment();
                metrics.downloadLatency.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                return result.get();
            }
            // A concurrent update commits the new version and then removes the old files, so a reader that
            // loaded the old version can find every replica gone. That is not a failure: re-read the metadata
            // and try the newer version. Give up only if the version did not move (a genuine outage).
            long now = metadata.find(objectId).map(ObjectEntity::getVersion).orElse(object.getVersion());
            if (now == object.getVersion()) {
                break;
            }
        }
        throw VaultException.insufficientReplicas(objectId, "No healthy replica could be read");
    }

    private Optional<Download> tryDownload(ObjectEntity object) {
        String objectId = object.getObjectId();
        Map<String, StorageNodeEntity> nodes = nodeService.byId();
        List<ReplicaEntity> candidates = metadata.replicasOf(objectId).stream()
                .filter(r -> r.getStatus() == ReplicaStatus.HEALTHY && r.getVersion() == object.getVersion())
                .filter(r -> nodes.containsKey(r.getNodeId()))
                .sorted(Comparator
                        .comparingInt((ReplicaEntity r) -> readRank(nodes.get(r.getNodeId()).getStatus()))
                        .thenComparingDouble(r -> nodes.get(r.getNodeId()).utilization()))
                .toList();

        if (props.quorum().enforceRead()) {
            long reachable = candidates.stream()
                    .filter(r -> NodeStatus.REACHABLE.contains(nodes.get(r.getNodeId()).getStatus())).count();
            int required = replication.readQuorum(object.getReplicationFactor());
            if (reachable < required) {
                throw VaultException.insufficientReplicas(objectId, "Read quorum not met: " + reachable
                        + " of " + required + " required replicas reachable");
            }
        }

        for (ReplicaEntity replica : candidates) {
            StorageNodeEntity node = nodes.get(replica.getNodeId());
            try {
                Downloaded d = client.get(node, objectId, replica.getVersion());
                if (d.checksum().equalsIgnoreCase(object.getChecksum())) {
                    return Optional.of(new Download(object, d.file()));
                }
                tempFiles.deleteQuietly(d.file());
                log.error("checksum mismatch reading {} from {}: expected {} got {}", objectId, node.getNodeId(),
                        object.getChecksum(), d.checksum());
                if (metadata.markReplica(objectId, node.getNodeId(), replica.getVersion(), ReplicaStatus.CORRUPTED)) {
                    metrics.corruptionsDetected.increment();
                    events.publish(new VaultEvent.ReplicaCorrupted(objectId, node.getNodeId()));
                }
            } catch (NodeRpcException e) {
                switch (e.getKind()) {
                    case UNREACHABLE -> nodeService.suspect(node.getNodeId());
                    case NOT_FOUND -> {
                        if (metadata.markReplica(objectId, node.getNodeId(), replica.getVersion(), ReplicaStatus.MISSING)) {
                            metrics.missingDetected.increment();
                            events.publish(new VaultEvent.ReplicaMissing(objectId, node.getNodeId()));
                        }
                    }
                    default -> log.warn("read of {} from {} failed: {}", objectId, node.getNodeId(), e.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    private static int readRank(NodeStatus status) {
        return switch (status) {
            case HEALTHY -> 0;
            case SUSPECTED -> 1;
            case RECOVERING -> 2;
            case UNHEALTHY -> 3;
        };
    }

    // ---- delete ----------------------------------------------------------------------------------

    /** Tombstones the object, then removes replicas; nodes that are down are cleaned up later. Idempotent. */
    public void delete(String objectId) {
        ObjectEntity object = metadata.find(objectId).orElseThrow(() -> VaultException.notFound(objectId));
        if (object.getStatus() != ObjectStatus.ACTIVE) {
            return; // already deleting/deleted
        }
        Optional<List<ReplicaEntity>> rows = metadata.beginDelete(objectId);
        if (rows.isEmpty()) {
            return; // lost a race with another delete
        }
        if (rows.get().isEmpty()) {
            metadata.finalizeDeletionIfDrained(objectId);
        }
        for (ReplicaEntity row : rows.get()) {
            garbageCollector.deleteNow(row);
        }
        metrics.deletes.increment();
    }

    // ---- listing ---------------------------------------------------------------------------------

    public record Page(List<ObjectMetadataResponse> items, int page, int size, long totalItems, int totalPages) {
    }

    /**
     * Everything the list screens can ask for. {@code ownerId == null} lists everyone's objects (admin
     * inspection; items then carry the owner's email).
     *
     * @param trashed        true = only trashed files, false = only files outside the trash
     * @param type           a {@link FileTypes.Category} key, or null
     * @param modifiedAfter  inclusive lower bound on last-modified time, or null
     * @param modifiedBefore exclusive upper bound, or null
     * @param sort           name | size | modified | created (default created)
     * @param descending     sort direction
     */
    public record ListQuery(String ownerId, boolean trashed, String nameContains, String type, Instant modifiedAfter,
                            Instant modifiedBefore, String projectId, String sort, boolean descending, int page,
                            int size) {
    }

    public Page list(String ownerId, int page, int size, String nameContains) {
        return list(new ListQuery(ownerId, false, nameContains, null, null, null, null, "created", true, page, size));
    }

    public Page list(ListQuery q) {
        String ownerId = q.ownerId();
        int safeSize = Math.max(1, Math.min(q.size(), 100));
        int safePage = Math.max(0, q.page());
        String sortField = switch (q.sort() == null ? "" : q.sort()) {
            case "name" -> "fileName";
            case "size" -> "sizeBytes";
            case "modified" -> "updatedAt";
            default -> "createdAt";
        };
        org.springframework.data.domain.Sort order = org.springframework.data.domain.Sort.by(
                q.descending() ? org.springframework.data.domain.Sort.Direction.DESC : org.springframework.data.domain.Sort.Direction.ASC,
                sortField).and(org.springframework.data.domain.Sort.by("objectId"));
        org.springframework.data.domain.PageRequest paging = org.springframework.data.domain.PageRequest.of(safePage,
                safeSize, order);

        org.springframework.data.jpa.domain.Specification<ObjectEntity> spec = (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> p = new java.util.ArrayList<>();
            p.add(cb.equal(root.get("status"), ObjectStatus.ACTIVE));
            p.add(q.trashed() ? cb.isNotNull(root.get("trashedAt")) : cb.isNull(root.get("trashedAt")));
            if (ownerId != null) {
                p.add(cb.equal(root.get("ownerId"), ownerId));
            }
            if (q.nameContains() != null && !q.nameContains().isBlank()) {
                p.add(cb.like(cb.lower(root.get("fileName")), "%" + q.nameContains().toLowerCase().replace("%", "\\%") + "%", '\\'));
            }
            if (q.projectId() != null && !q.projectId().isBlank()) {
                p.add(cb.equal(root.get("projectId"), q.projectId()));
            }
            if (q.modifiedAfter() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), q.modifiedAfter()));
            }
            if (q.modifiedBefore() != null) {
                p.add(cb.lessThan(root.get("updatedAt"), q.modifiedBefore()));
            }
            FileTypes.parse(q.type()).ifPresent(cat -> {
                if (cat == FileTypes.Category.OTHER) {
                    for (FileTypes.Category c : FileTypes.Category.values()) {
                        c.extensions().forEach(e -> p.add(cb.notLike(cb.lower(root.get("fileName")), "%." + e)));
                    }
                } else {
                    p.add(cb.or(cat.extensions().stream()
                            .map(e -> cb.like(cb.lower(root.get("fileName")), "%." + e))
                            .toArray(jakarta.persistence.criteria.Predicate[]::new)));
                }
            });
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        org.springframework.data.domain.Page<ObjectEntity> result = objectRepository.findAll(spec, paging);
        Map<String, StorageNodeEntity> nodes = nodeService.byId();
        Map<String, String> owners = ownerId == null
                ? users.emailsOf(result.getContent().stream().map(ObjectEntity::getOwnerId)
                        .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()))
                : new java.util.HashMap<>();
        List<ObjectMetadataResponse> items = result.getContent().stream()
                .map(o -> ObjectMetadataResponse.of(o, metadata.replicasOf(o.getObjectId()), nodes)
                        .withOwner(owners.get(o.getOwnerId())))
                .toList();
        return new Page(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }

    /**
     * Ownership check. Users only ever see their own objects; anything else is reported as 404 so that other
     * people's object ids cannot even be probed.
     *
     * @param adminMayAccess true for read/delete by an admin (admins never get to upload or replace content)
     */
    public void authorize(String objectId, String userId, boolean adminMayAccess) {
        ObjectEntity o = requireActive(objectId);
        if (!adminMayAccess && (o.getOwnerId() == null || !o.getOwnerId().equals(userId))) {
            throw VaultException.notFound(objectId);
        }
    }

    /** Owner's email for admin views, or null. */
    public String ownerEmailOf(String objectId) {
        return metadata.find(objectId).map(ObjectEntity::getOwnerId)
                .map(id -> users.emailsOf(List.of(id)).get(id)).orElse(null);
    }

    // ---- metadata --------------------------------------------------------------------------------

    public ObjectMetadataResponse metadata(String objectId) {
        return view(objectId);
    }

    private ObjectMetadataResponse view(String objectId) {
        ObjectEntity object = requireActive(objectId);
        return ObjectMetadataResponse.of(object, metadata.replicasOf(objectId), nodeService.byId());
    }

    /** An object that exists and is not in the trash: what every normal read/update operation works on. */
    private ObjectEntity requireActive(String objectId) {
        return metadata.find(objectId).filter(o -> o.getStatus() == ObjectStatus.ACTIVE && o.getTrashedAt() == null)
                .orElseThrow(() -> VaultException.notFound(objectId));
    }

    // ---- trash, usage, quota ---------------------------------------------------------------------

    /** Like {@link #authorize} but also accepts trashed objects (restore / delete forever). */
    public void authorizeAny(String objectId, String userId, boolean adminMayAccess) {
        ObjectEntity o = metadata.find(objectId).filter(x -> x.getStatus() == ObjectStatus.ACTIVE)
                .orElseThrow(() -> VaultException.notFound(objectId));
        if (!adminMayAccess && (o.getOwnerId() == null || !o.getOwnerId().equals(userId))) {
            throw VaultException.notFound(objectId);
        }
    }

    /** Soft delete: hidden everywhere, still stored, replicated and repaired until restored or purged. */
    public void moveToTrash(String objectId) {
        objectRepository.setTrashedAt(objectId, Instant.now());
    }

    public void restore(String objectId) {
        objectRepository.setTrashedAt(objectId, null);
    }

    /** Permanently deletes everything in the caller's trash. @return number of files deleted */
    public int emptyTrash(String ownerId) {
        List<String> ids = objectRepository.findTrashedIdsOf(ownerId);
        ids.forEach(this::delete);
        return ids.size();
    }

    /** Permanently deletes files that have sat in the trash longer than the retention period. */
    public int purgeExpiredTrash(Instant cutoff) {
        List<String> ids = objectRepository.findTrashedBefore(cutoff, org.springframework.data.domain.PageRequest.of(0, 500));
        ids.forEach(this::delete);
        return ids.size();
    }

    public record Usage(long usedBytes, long quotaBytes, long fileCount, Map<String, Long> bytesByType) {
    }

    /** Storage the account uses (trash included) with a per-type breakdown. */
    public Usage usage(String ownerId) {
        Map<String, Long> byType = new java.util.LinkedHashMap<>();
        for (FileTypes.Category c : FileTypes.Category.values()) {
            byType.put(c.key(), 0L);
        }
        long total = 0;
        long count = 0;
        for (Object[] row : objectRepository.fileSizesOf(ownerId)) {
            long size = ((Number) row[1]).longValue();
            total += size;
            count++;
            byType.merge(FileTypes.categoryOf((String) row[0]).key(), size, Long::sum);
        }
        return new Usage(total, props.userQuota().bytes(), count, byType);
    }

    private void checkQuota(String ownerId, long additionalBytes) {
        if (ownerId == null || additionalBytes <= 0) {
            return;
        }
        long used = usage(ownerId).usedBytes();
        if (used + additionalBytes > props.userQuota().bytes()) {
            throw new VaultException(org.springframework.http.HttpStatus.INSUFFICIENT_STORAGE, "QUOTA_EXCEEDED",
                    "Not enough storage: this file would exceed your " + props.userQuota().bytes() + "-byte limit", null);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Copies the upload to a temp file while computing its SHA-256, so it can be re-read once per replica. */
    private Spooled spool(InputStream body) {
        Path tmp = tempFiles.create("upload");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ChecksumService.Copied copied = checksums.copyAndHash(body, out);
            return new Spooled(tmp, copied.checksum(), copied.size());
        } catch (IOException e) {
            tempFiles.deleteQuietly(tmp);
            throw new UncheckedIOException(e);
        }
    }
}
