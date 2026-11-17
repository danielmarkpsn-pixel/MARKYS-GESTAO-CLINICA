-- Clinic financial management: receivables, payables, settlements and immutable audit.
CREATE TABLE finance_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL,
    direction VARCHAR(12) NOT NULL CHECK (direction IN ('INCOME','EXPENSE','BOTH')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_finance_categories_clinic_id_id UNIQUE (clinic_id, id)
);
CREATE UNIQUE INDEX uq_finance_categories_clinic_name_direction
    ON finance_categories (clinic_id, lower(name), direction);

CREATE TABLE finance_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    direction VARCHAR(12) NOT NULL CHECK (direction IN ('INCOME','EXPENSE')),
    description VARCHAR(180) NOT NULL,
    category_id UUID NOT NULL,
    patient_id UUID,
    appointment_id UUID,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    due_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','PARTIAL','PAID','OVERDUE','CANCELLED')),
    notes VARCHAR(500),
    created_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_finance_entries_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT fk_finance_entry_category FOREIGN KEY (clinic_id, category_id)
        REFERENCES finance_categories (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_entry_patient FOREIGN KEY (clinic_id, patient_id)
        REFERENCES patients (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_entry_appointment FOREIGN KEY (clinic_id, appointment_id)
        REFERENCES appointments (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_entry_creator FOREIGN KEY (clinic_id, created_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_entry_updater FOREIGN KEY (clinic_id, updated_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_finance_entries_period
    ON finance_entries (clinic_id, due_date DESC, direction, status);
CREATE INDEX ix_finance_entries_patient
    ON finance_entries (clinic_id, patient_id, due_date DESC)
    WHERE patient_id IS NOT NULL;

CREATE TABLE finance_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    entry_id UUID NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    payment_method VARCHAR(16) NOT NULL
        CHECK (payment_method IN ('CASH','PIX','DEBIT_CARD','CREDIT_CARD','TRANSFER','BOLETO','OTHER')),
    paid_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reference VARCHAR(120),
    notes VARCHAR(300),
    recorded_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_finance_payments_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT fk_finance_payment_entry FOREIGN KEY (clinic_id, entry_id)
        REFERENCES finance_entries (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_payment_recorder FOREIGN KEY (clinic_id, recorded_by_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_finance_payments_entry_date
    ON finance_payments (clinic_id, entry_id, paid_at DESC);

CREATE TABLE finance_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    entry_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL
        CHECK (event_type IN ('CREATED','UPDATED','PAYMENT_RECORDED','CANCELLED')),
    detail VARCHAR(300) NOT NULL,
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_finance_event_entry FOREIGN KEY (clinic_id, entry_id)
        REFERENCES finance_entries (clinic_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_event_actor FOREIGN KEY (clinic_id, actor_membership_id)
        REFERENCES clinic_memberships (clinic_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_finance_events_history
    ON finance_events (clinic_id, entry_id, created_at DESC);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['finance_categories','finance_entries','finance_payments','finance_events']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (clinic_id = NULLIF(current_setting(''app.current_clinic_id'', true), '''')::uuid) WITH CHECK (clinic_id = NULLIF(current_setting(''app.current_clinic_id'', true), '''')::uuid)',
            'tenant_isolation_' || t, t
        );
    END LOOP;
END $$;

CREATE FUNCTION prevent_finance_record_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Financial payment and audit records are immutable';
END;
$$;
CREATE TRIGGER trg_finance_payments_immutable
    BEFORE UPDATE OR DELETE ON finance_payments
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_record_mutation();
CREATE TRIGGER trg_finance_events_immutable
    BEFORE UPDATE OR DELETE ON finance_events
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_record_mutation();

-- Give existing clinics a practical starting chart of accounts.
INSERT INTO finance_categories (clinic_id,name,direction)
SELECT c.id, seed.name, seed.direction
  FROM clinics c
 CROSS JOIN (VALUES
    ('Consultas particulares','INCOME'),
    ('Exames e procedimentos','INCOME'),
    ('ASO e saúde ocupacional','INCOME'),
    ('Convênios','INCOME'),
    ('Outros recebimentos','INCOME'),
    ('Salários e honorários','EXPENSE'),
    ('Aluguel e condomínio','EXPENSE'),
    ('Materiais e insumos','EXPENSE'),
    ('Serviços terceirizados','EXPENSE'),
    ('Impostos e taxas','EXPENSE'),
    ('Outros pagamentos','EXPENSE')
 ) AS seed(name,direction)
ON CONFLICT DO NOTHING;
