package com.vault.storage;

import com.google.protobuf.ByteString;
import com.vault.config.VaultProperties;
import com.vault.metadata.StorageNodeEntity;
import com.vault.proto.CopyReplicaRequest;
import com.vault.proto.CopyReplicaResponse;
import com.vault.proto.DeleteObjectRequest;
import com.vault.proto.GetObjectRequest;
import com.vault.proto.GetObjectResponse;
import com.vault.proto.HealthRequest;
import com.vault.proto.HealthResponse;
import com.vault.proto.PutHeader;
import com.vault.proto.PutObjectRequest;
import com.vault.proto.PutObjectResponse;
import com.vault.proto.StorageNodeServiceGrpc;
import com.vault.proto.VerifyObjectRequest;
import com.vault.proto.VerifyObjectResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * The controller's only door to storage nodes (gRPC). Handles channel pooling, per-call deadlines
 * and bounded exponential-backoff retries for transient failures (UNREACHABLE only: a node that
 * answered "not found" or "checksum mismatch" will not change its mind).
 */
@Component
public class StorageNodeClient {

    private static final Logger log = LoggerFactory.getLogger(StorageNodeClient.class);
    private static final int CHUNK_SIZE = 256 * 1024;

    private final VaultProperties props;
    private final ChecksumService checksums;
    private final TempFiles tempFiles;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public StorageNodeClient(VaultProperties props, ChecksumService checksums, TempFiles tempFiles) {
        this.props = props;
        this.checksums = checksums;
        this.tempFiles = tempFiles;
    }

    public record PutResult(String checksum, long size) {
    }

    /** A verified-by-hash download sitting in a temp file. The caller owns (and must delete) the file. */
    public record Downloaded(Path file, String checksum, long size) {
    }

    public record VerifyResult(boolean exists, String checksum, long size) {
    }

    public record NodeHealthReport(String nodeId, boolean healthy, long totalCapacity, long usedCapacity,
                                   long objectCount) {
    }

    // ---- operations ------------------------------------------------------------------------------

    public PutResult put(StorageNodeEntity node, String objectId, long version, Path file, String expectedChecksum) {
        return withRetry(node, () -> {
            CompletableFuture<PutObjectResponse> result = new CompletableFuture<>();
            StreamObserver<PutObjectRequest> request = stub(node).withDeadlineAfter(
                            props.grpc().transferTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .putObject(new StreamObserver<>() {
                        @Override
                        public void onNext(PutObjectResponse value) {
                            result.complete(value);
                        }

                        @Override
                        public void onError(Throwable t) {
                            result.completeExceptionally(t);
                        }

                        @Override
                        public void onCompleted() {
                            // response already delivered via onNext
                        }
                    });
            ClientCallStreamObserver<PutObjectRequest> flow = (ClientCallStreamObserver<PutObjectRequest>) request;
            try (InputStream in = Files.newInputStream(file)) {
                request.onNext(PutObjectRequest.newBuilder().setHeader(PutHeader.newBuilder()
                        .setObjectId(objectId).setVersion(version)
                        .setExpectedChecksum(expectedChecksum == null ? "" : expectedChecksum)).build());
                byte[] buf = new byte[CHUNK_SIZE];
                int n;
                while (!result.isDone() && (n = in.read(buf)) > 0) {
                    while (!flow.isReady() && !result.isDone()) {
                        LockSupport.parkNanos(500_000);
                    }
                    if (result.isDone()) {
                        break;
                    }
                    request.onNext(PutObjectRequest.newBuilder().setChunk(ByteString.copyFrom(buf, 0, n)).build());
                }
                if (!result.isDone()) {
                    request.onCompleted();
                }
            } catch (Exception e) {
                flow.cancel("upload aborted", e);
                result.completeExceptionally(e);
            }
            PutObjectResponse response = await(result);
            return new PutResult(response.getChecksum(), response.getSize());
        });
    }

    /** Streams an object into a temp file, hashing on the way in. The caller compares the checksum. */
    public Downloaded get(StorageNodeEntity node, String objectId, long version) {
        return withRetry(node, () -> {
            Path tmp = tempFiles.create("get");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                MessageDigest md = checksums.newDigest();
                long size = 0;
                Iterator<GetObjectResponse> chunks = blocking(node)
                        .withDeadlineAfter(props.grpc().transferTimeoutMillis(), TimeUnit.MILLISECONDS)
                        .getObject(GetObjectRequest.newBuilder().setObjectId(objectId).setVersion(version).build());
                while (chunks.hasNext()) {
                    byte[] bytes = chunks.next().getChunk().toByteArray();
                    md.update(bytes);
                    out.write(bytes);
                    size += bytes.length;
                }
                out.close();
                return new Downloaded(tmp, checksums.hex(md), size);
            } catch (Throwable t) {
                tempFiles.deleteQuietly(tmp);
                throw NodeRpcException.from(node.getNodeId(), t);
            }
        });
    }

    /** Idempotent. {@code version == 0} removes every version. */
    public void delete(StorageNodeEntity node, String objectId, long version) {
        withRetry(node, () -> {
            blocking(node).withDeadlineAfter(props.grpc().rpcTimeoutMillis() * 2L, TimeUnit.MILLISECONDS)
                    .deleteObject(DeleteObjectRequest.newBuilder().setObjectId(objectId).setVersion(version).build());
            return null;
        });
    }

    public VerifyResult verify(StorageNodeEntity node, String objectId, long version) {
        return withRetry(node, () -> {
            VerifyObjectResponse r = blocking(node)
                    .withDeadlineAfter(props.grpc().transferTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .verifyObject(VerifyObjectRequest.newBuilder().setObjectId(objectId).setVersion(version).build());
            return new VerifyResult(r.getExists(), r.getChecksum(), r.getSize());
        });
    }

    /** Asks {@code target} to pull the object from {@code source} and verify it against {@code expectedChecksum}. */
    public PutResult copy(StorageNodeEntity target, StorageNodeEntity source, String objectId, long version,
                          String expectedChecksum) {
        return withRetry(target, () -> {
            CopyReplicaResponse r = blocking(target)
                    .withDeadlineAfter(props.grpc().transferTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .copyReplica(CopyReplicaRequest.newBuilder()
                            .setObjectId(objectId).setVersion(version)
                            .setSourceHost(source.getAddress()).setSourcePort(source.getPort())
                            .setExpectedChecksum(expectedChecksum).build());
            return new PutResult(r.getChecksum(), r.getSize());
        });
    }

    /** Heartbeat: single short attempt, no retries - the monitor's own failure threshold does the smoothing. */
    public NodeHealthReport health(StorageNodeEntity node) {
        try {
            ManagedChannel channel = channel(node);
            channel.resetConnectBackoff(); // do not wait out gRPC's reconnect backoff after an outage
            HealthResponse r = blocking(node)
                    .withDeadlineAfter(props.grpc().rpcTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .checkHealth(HealthRequest.getDefaultInstance());
            return new NodeHealthReport(r.getNodeId(), r.getHealthy(), r.getTotalCapacity(), r.getUsedCapacity(),
                    r.getObjectCount());
        } catch (Throwable t) {
            throw NodeRpcException.from(node.getNodeId(), t);
        }
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private <T> T withRetry(StorageNodeEntity node, Supplier<T> call) {
        int attempts = Math.max(1, props.grpc().maxAttempts());
        long backoff = props.grpc().baseBackoffMillis();
        NodeRpcException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.get();
            } catch (Throwable t) {
                last = NodeRpcException.from(node.getNodeId(), t);
                if (last.getKind() != NodeRpcException.Kind.UNREACHABLE || attempt == attempts) {
                    throw last;
                }
                log.debug("retrying {} (attempt {}/{}): {}", node.getNodeId(), attempt, attempts, last.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
                backoff *= 2;
            }
        }
        throw last; // unreachable
    }

    private <T> T await(CompletableFuture<T> f) {
        try {
            return f.get(props.grpc().transferTimeoutMillis() + 5_000L, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new RuntimeException(io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException());
        }
    }

    private StorageNodeServiceGrpc.StorageNodeServiceStub stub(StorageNodeEntity node) {
        return StorageNodeServiceGrpc.newStub(channel(node));
    }

    private StorageNodeServiceGrpc.StorageNodeServiceBlockingStub blocking(StorageNodeEntity node) {
        return StorageNodeServiceGrpc.newBlockingStub(channel(node));
    }

    private ManagedChannel channel(StorageNodeEntity node) {
        String key = node.getNodeId() + "@" + node.getAddress() + ":" + node.getPort();
        return channels.computeIfAbsent(key, k -> ManagedChannelBuilder
                .forAddress(node.getAddress(), node.getPort())
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .build());
    }

    @PreDestroy
    void shutdown() {
        channels.values().forEach(ManagedChannel::shutdownNow);
    }
}
