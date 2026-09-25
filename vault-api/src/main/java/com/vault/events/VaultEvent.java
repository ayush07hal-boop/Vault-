package com.vault.events;

/**
 * Domain events raised by the health monitor, integrity checker and write path.
 * Today they travel over Spring's in-process bus; {@link VaultEventPublisher} is the seam where
 * a Kafka producer can be substituted without touching the producers or consumers.
 */
public sealed interface VaultEvent {

    record NodeFailed(String nodeId) implements VaultEvent {
    }

    record NodeRecovered(String nodeId) implements VaultEvent {
    }

    record ReplicaMissing(String objectId, String nodeId) implements VaultEvent {
    }

    record ReplicaCorrupted(String objectId, String nodeId) implements VaultEvent {
    }

    /** A write finished with fewer replicas than the replication factor. */
    record ObjectUnderReplicated(String objectId) implements VaultEvent {
    }

    record ReplicaOutdated(String objectId, String nodeId) implements VaultEvent {
    }
}
