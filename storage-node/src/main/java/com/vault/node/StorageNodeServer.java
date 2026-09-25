package com.vault.node;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Embeddable storage node: used by {@link StorageNodeMain} and by integration tests. */
public final class StorageNodeServer {

    private final String nodeId;
    private final Path dataDir;
    private final ObjectStore store;
    private final Server server;

    public StorageNodeServer(String nodeId, int port, Path dataDir, long capacityBytes) throws IOException {
        this.nodeId = nodeId;
        this.dataDir = dataDir;
        this.store = new ObjectStore(dataDir, capacityBytes);
        this.server = ServerBuilder.forPort(port)
                .addService(new StorageNodeGrpcService(nodeId, store))
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build();
    }

    public StorageNodeServer start() throws IOException {
        server.start();
        return this;
    }

    /** Simulates a crash: drops connections immediately. */
    public void stop() {
        server.shutdownNow();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    public boolean isRunning() {
        return !server.isShutdown();
    }

    public int getPort() {
        return server.getPort();
    }

    public String getNodeId() {
        return nodeId;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public ObjectStore getStore() {
        return store;
    }
}
