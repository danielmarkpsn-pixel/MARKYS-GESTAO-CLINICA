-- Electronic signature workflow with tenant isolation and immutable evidence.
CREATE TABLE document_signature_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    patient_id UUID NOT NULL,
    patient_document_id UUID NOT NULL,
    document_name_snapshot VARCHAR(180) NOT NULL,
    document_sha256 BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SIGNED','CANCELLED')),
    requested_by_membership_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    signed_by_membership_id UUID,
    signer_name_snapshot VARCHAR(180),
    signed_at TIMESTAMPTZ,
    consent_text TEXT,
    signature_method VARCHAR(32),
    evidence_sha256 BYTEA,
    cancelled_by_membership_id UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(300),
    CONSTRAINT fk_signature_patient FOREIGN KEY (clinic_id,patient_id)
        REFERENCES patients(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_signature_document FOREIGN KEY (clinic_id,patient_document_id)
        REFERENCES patient_documents(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_signature_requester FOREIGN KEY (clinic_id,requested_by_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_signature_signer FOREIGN KEY (clinic_id,signed_by_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_signature_canceller FOREIGN KEY (clinic_id,cancelled_by_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT ck_signature_evidence CHECK (
      (status='PENDING' AND signed_at IS NULL AND evidence_sha256 IS NULL) OR
      (status='SIGNED' AND signed_at IS NOT NULL AND signer_name_snapshot IS NOT NULL
       AND consent_text IS NOT NULL AND evidence_sha256 IS NOT NULL) OR
      (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by_membership_id IS NOT NULL)
    )
);
CREATE INDEX ix_signature_requests_queue
    ON document_signature_requests(clinic_id,status,requested_at DESC);
CREATE INDEX ix_signature_requests_patient
    ON document_signature_requests(clinic_id,patient_id,requested_at DESC);
ALTER TABLE document_signature_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_signature_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_document_signature_requests ON document_signature_requests
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

CREATE TABLE document_signature_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('REQUESTED','SIGNED','CANCELLED')),
    detail VARCHAR(300),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_signature_event_request FOREIGN KEY (clinic_id,request_id)
        REFERENCES document_signature_requests(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_signature_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_signature_events_history
    ON document_signature_events(clinic_id,request_id,created_at DESC);
ALTER TABLE document_signature_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_signature_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_document_signature_events ON document_signature_events
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

CREATE FUNCTION prevent_document_signature_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Document signature events are immutable';
END;
$$;
CREATE TRIGGER trg_document_signature_events_immutable
    BEFORE UPDATE OR DELETE ON document_signature_events
    FOR EACH ROW EXECUTE FUNCTION prevent_document_signature_event_mutation();
