package com.vault.node;

import com.google.protobuf.ByteString;
import com.vault.proto.CopyReplicaRequest;
import com.vault.proto.CopyReplicaResponse;
import com.vault.proto.DeleteObjectRequest;
import com.vault.proto.DeleteObjectResponse;
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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** gRPC facade over {@link ObjectStore}. Holds no replication policy. */
public class StorageNodeGrpcService extends StorageNodeServiceGrpc.StorageNodeServiceImplBase {

    static final int CHUNK_SIZE = 256 * 1024;
    private static final long COPY_TIMEOUT_SECONDS = 600;
    private static final Logger LOG = Logger.getLogger(StorageNodeGrpcService.class.getName());

    private final String nodeId;
    private final ObjectStore store;

    public StorageNodeGrpcService(String nodeId, ObjectStore store) {
        this.nodeId = nodeId;
        this.store = store;
    }

    @Override
    public StreamObserver<PutObjectRequest> putObject(StreamObserver<PutObjectResponse> responseObserver) {
        return new StreamObserver<>() {
            private ObjectStore.Writer writer;
            private String expectedChecksum = "";
            private boolean failed;

            @Override
            public void onNext(PutObjectRequest request) {
                if (failed) {
                    return;
                }
                try {
                    switch (request.getPayloadCase()) {
                        case HEADER -> {
                            if (writer != null) {
                                throw new IllegalArgumentException("duplicate header");
                            }
                            PutHeader h = request.getHeader();
                            expectedChecksum = h.getExpectedChecksum();
                            writer = store.begin(h.getObjectId(), h.getVersion());
                        }
                        case CHUNK -> {
                            if (writer == null) {
                                throw new IllegalArgumentException("chunk before header");
                            }
                            byte[] bytes = request.getChunk().toByteArray();
                            writer.write(bytes, 0, bytes.length);
                        }
                        default -> throw new IllegalArgumentException("empty payload");
                    }
                } catch (Exception e) {
                    fail(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                failed = true;
                if (writer != null) {
                    writer.abort();
                }
            }

            @Override
            public void onCompleted() {
                if (failed) {
                    return;
                }
                if (writer == null) {
                    fail(new IllegalArgumentException("missing header"));
                    return;
                }
                try {
                    ObjectStore.Result r = writer.commit(expectedChecksum);
                    responseObserver.onNext(PutObjectResponse.newBuilder()
                            .setChecksum(r.checksum()).setSize(r.size()).build());
                    responseObserver.onCompleted();
                } catch (Exception e) {
                    fail(e);
                }
            }

            private void fail(Exception e) {
                failed = true;
                if (writer != null) {
                    writer.abort();
                }
                responseObserver.onError(toStatus(e).asRuntimeException());
            }
        };
    }

    @Override
    public void getObject(GetObjectRequest request, StreamObserver<GetObjectResponse> observer) {
        final InputStream in;
        try {
            Optional<Path> file = store.find(request.getObjectId(), request.getVersion());
            if (file.isEmpty()) {
                observer.onError(Status.NOT_FOUND.withDescription("object not found").asRuntimeException());
                return;
            }
            in = Files.newInputStream(file.get());
        } catch (Exception e) {
            observer.onError(toStatus(e).asRuntimeException());
            return;
        }

        ServerCallStreamObserver<GetObjectResponse> so = (ServerCallStreamObserver<GetObjectResponse>) observer;
        byte[] buf = new byte[CHUNK_SIZE];
        boolean[] done = {false};
        so.setOnCancelHandler(() -> closeQuietly(in));
        // Honour flow control: only push while the transport can accept more.
        so.setOnReadyHandler(() -> {
            if (done[0]) {
                return;
            }
            try {
                while (so.isReady()) {
                    int n = in.read(buf);
                    if (n < 0) {
                        done[0] = true;
                        closeQuietly(in);
                        so.onCompleted();
                        return;
                    }
                    so.onNext(GetObjectResponse.newBuilder().setChunk(ByteString.copyFrom(buf, 0, n)).build());
                }
            } catch (Exception e) {
                done[0] = true;
                closeQuietly(in);
                so.onError(toStatus(e).asRuntimeException());
            }
        });
    }

    @Override
    public void deleteObject(DeleteObjectRequest request, StreamObserver<DeleteObjectResponse> observer) {
        try {
            boolean removed = store.delete(request.getObjectId(), request.getVersion());
            observer.onNext(DeleteObjectResponse.newBuilder().setDeleted(removed).build());
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(toStatus(e).asRuntimeException());
        }
    }

    @Override
    public void verifyObject(VerifyObjectRequest request, StreamObserver<VerifyObjectResponse> observer) {
        try {
            Optional<ObjectStore.Result> r = store.verify(request.getObjectId(), request.getVersion());
            VerifyObjectResponse.Builder b = VerifyObjectResponse.newBuilder().setExists(r.isPresent());
            r.ifPresent(res -> b.setChecksum(res.checksum()).setSize(res.size()));
            observer.onNext(b.build());
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(toStatus(e).asRuntimeException());
        }
    }

    @Override
    public void copyReplica(CopyReplicaRequest request, StreamObserver<CopyReplicaResponse> observer) {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(request.getSourceHost(), request.getSourcePort())
                .usePlaintext()
                .build();
        ObjectStore.Writer writer = null;
        try {
            writer = store.begin(request.getObjectId(), request.getVersion());
            Iterator<GetObjectResponse> chunks = StorageNodeServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getObject(GetObjectRequest.newBuilder()
                            .setObjectId(request.getObjectId())
                            .setVersion(request.getVersion())
                            .build());
            while (chunks.hasNext()) {
                byte[] bytes = chunks.next().getChunk().toByteArray();
                writer.write(bytes, 0, bytes.length);
            }
            ObjectStore.Result r = writer.commit(request.getExpectedChecksum());
            observer.onNext(CopyReplicaResponse.newBuilder().setChecksum(r.checksum()).setSize(r.size()).build());
            observer.onCompleted();
        } catch (StatusRuntimeException e) {
            if (writer != null) {
                writer.abort();
            }
            // A dead or corrupt *source* is reported as UNAVAILABLE/NOT_FOUND so the caller can try another source.
            observer.onError(Status.fromCode(e.getStatus().getCode())
                    .withDescription("copy source " + request.getSourceHost() + ":" + request.getSourcePort()
                            + " failed: " + e.getStatus().getDescription())
                    .asRuntimeException());
        } catch (Exception e) {
            if (writer != null) {
                writer.abort();
            }
            observer.onError(toStatus(e).asRuntimeException());
        } finally {
            channel.shutdown();
        }
    }

    @Override
    public void checkHealth(HealthRequest request, StreamObserver<HealthResponse> observer) {
        observer.onNext(HealthResponse.newBuilder()
                .setNodeId(nodeId)
                .setHealthy(true)
                .setTotalCapacity(store.capacityBytes())
                .setUsedCapacity(store.usedBytes())
                .setObjectCount(store.objectCount())
                .build());
        observer.onCompleted();
    }

    private Status toStatus(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
        }
        if (e instanceof ObjectStore.ChecksumMismatchException) {
            return Status.DATA_LOSS.withDescription(e.getMessage());
        }
        if (e instanceof ObjectStore.CapacityExceededException) {
            return Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage());
        }
        if (e instanceof java.nio.file.NoSuchFileException) {
            return Status.NOT_FOUND.withDescription("object not found");
        }
        LOG.log(Level.WARNING, "node " + nodeId + " internal error", e);
        return Status.INTERNAL.withDescription(String.valueOf(e.getMessage()));
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
