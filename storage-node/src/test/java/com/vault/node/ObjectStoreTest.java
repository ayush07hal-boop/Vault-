package com.vault.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStoreTest {

    @TempDir
    Path dir;

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static ObjectStore.Result put(ObjectStore store, String id, long v, byte[] data, String expected) throws Exception {
        try (ObjectStore.Writer w = store.begin(id, v)) {
            w.write(data, 0, data.length);
            return w.commit(expected);
        }
    }

    @Test
    void writeVerifyDelete() throws Exception {
        ObjectStore store = new ObjectStore(dir, 1024);
        byte[] data = "hello vault".getBytes(StandardCharsets.UTF_8);

        ObjectStore.Result r = put(store, "obj-1", 1, data, sha256(data));

        assertEquals(sha256(data), r.checksum());
        assertEquals(data.length, store.usedBytes());
        assertEquals(1, store.objectCount());
        assertEquals(r, store.verify("obj-1", 1).orElseThrow());

        assertTrue(store.delete("obj-1", 1));
        assertFalse(store.delete("obj-1", 1), "delete is idempotent");
        assertEquals(0, store.usedBytes());
        assertTrue(store.verify("obj-1", 1).isEmpty());
    }

    @Test
    void verifyDetectsOnDiskCorruption() throws Exception {
        ObjectStore store = new ObjectStore(dir, 1024);
        byte[] data = "important".getBytes(StandardCharsets.UTF_8);
        ObjectStore.Result r = put(store, "obj-2", 1, data, null);

        Files.write(store.pathOf("obj-2", 1), "tampered".getBytes(StandardCharsets.UTF_8));

        assertTrue(!store.verify("obj-2", 1).orElseThrow().checksum().equals(r.checksum()));
    }

    @Test
    void checksumMismatchRejectsWriteAndLeavesNothingBehind() throws Exception {
        ObjectStore store = new ObjectStore(dir, 1024);
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);

        assertThrows(ObjectStore.ChecksumMismatchException.class, () -> put(store, "obj-3", 1, data, "deadbeef"));

        assertTrue(store.find("obj-3", 1).isEmpty());
        assertEquals(0, store.usedBytes());
    }

    @Test
    void capacityIsEnforced() throws Exception {
        ObjectStore store = new ObjectStore(dir, 10);
        assertThrows(ObjectStore.CapacityExceededException.class, () -> put(store, "big", 1, new byte[11], null));
        assertEquals(0, store.usedBytes());
    }

    @Test
    void versionZeroDeletesAllVersionsAndIdsAreValidated() throws Exception {
        ObjectStore store = new ObjectStore(dir, 1024);
        put(store, "multi", 1, new byte[]{1}, null);
        put(store, "multi", 2, new byte[]{2}, null);

        assertTrue(store.delete("multi", 0));

        assertTrue(store.find("multi", 1).isEmpty());
        assertTrue(store.find("multi", 2).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.pathOf("../escape", 1));
    }

    @Test
    void restartRecoversUsageFromDisk() throws Exception {
        ObjectStore first = new ObjectStore(dir, 1024);
        put(first, "persist", 1, new byte[100], null);

        ObjectStore second = new ObjectStore(dir, 1024);

        assertEquals(100, second.usedBytes());
        assertEquals(1, second.objectCount());
    }
}
