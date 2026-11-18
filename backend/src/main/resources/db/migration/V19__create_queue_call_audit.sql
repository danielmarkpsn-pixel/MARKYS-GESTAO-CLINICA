-- TELA DE CHAMADOS: immutable audit trail for every queue assignment and status transition.
CREATE TABLE queue_entry_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    queue_entry_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL
        CHECK (event_type IN ('CREATED','ASSIGNED','CALLED','RECALLED','STARTED','COMPLETED','RELEASED','CANCELLED','RETURNED')),
    previous_status VARCHAR(24),
    new_status VARCHAR(24),
    previous_room_id UUID,
    new_room_id UUID,
    previous_professional_id UUID,
    new_professional_id UUID,
    detail VARCHAR(300),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_queue_event_entry FOREIGN KEY (clinic_id,queue_entry_id)
        REFERENCES queue_entries (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_event_previous_room FOREIGN KEY (clinic_id,previous_room_id)
        REFERENCES clinic_rooms (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_event_new_room FOREIGN KEY (clinic_id,new_room_id)
        REFERENCES clinic_rooms (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_event_previous_professional FOREIGN KEY (clinic_id,previous_professional_id)
        REFERENCES professionals (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_event_new_professional FOREIGN KEY (clinic_id,new_professional_id)
        REFERENCES professionals (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_queue_entry_events_history
    ON queue_entry_events (clinic_id,queue_entry_id,created_at DESC);

ALTER TABLE queue_entry_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE queue_entry_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_queue_entry_events ON queue_entry_events
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

CREATE FUNCTION prevent_queue_entry_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Queue entry events are immutable';
END;
$$;
CREATE TRIGGER trg_queue_entry_events_immutable
    BEFORE UPDATE OR DELETE ON queue_entry_events
    FOR EACH ROW EXECUTE FUNCTION prevent_queue_entry_event_mutation();
