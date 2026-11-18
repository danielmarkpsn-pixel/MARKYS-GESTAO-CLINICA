-- Operational exam orders and immutable clinical audit history.
CREATE TABLE exam_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    patient_id UUID NOT NULL,
    exam_id UUID NOT NULL,
    exam_name_snapshot VARCHAR(140) NOT NULL,
    exam_category_snapshot VARCHAR(60) NOT NULL,
    room_id UUID NOT NULL,
    room_name_snapshot VARCHAR(100) NOT NULL,
    appointment_id UUID,
    queue_entry_id UUID,
    professional_id UUID,
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL','URGENT')),
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED','SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED')),
    scheduled_at TIMESTAMPTZ,
    notes_ciphertext BYTEA,
    result_ciphertext BYTEA,
    result_recorded_by_membership_id UUID,
    requested_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_exam_orders_clinic_id_id UNIQUE (clinic_id,id),
    CONSTRAINT fk_exam_order_patient FOREIGN KEY (clinic_id,patient_id)
        REFERENCES patients (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_catalog FOREIGN KEY (clinic_id,exam_id)
        REFERENCES exam_catalog (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_room FOREIGN KEY (clinic_id,room_id)
        REFERENCES clinic_rooms (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_appointment FOREIGN KEY (clinic_id,appointment_id)
        REFERENCES appointments (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_queue FOREIGN KEY (clinic_id,queue_entry_id)
        REFERENCES queue_entries (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_professional FOREIGN KEY (clinic_id,professional_id)
        REFERENCES professionals (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_requester FOREIGN KEY (clinic_id,requested_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_updater FOREIGN KEY (clinic_id,updated_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_result_author FOREIGN KEY (clinic_id,result_recorded_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_exam_orders_clinic_status_requested
    ON exam_orders (clinic_id,status,requested_at DESC);
CREATE INDEX ix_exam_orders_patient_history
    ON exam_orders (clinic_id,patient_id,requested_at DESC);
CREATE INDEX ix_exam_orders_professional_schedule
    ON exam_orders (clinic_id,professional_id,scheduled_at)
    WHERE status IN ('REQUESTED','SCHEDULED','IN_PROGRESS');

CREATE TABLE exam_order_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    exam_order_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL
        CHECK (event_type IN ('CREATED','SCHEDULED','STARTED','COMPLETED','CANCELLED','UPDATED')),
    previous_status VARCHAR(20),
    new_status VARCHAR(20),
    detail VARCHAR(300),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_exam_order_event_order FOREIGN KEY (clinic_id,exam_order_id)
        REFERENCES exam_orders (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_order_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_exam_order_events_history
    ON exam_order_events (clinic_id,exam_order_id,created_at DESC);

ALTER TABLE exam_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE exam_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_exam_orders ON exam_orders
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

ALTER TABLE exam_order_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE exam_order_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_exam_order_events ON exam_order_events
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

CREATE FUNCTION prevent_exam_order_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Exam order audit events are immutable';
END;
$$;
CREATE TRIGGER trg_exam_order_events_immutable
    BEFORE UPDATE OR DELETE ON exam_order_events
    FOR EACH ROW EXECUTE FUNCTION prevent_exam_order_event_mutation();
