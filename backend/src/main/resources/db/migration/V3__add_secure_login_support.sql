ALTER TABLE app_users
    ADD COLUMN failed_login_attempts SMALLINT NOT NULL DEFAULT 0
        CHECK (failed_login_attempts BETWEEN 0 AND 100),
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD COLUMN last_login_at TIMESTAMPTZ,
    ADD COLUMN password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX ix_app_users_status_locked_until
    ON app_users (status, locked_until);
