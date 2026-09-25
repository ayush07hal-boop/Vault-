-- Trash (soft delete: object stays ACTIVE so repair/integrity keep protecting it) and per-user projects.
ALTER TABLE objects ADD COLUMN trashed_at TIMESTAMP;
ALTER TABLE objects ADD COLUMN project_id VARCHAR(100);
CREATE INDEX idx_objects_trashed ON objects (trashed_at);
CREATE INDEX idx_objects_project ON objects (project_id);

CREATE TABLE projects (
    project_id VARCHAR(100) PRIMARY KEY,
    owner_id   VARCHAR(100) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
CREATE INDEX idx_projects_owner ON projects (owner_id);
