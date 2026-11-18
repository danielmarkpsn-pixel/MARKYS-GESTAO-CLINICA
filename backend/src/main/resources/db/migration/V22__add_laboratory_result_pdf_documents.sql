-- Optional PDF evidence attached to a laboratory result.
CREATE TABLE lab_result_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    specimen_id UUID NOT NULL,
    file_name VARCHAR(180) NOT NULL,
    content_type VARCHAR(80) NOT NULL DEFAULT 'application/pdf' CHECK (content_type='application/pdf'),
    file_size BIGINT NOT NULL CHECK (file_size BETWEEN 1 AND 10485760),
    sha256 CHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    uploaded_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_lab_result_documents_specimen UNIQUE (clinic_id,specimen_id),
    CONSTRAINT fk_lab_result_document_specimen FOREIGN KEY (clinic_id,specimen_id)
        REFERENCES lab_specimens (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_result_document_uploader FOREIGN KEY (clinic_id,uploaded_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_lab_result_documents_hash ON lab_result_documents(clinic_id,sha256);
ALTER TABLE lab_result_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE lab_result_documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_lab_result_documents ON lab_result_documents
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);
