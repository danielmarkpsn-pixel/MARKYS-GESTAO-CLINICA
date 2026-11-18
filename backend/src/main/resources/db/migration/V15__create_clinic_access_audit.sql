-- Clinic-scoped access management audit history.
ALTER TABLE clinic_memberships
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE clinic_membership_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    membership_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN
        ('CREATED','ROLE_CHANGED','SUSPENDED','REACTIVATED','PASSWORD_RESET','ACCOUNT_UNLOCKED')),
    detail VARCHAR(300),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_membership_event_membership FOREIGN KEY (clinic_id,membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_membership_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_clinic_membership_events_history
    ON clinic_membership_events (clinic_id,membership_id,created_at DESC);

ALTER TABLE clinic_membership_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinic_membership_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_clinic_membership_events ON clinic_membership_events
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

CREATE FUNCTION prevent_clinic_membership_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Clinic membership audit events are immutable';
END;
$$;
CREATE TRIGGER trg_clinic_membership_events_immutable
    BEFORE UPDATE OR DELETE ON clinic_membership_events
    FOR EACH ROW EXECUTE FUNCTION prevent_clinic_membership_event_mutation();
