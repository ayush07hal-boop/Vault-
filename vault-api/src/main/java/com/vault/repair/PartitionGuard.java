package com.vault.repair;

import com.vault.config.VaultProperties;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.StorageNodeEntity;
import com.vault.service.NodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * When most of the cluster suddenly looks unreachable, the likelier explanation is a network partition
 * (or a problem on the controller's side) than dozens of simultaneous disk deaths. Re-replicating in that
 * situation would flood the network and create duplicate copies that must be trimmed once the partition heals,
 * so background repair pauses until a majority of nodes is reachable again.
 */
@Component
public class PartitionGuard {

    private static final Logger log = LoggerFactory.getLogger(PartitionGuard.class);

    private final NodeService nodeService;
    private final VaultProperties props;

    public PartitionGuard(NodeService nodeService, VaultProperties props) {
        this.nodeService = nodeService;
        this.props = props;
    }

    public boolean isTripped() {
        List<StorageNodeEntity> all = nodeService.all();
        if (all.size() < 2) {
            return false;
        }
        long unreachable = all.stream()
                .filter(n -> n.getStatus() == NodeStatus.SUSPECTED || n.getStatus() == NodeStatus.UNHEALTHY)
                .count();
        double fraction = (double) unreachable / all.size();
        boolean tripped = fraction > props.repair().partitionGuardFraction();
        if (tripped) {
            log.warn("partition guard: {}/{} nodes unreachable - background repair paused", unreachable, all.size());
        }
        return tripped;
    }
}
