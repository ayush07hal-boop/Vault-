-- Vault metadata schema (PostgreSQL; also valid on H2 in PostgreSQL mode).

CREATE TABLE objects (
    object_id          VARCHAR(100) PRIMARY KEY,
    file_name          VARCHAR(255) NOT NULL,
    file_size          BIGINT       NOT NULL,
    checksum           VARCHAR(128) NOT NULL,
    version            BIGINT       NOT NULL,
    replication_factor INT          NOT NULL,
    status             VARCHAR(30)  NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);
CREATE INDEX idx_objects_status ON objects (status);

CREATE TABLE storage_nodes (
    node_id               VARCHAR(100) PRIMARY KEY,
    address               VARCHAR(255) NOT NULL,
    port                  INT          NOT NULL,
    zone                  VARCHAR(50),
    status                VARCHAR(30)  NOT NULL,
    total_capacity        BIGINT       NOT NULL,
    used_capacity         BIGINT       NOT NULL,
    last_heartbeat        TIMESTAMP,
    consecutive_failures  INT          NOT NULL DEFAULT 0,
    consecutive_successes INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL
);

CREATE TABLE replicas (
    object_id     VARCHAR(100) NOT NULL REFERENCES objects (object_id),
    node_id       VARCHAR(100) NOT NULL REFERENCES storage_nodes (node_id),
    version       BIGINT       NOT NULL,
    checksum      VARCHAR(128) NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    last_verified TIMESTAMP,
    PRIMARY KEY (object_id, node_id)
);
CREATE INDEX idx_replicas_node ON replicas (node_id);
CREATE INDEX idx_replicas_status ON replicas (status);

-- Makes POST /objects safe to retry: one key maps to at most one created object.
CREATE TABLE idempotency_keys (
    idem_key   VARCHAR(200) PRIMARY KEY,
    object_id  VARCHAR(100),
    status     VARCHAR(20) NOT NULL,
    created_at TIMESTAMP   NOT NULL
);
