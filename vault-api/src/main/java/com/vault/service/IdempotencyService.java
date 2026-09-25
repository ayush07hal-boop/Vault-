package com.vault.service;

import com.vault.error.VaultException;
import com.vault.metadata.IdempotencyKeyEntity;
import com.vault.metadata.IdempotencyKeyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Makes upload retries safe. The first request with a given key reserves it (atomic INSERT);
 * retries either get the original result or a 409 while the first attempt is still running.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository keys;

    public IdempotencyService(IdempotencyKeyRepository keys) {
        this.keys = keys;
    }

    /**
     * @return the object id of the earlier, completed request; empty if this caller now owns the key
     * @throws VaultException 409 if another request holding the key has not finished
     */
    public Optional<String> begin(String key) {
        Optional<String> replay = replayOf(key);
        if (replay.isPresent()) {
            return replay;
        }
        try {
            keys.reserve(key, Instant.now());
            return Optional.empty();
        } catch (DataIntegrityViolationException raced) {
            // Another request reserved the key between our check and insert; the primary key arbitrates.
            return replayOf(key).or(() -> {
                throw VaultException.duplicateInProgress(key);
            });
        }
    }

    private Optional<String> replayOf(String key) {
        Optional<IdempotencyKeyEntity> existing = keys.findById(key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if ("DONE".equals(existing.get().getStatus()) && existing.get().getObjectId() != null) {
            return Optional.of(existing.get().getObjectId());
        }
        throw VaultException.duplicateInProgress(key);
    }

    public void complete(String key, String objectId) {
        keys.complete(key, objectId);
    }

    /** The owning request failed: free the key so the client may retry. */
    public void release(String key) {
        keys.deleteById(key);
    }
}
