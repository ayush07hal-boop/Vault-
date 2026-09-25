package com.vault.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "storage_nodes")
public class StorageNodeEntity {

    @Id
    @Column(name = "node_id", length = 100)
    private String nodeId;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private int port;

    @Column(length = 50)
    private String zone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NodeStatus status;

    @Column(name = "total_capacity", nullable = false)
    private long totalCapacity;

    @Column(name = "used_capacity", nullable = false)
    private long usedCapacity;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "consecutive_successes", nullable = false)
    private int consecutiveSuccesses;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StorageNodeEntity() {
    }

    public StorageNodeEntity(String nodeId, String address, int port, String zone) {
        this.nodeId = nodeId;
        this.address = address;
        this.port = port;
        this.zone = zone;
        this.status = NodeStatus.SUSPECTED; // unproven until the first heartbeat succeeds
        this.createdAt = Instant.now();
    }

    /** Fraction of capacity in use, 0..1. Unknown capacity counts as full so we never place onto it. */
    public double utilization() {
        return totalCapacity <= 0 ? 1.0 : (double) usedCapacity / totalCapacity;
    }

    public long freeBytes() {
        return Math.max(0, totalCapacity - usedCapacity);
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public String getZone() {
        return zone;
    }

    public NodeStatus getStatus() {
        return status;
    }

    public long getTotalCapacity() {
        return totalCapacity;
    }

    public long getUsedCapacity() {
        return usedCapacity;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public int getConsecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public void setStatus(NodeStatus status) {
        this.status = status;
    }

    public void setTotalCapacity(long totalCapacity) {
        this.totalCapacity = totalCapacity;
    }

    public void setUsedCapacity(long usedCapacity) {
        this.usedCapacity = usedCapacity;
    }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public void setConsecutiveSuccesses(int consecutiveSuccesses) {
        this.consecutiveSuccesses = consecutiveSuccesses;
    }
}
