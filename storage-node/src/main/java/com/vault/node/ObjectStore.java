package com.vault.node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Disk-backed store for one storage node. Files are named {@code <objectId>.v<version>}.
 * Writes go to a temp file and are atomically renamed into place only after the
 * checksum has been verified, so readers never observe partial objects.
 */
public final class ObjectStore {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final Path objectsDir;
    private final Path tmpDir;
    private final long capacityBytes;
    private final AtomicLong usedBytes = new AtomicLong();
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong objectCount = new AtomicLong();

    public ObjectStore(Path dataDir, long capacityBytes) throws IOException {
        this.objectsDir = dataDir.resolve("objects");
        this.tmpDir = dataDir.resolve("tmp");
        this.capacityBytes = capacityBytes;
        Files.createDirectories(objectsDir);
        Files.createDirectories(tmpDir);
        try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(tmpDir)) {
            for (Path p : leftovers) {
                Files.deleteIfExists(p);
            }
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(objectsDir)) {
            for (Path p : files) {
                usedBytes.addAndGet(Files.size(p));
                objectCount.incrementAndGet();
            }
        }
    }

    public static void validateId(String objectId) {
        if (objectId == null || !VALID_ID.matcher(objectId).matches()) {
            throw new IllegalArgumentException("invalid object id: " + objectId);
        }
    }

    public Path pathOf(String objectId, long version) {
        validateId(objectId);
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        return objectsDir.resolve(objectId + ".v" + version);
    }

    public Writer begin(String objectId, long version) throws IOException {
        Path target = pathOf(objectId, version);
        return new Writer(target, Files.createTempFile(tmpDir, "put-", ".part"));
    }

    public Optional<Path> find(String objectId, long version) {
        Path p = pathOf(objectId, version);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /** @return true if at least one file was removed */
    public boolean delete(String objectId, long version) throws IOException {
        validateId(objectId);
        boolean removed = false;
        if (version > 0) {
            removed = deleteFile(pathOf(objectId, version));
        } else {
            try (DirectoryStream<Path> matches = Files.newDirectoryStream(objectsDir, objectId + ".v*")) {
                for (Path p : matches) {
                    removed |= deleteFile(p);
                }
            }
        }
        return removed;
    }

    private boolean deleteFile(Path p) throws IOException {
        if (!Files.exists(p)) {
            return false;
        }
        long size = Files.size(p);
        if (Files.deleteIfExists(p)) {
            usedBytes.addAndGet(-size);
            objectCount.decrementAndGet();
            return true;
        }
        return false;
    }

    /** Recomputes the checksum by re-reading the bytes from disk. */
    public Optional<Result> verify(String objectId, long version) throws IOException {
        Optional<Path> file = find(objectId, version);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        MessageDigest md = newDigest();
        long size = 0;
        try (InputStream in = Files.newInputStream(file.get())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                size += n;
            }
        } catch (java.nio.file.NoSuchFileException gone) {
            return Optional.empty();
        }
        return Optional.of(new Result(HexFormat.of().formatHex(md.digest()), size));
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    public long capacityBytes() {
        return capacityBytes;
    }

    public long objectCount() {
        return objectCount.get();
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Result(String checksum, long size) {
    }

    public static class ChecksumMismatchException extends IOException {
        public ChecksumMismatchException(String expected, String actual) {
            super("checksum mismatch: expected " + expected + " but received " + actual);
        }
    }

    public static class CapacityExceededException extends IOException {
        public CapacityExceededException() {
            super("node capacity exceeded");
        }
    }

    /** Streaming writer: hashes while writing, publishes atomically on commit. */
    public final class Writer implements AutoCloseable {
        private final Path target;
        private final Path tmp;
        private final OutputStream out;
        private final MessageDigest md = newDigest();
        private long size;
        private boolean finished;

        private Writer(Path target, Path tmp) throws IOException {
            this.target = target;
            this.tmp = tmp;
            this.out = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        public void write(byte[] buf, int off, int len) throws IOException {
            if (usedBytes.get() + reservedBytes.get() + len > capacityBytes) {
                abort();
                throw new CapacityExceededException();
            }
            reservedBytes.addAndGet(len);
            size += len;
            md.update(buf, off, len);
            out.write(buf, off, len);
        }

        public Result commit(String expectedChecksum) throws IOException {
            out.close();
            String actual = HexFormat.of().formatHex(md.digest());
            if (expectedChecksum != null && !expectedChecksum.isEmpty() && !expectedChecksum.equalsIgnoreCase(actual)) {
                abort();
                throw new ChecksumMismatchException(expectedChecksum, actual);
            }
            long previous = Files.exists(target) ? Files.size(target) : -1;
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            finished = true;
            reservedBytes.addAndGet(-size);
            if (previous >= 0) {
                usedBytes.addAndGet(size - previous);
            } else {
                usedBytes.addAndGet(size);
                objectCount.incrementAndGet();
            }
            return new Result(actual, size);
        }

        public void abort() {
            if (finished) {
                return;
            }
            finished = true;
            reservedBytes.addAndGet(-size);
            try {
                out.close();
            } catch (IOException ignored) {
                // best effort
            }
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }

        @Override
        public void close() {
            abort();
        }
    }
}
