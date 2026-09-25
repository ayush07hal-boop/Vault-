package com.vault.metadata;

public enum ObjectStatus {
    ACTIVE,
    /** Tombstone: the object is logically gone; replicas are being garbage-collected. */
    DELETING,
    /** Tombstone with every replica confirmed removed. Kept so stale copies can never resurrect it. */
    DELETED
}
