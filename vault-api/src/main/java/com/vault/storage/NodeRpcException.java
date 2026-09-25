package com.vault.storage;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** A failed call to a storage node, classified so callers can decide what it means. */
public class NodeRpcException extends RuntimeException {

    public enum Kind {
        /** Timeout / connection refused: says nothing about whether the data is still there. */
        UNREACHABLE,
        /** Node answered, object (or source object) is not there. */
        NOT_FOUND,
        /** Bytes did not hash to the expected checksum. */
        CHECKSUM_MISMATCH,
        NO_SPACE,
        ERROR
    }

    private final String nodeId;
    private final Kind kind;

    public NodeRpcException(String nodeId, Kind kind, String message, Throwable cause) {
        super("node " + nodeId + ": " + message, cause);
        this.nodeId = nodeId;
        this.kind = kind;
    }

    public String getNodeId() {
        return nodeId;
    }

    public Kind getKind() {
        return kind;
    }

    static NodeRpcException from(String nodeId, Throwable t) {
        if (t instanceof NodeRpcException n) {
            return n;
        }
        Status status = Status.fromThrowable(t);
        Kind kind = switch (status.getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED -> Kind.UNREACHABLE;
            case NOT_FOUND -> Kind.NOT_FOUND;
            case DATA_LOSS -> Kind.CHECKSUM_MISMATCH;
            case RESOURCE_EXHAUSTED -> Kind.NO_SPACE;
            default -> Kind.ERROR;
        };
        String msg = t instanceof StatusRuntimeException
                ? status.getCode() + (status.getDescription() == null ? "" : ": " + status.getDescription())
                : String.valueOf(t.getMessage());
        return new NodeRpcException(nodeId, kind, msg, t);
    }
}
