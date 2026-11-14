-- Patient registration and immutable exam selection snapshot for each queue entry.
ALTER TABLE patients
    ADD COLUMN insurance_name VARCHAR(180),
    ADD COLUMN job_title VARCHAR(160);

ALTER TABLE queue_entries
    ADD COLUMN service_type_id UUID;

ALTER TABLE queue_entries
    ADD CONSTRAINT fk_queue_service_type
    FOREIGN KEY (clinic_id, service_type_id)
    REFERENCES service_types (clinic_id, id) ON DELETE RESTRICT;

CREATE TABLE queue_entry_exams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    queue_entry_id UUID NOT NULL,
    exam_id UUID NOT NULL,
    exam_name_snapshot VARCHAR(140) NOT NULL,
    room_id UUID NOT NULL,
    room_name_snapshot VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_queue_entry_exams_queue
        FOREIGN KEY (clinic_id, queue_entry_id)
        REFERENCES queue_entries (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_entry_exams_exam
        FOREIGN KEY (clinic_id, exam_id)
        REFERENCES exam_catalog (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_entry_exams_room
        FOREIGN KEY (clinic_id, room_id)
        REFERENCES clinic_rooms (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_queue_entry_exam UNIQUE (clinic_id, queue_entry_id, exam_id)
);
CREATE INDEX ix_queue_entry_exams_queue
    ON queue_entry_exams (clinic_id, queue_entry_id);

CREATE TABLE patient_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    patient_id UUID NOT NULL,
    storage_key VARCHAR(300) NOT NULL,
    original_file_name VARCHAR(180) NOT NULL,
    content_type VARCHAR(100) NOT NULL CHECK (content_type = 'application/pdf'),
    file_size BIGINT NOT NULL CHECK (file_size BETWEEN 1 AND 10485760),
    sha256 BYTEA NOT NULL,
    uploaded_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_patient_documents_patient
        FOREIGN KEY (clinic_id, patient_id)
        REFERENCES patients (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_patient_documents_uploader
        FOREIGN KEY (clinic_id, uploaded_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_patient_documents_storage_key UNIQUE (storage_key)
);
CREATE INDEX ix_patient_documents_patient
    ON patient_documents (clinic_id, patient_id, created_at DESC);

ALTER TABLE queue_entry_exams ENABLE ROW LEVEL SECURITY;
ALTER TABLE queue_entry_exams FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_queue_entry_exams ON queue_entry_exams
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid);

ALTER TABLE patient_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_patient_documents ON patient_documents
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid);
