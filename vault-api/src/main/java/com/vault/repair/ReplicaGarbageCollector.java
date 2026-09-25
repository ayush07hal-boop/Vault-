package com.vault.repair;

import com.vault.metadata.MetadataService;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaRepository;
import com.vault.metadata.ReplicaStatus;
import com.vault.metadata.StorageNodeEntity;
import com.vault.replication.ObjectLocks;
import com.vault.service.NodeService;
import com.vault.storage.NodeRpcException;
import com.vault.storage.StorageNodeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

/**
 * Removes replica files that metadata has already given up on (deleted objects, trimmed or moved copies).
 * Metadata always changes first; the file is only deleted afterwards, and the row is only dropped once
 * the node confirms the deletion. Nodes that are down are simply retried on the next pass.
 */
@Service
public class ReplicaGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(ReplicaGarbageCollector.class);

    private final ReplicaRepository replicas;
    private final MetadataService metadata;
    private final NodeService nodeService;
    private final StorageNodeClient client;
    private final ObjectLocks locks;

    public ReplicaGarbageCollector(ReplicaRepository replicas, MetadataService metadata, NodeService nodeService,
                                   StorageNodeClient client, ObjectLocks locks) {
        this.replicas = replicas;
        this.metadata = metadata;
        this.nodeService = nodeService;
        this.client = client;
        this.locks = locks;
    }

    /** One sweep over PENDING_DELETE rows. @return number of replicas actually removed */
    public int collect(int limit) {
        int removed = 0;
        List<ReplicaEntity> pending = replicas.findByStatus(ReplicaStatus.PENDING_DELETE, PageRequest.of(0, limit));
        for (ReplicaEntity row : pending) {
            if (deleteNow(row)) {
                removed++;
            }
        }
        return removed;
    }

    /** @return true if the file is gone and the metadata row has been removed */
    public boolean deleteNow(ReplicaEntity row) {
        Lock lock = locks.lockFor(row.getObjectId());
        lock.lock();
        try {
            Optional<StorageNodeEntity> node = nodeService.find(row.getNodeId());
            if (node.isEmpty() || !NodeStatus.REACHABLE.contains(node.get().getStatus())) {
                return false; // try again when the node is back
            }
            client.delete(node.get(), row.getObjectId(), row.getVersion());
            metadata.removeReplica(row.getObjectId(), row.getNodeId());
            return true;
        } catch (NodeRpcException e) {
            log.warn("could not delete {} v{} from {}: {}", row.getObjectId(), row.getVersion(), row.getNodeId(),
                    e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Best-effort removal of one specific old version file (no metadata involved). */
    public void deleteFileQuietly(StorageNodeEntity node, String objectId, long version) {
        try {
            client.delete(node, objectId, version);
        } catch (NodeRpcException e) {
            log.warn("could not remove old {} v{} from {}: {}", objectId, version, node.getNodeId(), e.getMessage());
        }
    }
}
