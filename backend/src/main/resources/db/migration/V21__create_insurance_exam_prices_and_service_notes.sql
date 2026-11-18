-- Per-insurance exam prices and signed service notes (not tax invoices).
CREATE TABLE insurance_exam_prices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    insurance_id UUID NOT NULL,
    exam_id UUID NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_insurance_exam_prices_clinic_id_id UNIQUE (clinic_id,id),
    CONSTRAINT uq_insurance_exam_prices_pair UNIQUE (clinic_id,insurance_id,exam_id),
    CONSTRAINT fk_insurance_exam_price_insurance FOREIGN KEY (clinic_id,insurance_id)
        REFERENCES clinic_insurances (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_insurance_exam_price_exam FOREIGN KEY (clinic_id,exam_id)
        REFERENCES exam_catalog (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_insurance_exam_price_creator FOREIGN KEY (clinic_id,created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_insurance_exam_price_updater FOREIGN KEY (clinic_id,updated_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_insurance_exam_prices_lookup ON insurance_exam_prices(clinic_id,insurance_id,active);

CREATE TABLE service_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    note_number VARCHAR(32) NOT NULL,
    patient_id UUID NOT NULL,
    insurance_id UUID NOT NULL,
    service_date DATE NOT NULL,
    total_amount NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SIGNED','CANCELLED')),
    notes VARCHAR(500),
    signer_name VARCHAR(180),
    signature_text VARCHAR(240),
    signed_at TIMESTAMPTZ,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_notes_clinic_id_id UNIQUE (clinic_id,id),
    CONSTRAINT uq_service_notes_number UNIQUE (clinic_id,note_number),
    CONSTRAINT fk_service_note_patient FOREIGN KEY (clinic_id,patient_id)
        REFERENCES patients (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_service_note_insurance FOREIGN KEY (clinic_id,insurance_id)
        REFERENCES clinic_insurances (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_service_note_creator FOREIGN KEY (clinic_id,created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT ck_service_note_signature CHECK (
        (status <> 'SIGNED') OR (signer_name IS NOT NULL AND signature_text IS NOT NULL AND signed_at IS NOT NULL)
    )
);
CREATE INDEX ix_service_notes_clinic_date ON service_notes(clinic_id,service_date DESC,created_at DESC);

CREATE TABLE service_note_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    service_note_id UUID NOT NULL,
    exam_id UUID NOT NULL,
    exam_name_snapshot VARCHAR(140) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 100),
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price > 0),
    total_amount NUMERIC(12,2) NOT NULL CHECK (total_amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_service_note_item_note FOREIGN KEY (clinic_id,service_note_id)
        REFERENCES service_notes (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_service_note_item_exam FOREIGN KEY (clinic_id,exam_id)
        REFERENCES exam_catalog (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_service_note_items_note ON service_note_items(clinic_id,service_note_id);

CREATE TABLE service_note_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    service_note_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('CREATED','SIGNED','CANCELLED')),
    detail VARCHAR(300) NOT NULL,
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_service_note_event_note FOREIGN KEY (clinic_id,service_note_id)
        REFERENCES service_notes (clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_service_note_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
        REFERENCES clinic_memberships (clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_service_note_events_history ON service_note_events(clinic_id,service_note_id,created_at DESC);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['insurance_exam_prices','service_notes','service_note_items','service_note_events']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (clinic_id = NULLIF(current_setting(''app.current_clinic_id'', true), '''')::uuid) WITH CHECK (clinic_id = NULLIF(current_setting(''app.current_clinic_id'', true), '''')::uuid)',
            'tenant_isolation_' || t, t
        );
    END LOOP;
END $$;

CREATE FUNCTION prevent_service_note_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Service note audit events are immutable';
END;
$$;
CREATE TRIGGER trg_service_note_events_immutable
    BEFORE UPDATE OR DELETE ON service_note_events
    FOR EACH ROW EXECUTE FUNCTION prevent_service_note_event_mutation();
