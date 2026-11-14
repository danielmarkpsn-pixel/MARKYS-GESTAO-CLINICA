-- Clinic-owned clinical tables. All cross-table references include clinic_id,
-- preventing a row from referencing another clinic's records.
ALTER TABLE clinic_memberships
    ADD CONSTRAINT uq_memberships_clinic_id_id UNIQUE (clinic_id, id);

CREATE TABLE patients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    full_name VARCHAR(180) NOT NULL,
    birth_date DATE,
    sex VARCHAR(30),
    document_type VARCHAR(20),
    document_fingerprint BYTEA,
    document_ciphertext BYTEA,
    contact_ciphertext BYTEA,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'MERGED')),
    created_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_patients_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT fk_patients_creator_membership
        FOREIGN KEY (clinic_id, created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_patient_document_pair CHECK (
        (document_type IS NULL AND document_fingerprint IS NULL AND document_ciphertext IS NULL)
        OR
        (document_type IS NOT NULL AND document_fingerprint IS NOT NULL AND document_ciphertext IS NOT NULL)
    )
);
CREATE INDEX ix_patients_clinic_name ON patients (clinic_id, lower(full_name));
CREATE UNIQUE INDEX uq_patients_clinic_document_fingerprint
    ON patients (clinic_id, document_fingerprint)
    WHERE document_fingerprint IS NOT NULL;

CREATE TABLE professionals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    membership_id UUID NOT NULL,
    display_name VARCHAR(180) NOT NULL,
    council_type VARCHAR(30),
    council_number_ciphertext BYTEA,
    specialty VARCHAR(120),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_professionals_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT uq_professionals_clinic_membership UNIQUE (clinic_id, membership_id),
    CONSTRAINT fk_professionals_membership
        FOREIGN KEY (clinic_id, membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);

CREATE TABLE clinic_rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_clinic_rooms_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT uq_clinic_rooms_name UNIQUE (clinic_id, name)
);

CREATE TABLE queue_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    patient_id UUID NOT NULL,
    room_id UUID,
    assigned_professional_id UUID,
    service_label VARCHAR(120),
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
        CHECK (priority IN ('NORMAL', 'PRIORITY', 'URGENT')),
    status VARCHAR(24) NOT NULL DEFAULT 'WAITING'
        CHECK (status IN ('WAITING', 'CALLED', 'IN_SERVICE', 'COMPLETED', 'RELEASED', 'CANCELLED')),
    arrived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    called_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by_membership_id UUID NOT NULL,
    CONSTRAINT uq_queue_entries_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT fk_queue_patient FOREIGN KEY (clinic_id, patient_id)
        REFERENCES patients (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_room FOREIGN KEY (clinic_id, room_id)
        REFERENCES clinic_rooms (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_professional FOREIGN KEY (clinic_id, assigned_professional_id)
        REFERENCES professionals (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_creator FOREIGN KEY (clinic_id, created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_queue_clinic_status_arrival ON queue_entries (clinic_id, status, arrived_at);

CREATE TABLE triage_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    queue_entry_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_membership_id UUID NOT NULL,
    systolic_pressure SMALLINT,
    diastolic_pressure SMALLINT,
    heart_rate SMALLINT CHECK (heart_rate BETWEEN 1 AND 300),
    temperature_celsius NUMERIC(4,1) CHECK (temperature_celsius BETWEEN 25 AND 45),
    oxygen_saturation SMALLINT CHECK (oxygen_saturation BETWEEN 1 AND 100),
    weight_kg NUMERIC(6,2) CHECK (weight_kg BETWEEN 0.2 AND 500),
    height_cm NUMERIC(5,1) CHECK (height_cm BETWEEN 20 AND 280),
    notes_ciphertext BYTEA,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_triage_records_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT fk_triage_queue FOREIGN KEY (clinic_id, queue_entry_id)
        REFERENCES queue_entries (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_triage_patient FOREIGN KEY (clinic_id, patient_id)
        REFERENCES patients (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_triage_recorder FOREIGN KEY (clinic_id, recorded_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_triage_pressure_pair CHECK (
        (systolic_pressure IS NULL AND diastolic_pressure IS NULL)
        OR
        (systolic_pressure IS NOT NULL AND diastolic_pressure IS NOT NULL)
    )
);
CREATE INDEX ix_triage_clinic_patient_recorded ON triage_records (clinic_id, patient_id, recorded_at DESC);

CREATE TABLE encounters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    patient_id UUID NOT NULL,
    queue_entry_id UUID,
    professional_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'SIGNED', 'CANCELLED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    signed_at TIMESTAMPTZ,
    CONSTRAINT uq_encounters_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT fk_encounters_patient FOREIGN KEY (clinic_id, patient_id)
        REFERENCES patients (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_encounters_queue FOREIGN KEY (clinic_id, queue_entry_id)
        REFERENCES queue_entries (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_encounters_professional FOREIGN KEY (clinic_id, professional_id)
        REFERENCES professionals (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_encounters_clinic_patient_started ON encounters (clinic_id, patient_id, started_at DESC);

CREATE TABLE clinical_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    encounter_id UUID NOT NULL,
    author_membership_id UUID NOT NULL,
    content_ciphertext BYTEA NOT NULL,
    encryption_key_version VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_clinical_notes_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT fk_clinical_notes_encounter FOREIGN KEY (clinic_id, encounter_id)
        REFERENCES encounters (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_clinical_notes_author FOREIGN KEY (clinic_id, author_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_clinical_notes_clinic_encounter ON clinical_notes (clinic_id, encounter_id, created_at);
