package com.vault.node;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Runs several logical storage nodes (separate ports, separate directories) inside one JVM. Meant for small
 * single-machine deployments and demos, where one process per node would waste memory. Real deployments run
 * {@link StorageNodeMain} on separate machines.
 *
 * <p>Environment: NODE_COUNT (default 4), BASE_PORT (default 9091), DATA_ROOT (default ./data/nodes),
 * NODE_CAPACITY_BYTES (default 1 GiB per node). Nodes are named node-1..node-N on BASE_PORT..BASE_PORT+N-1.
 */
public final class MultiNodeMain {

    private static final Logger LOG = Logger.getLogger(MultiNodeMain.class.getName());

    private MultiNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(env("NODE_COUNT", "4"));
        int basePort = Integer.parseInt(env("BASE_PORT", "9091"));
        Path root = Path.of(env("DATA_ROOT", "./data/nodes"));
        long capacity = Long.parseLong(env("NODE_CAPACITY_BYTES", String.valueOf(1024L * 1024 * 1024)));

        StorageNodeServer[] nodes = new StorageNodeServer[count];
        for (int i = 0; i < count; i++) {
            String id = "node-" + (i + 1);
            nodes[i] = new StorageNodeServer(id, basePort + i, root.resolve(id), capacity).start();
            LOG.info("storage node " + id + " listening on :" + nodes[i].getPort());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (StorageNodeServer n : nodes) {
                n.stop();
            }
        }));
        nodes[0].awaitTermination();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
