package com.vault.events;

/** Wire format of a {@link VaultEvent} on Kafka (JSON). */
public record EventMessage(String type, String objectId, String nodeId) {

    public static EventMessage of(VaultEvent e) {
        return switch (e) {
            case VaultEvent.NodeFailed n -> new EventMessage("NODE_FAILED", null, n.nodeId());
            case VaultEvent.NodeRecovered n -> new EventMessage("NODE_RECOVERED", null, n.nodeId());
            case VaultEvent.ReplicaMissing r -> new EventMessage("REPLICA_MISSING", r.objectId(), r.nodeId());
            case VaultEvent.ReplicaCorrupted r -> new EventMessage("REPLICA_CORRUPTED", r.objectId(), r.nodeId());
            case VaultEvent.ReplicaOutdated r -> new EventMessage("REPLICA_OUTDATED", r.objectId(), r.nodeId());
            case VaultEvent.ObjectUnderReplicated o -> new EventMessage("OBJECT_UNDER_REPLICATED", o.objectId(), null);
        };
    }

    public VaultEvent toEvent() {
        return switch (type) {
            case "NODE_FAILED" -> new VaultEvent.NodeFailed(nodeId);
            case "NODE_RECOVERED" -> new VaultEvent.NodeRecovered(nodeId);
            case "REPLICA_MISSING" -> new VaultEvent.ReplicaMissing(objectId, nodeId);
            case "REPLICA_CORRUPTED" -> new VaultEvent.ReplicaCorrupted(objectId, nodeId);
            case "REPLICA_OUTDATED" -> new VaultEvent.ReplicaOutdated(objectId, nodeId);
            case "OBJECT_UNDER_REPLICATED" -> new VaultEvent.ObjectUnderReplicated(objectId);
            default -> throw new IllegalArgumentException("unknown event type " + type);
        };
    }

    public boolean isNodeEvent() {
        return type.startsWith("NODE_");
    }
}
