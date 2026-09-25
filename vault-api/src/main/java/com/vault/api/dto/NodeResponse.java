package com.vault.api.dto;

import com.vault.metadata.StorageNodeEntity;

import java.time.Instant;

public record NodeResponse(String nodeId, String address, int port, String zone, String status,
                           long totalCapacity, long usedCapacity, double utilization, Instant lastHeartbeat,
                           int consecutiveFailures) {

    public static NodeResponse of(StorageNodeEntity n) {
        return new NodeResponse(n.getNodeId(), n.getAddress(), n.getPort(), n.getZone(), n.getStatus().name(),
                n.getTotalCapacity(), n.getUsedCapacity(), n.utilization(), n.getLastHeartbeat(),
                n.getConsecutiveFailures());
    }
}
