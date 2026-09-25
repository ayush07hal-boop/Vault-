package com.vault.replication;

import com.vault.config.AsyncConfig;
import com.vault.config.VaultProperties;
import com.vault.metadata.StorageNodeEntity;
import com.vault.service.NodeService;
import com.vault.storage.NodeRpcException;
import com.vault.storage.StorageNodeClient;
import com.vault.storage.StorageNodeClient.PutResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** Fans a write out to several nodes in parallel and reports which ones acknowledged it. */
@Service
public class ReplicationManager {

    private static final Logger log = LoggerFactory.getLogger(ReplicationManager.class);

    private final StorageNodeClient client;
    private final NodeService nodeService;
    private final VaultProperties props;
    private final ExecutorService io;

    public ReplicationManager(StorageNodeClient client, NodeService nodeService, VaultProperties props,
                              @Qualifier(AsyncConfig.IO_EXECUTOR) ExecutorService io) {
        this.client = client;
        this.nodeService = nodeService;
        this.props = props;
        this.io = io;
    }

    public record WriteResult(StorageNodeEntity node, PutResult result, NodeRpcException error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** Effective write quorum for an object: configured W, but never more than the replication factor. */
    public int writeQuorum(int replicationFactor) {
        return Math.max(1, Math.min(props.quorum().write(), replicationFactor));
    }

    public int readQuorum(int replicationFactor) {
        return Math.max(1, Math.min(props.quorum().read(), replicationFactor));
    }

    public List<WriteResult> writeAll(String objectId, long version, Path file, String checksum,
                                      List<StorageNodeEntity> targets) {
        List<CompletableFuture<WriteResult>> futures = targets.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> writeOne(objectId, version, file, checksum, node), io))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private WriteResult writeOne(String objectId, long version, Path file, String checksum, StorageNodeEntity node) {
        try {
            PutResult r = client.put(node, objectId, version, file, checksum);
            if (!checksum.equalsIgnoreCase(r.checksum())) {
                throw new NodeRpcException(node.getNodeId(), NodeRpcException.Kind.CHECKSUM_MISMATCH,
                        "node reported checksum " + r.checksum(), null);
            }
            return new WriteResult(node, r, null);
        } catch (NodeRpcException e) {
            log.warn("write of {} v{} to {} failed: {}", objectId, version, node.getNodeId(), e.getMessage());
            if (e.getKind() == NodeRpcException.Kind.UNREACHABLE) {
                nodeService.suspect(node.getNodeId());
            }
            return new WriteResult(node, null, e);
        }
    }

    /** Best-effort removal of files we wrote but could not commit to metadata. */
    public void discard(String objectId, long version, List<WriteResult> written) {
        for (WriteResult w : written) {
            if (w.ok()) {
                try {
                    client.delete(w.node(), objectId, version);
                } catch (NodeRpcException e) {
                    log.warn("could not discard {} v{} on {}: {}", objectId, version, w.node().getNodeId(), e.getMessage());
                }
            }
        }
    }
}
