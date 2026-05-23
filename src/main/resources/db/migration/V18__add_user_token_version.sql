ALTER TABLE system.users
    ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE system.users
    ADD CONSTRAINT ck_users_token_version_nonnegative
        CHECK (token_version >= 0);
