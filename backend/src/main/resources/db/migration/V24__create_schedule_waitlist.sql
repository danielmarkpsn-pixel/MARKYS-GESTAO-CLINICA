-- Patient waitlist for professional appointments, isolated by clinic.
CREATE TABLE schedule_waitlist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    patient_id UUID NOT NULL,
    professional_id UUID NOT NULL,
    preferred_date DATE,
    notes VARCHAR(240),
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','CONTACTED','SCHEDULED','CANCELLED')),
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_schedule_waitlist_patient FOREIGN KEY (clinic_id,patient_id) REFERENCES patients(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_waitlist_professional FOREIGN KEY (clinic_id,professional_id) REFERENCES professionals(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_waitlist_creator FOREIGN KEY (clinic_id,created_by_membership_id) REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_schedule_waitlist_queue ON schedule_waitlist(clinic_id,professional_id,status,created_at);
CREATE UNIQUE INDEX uq_schedule_waitlist_active ON schedule_waitlist(clinic_id,patient_id,professional_id)
 WHERE status IN ('WAITING','CONTACTED');
ALTER TABLE schedule_waitlist ENABLE ROW LEVEL SECURITY;
ALTER TABLE schedule_waitlist FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_schedule_waitlist ON schedule_waitlist
 USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
 WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);
