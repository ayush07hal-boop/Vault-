package com.vault.metadata;

import java.io.Serializable;
import java.util.Objects;

public class ReplicaId implements Serializable {

    private String objectId;
    private String nodeId;

    public ReplicaId() {
    }

    public ReplicaId(String objectId, String nodeId) {
        this.objectId = objectId;
        this.nodeId = nodeId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReplicaId r && Objects.equals(objectId, r.objectId) && Objects.equals(nodeId, r.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(objectId, nodeId);
    }
}
