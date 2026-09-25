package com.vault.error;

import org.springframework.http.HttpStatus;

/** Base type for errors that map directly onto an HTTP response. */
public class VaultException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String objectId;

    public VaultException(HttpStatus status, String code, String message, String objectId) {
        super(message);
        this.status = status;
        this.code = code;
        this.objectId = objectId;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getObjectId() {
        return objectId;
    }

    public static VaultException badRequest(String message) {
        return new VaultException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, null);
    }

    public static VaultException notFound(String objectId) {
        return new VaultException(HttpStatus.NOT_FOUND, "OBJECT_NOT_FOUND", "Object not found: " + objectId, objectId);
    }

    public static VaultException versionConflict(String objectId, long expected, long actual) {
        return new VaultException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "Expected version " + expected + " but current version is " + actual, objectId);
    }

    public static VaultException insufficientReplicas(String objectId, String message) {
        return new VaultException(HttpStatus.SERVICE_UNAVAILABLE, "INSUFFICIENT_REPLICAS", message, objectId);
    }

    public static VaultException duplicateInProgress(String key) {
        return new VaultException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS",
                "A request with Idempotency-Key '" + key + "' is still being processed", null);
    }
}
