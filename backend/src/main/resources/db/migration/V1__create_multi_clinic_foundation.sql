CREATE TABLE clinics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(180) NOT NULL,
    legal_name VARCHAR(220),
    tax_id VARCHAR(32),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE')),
    timezone VARCHAR(80) NOT NULL DEFAULT 'America/Manaus',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_clinics_tax_id ON clinics (tax_id) WHERE tax_id IS NOT NULL;

CREATE TABLE app_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(180) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_app_users_email_lower ON app_users (lower(email));

CREATE TABLE clinic_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    role VARCHAR(30) NOT NULL
        CHECK (role IN ('CLINIC_ADMIN', 'RECEPTION', 'PROFESSIONAL', 'TRIAGE', 'AUDITOR')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (clinic_id, user_id)
);
CREATE INDEX ix_memberships_user_status ON clinic_memberships (user_id, status);
CREATE INDEX ix_memberships_clinic_status ON clinic_memberships (clinic_id, status);

-- Every clinic-owned table must carry clinic_id and use composite foreign keys
-- (clinic_id, id) to prevent cross-clinic references.
-- PostgreSQL RLS policies will be added with the authenticated tenant context,
-- before any patient/clinical-data endpoint is enabled.
