-- Per-user accounts (Google sign-in) and object ownership.
CREATE TABLE users (
    user_id    VARCHAR(100) PRIMARY KEY,
    google_sub VARCHAR(255) NOT NULL UNIQUE,
    email      VARCHAR(255) NOT NULL UNIQUE,
    name       VARCHAR(255),
    picture    VARCHAR(1000),
    created_at TIMESTAMP    NOT NULL,
    last_login TIMESTAMP    NOT NULL
);

ALTER TABLE objects ADD COLUMN owner_id VARCHAR(100);
CREATE INDEX idx_objects_owner ON objects (owner_id);
