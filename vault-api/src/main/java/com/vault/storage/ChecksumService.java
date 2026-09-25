package com.vault.storage;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. Everything stored in metadata is a lowercase hex digest. */
@Service
public class ChecksumService {

    public static final String ALGORITHM = "SHA-256";

    public MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    public String sha256(byte[] data) {
        MessageDigest md = newDigest();
        md.update(data);
        return hex(md);
    }

    public String sha256(Path file) throws IOException {
        MessageDigest md = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md);
    }

    /** Copies {@code in} to {@code out} in one pass, returning digest and length. */
    public Copied copyAndHash(InputStream in, OutputStream out) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
            out.write(buf, 0, n);
            total += n;
        }
        return new Copied(hex(md), total);
    }

    public record Copied(String checksum, long size) {
    }
}
