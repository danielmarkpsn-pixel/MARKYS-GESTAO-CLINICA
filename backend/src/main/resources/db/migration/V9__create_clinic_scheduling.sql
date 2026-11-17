-- Scheduling module: appointments, recurring professional hours and time blocks.
CREATE TABLE professional_schedule_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    professional_id UUID NOT NULL,
    weekday SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    starts_local TIME NOT NULL,
    ends_local TIME NOT NULL,
    slot_minutes SMALLINT NOT NULL DEFAULT 30 CHECK (slot_minutes BETWEEN 5 AND 240),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_schedule_rule_time CHECK (ends_local > starts_local),
    CONSTRAINT fk_schedule_rule_professional FOREIGN KEY (clinic_id, professional_id)
        REFERENCES professionals (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_schedule_rules_professional_day
    ON professional_schedule_rules (clinic_id, professional_id, weekday, active, starts_local);

CREATE TABLE schedule_blocks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    professional_id UUID,
    room_id UUID,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(180) NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_schedule_block_range CHECK (ends_at > starts_at),
    CONSTRAINT ck_schedule_block_target CHECK (professional_id IS NOT NULL OR room_id IS NOT NULL),
    CONSTRAINT fk_schedule_block_professional FOREIGN KEY (clinic_id, professional_id)
        REFERENCES professionals (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_block_room FOREIGN KEY (clinic_id, room_id)
        REFERENCES clinic_rooms (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_block_creator FOREIGN KEY (clinic_id, created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_schedule_blocks_period
    ON schedule_blocks (clinic_id, starts_at, ends_at);

CREATE TABLE appointments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    patient_id UUID NOT NULL,
    professional_id UUID NOT NULL,
    room_id UUID,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED','CONFIRMED','CHECKED_IN','COMPLETED','CANCELLED','NO_SHOW')),
    reason VARCHAR(180),
    cancellation_reason VARCHAR(240),
    created_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_appointment_range CHECK (ends_at > starts_at),
    CONSTRAINT fk_appointment_patient FOREIGN KEY (clinic_id, patient_id)
        REFERENCES patients (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_professional FOREIGN KEY (clinic_id, professional_id)
        REFERENCES professionals (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_room FOREIGN KEY (clinic_id, room_id)
        REFERENCES clinic_rooms (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_creator FOREIGN KEY (clinic_id, created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_updater FOREIGN KEY (clinic_id, updated_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_appointments_clinic_period
    ON appointments (clinic_id, starts_at, ends_at);
CREATE INDEX ix_appointments_professional_period
    ON appointments (clinic_id, professional_id, starts_at, ends_at)
    WHERE status NOT IN ('CANCELLED','NO_SHOW');
CREATE INDEX ix_appointments_patient_period
    ON appointments (clinic_id, patient_id, starts_at DESC);

ALTER TABLE professional_schedule_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional_schedule_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_schedule_rules ON professional_schedule_rules
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid);

ALTER TABLE schedule_blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE schedule_blocks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_schedule_blocks ON schedule_blocks
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid);

ALTER TABLE appointments ENABLE ROW LEVEL SECURITY;
ALTER TABLE appointments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_appointments ON appointments
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid);
