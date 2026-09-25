package com.vault.repair;

import com.vault.config.VaultProperties;
import com.vault.events.VaultEvent;
import com.vault.metadata.ReplicaRepository;
import com.vault.metrics.VaultMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns events into repair work and drains the {@link RepairQueue} with a bounded number of threads
 * ({@code vault.repair.max-concurrent-tasks}).
 */
@Component
public class RepairWorker {

    private static final Logger log = LoggerFactory.getLogger(RepairWorker.class);

    private final RepairQueue queue;
    private final RepairService repairService;
    private final PartitionGuard guard;
    private final ReplicaRepository replicas;
    private final VaultMetrics metrics;
    private final VaultProperties props;
    private final List<Thread> threads = new ArrayList<>();

    public RepairWorker(RepairQueue queue, RepairService repairService, PartitionGuard guard,
                        ReplicaRepository replicas, VaultMetrics metrics, VaultProperties props) {
        this.queue = queue;
        this.repairService = repairService;
        this.guard = guard;
        this.replicas = replicas;
        this.metrics = metrics;
        this.props = props;
    }

    @PostConstruct
    void start() {
        if (!props.workers().enabled()) {
            return;
        }
        for (int i = 0; i < Math.max(1, props.repair().maxConcurrentTasks()); i++) {
            Thread t = new Thread(this::loop, "repair-worker-" + i);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
    }

    @PreDestroy
    void stop() {
        threads.forEach(Thread::interrupt);
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String objectId = queue.take();
                if (guard.isTripped()) {
                    metrics.repairsPausedByPartitionGuard.increment();
                    continue; // the periodic scan re-discovers it once the cluster is reachable again
                }
                RepairService.Outcome outcome = repairService.repairObject(objectId);
                log.debug("repair of {} -> {}", objectId, outcome);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                metrics.repairFailures.increment();
                log.error("repair task failed", e);
            }
        }
    }

    // ---- event -> work ---------------------------------------------------------------------------

    @EventListener
    public void on(VaultEvent.NodeFailed e) {
        metrics.nodeFailures.increment();
        int added = queue.enqueueAll(replicas.findObjectIdsByNodeId(e.nodeId()));
        log.warn("node {} failed: queued {} object(s) for repair", e.nodeId(), added);
    }

    @EventListener
    public void on(VaultEvent.NodeRecovered e) {
        // A returning node may cause over-replication or hold stale data; reconcile everything it had.
        queue.enqueueAll(replicas.findObjectIdsByNodeId(e.nodeId()));
    }

    @EventListener
    public void on(VaultEvent.ReplicaCorrupted e) {
        queue.enqueue(e.objectId());
    }

    @EventListener
    public void on(VaultEvent.ReplicaMissing e) {
        queue.enqueue(e.objectId());
    }

    @EventListener
    public void on(VaultEvent.ReplicaOutdated e) {
        queue.enqueue(e.objectId());
    }

    @EventListener
    public void on(VaultEvent.ObjectUnderReplicated e) {
        queue.enqueue(e.objectId());
    }
}
