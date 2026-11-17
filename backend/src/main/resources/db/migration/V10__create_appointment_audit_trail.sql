-- Add the tenant-scoped unique key required by the composite audit foreign key.
ALTER TABLE appointments
    ADD CONSTRAINT uq_appointments_clinic_id_id UNIQUE (clinic_id, id);

-- Immutable event trail for scheduling operations.
CREATE TABLE appointment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    appointment_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL
        CHECK (event_type IN ('CREATED','RESCHEDULED','STATUS_CHANGED')),
    previous_status VARCHAR(20),
    new_status VARCHAR(20),
    detail VARCHAR(300),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_appointment_event_appointment FOREIGN KEY (clinic_id, appointment_id)
        REFERENCES appointments (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_event_actor FOREIGN KEY (clinic_id, actor_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_appointment_events_history
    ON appointment_events (clinic_id, appointment_id, created_at DESC);

ALTER TABLE appointment_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE appointment_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_appointment_events ON appointment_events
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid);
