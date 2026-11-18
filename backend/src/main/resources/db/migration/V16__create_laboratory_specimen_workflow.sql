-- Laboratory workflow: specimen tracking, encrypted results and dedicated lab role.
ALTER TABLE clinic_memberships DROP CONSTRAINT clinic_memberships_role_check;
ALTER TABLE clinic_memberships ADD CONSTRAINT clinic_memberships_role_check
    CHECK (role IN ('CLINIC_ADMIN','RECEPTION','PROFESSIONAL','TRIAGE','AUDITOR','LAB_TECHNICIAN'));

CREATE TABLE lab_specimens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    exam_order_id UUID NOT NULL,
    specimen_type VARCHAR(100) NOT NULL,
    lab_reference VARCHAR(80),
    collection_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_COLLECTION'
        CHECK (collection_status IN ('PENDING_COLLECTION','COLLECTED','RECEIVED','PROCESSING',
            'RESULT_READY','RELEASED','REJECTED','CANCELLED')),
    collection_notes_ciphertext BYTEA,
    rejection_reason_ciphertext BYTEA,
    result_ciphertext BYTEA,
    result_entered_by_membership_id UUID,
    created_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    collected_by_membership_id UUID,
    received_by_membership_id UUID,
    processing_by_membership_id UUID,
    released_by_membership_id UUID,
    collected_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    result_ready_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_lab_specimens_clinic_id_id UNIQUE (clinic_id,id),
    CONSTRAINT uq_lab_specimens_order UNIQUE (clinic_id,exam_order_id),
    CONSTRAINT fk_lab_specimen_order FOREIGN KEY (clinic_id,exam_order_id)
        REFERENCES exam_orders (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_specimen_result_author FOREIGN KEY (clinic_id,result_entered_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_specimen_creator FOREIGN KEY (clinic_id,created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_specimen_updater FOREIGN KEY (clinic_id,updated_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_specimen_collector FOREIGN KEY (clinic_id,collected_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_specimen_receiver FOREIGN KEY (clinic_id,received_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_specimen_processor FOREIGN KEY (clinic_id,processing_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_specimen_releaser FOREIGN KEY (clinic_id,released_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_lab_specimens_reference
    ON lab_specimens (clinic_id,upper(lab_reference))
    WHERE lab_reference IS NOT NULL AND btrim(lab_reference)<>'';
CREATE INDEX ix_lab_specimens_status_created
    ON lab_specimens (clinic_id,collection_status,created_at DESC);

-- Preserve all existing laboratory orders and any already released encrypted result.
INSERT INTO lab_specimens(
    clinic_id,exam_order_id,specimen_type,collection_status,result_ciphertext,
    result_entered_by_membership_id,created_by_membership_id,updated_by_membership_id,
    processing_started_at,result_ready_at,released_at,cancelled_at
)
SELECT o.clinic_id,o.id,'A definir',
       CASE o.status WHEN 'COMPLETED' THEN 'RELEASED'
                     WHEN 'CANCELLED' THEN 'CANCELLED'
                     WHEN 'IN_PROGRESS' THEN 'PROCESSING'
                     ELSE 'PENDING_COLLECTION' END,
       o.result_ciphertext,o.result_recorded_by_membership_id,
       o.requested_by_membership_id,o.updated_by_membership_id,
       CASE WHEN o.status='IN_PROGRESS' THEN o.started_at END,
       CASE WHEN o.status='COMPLETED' THEN o.completed_at END,
       CASE WHEN o.status='COMPLETED' THEN o.completed_at END,
       CASE WHEN o.status='CANCELLED' THEN o.cancelled_at END
  FROM exam_orders o
 WHERE o.exam_category_snapshot='LABORATORIO';

CREATE TABLE lab_specimen_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    specimen_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN
        ('CREATED','COLLECTED','RECEIVED','PROCESSING_STARTED','RESULT_READY','RELEASED',
         'REJECTED','RECOLLECTED','CANCELLED','UPDATED')),
    previous_status VARCHAR(24),
    new_status VARCHAR(24),
    detail VARCHAR(300),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_lab_specimen_event_specimen FOREIGN KEY (clinic_id,specimen_id)
        REFERENCES lab_specimens (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_lab_specimen_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_lab_specimen_events_history
    ON lab_specimen_events (clinic_id,specimen_id,created_at DESC);

INSERT INTO lab_specimen_events(clinic_id,specimen_id,event_type,new_status,detail,actor_membership_id)
SELECT s.clinic_id,s.id,'CREATED',s.collection_status,
       'Fluxo laboratorial inicializado para solicitação existente',s.created_by_membership_id
  FROM lab_specimens s;
INSERT INTO lab_specimen_events(clinic_id,specimen_id,event_type,previous_status,new_status,detail,actor_membership_id)
SELECT s.clinic_id,s.id,'RELEASED','RESULT_READY','RELEASED',
       'Resultado já concluído antes da implantação do fluxo laboratorial',
       COALESCE(s.result_entered_by_membership_id,s.updated_by_membership_id)
  FROM lab_specimens s WHERE s.collection_status='RELEASED';

ALTER TABLE lab_specimens ENABLE ROW LEVEL SECURITY;
ALTER TABLE lab_specimens FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_lab_specimens ON lab_specimens
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

ALTER TABLE lab_specimen_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE lab_specimen_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_lab_specimen_events ON lab_specimen_events
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

CREATE FUNCTION prevent_lab_specimen_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Laboratory specimen audit events are immutable';
END;
$$;
CREATE TRIGGER trg_lab_specimen_events_immutable
    BEFORE UPDATE OR DELETE ON lab_specimen_events
    FOR EACH ROW EXECUTE FUNCTION prevent_lab_specimen_event_mutation();
