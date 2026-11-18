-- Tenant-scoped insurance providers; preserve legacy patient insurance_name snapshots.
CREATE TABLE clinic_insurances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    name VARCHAR(180) NOT NULL,
    ans_code VARCHAR(24),
    contact_name VARCHAR(180),
    phone VARCHAR(40),
    email VARCHAR(254),
    billing_email VARCHAR(254),
    payment_terms_days SMALLINT NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 365),
    notes VARCHAR(1000),
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_clinic_insurances_clinic_id_id UNIQUE (clinic_id,id),
    CONSTRAINT fk_clinic_insurance_creator FOREIGN KEY (clinic_id,created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_clinic_insurance_updater FOREIGN KEY (clinic_id,updated_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_clinic_insurances_name
    ON clinic_insurances (clinic_id,lower(name));
CREATE UNIQUE INDEX uq_clinic_insurances_ans_code
    ON clinic_insurances (clinic_id,upper(ans_code)) WHERE ans_code IS NOT NULL AND btrim(ans_code)<>'';
CREATE INDEX ix_clinic_insurances_status_name
    ON clinic_insurances (clinic_id,status,lower(name));

ALTER TABLE patients ADD COLUMN insurance_id UUID;
ALTER TABLE patients ADD CONSTRAINT fk_patient_insurance
    FOREIGN KEY (clinic_id,insurance_id) REFERENCES clinic_insurances (clinic_id,id) ON DELETE RESTRICT;
CREATE INDEX ix_patients_insurance
    ON patients (clinic_id,insurance_id) WHERE insurance_id IS NOT NULL;

CREATE TABLE clinic_insurance_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    insurance_id UUID NOT NULL,
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('CREATED','UPDATED','ACTIVATED','DEACTIVATED')),
    detail VARCHAR(300),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_clinic_insurance_event_insurance FOREIGN KEY (clinic_id,insurance_id)
        REFERENCES clinic_insurances (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_clinic_insurance_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_clinic_insurance_events_history
    ON clinic_insurance_events (clinic_id,insurance_id,created_at DESC);

ALTER TABLE clinic_insurances ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinic_insurances FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_clinic_insurances ON clinic_insurances
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

ALTER TABLE clinic_insurance_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinic_insurance_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_clinic_insurance_events ON clinic_insurance_events
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

CREATE FUNCTION prevent_clinic_insurance_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Clinic insurance audit events are immutable';
END;
$$;
CREATE TRIGGER trg_clinic_insurance_events_immutable
    BEFORE UPDATE OR DELETE ON clinic_insurance_events
    FOR EACH ROW EXECUTE FUNCTION prevent_clinic_insurance_event_mutation();
