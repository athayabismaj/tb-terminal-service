-- Batch 7: server-side PostgreSQL backup/restore metadata.
-- Binary dumps and database credentials are deliberately not stored in the database.

CREATE TABLE system.database_backup_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('BACKUP', 'RESTORE')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'VALIDATED', 'SUCCEEDED', 'FAILED')),
    file_name VARCHAR(160) NOT NULL,
    file_size BIGINT,
    sha256 CHAR(64),
    requested_by UUID REFERENCES system.users(id) ON DELETE RESTRICT,
    source_backup_id UUID REFERENCES system.database_backup_jobs(id) ON DELETE RESTRICT,
    confirmation_hash CHAR(64),
    confirmation_expires_at TIMESTAMPTZ,
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    removed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_backup_file_name CHECK (file_name !~ '[\\/]'),
    CONSTRAINT ck_backup_sha256 CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_database_backup_jobs_created
    ON system.database_backup_jobs(operation, created_at DESC);

CREATE INDEX idx_database_backup_jobs_retention
    ON system.database_backup_jobs(completed_at)
    WHERE operation = 'BACKUP' AND status = 'SUCCEEDED' AND removed_at IS NULL;

