package com.vault.repair;

import com.vault.config.VaultProperties;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectRepository;
import com.vault.metrics.VaultMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Periodic safety net: finds every object whose replica state differs from its target (even if no event
 * was ever raised, e.g. after a controller restart) and queues it, and sweeps pending file deletions.
 */
@Component
public class RepairScanner {

    private static final Logger log = LoggerFactory.getLogger(RepairScanner.class);

    private final ObjectRepository objects;
    private final RepairQueue queue;
    private final PartitionGuard guard;
    private final ReplicaGarbageCollector garbageCollector;
    private final VaultMetrics metrics;
    private final VaultProperties props;

    public RepairScanner(ObjectRepository objects, RepairQueue queue, PartitionGuard guard,
                         ReplicaGarbageCollector garbageCollector, VaultMetrics metrics, VaultProperties props) {
        this.objects = objects;
        this.queue = queue;
        this.guard = guard;
        this.garbageCollector = garbageCollector;
        this.metrics = metrics;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${vault.repair.interval-seconds:10}",
            initialDelayString = "${vault.repair.interval-seconds:10}", timeUnit = TimeUnit.SECONDS)
    void scheduledScan() {
        try {
            scan();
        } catch (Exception e) {
            log.error("repair scan failed", e);
        }
    }

    /** @return number of objects newly queued for repair */
    public int scan() {
        if (guard.isTripped()) {
            metrics.repairsPausedByPartitionGuard.increment();
            return 0;
        }
        garbageCollector.collect(props.repair().scanLimit());
        List<String> ids = objects.findObjectIdsNeedingRepair(NodeStatus.REACHABLE,
                PageRequest.of(0, props.repair().scanLimit()));
        int added = queue.enqueueAll(ids);
        if (added > 0) {
            log.info("repair scan queued {} object(s)", added);
        }
        return added;
    }
}
