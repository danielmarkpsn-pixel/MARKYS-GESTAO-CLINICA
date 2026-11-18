-- eSocial SST preparation: employer registration, worker records and auditable event drafts.
CREATE TABLE esocial_employers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL UNIQUE REFERENCES clinics(id) ON DELETE RESTRICT,
    registration_type VARCHAR(8) NOT NULL CHECK (registration_type IN ('CNPJ','CPF','CAEPF','CNO')),
    registration_ciphertext BYTEA NOT NULL,
    registration_fingerprint BYTEA NOT NULL,
    legal_name VARCHAR(180) NOT NULL,
    classification_code VARCHAR(12),
    environment VARCHAR(12) NOT NULL DEFAULT 'PRODUCTION_RESTRICTED'
        CHECK (environment IN ('PRODUCTION_RESTRICTED','PRODUCTION','RESTRICTED')),
    contact_name VARCHAR(160),
    contact_email VARCHAR(180),
    contact_phone VARCHAR(30),
    configured_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_esocial_employer_configurer FOREIGN KEY (clinic_id,configured_by_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT
);
CREATE TABLE esocial_workers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    patient_id UUID,
    cpf_ciphertext BYTEA NOT NULL,
    cpf_fingerprint BYTEA NOT NULL,
    full_name VARCHAR(180) NOT NULL,
    birth_date DATE NOT NULL,
    sex VARCHAR(1) NOT NULL CHECK (sex IN ('M','F')),
    registration_code VARCHAR(60) NOT NULL,
    admission_date DATE NOT NULL,
    category_code VARCHAR(8) NOT NULL,
    cbo_code VARCHAR(8),
    job_title VARCHAR(120) NOT NULL,
    department VARCHAR(120),
    work_location VARCHAR(180),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_esocial_workers_clinic_id_id UNIQUE (clinic_id,id),
    CONSTRAINT uq_esocial_workers_registration UNIQUE (clinic_id,registration_code),
    CONSTRAINT uq_esocial_workers_cpf UNIQUE (clinic_id,cpf_fingerprint),
    CONSTRAINT fk_esocial_worker_patient FOREIGN KEY (clinic_id,patient_id)
        REFERENCES patients(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_esocial_worker_creator FOREIGN KEY (clinic_id,created_by_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_esocial_worker_updater FOREIGN KEY (clinic_id,updated_by_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_esocial_workers_name ON esocial_workers(clinic_id,lower(full_name));
CREATE TABLE esocial_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    worker_id UUID NOT NULL,
    event_type VARCHAR(8) NOT NULL CHECK (event_type IN ('S-2210','S-2220','S-2240')),
    event_date DATE NOT NULL,
    event_sequence INTEGER NOT NULL DEFAULT 1 CHECK (event_sequence > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','READY','TRANSMITTED','ACCEPTED','REJECTED','CANCELLED')),
    payload_ciphertext BYTEA NOT NULL,
    validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_exam_order_id UUID,
    protocol_number VARCHAR(100),
    receipt_number VARCHAR(100),
    rejection_reason VARCHAR(500),
    created_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_esocial_events_clinic_id_id UNIQUE (clinic_id,id),
    CONSTRAINT uq_esocial_events_sequence UNIQUE (clinic_id,worker_id,event_type,event_sequence),
    CONSTRAINT fk_esocial_event_worker FOREIGN KEY (clinic_id,worker_id)
        REFERENCES esocial_workers(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_esocial_event_exam FOREIGN KEY (clinic_id,source_exam_order_id)
        REFERENCES exam_orders(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_esocial_event_creator FOREIGN KEY (clinic_id,created_by_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_esocial_event_updater FOREIGN KEY (clinic_id,updated_by_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_esocial_events_queue ON esocial_events(clinic_id,status,event_date DESC);
CREATE INDEX ix_esocial_events_worker ON esocial_events(clinic_id,worker_id,event_date DESC);
CREATE TABLE esocial_event_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    event_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL CHECK (action IN ('CREATED','UPDATED','VALIDATED','MARKED_READY','CANCELLED')),
    detail VARCHAR(300) NOT NULL,
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_esocial_audit_event FOREIGN KEY (clinic_id,event_id)
        REFERENCES esocial_events(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_esocial_audit_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT
);
DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['esocial_employers','esocial_workers','esocial_events','esocial_event_audit'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (clinic_id=NULLIF(current_setting(''app.current_clinic_id'',true),'''')::uuid) WITH CHECK (clinic_id=NULLIF(current_setting(''app.current_clinic_id'',true),'''')::uuid)','tenant_isolation_'||t,t);
 END LOOP;
END $$;
CREATE FUNCTION prevent_esocial_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'eSocial audit records are immutable'; END;
$$;
CREATE TRIGGER trg_esocial_audit_immutable BEFORE UPDATE OR DELETE ON esocial_event_audit
FOR EACH ROW EXECUTE FUNCTION prevent_esocial_audit_mutation();
