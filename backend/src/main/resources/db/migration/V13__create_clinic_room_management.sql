-- Dedicated room / consultation-room metadata and immutable room audit history.
ALTER TABLE clinic_rooms
    ADD COLUMN room_type VARCHAR(30) NOT NULL DEFAULT 'CONSULTORIO'
        CHECK (room_type IN ('CONSULTORIO','EXAMES','COLETA','AUDIOMETRIA','RAIO_X','MULTIUSO')),
    ADD COLUMN floor_label VARCHAR(80),
    ADD COLUMN capacity SMALLINT NOT NULL DEFAULT 1 CHECK (capacity BETWEEN 1 AND 100),
    ADD COLUMN description VARCHAR(500),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX ix_clinic_rooms_type_status
    ON clinic_rooms (clinic_id,room_type,status,lower(name));

CREATE TABLE clinic_room_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    room_id UUID NOT NULL,
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('CREATED','UPDATED','ACTIVATED','DEACTIVATED')),
    detail VARCHAR(300),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_clinic_room_event_room FOREIGN KEY (clinic_id,room_id)
        REFERENCES clinic_rooms (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_clinic_room_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_clinic_room_events_history
    ON clinic_room_events (clinic_id,room_id,created_at DESC);

ALTER TABLE clinic_room_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinic_room_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_clinic_room_events ON clinic_room_events
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

CREATE FUNCTION prevent_clinic_room_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Clinic room audit events are immutable';
END;
$$;
CREATE TRIGGER trg_clinic_room_events_immutable
    BEFORE UPDATE OR DELETE ON clinic_room_events
    FOR EACH ROW EXECUTE FUNCTION prevent_clinic_room_event_mutation();
