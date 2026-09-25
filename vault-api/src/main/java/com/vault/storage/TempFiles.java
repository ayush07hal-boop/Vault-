package com.vault.storage;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Scratch space for spooling uploads and verified downloads. */
@Component
public class TempFiles {

    public Path create(String prefix) {
        try {
            return Files.createTempFile("vault-" + prefix + "-", ".tmp");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
