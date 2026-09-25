package com.vault.metadata;

import java.util.EnumSet;
import java.util.Set;

public enum NodeStatus {
    HEALTHY,
    /** Missed a heartbeat or failed an RPC; may just be a transient network problem. */
    SUSPECTED,
    /** Failed repeatedly; its replicas no longer count towards durability. */
    UNHEALTHY,
    /** Answering again after being UNHEALTHY but not yet trusted with new data. */
    RECOVERING;

    /** Nodes we may still try to read from / copy from. */
    public static final Set<NodeStatus> REACHABLE = EnumSet.of(HEALTHY, SUSPECTED, RECOVERING);
}
