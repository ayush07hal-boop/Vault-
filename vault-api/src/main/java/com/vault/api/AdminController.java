package com.vault.api;

import com.vault.health.HealthMonitor;
import com.vault.integrity.IntegrityVerifier;
import com.vault.rebalance.Rebalancer;
import com.vault.repair.RepairScanner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual triggers for the background loops, useful for demos and operations. The prototype has no
 * authentication; these endpoints must sit behind auth (or be removed) before any real deployment.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final HealthMonitor healthMonitor;
    private final RepairScanner repairScanner;
    private final IntegrityVerifier integrityVerifier;
    private final Rebalancer rebalancer;

    public AdminController(HealthMonitor healthMonitor, RepairScanner repairScanner,
                           IntegrityVerifier integrityVerifier, Rebalancer rebalancer) {
        this.healthMonitor = healthMonitor;
        this.repairScanner = repairScanner;
        this.integrityVerifier = integrityVerifier;
        this.rebalancer = rebalancer;
    }

    @PostMapping("/heartbeat")
    public Map<String, String> heartbeat() {
        healthMonitor.pollOnce();
        return Map.of("status", "done");
    }

    @PostMapping("/repair/scan")
    public Map<String, Integer> repairScan() {
        return Map.of("queued", repairScanner.scan());
    }

    @PostMapping("/integrity/verify")
    public IntegrityVerifier.Report integrity() {
        return integrityVerifier.verifyBatch();
    }

    @PostMapping("/rebalance/run")
    public Map<String, Integer> rebalance() {
        return Map.of("moved", rebalancer.runOnce());
    }
}
