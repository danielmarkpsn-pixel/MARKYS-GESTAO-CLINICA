CREATE TABLE platform_admins (
    user_id UUID PRIMARY KEY REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(254) NOT NULL DEFAULT 'bootstrap'
);

CREATE INDEX ix_platform_admins_created_at ON platform_admins (created_at);
