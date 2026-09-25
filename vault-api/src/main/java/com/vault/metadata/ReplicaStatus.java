package com.vault.metadata;

import java.util.EnumSet;
import java.util.Set;

public enum ReplicaStatus {
    HEALTHY,
    /** Checksum on disk does not match metadata. */
    CORRUPTED,
    /** Node is up but the file is gone. */
    MISSING,
    /** Holds an older version than the object's current one. */
    OUTDATED,
    /** Scheduled for removal from the node (trim, rebalance, delete). */
    PENDING_DELETE;

    /** Statuses that mean "this row is broken and should be re-copied in place". */
    public static final Set<ReplicaStatus> NEEDS_REPAIR = EnumSet.of(CORRUPTED, MISSING, OUTDATED);
}
