package com.vault.node;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Entry point for a standalone storage node. Configuration via environment variables:
 * NODE_ID, NODE_PORT (default 9090), DATA_DIR (default ./data), NODE_CAPACITY_BYTES (default 10 GiB).
 */
public final class StorageNodeMain {

    private static final Logger LOG = Logger.getLogger(StorageNodeMain.class.getName());

    private StorageNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        String nodeId = env("NODE_ID", "node-1");
        int port = Integer.parseInt(env("NODE_PORT", "9090"));
        Path dataDir = Path.of(env("DATA_DIR", "./data"));
        long capacity = Long.parseLong(env("NODE_CAPACITY_BYTES", String.valueOf(10L * 1024 * 1024 * 1024)));

        StorageNodeServer node = new StorageNodeServer(nodeId, port, dataDir, capacity).start();
        LOG.info("storage node " + nodeId + " listening on :" + node.getPort() + " data=" + dataDir.toAbsolutePath());
        Runtime.getRuntime().addShutdownHook(new Thread(node::stop));
        node.awaitTermination();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
